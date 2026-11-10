package com.cafetecnologico.convertidor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sonidos de ambiente y de emociones. No viajan dentro del APK: se descargan una sola vez, en un
 * paquete zip que está en una versión aparte de GitHub, y se guardan en la memoria de la app.
 * Se pueden usar de fondo de todo el audio, o como efecto con una etiqueta: [sonido lluvia 1].
 */
final class Ambientes {

    private Ambientes() {}

    /** El zip está en la raíz del repositorio (rama main), junto a los archivos de la app. */
    static final String URL_PAQUETE = MainActivity.REPO
            + "/raw/main/sonidos-ambiente-cafe-tecnologico.zip";
    static final int MEGAS_PAQUETE = 20;
    static final String PREFIJO_ETIQUETA = "sonido ";

    static final String[] CATEGORIAS = {
        "Lluvia y tormenta",
        "Parque y naturaleza",
        "Cafetería",
        "Oficina e interiores",
        "Noche, suspenso y terror",
        "Felicidad",
        "Tristeza y calma",
        "Ficción y magia",
        "Tren y viaje"
    };

    /** De este largo en adelante un sonido sirve de fondo; hasta este otro, como efecto. */
    static final double MIN_FONDO_S = 8;
    static final double MAX_EFECTO_S = 30;

    static final String[] NOMBRES_VOLUMEN = {"bajo", "medio", "alto"};
    /** Fuerza del fondo respecto a la voz: unos 18, 12 y 7 decibeles por debajo. */
    static final double[] NIVELES_VOLUMEN = {0.12, 0.25, 0.45};

    static final class Sonido {
        final String archivo;
        final String nombre;
        final String etiqueta;
        final int[] categorias;
        final double segundos;
        final boolean fondo;
        final boolean efecto;

        Sonido(String archivo, String nombre, int[] categorias, double segundos) {
            this.archivo = archivo;
            this.nombre = nombre;
            this.etiqueta = PREFIJO_ETIQUETA + normalizar(nombre.replace(",", ""));
            this.categorias = categorias;
            this.segundos = segundos;
            this.fondo = segundos >= MIN_FONDO_S;
            this.efecto = segundos <= MAX_EFECTO_S;
        }

        /** "3 segundos", "40 segundos", "2 minutos y 5 segundos". */
        String duracion() {
            int s = Math.max(1, (int) Math.round(segundos));
            if (s < 60) return s + (s == 1 ? " segundo" : " segundos");
            int m = s / 60;
            int r = s % 60;
            return m + (m == 1 ? " minuto" : " minutos")
                    + (r == 0 ? "" : " y " + r + (r == 1 ? " segundo" : " segundos"));
        }

        boolean estaEn(int categoria) {
            for (int c : categorias) if (c == categoria) return true;
            return false;
        }
    }

