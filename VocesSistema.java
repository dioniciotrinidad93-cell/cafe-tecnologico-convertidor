package com.cafetecnologico.convertidor;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Voces de los motores de texto a voz instalados en el teléfono (Google, Samsung, RHVoice,
 * Eloquence, etc.). Se le pide a cada motor que guarde la voz en un archivo, trozo por trozo, y
 * ese audio se une al resto del montaje igual que el de Gemini: efectos, pausas, fondos y
 * conversaciones. No necesita clave ni internet, salvo las voces del motor que lo exijan.
 */
final class VocesSistema {

    private VocesSistema() {}

    static final int TASA = Efectos.TASA;
    /** Un trozo se pide al motor de unos 400 caracteres, para que no acapare al lector de pantalla. */
    static final int TROZO_OBJETIVO = 400;
    static final int TROZO_MAXIMO = 600;
    static final int PAUSA_TROZOS_MS = 150;
    static final long ESPERA_TROZO_MS = 120000;
    static final double RMS_OBJETIVO = 3500;

    static final String[] NOMBRES_VELOCIDAD = {
        "Muy lenta", "Lenta", "Normal", "Un poco rápida", "Rápida", "Muy rápida"
    };
    static final float[] VELOCIDADES = {0.6f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f};
    static final String[] NOMBRES_TONO = {"Grave", "Un poco grave", "Normal", "Un poco agudo", "Agudo"};
    static final float[] TONOS = {0.7f, 0.85f, 1.0f, 1.15f, 1.3f};

    // ---------- Motores instalados ----------

    static final class Motor {
        final String paquete;
        final String etiqueta;

        Motor(String paquete, String etiqueta) {
            this.paquete = paquete;
            this.etiqueta = etiqueta;
        }
    }

