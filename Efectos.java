package com.cafetecnologico.convertidor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Efectos de sonido y pausas dentro del texto, igual que en el complemento de Jieshuo.
 * Etiquetas entre corchetes: [perro], [pausa 2], y los sonidos de ambiente y emociones, que se
 * escriben con "sonido" y su nombre: [sonido lluvia 1]. Esos se descargan aparte (Ambientes).
 * Los datos de los efectos están en EfectosDatos.
 */
final class Efectos {

    private Efectos() {}

    static final String[] CATEGORIAS = {
        "Animales", "Agua", "Objetos y ambiente", "Personas y humor", "Acción"
    };
    static final int TASA = 24000;
    static final int MAX_PAUSA_S = 10;
    static final int PAUSA_EFECTO_MS = 300;
    static final int PAUSA_ENTRE_BLOQUES_MS = 400;

    // ---------- Catálogo ----------

    static final class Efecto {
        final String t;
        final String nombre;
        final String cat;
        final String[] alias;
        final char formato;
        final int muestras;
        final int inicio;
        final int largo;

        Efecto(String t, String nombre, String cat, String[] alias, char formato,
               int muestras, int inicio, int largo) {
            this.t = t;
            this.nombre = nombre;
            this.cat = cat;
            this.alias = alias;
            this.formato = formato;
            this.muestras = muestras;
            this.inicio = inicio;
            this.largo = largo;
        }
    }

    static final List<Efecto> TODOS = new ArrayList<>();
    static final Map<String, Efecto> BUSQUEDA = new HashMap<>();

    static {
        for (String fila : EfectosDatos.INDICE) {
            String[] c = fila.split("\\|", -1);
            String[] alias = c[3].isEmpty() ? new String[0] : c[3].split(";");
            TODOS.add(new Efecto(c[0], c[1], c[2], alias, c[4].charAt(0),
                    Integer.parseInt(c[5]), Integer.parseInt(c[6]), Integer.parseInt(c[7])));
        }
        for (Efecto e : TODOS) BUSQUEDA.put(normalizar(e.t), e);
        for (Efecto e : TODOS) {
            for (String a : e.alias) {
                String k = normalizar(a);
                if (!BUSQUEDA.containsKey(k)) BUSQUEDA.put(k, e);
            }
        }
    }

    /** Minúsculas, sin acentos y con los espacios normalizados. */
    static String normalizar(String s) {
        String n = s.replace(' ', ' ').toLowerCase(Locale.ROOT);
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** Las etiquetas de todos los efectos, separadas por comas (para pedírselas a la IA). */
    static String listaEtiquetas() {
        StringBuilder sb = new StringBuilder();
        for (Efecto e : TODOS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.t);
        }
        return sb.toString();
    }

    // ---------- Decodificación ----------