    // archivo|nombre|categorías separadas por ;|segundos
    private static final String[] FILAS = {
        "ambiente-birds.ogg|Pájaros 1|1|30.7",
        "ambiente-cave.ogg|Cueva|4|35.4",
        "ambiente-cicadas.ogg|Cigarras|1|23.1",
        "ambiente-crickets.mp3|Grillos 1|1|11.5",
        "ambiente-fire.ogg|Fuego|4|28.7",
        "ambiente-forest.ogg|Bosque 1|1|39.4",
        "ambiente-grass.ogg|Pasto, roce corto|1|0.4",
        "ambiente-indoor.ogg|Interior|3|31.4",
        "ambiente-rain.ogg|Lluvia 1|0|26.0",
        "ambiente-thunder.ogg|Trueno 1|0|5.3",
        "ambiente-town.ogg|Pueblo|2|22.2",
        "ambiente-water.ogg|Agua|1|8.7",
        "ambiente-waves.ogg|Olas 1|1|35.4",
        "ambiente-wind.ogg|Viento 1|4|35.4",
        "doever-birds.ogg|Pájaros 2|1|24.0",
        "doever-cafe.ogg|Cafetería 1|2|24.0",
        "doever-crickets.ogg|Grillos 2|1|24.0",
        "doever-fireplace.ogg|Chimenea 1|4|24.0",
        "doever-heavyRain.ogg|Lluvia fuerte|0|24.0",
        "doever-keyboard.ogg|Teclado|3|24.0",
        "doever-lightRain.ogg|Lluvia ligera|0|24.0",
        "doever-ocean.ogg|Océano|8|24.0",
        "doever-office.ogg|Oficina|3|24.0",
        "doever-stream.ogg|Arroyo|1|24.0",
        "doever-thunder.ogg|Trueno 2|0|24.0",
        "doever-train.ogg|Tren|8|24.0",
        "doever-vinyl.ogg|Disco de vinilo|8|24.0",
        "doever-wind.ogg|Viento 2|4|24.0",
        "flowdesk-cafe.mp3|Cafetería 2|2|119.4",
        "flowdesk-fireplace.mp3|Chimenea 2|4|14.8",
        "flowdesk-forest.mp3|Bosque 2|1|81.6",
        "flowdesk-night.mp3|Noche|4|67.4",
        "flowdesk-rain.mp3|Lluvia 2|0|92.4",
        "flowdesk-river.mp3|Río|1|49.9",
        "flowdesk-waves.mp3|Olas 2|1|95.8",
        "flowdesk-wind.mp3|Viento 3|4|41.2",
        "emocion-bass-808-long.ogg|Bajo 808, largo|4|2.6",
        "emocion-bass-808-medium.ogg|Bajo 808, medio|4|1.5",
        "emocion-bass-808-short.ogg|Bajo 808, corto|4|0.7",
        "emocion-bass-rumble-long.ogg|Retumbo grave, largo|4|4.7",
        "emocion-bass-rumble-medium.ogg|Retumbo grave, medio|4|2.8",
        "emocion-bass-rumble-short.ogg|Retumbo grave, corto|4|1.5",
        "emocion-bass-sub-drop-long.ogg|Caída de subgrave, largo|4|3.9",
        "emocion-bass-sub-drop-medium.ogg|Caída de subgrave, medio|4|2.2",
        "emocion-celebration-applause-big.ogg|Aplausos, muchos|5|4.6",
        "emocion-celebration-applause-small.ogg|Aplausos, pocos|5|2.9",
        "emocion-celebration-confetti.ogg|Confeti|5|2.0",
        "emocion-celebration-fireworks.ogg|Fuegos artificiales|5|3.3",
        "emocion-celebration-popper.ogg|Cañón de confeti|5|1.0",
        "emocion-cinematic-drum-big.ogg|Tambor de película, grande|4|2.2",
        "emocion-cinematic-drum-massive.ogg|Tambor de película, enorme|4|3.3",
        "emocion-cinematic-metal-big.ogg|Golpe metálico de película, grande|4|2.1",
        "emocion-cinematic-metal-massive.ogg|Golpe metálico de película, enorme|4|3.2",
        "emocion-cinematic-slam-big.ogg|Golpe seco de película, grande|4|2.2",
        "emocion-cinematic-slam-massive.ogg|Golpe seco de película, enorme|4|3.1",
        "emocion-cinematic-thunder-big.ogg|Trueno de película, grande|4|3.8",
        "emocion-cinematic-thunder-massive.ogg|Trueno de película, enorme|4|4.9",
        "emocion-faller-1s-soft.ogg|Descenso de tensión, 1 segundo, suave|4;6|0.9",
        "emocion-faller-1s-strong.ogg|Descenso de tensión, 1 segundo, fuerte|4;6|0.9",
        "emocion-faller-2s-soft.ogg|Descenso de tensión, 2 segundos, suave|4;6|1.8",
        "emocion-faller-2s-strong.ogg|Descenso de tensión, 2 segundos, fuerte|4;6|1.9",
        "emocion-faller-4s-soft.ogg|Descenso de tensión, 4 segundos, suave|4;6|3.9",
        "emocion-faller-4s-strong.ogg|Descenso de tensión, 4 segundos, fuerte|4;6|3.9",
        "emocion-impact-deep-firm.ogg|Impacto profundo, firme|4|1.1",
        "emocion-impact-deep-hard.ogg|Impacto profundo, fuerte|4|1.2",
        "emocion-impact-deep-soft.ogg|Impacto profundo, suave|4|1.3",
        "emocion-impact-heavy-firm.ogg|Impacto pesado, firme|4|0.3",
        "emocion-impact-heavy-hard.ogg|Impacto pesado, fuerte|4|0.3",
        "emocion-impact-heavy-soft.ogg|Impacto pesado, suave|4|0.3",
        "emocion-riser-1s-soft.ogg|Ascenso de tensión, 1 segundo, suave|4|1.2",
        "emocion-riser-1s-strong.ogg|Ascenso de tensión, 1 segundo, fuerte|4|1.2",
        "emocion-riser-2s-soft.ogg|Ascenso de tensión, 2 segundos, suave|4|2.2",
        "emocion-riser-2s-strong.ogg|Ascenso de tensión, 2 segundos, fuerte|4|2.2",
        "emocion-riser-4s-soft.ogg|Ascenso de tensión, 4 segundos, suave|4|4.2",
        "emocion-riser-4s-strong.ogg|Ascenso de tensión, 4 segundos, fuerte|4|4.2",
        "emocion-shimmer-long-bright.ogg|Destello largo, brillante|5;7|2.1",
        "emocion-shimmer-long-soft.ogg|Destello largo, suave|5;7|1.9",
        "emocion-shimmer-long-sparkle.ogg|Destello largo, chispeante|5;7|1.9",
        "emocion-shimmer-medium-bright.ogg|Destello medio, brillante|5;7|1.1",
        "emocion-shimmer-medium-soft.ogg|Destello medio, suave|5;7|1.1",
        "emocion-shimmer-medium-sparkle.ogg|Destello medio, chispeante|5;7|1.1",
        "emocion-shimmer-short-bright.ogg|Destello corto, brillante|5;7|0.5",
        "emocion-shimmer-short-soft.ogg|Destello corto, suave|5;7|0.5",
        "emocion-shimmer-short-sparkle.ogg|Destello corto, chispeante|5;7|0.5",
        "emocion-sting-airy-long.ogg|Remate musical etéreo, largo|7|4.6",
        "emocion-sting-airy-short.ogg|Remate musical etéreo, corto|7|3.1",
        "emocion-sting-bold-long.ogg|Remate musical audaz, largo|7|2.6",
        "emocion-sting-bold-short.ogg|Remate musical audaz, corto|7|1.7",
        "emocion-sting-bright-long.ogg|Remate musical brillante, largo|7|3.2",
        "emocion-sting-bright-short.ogg|Remate musical brillante, corto|7|1.5",
        "emocion-tone-ding-high.ogg|Campanita, agudo|6;7|0.8",
        "emocion-tone-ding-low.ogg|Campanita, grave|6;7|1.1",
        "emocion-tone-ding-mid.ogg|Campanita, medio|6;7|1.1",
        "emocion-tone-step-1.ogg|Nota ascendente, paso 1 de 5|6;7|0.5",
        "emocion-tone-step-2.ogg|Nota ascendente, paso 2 de 5|6;7|0.4",
        "emocion-tone-step-3.ogg|Nota ascendente, paso 3 de 5|6;7|0.4",
        "emocion-tone-step-4.ogg|Nota ascendente, paso 4 de 5|6;7|0.4",
        "emocion-tone-step-5.ogg|Nota ascendente, paso 5 de 5|6;7|0.3",
    };