    /** Los motores de texto a voz que hay en el teléfono. Pide el permiso de consulta del manifiesto. */
    static List<Motor> motores(Context c) {
        List<Motor> r = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ResolveInfo> l = pm.queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            for (ResolveInfo ri : l) {
                if (ri.serviceInfo == null) continue;
                String paquete = ri.serviceInfo.packageName;
                boolean repetido = false;
                for (Motor m : r) if (m.paquete.equals(paquete)) repetido = true;
                if (repetido) continue;
                CharSequence et = ri.loadLabel(pm);
                r.add(new Motor(paquete, et == null ? paquete : et.toString()));
            }
        } catch (Exception ignorada) {
            // se prueba con el motor predeterminado
        }
        if (r.isEmpty()) {
            try {
                String p = Settings.Secure.getString(c.getContentResolver(), "tts_default_synth");
                if (p != null && !p.isEmpty()) r.add(new Motor(p, p));
            } catch (Exception ignorada) {
                // sin motor predeterminado
            }
        }
        Collections.sort(r, (a, b) -> a.etiqueta.compareToIgnoreCase(b.etiqueta));
        return r;
    }

    // ---------- Idiomas y voces de un motor ----------

    static final class Voz {
        final String nombre;
        final String etiqueta;

        Voz(String nombre, String etiqueta) {
            this.nombre = nombre;
            this.etiqueta = etiqueta;
        }
    }

    static final class Idioma {
        final String tag;
        final String etiqueta;
        final List<Voz> voces = new ArrayList<>();

        Idioma(String tag, String etiqueta) {
            this.tag = tag;
            this.etiqueta = etiqueta;
        }
    }

    interface AlCargar {
        void listo(List<Idioma> idiomas, String error);
    }

    /** Pregunta a un motor qué idiomas y voces tiene. Se llama desde el hilo principal. */
    static void cargar(Context c, String motor, final AlCargar alTerminar) {
        final Handler ui = new Handler(Looper.getMainLooper());
        final TextToSpeech[] t = new TextToSpeech[1];
        t[0] = new TextToSpeech(c.getApplicationContext(), status -> {
            final TextToSpeech tts = t[0];
            if (status != TextToSpeech.SUCCESS || tts == null) {
                if (tts != null) tts.shutdown();
                ui.post(() -> alTerminar.listo(null, "El motor no pudo iniciarse. Puede que no esté "
                        + "bien instalado o que le falten datos de voz."));
                return;
            }
            new Thread(() -> {
                List<Idioma> r = null;
                String error = null;
                try {
                    r = construir(tts);
                } catch (Throwable e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                } finally {
                    try {
                        tts.shutdown();
                    } catch (Exception ignorada) {
                        // ya estaba cerrado
                    }
                }
                final List<Idioma> res = r;
                final String falla = error;
                ui.post(() -> alTerminar.listo(res, falla));
            }).start();
        }, motor);
    }

    private static String capitalizar(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(new Locale("es")) + s.substring(1);
    }

    private static String nombreIdioma(Locale l) {
        String n = l.getDisplayName(new Locale("es"));
        if (n == null || n.isEmpty()) n = l.toLanguageTag();
        return capitalizar(n);
    }

    private static List<Idioma> construir(TextToSpeech tts) throws Exception {
        Map<String, List<Voice>> voces = new LinkedHashMap<>();
        Map<String, Locale> locales = new LinkedHashMap<>();
        Set<Voice> conjunto = null;
        try {
            conjunto = tts.getVoices();
        } catch (Throwable ignorada) {
            // algunos motores no saben listar voces
        }
        if (conjunto != null) {
            for (Voice v : conjunto) {
                Set<String> rasgos = v.getFeatures();
                if (rasgos != null && rasgos.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    continue;
                }
                Locale l = v.getLocale();
                if (l == null) continue;
                String tag = l.toLanguageTag();
                locales.put(tag, l);
                List<Voice> lista = voces.get(tag);
                if (lista == null) {
                    lista = new ArrayList<>();
                    voces.put(tag, lista);
                }
                lista.add(v);
            }
        }
        if (locales.isEmpty()) {
            Set<Locale> ls = null;
            try {
                ls = tts.getAvailableLanguages();
            } catch (Throwable ignorada) {
                // sin lista de idiomas
            }
            if (ls != null) {
                for (Locale l : ls) {
                    if (l != null) locales.put(l.toLanguageTag(), l);
                }
            }
        }
        if (locales.isEmpty()) {
            throw new Exception("El motor no informó de ningún idioma. Se usará su idioma "
                    + "predeterminado.");
        }
        List<Idioma> r = new ArrayList<>();
        for (Map.Entry<String, Locale> e : locales.entrySet()) {
            Idioma i = new Idioma(e.getKey(), nombreIdioma(e.getValue()));
            List<Voice> lista = voces.get(e.getKey());
            if (lista != null) {
                Collections.sort(lista, (a, b) -> {
                    if (a.getQuality() != b.getQuality()) return b.getQuality() - a.getQuality();
                    return a.getName().compareTo(b.getName());
                });
                int k = 1;
                for (Voice v : lista) {
                    String et = "Voz " + (k++) + ", " + v.getName();
                    if (v.getQuality() >= Voice.QUALITY_HIGH) et += ", calidad alta";
                    if (v.isNetworkConnectionRequired()) et += ", necesita internet";
                    i.voces.add(new Voz(v.getName(), et));
                }
            }
            r.add(i);
        }
        Collections.sort(r, (a, b) -> {
            boolean ea = a.tag.startsWith("es");
            boolean eb = b.tag.startsWith("es");
            if (ea != eb) return ea ? -1 : 1;
            boolean ma = a.tag.equals("es-MX");
            boolean mb = b.tag.equals("es-MX");
            if (ma != mb) return ma ? -1 : 1;
            return a.etiqueta.compareToIgnoreCase(b.etiqueta);
        });
        return r;
    }

    // ---------- Síntesis a archivo ----------

    interface Cancelado {
        boolean si();
    }

    /** Un motor ya iniciado que convierte texto en audio de 24 kHz, mono. Se usa desde un hilo aparte. */
    static final class Sintetizador {
        private final TextToSpeech tts;
        private volatile CountDownLatch espera = new CountDownLatch(1);
        private volatile String idActual = "";
        private volatile String errorActual = null;
        private int contador = 0;

        Sintetizador(Context c, String motor) throws Exception {
            final CountDownLatch listo = new CountDownLatch(1);
            final int[] estado = {-99};
            tts = new TextToSpeech(c.getApplicationContext(), s -> {
                estado[0] = s;
                listo.countDown();
            }, motor);
            if (!listo.await(25, TimeUnit.SECONDS) || estado[0] != TextToSpeech.SUCCESS) {
                tts.shutdown();
                throw new Exception("No pude iniciar el motor de voz elegido. Revisa que siga "
                        + "instalado, o elige otro.");
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    // no hace falta
                }

                @Override
                public void onDone(String id) {
                    if (id != null && id.equals(idActual)) espera.countDown();
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String id) {
                    fallo(id, "error desconocido");
                }

                @Override
                public void onError(String id, int codigo) {
                    fallo(id, "código " + codigo);
                }

                private void fallo(String id, String motivo) {
                    if (id != null && id.equals(idActual)) {
                        errorActual = motivo;
                        espera.countDown();
                    }
                }
            });
        }

        /**
         * Elige idioma, voz, velocidad y tono. Con nombreVoz vacío se usa la voz predeterminada del
         * idioma; con idioma vacío, la del motor.
         */
        void configurar(String idiomaTag, String nombreVoz, float velocidad, float tono)
                throws Exception {
            boolean vozPuesta = false;
            if (nombreVoz != null && !nombreVoz.isEmpty()) {
                try {
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) {
                        for (Voice v : vs) {
                            if (v.getName().equals(nombreVoz)) {
                                vozPuesta = tts.setVoice(v) == TextToSpeech.SUCCESS;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignorada) {
                    // se prueba solo con el idioma
                }
            }
            if (!vozPuesta && idiomaTag != null && !idiomaTag.isEmpty()) {
                int r = tts.setLanguage(Locale.forLanguageTag(idiomaTag));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    throw new Exception("El motor no tiene datos de voz para el idioma elegido. "
                            + "Instálalos desde los ajustes del motor de voz.");
                }
            }
            tts.setSpeechRate(velocidad);
            tts.setPitch(tono);
        }

        /** Convierte un trozo de texto en PCM de 16 bits, mono, a 24 kHz. */
        byte[] trozo(String texto, File tmp, Cancelado cancelado) throws Exception {
            final String id = "ct" + (++contador);
            tmp.delete();
            espera = new CountDownLatch(1);
            errorActual = null;
            idActual = id;
            int r = tts.synthesizeToFile(texto, new Bundle(), tmp, id);
            if (r != TextToSpeech.SUCCESS) {
                throw new Exception("El motor de voz rechazó el texto. Puede que no permita guardar "
                        + "el audio en un archivo; prueba con otro motor.");
            }
            long limite = System.currentTimeMillis() + ESPERA_TROZO_MS;
            while (!espera.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelado != null && cancelado.si()) {
                    tts.stop();
                    throw new Exception(MainActivity.MSG_DETENIDA);
                }
                if (System.currentTimeMillis() > limite) {
                    tts.stop();
                    throw new Exception("El motor de voz tardó demasiado en responder.");
                }
            }
            if (errorActual != null) {
                tmp.delete();
                throw new Exception("El motor de voz falló (" + errorActual + "). Prueba con otra "
                        + "voz u otro motor.");
            }
            try {
                if (!tmp.isFile() || tmp.length() < 100) {
                    throw new Exception("El motor de voz no devolvió audio. Puede que no permita "
                            + "guardar en archivo; prueba con otro motor.");
                }
                return leerWav(tmp);
            } finally {
                tmp.delete();
            }
        }

        void cerrar() {
            try {
                tts.stop();
            } catch (Exception ignorada) {
                // ya estaba detenido
            }
            try {
                tts.shutdown();
            } catch (Exception ignorada) {
                // ya estaba cerrado
            }
        }
    }

    /**
     * Convierte un texto en audio con la voz ya configurada: lo parte en trozos de frase, los pide
     * uno a uno (así el motor puede atender también al lector de pantalla entre un trozo y otro),
     * los une, recorta los silencios de los extremos y nivela el volumen. Devuelve PCM de 16 bits,
     * mono, a 24 kHz, o null si el texto no tiene nada que leer.
     */
    static byte[] texto(Sintetizador s, String texto, File tmp, Cancelado cancelado)
            throws Exception {
        ByteArrayOutputStream todo = new ByteArrayOutputStream();
        byte[] pausa = new byte[TASA * PAUSA_TROZOS_MS / 1000 * 2];
        for (String t : trozos(texto)) {
            if (cancelado != null && cancelado.si()) throw new Exception(MainActivity.MSG_DETENIDA);
            byte[] pcm = s.trozo(t, tmp, cancelado);
            if (pcm.length == 0) continue;
            if (todo.size() > 0) todo.write(pausa, 0, pausa.length);
            todo.write(pcm, 0, pcm.length);
        }
        if (todo.size() == 0) return null;
        byte[] r = pulir(todo.toByteArray());
        return r.length == 0 ? null : r;
    }

    /** Parte el texto en trozos de unas pocas frases, sin cortar palabras. Sin letras ni números, no hay trozo. */
    static List<String> trozos(String texto) {
        List<String> r = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String frase : texto.split("(?<=[.!?…])\\s+|\\n+")) {
            String f = frase.trim();
            if (f.isEmpty()) continue;
            while (f.length() > TROZO_MAXIMO) {
                int corte = f.lastIndexOf(' ', TROZO_MAXIMO);
                if (corte < TROZO_MAXIMO / 2) corte = TROZO_MAXIMO;
                agregarTrozo(r, sb, f.substring(0, corte).trim());
                f = f.substring(corte).trim();
            }
            agregarTrozo(r, sb, f);
        }
        soltar(r, sb);
        return r;
    }

    private static void agregarTrozo(List<String> r, StringBuilder sb, String f) {
        if (f.isEmpty()) return;
        if (sb.length() > 0 && sb.length() + 1 + f.length() > TROZO_OBJETIVO) soltar(r, sb);
        if (sb.length() > 0) sb.append(' ');
        sb.append(f);
        if (sb.length() >= TROZO_OBJETIVO) soltar(r, sb);
    }

    private static void soltar(List<String> r, StringBuilder sb) {
        String t = sb.toString().trim();
        sb.setLength(0);
        if (t.matches("(?s).*[\\p{L}\\p{N}].*")) r.add(t);
    }

    // ---------- Audio ----------

    /** Lee el WAV que dejó el motor (8 bits, 16 bits o decimal) y lo deja en 16 bits, mono, a 24 kHz. */
    static byte[] leerWav(File f) throws Exception {
        byte[] d;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            d = bos.toByteArray();
        }
        if (d.length < 44 || d[0] != 'R' || d[1] != 'I' || d[2] != 'F' || d[3] != 'F') {
            throw new Exception("El motor de voz guardó un archivo que no es WAV.");
        }
        int formato = 1, canales = 1, hz = 22050, bits = 16;
        int inicio = -1;
        int tam = 0;
        int p = 12;
        while (p + 8 <= d.length) {
            String id = new String(d, p, 4, "US-ASCII");
            long sz = (d[p + 4] & 0xFFL) | ((d[p + 5] & 0xFFL) << 8) | ((d[p + 6] & 0xFFL) << 16)
                    | ((d[p + 7] & 0xFFL) << 24);
            if (id.equals("fmt ") && p + 24 <= d.length) {
                formato = (d[p + 8] & 0xFF) | ((d[p + 9] & 0xFF) << 8);
                canales = Math.max(1, (d[p + 10] & 0xFF) | ((d[p + 11] & 0xFF) << 8));
                hz = (d[p + 12] & 0xFF) | ((d[p + 13] & 0xFF) << 8) | ((d[p + 14] & 0xFF) << 16)
                        | ((d[p + 15] & 0xFF) << 24);
                bits = (d[p + 22] & 0xFF) | ((d[p + 23] & 0xFF) << 8);
            } else if (id.equals("data")) {
                inicio = p + 8;
                tam = (sz <= 0 || inicio + sz > d.length) ? d.length - inicio : (int) sz;
                break;
            }
            long sig = p + 8 + sz + (sz & 1);
            if (sig > d.length || sig <= p) break;
            p = (int) sig;
        }
        if (inicio < 0 || tam <= 0) throw new Exception("El motor de voz no devolvió audio.");
        if (hz <= 0) hz = 22050;
        int bytesMuestra = bits / 8;
        boolean decimal = formato == 3 || (formato == 0xFFFE && bits == 32);
        if (!(bits == 16 || bits == 8 || (decimal && bits == 32))) {
            throw new Exception("El motor de voz usa un formato de audio que no puedo leer.");
        }
        int marcos = tam / (bytesMuestra * canales);
        byte[] mono = new byte[marcos * 2];
        for (int i = 0; i < marcos; i++) {
            double suma = 0;
            for (int c = 0; c < canales; c++) {
                int q = inicio + (i * canales + c) * bytesMuestra;
                double v;
                if (bits == 16) {
                    v = (short) ((d[q] & 0xFF) | (d[q + 1] << 8));
                } else if (bits == 8) {
                    v = ((d[q] & 0xFF) - 128) << 8;
                } else {
                    int b = (d[q] & 0xFF) | ((d[q + 1] & 0xFF) << 8) | ((d[q + 2] & 0xFF) << 16)
                            | ((d[q + 3] & 0xFF) << 24);
                    v = Math.max(-1f, Math.min(1f, Float.intBitsToFloat(b))) * 32767.0;
                }
                suma += v;
            }
            int m = (int) Math.max(-32768, Math.min(32767, Math.round(suma / canales)));
            mono[2 * i] = (byte) (m & 0xFF);
            mono[2 * i + 1] = (byte) ((m >> 8) & 0xFF);
        }
        return hz == TASA ? mono : Ambientes.remuestrear(mono, hz, TASA);
    }

    /** Quita el silencio de los extremos y sube o baja el volumen hasta un nivel parecido al de Gemini. */
    static byte[] pulir(byte[] pcm) {
        short[] m = Ambientes.aCortos(pcm);
        int n = m.length;
        int primero = -1;
        int ultimo = -1;
        int pico = 0;
        double suma = 0;
        long cuenta = 0;
        for (int i = 0; i < n; i++) {
            int a = Math.abs((int) m[i]);
            if (a > 200) {
                if (primero < 0) primero = i;
                ultimo = i;
            }
            if (a > pico) pico = a;
            if (a > 500) {
                suma += (double) m[i] * m[i];
                cuenta++;
            }
        }
        if (primero < 0) return new byte[0];
        int margen = TASA / 25;
        int desde = Math.max(0, primero - margen);
        int hasta = Math.min(n, ultimo + margen + 1);
        double rms = cuenta > 0 ? Math.sqrt(suma / cuenta) : RMS_OBJETIVO;
        double g = RMS_OBJETIVO / Math.max(1, rms);
        g = Math.max(0.5, Math.min(8, g));
        if (pico > 0) g = Math.min(g, 30000.0 / pico);
        short[] r = new short[hasta - desde];
        for (int i = 0; i < r.length; i++) {
            r[i] = (short) Math.max(-32768, Math.min(32767, Math.round(m[desde + i] * g)));
        }
        return Ambientes.aBytes(r);
    }
}