    private static final int[] PASOS_ADPCM = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66,
        73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408,
        449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
        9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
        32767
    };
    private static final int[] SALTO_ADPCM = {-1, -1, -1, -1, 2, 4, 6, 8};

    private static byte[] datos;

    private static synchronized byte[] datos() {
        if (datos == null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(3400000);
            for (String trozo : EfectosDatos.datos()) {
                byte[] b = Base64.getDecoder().decode(trozo);
                bos.write(b, 0, b.length);
            }
            datos = bos.toByteArray();
        }
        return datos;
    }

    /** PCM de 16 bits, little endian, 24 kHz, mono. */
    static byte[] pcm(Efecto e) {
        byte[] d = datos();
        short[] m = e.formato == 'm' ? ulaw(d, e) : adpcm(d, e);
        byte[] r = new byte[m.length * 2];
        for (int i = 0; i < m.length; i++) {
            r[2 * i] = (byte) (m[i] & 0xFF);
            r[2 * i + 1] = (byte) ((m[i] >> 8) & 0xFF);
        }
        return r;
    }

    private static short[] adpcm(byte[] d, Efecto e) {
        short[] r = new short[e.muestras];
        int pred = 0, idx = 0, k = 0;
        for (int i = 0; i < e.largo && k < e.muestras; i++) {
            int b = d[e.inicio + i] & 0xFF;
            for (int mitad = 0; mitad < 2 && k < e.muestras; mitad++) {
                int code = mitad == 0 ? (b & 15) : (b >> 4);
                int paso = PASOS_ADPCM[idx];
                int diff = paso / 8;
                if ((code & 1) != 0) diff += paso / 4;
                if ((code & 2) != 0) diff += paso / 2;
                if ((code & 4) != 0) diff += paso;
                if (code >= 8) pred -= diff; else pred += diff;
                if (pred > 32767) pred = 32767;
                if (pred < -32768) pred = -32768;
                idx += SALTO_ADPCM[code & 7];
                if (idx < 0) idx = 0;
                if (idx > 88) idx = 88;
                r[k++] = (short) pred;
            }
        }
        return r;
    }

    private static short[] ulaw(byte[] d, Efecto e) {
        int n = Math.min(e.largo, e.muestras);
        short[] r = new short[n];
        for (int i = 0; i < n; i++) {
            int u = 255 - (d[e.inicio + i] & 0xFF);
            int exp = (u >> 4) & 7;
            int man = u & 15;
            int v = ((man * 8 + 132) << exp) - 132;
            if (u >= 128) v = -v;
            r[i] = (short) v;
        }
        return r;
    }

    // ---------- Etiquetas en el texto ----------

    static final int TEXTO = 0;
    static final int EFECTO = 1;
    static final int PAUSA = 2;
    static final int VOZ = 3;
    static final int SONIDO = 4;

    static final class Paso {
        final int tipo;
        final String texto;
        final Efecto efecto;
        final double segundos;
        final Ambientes.Sonido sonido;

        Paso(int tipo, String texto, Efecto efecto, double segundos) {
            this.tipo = tipo;
            this.texto = texto;
            this.efecto = efecto;
            this.segundos = segundos;
            this.sonido = null;
        }

        Paso(Ambientes.Sonido sonido) {
            this.tipo = SONIDO;
            this.texto = null;
            this.efecto = null;
            this.segundos = 0;
            this.sonido = sonido;
        }
    }

    private static final Pattern ETIQUETA = Pattern.compile("\\[([^\\[\\]\\r\\n]*)\\]");
    private static final Pattern PAUSA_PAT = Pattern.compile("^pausa\\s*([0-9.,]+)\\s*(.*)$");

    /** Segundos de una pausa ("pausa", "pausa 2", "pausa 1,5 segundos"), o -1 si no es una pausa. */
    static double segundosDePausa(String norm) {
        if (norm.equals("pausa")) return 1;
        Matcher m = PAUSA_PAT.matcher(norm);
        if (!m.matches()) return -1;
        String resto = m.group(2);
        if (!(resto.isEmpty() || resto.equals("s") || resto.equals("seg") || resto.equals("segs")
                || resto.equals("segundo") || resto.equals("segundos"))) {
            return -1;
        }
        double n;
        try {
            n = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (n <= 0) return -1;
        return Math.min(n, MAX_PAUSA_S);
    }

    /** Resultado de analizar un texto: pasos, etiquetas desconocidas y el texto que se leerá. */
    static final class Plan {
        final List<Paso> pasos = new ArrayList<>();
        final List<String> desconocidas = new ArrayList<>();
        int bloquesVoz = 0;
        int efectos = 0;
        /** De los efectos, cuántos son sonidos de ambiente y emociones. */
        int sonidos = 0;
        int pausas = 0;
        /** Lo que se leerá en voz alta, sin las etiquetas reconocidas. */
        String hablado = "";
    }

    /**
     * Separa el texto en voz, efectos y pausas. Lo que esté entre corchetes y no se reconozca se
     * deja como texto y se anota en "desconocidas". Cada trozo de texto se parte en bloques de voz
     * de como máximo maxBytes bytes.
     */
    static Plan planear(String texto, int maxBytes) {
        Plan plan = new Plan();
        Set<String> vistas = new LinkedHashSet<>();
        StringBuilder acumulado = new StringBuilder();
        StringBuilder hablado = new StringBuilder();
        Matcher m = ETIQUETA.matcher(texto);
        int pos = 0;
        while (m.find()) {
            String norm = normalizar(m.group(1));
            Efecto ef = BUSQUEDA.get(norm);
            Ambientes.Sonido so = ef == null ? Ambientes.buscar(norm) : null;
            double seg = (ef == null && so == null) ? segundosDePausa(norm) : -1;
            acumulado.append(texto, pos, m.start());
            if (ef != null || so != null || seg > 0) {
                volcar(plan, acumulado, hablado, maxBytes);
                if (ef != null) {
                    plan.pasos.add(new Paso(EFECTO, null, ef, 0));
                    plan.efectos++;
                } else if (so != null) {
                    plan.pasos.add(new Paso(so));
                    plan.efectos++;
                    plan.sonidos++;
                } else {
                    plan.pasos.add(new Paso(PAUSA, null, null, seg));
                    plan.pausas++;
                }
            } else {
                acumulado.append(m.group());
                if (norm.matches(".*\\p{L}.*") && norm.length() <= 30) vistas.add(norm);
            }
            pos = m.end();
        }
        acumulado.append(texto.substring(pos));
        volcar(plan, acumulado, hablado, maxBytes);
        plan.desconocidas.addAll(vistas);
        plan.hablado = hablado.toString();
        return plan;
    }

    private static void volcar(Plan plan, StringBuilder acumulado, StringBuilder hablado,
                               int maxBytes) {
        String s = acumulado.toString();
        acumulado.setLength(0);
        if (s.trim().isEmpty()) return;
        hablado.append(s);
        for (String b : Audio.dividir(MainActivity.paraVoz(s), maxBytes)) {
            plan.pasos.add(new Paso(VOZ, b, null, 0));
            plan.bloquesVoz++;
        }
    }

    /** Quita de la respuesta de la IA las etiquetas entre corchetes que no existen. */
    static String validarEtiquetas(String texto) {
        Matcher m = Pattern.compile("\\[([^\\[\\]\\n]*)\\]").matcher(texto);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String norm = normalizar(m.group(1));
            boolean valida = BUSQUEDA.containsKey(norm) || segundosDePausa(norm) > 0
                    || Ambientes.buscar(norm) != null;
            m.appendReplacement(sb, Matcher.quoteReplacement(valida ? m.group() : ""));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ");
    }

    /** El texto sin ninguna etiqueta entre corchetes ni espacios, para comparar. */
    static String sinEtiquetas(String texto) {
        return texto.replaceAll("\\[[^\\[\\]\\n]*\\]", "").replaceAll("\\s+", "");
    }

    // ---------- Montaje del audio ----------

    /**
     * Va armando el audio paso a paso: bloques de voz con su fundido de salida, efectos y pausas,
     * con los mismos huecos que el complemento. Al final agrega el silencio y devuelve un WAV.
     */
    static final class Montaje {
        private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
        private int hz = TASA;
        private boolean hzFijado = false;
        private int anterior = -1;

        private void hueco(int siguiente) {
            if (anterior < 0) return;
            if (anterior == PAUSA || siguiente == PAUSA) return;
            int ms = (anterior == VOZ && siguiente == VOZ) ? PAUSA_ENTRE_BLOQUES_MS : PAUSA_EFECTO_MS;
            byte[] s = Audio.silencio(hz, 1, ms);
            salida.write(s, 0, s.length);
        }

        void voz(Audio a) {
            if (!hzFijado) {
                hz = a.hz;
                hzFijado = true;
            }
            byte[] pcm = aMono(a);
            if (a.hz != hz) pcm = remuestrear(pcm, a.hz, hz);
            hueco(VOZ);
            byte[] f = Audio.fundidoSalida(pcm, hz, 1, 50);
            salida.write(f, 0, f.length);
            anterior = VOZ;
        }

        void efecto(Efecto e) {
            byte[] pcm = pcm(e);
            if (hz != TASA) pcm = remuestrear(pcm, TASA, hz);
            hueco(EFECTO);
            salida.write(pcm, 0, pcm.length);
            anterior = EFECTO;
        }

        /** Un sonido de ambiente o de emociones ya decodificado a 24 kHz, mono. */
        void sonido(byte[] pcm24) {
            byte[] pcm = pcm24;
            if (hz != TASA) pcm = remuestrear(pcm, TASA, hz);
            hueco(EFECTO);
            salida.write(pcm, 0, pcm.length);
            anterior = EFECTO;
        }

        void pausa(double segundos) {
            hueco(PAUSA);
            byte[] s = Audio.silencio(hz, 1, (int) Math.round(segundos * 1000));
            salida.write(s, 0, s.length);
            anterior = PAUSA;
        }

        byte[] terminar(int silencioFinalMs) throws Exception {
            return terminar(silencioFinalMs, null, 0);
        }

        /** Con un fondo (archivo de Ambientes), lo mezcla debajo de todo el audio. */
        byte[] terminar(int silencioFinalMs, File fondo, double nivel) throws Exception {
            if (anterior < 0) throw new IllegalArgumentException("No hay audio que unir.");
            byte[] fin = Audio.silencio(hz, 1, silencioFinalMs);
            salida.write(fin, 0, fin.length);
            byte[] pcm = salida.toByteArray();
            if (fondo != null) {
                pcm = Ambientes.mezclarFondo(pcm, hz, Ambientes.decodificar(fondo, hz), nivel);
            }
            return Audio.aWav(pcm, hz, 1);
        }
    }

    private static byte[] aMono(Audio a) {
        if (a.canales == 1) return a.pcm;
        int marco = 2 * a.canales;
        int n = a.pcm.length / marco;
        byte[] r = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            r[2 * i] = a.pcm[i * marco];
            r[2 * i + 1] = a.pcm[i * marco + 1];
        }
        return r;
    }

    /** Remuestreo lineal simple, solo por si Google devolviera otra frecuencia que 24 kHz. */
    static byte[] remuestrear(byte[] pcm, int desde, int hasta) {
        int n = pcm.length / 2;
        int m = (int) ((long) n * hasta / desde);
        byte[] r = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            double p = (double) i * desde / hasta;
            int i0 = Math.min((int) p, n - 1);
            int i1 = Math.min(i0 + 1, n - 1);
            double f = p - i0;
            int a = (short) ((pcm[2 * i0] & 0xFF) | (pcm[2 * i0 + 1] << 8));
            int b = (short) ((pcm[2 * i1] & 0xFF) | (pcm[2 * i1 + 1] << 8));
            int v = (int) Math.round(a + (b - a) * f);
            r[2 * i] = (byte) (v & 0xFF);
            r[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return r;
    }
}