    static final List<Sonido> TODOS = new ArrayList<>();
    static final Map<String, Sonido> POR_ETIQUETA = new HashMap<>();
    static final Map<String, Sonido> POR_ARCHIVO = new HashMap<>();

    static {
        for (String fila : FILAS) {
            String[] c = fila.split("\\|", -1);
            String[] cs = c[2].split(";");
            int[] cats = new int[cs.length];
            for (int i = 0; i < cs.length; i++) cats[i] = Integer.parseInt(cs[i]);
            Sonido s = new Sonido(c[0], c[1], cats, Double.parseDouble(c[3]));
            TODOS.add(s);
            POR_ETIQUETA.put(s.etiqueta, s);
            POR_ARCHIVO.put(s.archivo, s);
        }
    }

    /** Minúsculas, sin acentos y con los espacios normalizados. */
    static String normalizar(String s) {
        String n = s.toLowerCase(Locale.ROOT);
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ").trim();
    }

    /** El sonido que corresponde a una etiqueta ya normalizada, o null. */
    static Sonido buscar(String etiquetaNormalizada) {
        return POR_ETIQUETA.get(etiquetaNormalizada);
    }

    static List<Sonido> deCategoria(int categoria) {
        List<Sonido> r = new ArrayList<>();
        for (Sonido s : TODOS) if (s.estaEn(categoria)) r.add(s);
        return r;
    }

    // ---------- Archivos descargados ----------

    static File carpeta(Context c) {
        File d = new File(c.getFilesDir(), "ambientes_v1");
        d.mkdirs();
        return d;
    }

    static File archivo(Context c, Sonido s) {
        return new File(carpeta(c), s.archivo);
    }

    static boolean disponible(Context c, Sonido s) {
        File f = archivo(c, s);
        return f.isFile() && f.length() > 0;
    }

    static int cuantosDisponibles(Context c) {
        int n = 0;
        for (Sonido s : TODOS) if (disponible(c, s)) n++;
        return n;
    }

    static void borrarTodo(Context c) {
        File[] fs = carpeta(c).listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }

    // ---------- Descarga ----------

    interface Progreso {
        void porcentaje(int p);
    }

    private static final long MAX_ZIP = 80L * 1024 * 1024;
    private static final long MAX_SONIDO = 30L * 1024 * 1024;
    private static final long MAX_TOTAL = 150L * 1024 * 1024;

    /**
     * Descarga el paquete y guarda cada sonido conocido en la memoria de la app. Los nombres de
     * los archivos del zip se toman de la lista de arriba, nunca de lo que diga el zip, así que
     * nada puede escribirse fuera de la carpeta. Devuelve cuántos sonidos quedaron.
     */
    static int descargar(Context c, Progreso progreso) throws Exception {
        File dir = carpeta(c);
        File zip = new File(dir, "paquete.zip.part");
        HttpURLConnection con = (HttpURLConnection) new URL(URL_PAQUETE).openConnection();
        try {
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);
            int cod = con.getResponseCode();
            if (cod == 404) {
                throw new Exception("El paquete de sonidos todavía no está publicado en GitHub "
                        + "(error 404). Avísale a Dionisio.");
            }
            if (cod != 200) {
                throw new Exception("GitHub respondió con el código " + cod + ".");
            }
            long total = con.getContentLengthLong();
            InputStream in = con.getInputStream();
            OutputStream out = new FileOutputStream(zip);
            try {
                byte[] buf = new byte[32768];
                long leido = 0;
                int n;
                int ultimo = -1;
                while ((n = in.read(buf)) > 0) {
                    leido += n;
                    if (leido > MAX_ZIP) throw new Exception("El paquete es más grande de lo esperado.");
                    out.write(buf, 0, n);
                    if (total > 0 && progreso != null) {
                        int p = (int) (leido * 90 / total);
                        if (p != ultimo) {
                            ultimo = p;
                            progreso.porcentaje(p);
                        }
                    }
                }
            } finally {
                out.close();
                in.close();
            }
        } catch (Exception e) {
            zip.delete();
            throw e;
        } finally {
            con.disconnect();
        }

        if (progreso != null) progreso.porcentaje(90);
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            long acumulado = 0;
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nombre = e.getName();
                int k = Math.max(nombre.lastIndexOf('/'), nombre.lastIndexOf('\\'));
                nombre = nombre.substring(k + 1);
                if (!POR_ARCHIVO.containsKey(nombre)) continue;
                File parte = new File(dir, nombre + ".part");
                OutputStream out = new FileOutputStream(parte);
                try {
                    byte[] buf = new byte[32768];
                    long este = 0;
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        este += n;
                        acumulado += n;
                        if (este > MAX_SONIDO || acumulado > MAX_TOTAL) {
                            throw new Exception("El paquete trae archivos más grandes de lo esperado.");
                        }
                        out.write(buf, 0, n);
                    }
                } catch (Exception ex) {
                    out.close();
                    parte.delete();
                    throw ex;
                }
                out.close();
                File destino = new File(dir, nombre);
                destino.delete();
                if (!parte.renameTo(destino)) {
                    parte.delete();
                    throw new Exception("No pude guardar el sonido " + nombre + ".");
                }
            }
        } finally {
            zin.close();
            zip.delete();
        }
        if (progreso != null) progreso.porcentaje(100);
        return cuantosDisponibles(c);
    }

    // ---------- Decodificación ----------

    /** Decodifica un OGG o MP3 a PCM de 16 bits, mono, a la frecuencia pedida. */
    static byte[] decodificar(File f, int hzDestino) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat t = ex.getTrackFormat(i);
                String mime = t.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    fmt = t;
                    break;
                }
            }
            if (fmt == null) throw new Exception("El archivo " + f.getName() + " no tiene audio.");
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int hz = entero(fmt, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int canales = entero(fmt, MediaFormat.KEY_CHANNEL_COUNT, 1);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteArrayOutputStream mono = new ByteArrayOutputStream(1 << 20);
            boolean finEntrada = false;
            boolean fin = false;
            int sinAvance = 0;
            while (!fin) {
                boolean avance = false;
                if (!finEntrada) {
                    int i = codec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = codec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            finEntrada = true;
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                        avance = true;
                    }
                }
                int o = codec.dequeueOutputBuffer(info, 10000);
                if (o >= 0) {
                    avance = true;
                    if (info.size > 0) {
                        ByteBuffer b = codec.getOutputBuffer(o);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        byte[] trozo = new byte[info.size];
                        b.get(trozo);
                        byte[] m = aMono(trozo, canales);
                        mono.write(m, 0, m.length);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) fin = true;
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    hz = entero(nf, MediaFormat.KEY_SAMPLE_RATE, hz);
                    canales = entero(nf, MediaFormat.KEY_CHANNEL_COUNT, canales);
                    avance = true;
                }
                if (avance) {
                    sinAvance = 0;
                } else if (++sinAvance > 1500) {
                    throw new Exception("No pude decodificar " + f.getName() + ".");
                }
            }
            byte[] pcm = mono.toByteArray();
            return hz == hzDestino ? pcm : remuestrear(pcm, hz, hzDestino);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignorada) {
                    // ya estaba detenido
                }
                codec.release();
            }
            ex.release();
        }
    }

    private static int entero(MediaFormat f, String clave, int porDefecto) {
        return f.containsKey(clave) ? f.getInteger(clave) : porDefecto;
    }

    /** Promedia los canales de PCM de 16 bits. */
    private static byte[] aMono(byte[] pcm, int canales) {
        if (canales <= 1) return pcm;
        int marcos = pcm.length / (2 * canales);
        byte[] r = new byte[marcos * 2];
        for (int i = 0; i < marcos; i++) {
            int suma = 0;
            for (int c = 0; c < canales; c++) {
                int p = (i * canales + c) * 2;
                suma += (short) ((pcm[p] & 0xFF) | (pcm[p + 1] << 8));
            }
            int v = suma / canales;
            r[2 * i] = (byte) (v & 0xFF);
            r[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return r;
    }

    private static short[] aCortos(byte[] pcm) {
        short[] r = new short[pcm.length / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        }
        return r;
    }

    private static byte[] aBytes(short[] m) {
        byte[] r = new byte[m.length * 2];
        for (int i = 0; i < m.length; i++) {
            r[2 * i] = (byte) (m[i] & 0xFF);
            r[2 * i + 1] = (byte) ((m[i] >> 8) & 0xFF);
        }
        return r;
    }

    /** Cambia la frecuencia: promedia si baja, interpola si sube. */
    static byte[] remuestrear(byte[] pcm, int desde, int hasta) {
        short[] in = aCortos(pcm);
        int n = in.length;
        if (n == 0) return pcm;
        int m = (int) ((long) n * hasta / desde);
        short[] out = new short[m];
        double r = (double) desde / hasta;
        for (int i = 0; i < m; i++) {
            double a = i * r;
            if (r <= 1) {
                int i0 = Math.min((int) a, n - 1);
                int i1 = Math.min(i0 + 1, n - 1);
                double fr = a - i0;
                out[i] = (short) Math.round(in[i0] + (in[i1] - in[i0]) * fr);
            } else {
                int i0 = Math.min((int) a, n - 1);
                int i1 = Math.min(Math.max((int) Math.ceil((i + 1) * r), i0 + 1), n);
                long suma = 0;
                for (int k = i0; k < i1; k++) suma += in[k];
                out[i] = (short) (suma / (i1 - i0));
            }
        }
        return aBytes(out);
    }

    // ---------- Montaje ----------

    /** Sube o baja el sonido hasta que su punto más alto sea la fracción pedida del máximo. */
    static byte[] normalizarPico(byte[] pcm, double objetivo) {
        short[] m = aCortos(pcm);
        int pico = 0;
        for (short v : m) pico = Math.max(pico, Math.abs((int) v));
        if (pico < 50) return pcm;
        double g = objetivo * 32767 / pico;
        for (int i = 0; i < m.length; i++) {
            m[i] = (short) Math.max(-32768, Math.min(32767, Math.round(m[i] * g)));
        }
        return aBytes(m);
    }

    /** El sonido decodificado a 24 kHz, mono, listo para ponerlo entre la voz. */
    static byte[] paraEfecto(Context c, Sonido s) throws Exception {
        return normalizarPico(decodificar(archivo(c, s), Efectos.TASA), 0.5);
    }

    /**
     * Mezcla el fondo debajo de la voz, en todo el audio. El fondo se repite las veces que haga
     * falta, con un cruce de un segundo para que no se note el salto, entra con un desvanecimiento
     * de uno y medio segundos y sale con uno de dos y medio. Su fuerza se mide contra la voz, así
     * que suena parecido sin importar cómo venga grabado el sonido.
     */
    static byte[] mezclarFondo(byte[] voz, int hz, byte[] fondo, double nivel) {
        int n = voz.length / 2;
        short[] bg = aCortos(fondo);
        int largo = bg.length;
        if (n == 0 || largo < hz) return voz;

        double sumaVoz = 0;
        long cuentaVoz = 0;
        for (int i = 0; i < n; i++) {
            int v = (short) ((voz[2 * i] & 0xFF) | (voz[2 * i + 1] << 8));
            if (Math.abs(v) > 500) {
                sumaVoz += (double) v * v;
                cuentaVoz++;
            }
        }
        double rmsVoz = cuentaVoz > 0 ? Math.sqrt(sumaVoz / cuentaVoz) : 3000;
        double sumaFondo = 0;
        for (short v : bg) sumaFondo += (double) v * v;
        double rmsFondo = Math.sqrt(sumaFondo / largo);
        if (rmsFondo < 1) return voz;
        double ganancia = rmsVoz * nivel / rmsFondo;

        int cruce = Math.min(hz, largo / 4);
        int ciclo = largo - cruce;
        int entra = (int) (1.5 * hz);
        int sale = (int) (2.5 * hz);
        byte[] r = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int p = i % ciclo;
            double b = bg[p];
            if (p < cruce) {
                double t = (double) p / cruce * Math.PI / 2;
                b = bg[p] * Math.sin(t) + bg[ciclo + p] * Math.cos(t);
            }
            double f = 1;
            if (i < entra) f = (double) i / entra;
            int resta = n - i;
            if (resta < sale) f = Math.min(f, (double) resta / sale);
            double v = (short) ((voz[2 * i] & 0xFF) | (voz[2 * i + 1] << 8)) + b * ganancia * f;
            int q = (int) Math.max(-32768, Math.min(32767, Math.round(v)));
            r[2 * i] = (byte) (q & 0xFF);
            r[2 * i + 1] = (byte) ((q >> 8) & 0xFF);
        }
        return r;
    }
}
