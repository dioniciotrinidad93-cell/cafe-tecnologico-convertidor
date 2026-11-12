package com.cafetecnologico.convertidor;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Sonidos de ambiente, de emociones y música. No viajan dentro del APK: se descargan una sola vez,
 * en dos paquetes zip (sonidos y música clásica) que están en la raíz del repositorio, y se guardan
 * en la memoria de la app. Además, cada persona puede agregar sus propios sonidos.
 * Se pueden usar de fondo de todo el audio, como efecto con una etiqueta [sonido lluvia 1], o
 * para cambiar de fondo en medio del audio con una etiqueta [fondo lluvia 1].
 */
final class Ambientes {

    private Ambientes() {}

    static final int PAQ_AMBIENTE = 0;
    static final int PAQ_MUSICA = 1;
    /** Los zip están en la raíz del repositorio (rama main), junto a los archivos de la app. */
    static final String[] URL_PAQUETES = {
        MainActivity.REPO + "/raw/main/sonidos-ambiente-cafe-tecnologico.zip",
        MainActivity.REPO + "/raw/main/musica-clasica-cafe-tecnologico.zip"
    };
    static final int[] MEGAS_PAQUETES = {20, 19};
    /** Cómo se llama cada paquete en los avisos, con su artículo. */
    static final String[] NOMBRES_PAQUETES = {"el paquete de sonidos", "la música clásica"};
    static final String PREFIJO_ETIQUETA = "sonido ";
    static final String PREFIJO_FONDO = "fondo ";

    static final int CAT_MUSICA = 9;
    static final int CAT_PROPIOS = 10;

    static final String[] CATEGORIAS = {
        "Lluvia y tormenta",
        "Parque y naturaleza",
        "Cafetería",
        "Oficina e interiores",
        "Noche, suspenso y terror",
        "Felicidad",
        "Tristeza y calma",
        "Ficción y magia",
        "Tren y viaje",
        "Música clásica",
        "Mis sonidos"
    };

    /** De este largo en adelante un sonido sirve de fondo; hasta este otro, como efecto. */
    static final double MIN_FONDO_S = 8;
    static final double MAX_EFECTO_S = 30;

    /** Lo que dura el fundido cruzado cuando el fondo cambia de un sonido a otro, en segundos. */
    static final double TRANSICION_S = 3.0;
    /** Máximo de cambios de fondo por audio. */
    static final int MAX_FONDOS = 10;

    static final String[] NOMBRES_VOLUMEN = {"bajo", "medio", "alto"};
    /** Fuerza del fondo respecto a la voz: unos 18, 12 y 7 decibeles por debajo. */
    static final double[] NIVELES_VOLUMEN = {0.12, 0.25, 0.45};

    // Sonidos propios: límites
    static final long MAX_PROPIO_BYTES = 40L * 1024 * 1024;
    static final int MAX_PROPIO_S = 600;
    static final int MAX_PROPIOS = 60;

    static final class Sonido {
        final String archivo;
        final String nombre;
        final String etiqueta;
        final int[] categorias;
        final double segundos;
        final boolean fondo;
        final boolean efecto;
        /** Lo subió la persona; no viene en ninguno de los paquetes. */
        final boolean propio;
        /** Paquete de descarga al que pertenece; -1 en los propios. */
        final int paquete;

        Sonido(String archivo, String nombre, int[] categorias, double segundos) {
            this(archivo, nombre, categorias, segundos, false);
        }

        Sonido(String archivo, String nombre, int[] categorias, double segundos, boolean propio) {
            this.archivo = archivo;
            this.nombre = nombre;
            this.etiqueta = PREFIJO_ETIQUETA + normalizar(nombre.replace(",", ""));
            this.categorias = categorias;
            this.segundos = segundos;
            this.fondo = segundos >= MIN_FONDO_S;
            this.efecto = segundos <= MAX_EFECTO_S;
            this.propio = propio;
            boolean musica = false;
            for (int c : categorias) if (c == CAT_MUSICA) musica = true;
            this.paquete = propio ? -1 : (musica ? PAQ_MUSICA : PAQ_AMBIENTE);
        }

        /** La etiqueta que cambia el fondo a este sonido: "fondo lluvia 1". */
        String etiquetaFondo() {
            return PREFIJO_FONDO + etiqueta.substring(PREFIJO_ETIQUETA.length());
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
        // Música clásica: piano solo, grabaciones de Musopen. Paquete aparte.
        "01-beethoven-claro-de-luna.mp3|Claro de luna, Beethoven|9|150.0",
        "02-beethoven-patetica-adagio.mp3|Patética, Beethoven|9|150.0",
        "03-chopin-nocturno-op9-no2.mp3|Nocturno, Chopin|9|150.0",
        "04-chopin-preludio-op28-no15-gota-de-lluvia.mp3|Gota de lluvia, Chopin|9|120.0",
        "05-mozart-sonata-k545-allegro.mp3|Sonata fácil, Mozart|9|132.0",
        "06-schubert-impromptu-op90-no3.mp3|Impromptu, Schubert|9|150.0",
        "07-bach-variaciones-goldberg-aria.mp3|Aria Goldberg, Bach|9|150.0",
        "08-satie-gimnopedia-no1.mp3|Gimnopedia, Satie|9|150.0",
    };

    static final List<Sonido> TODOS = new ArrayList<>();
    static final Map<String, Sonido> POR_ETIQUETA = new HashMap<>();
    static final Map<String, Sonido> POR_ARCHIVO = new HashMap<>();
    /** Los sonidos que subió la persona. Se leen y se cambian siempre dentro de synchronized. */
    static final List<Sonido> PROPIOS = new ArrayList<>();

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

    /** El sonido que corresponde a una etiqueta ya normalizada, o null. Busca también en los propios. */
    static Sonido buscar(String etiquetaNormalizada) {
        Sonido s = POR_ETIQUETA.get(etiquetaNormalizada);
        if (s != null) return s;
        synchronized (PROPIOS) {
            for (Sonido p : PROPIOS) {
                if (p.etiqueta.equals(etiquetaNormalizada)) return p;
            }
        }
        return null;
    }

    /** El sonido de un archivo, sea del paquete o propio, o null. */
    static Sonido porArchivo(String archivo) {
        if (archivo == null || archivo.isEmpty()) return null;
        Sonido s = POR_ARCHIVO.get(archivo);
        if (s != null) return s;
        synchronized (PROPIOS) {
            for (Sonido p : PROPIOS) {
                if (p.archivo.equals(archivo)) return p;
            }
        }
        return null;
    }

    /** "fondo lluvia 1" (ya normalizada): el sonido que se usará de fondo, o null si no existe o es muy corto. */
    static Sonido buscarFondo(String etiquetaNormalizada) {
        if (!etiquetaNormalizada.startsWith(PREFIJO_FONDO)) return null;
        Sonido s = buscar(PREFIJO_ETIQUETA + etiquetaNormalizada.substring(PREFIJO_FONDO.length()));
        return s != null && s.fondo ? s : null;
    }

    /** Las etiquetas que quitan el fondo desde ese punto del texto. */
    static boolean esQuitarFondo(String etiquetaNormalizada) {
        return etiquetaNormalizada.equals("sin fondo") || etiquetaNormalizada.equals("quitar fondo")
                || etiquetaNormalizada.equals("fondo ninguno");
    }

    static List<Sonido> deCategoria(int categoria) {
        if (categoria == CAT_PROPIOS) {
            synchronized (PROPIOS) {
                return new ArrayList<>(PROPIOS);
            }
        }
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

    static File carpetaPropios(Context c) {
        File d = new File(c.getFilesDir(), "sonidos_propios");
        d.mkdirs();
        return d;
    }

    static File archivo(Context c, Sonido s) {
        return new File(s.propio ? carpetaPropios(c) : carpeta(c), s.archivo);
    }

    static boolean disponible(Context c, Sonido s) {
        File f = archivo(c, s);
        return f.isFile() && f.length() > 0;
    }

    /** Cuántos sonidos de un paquete están descargados. */
    static int cuantosDisponibles(Context c, int paquete) {
        int n = 0;
        for (Sonido s : TODOS) if (s.paquete == paquete && disponible(c, s)) n++;
        return n;
    }

    static int totalDe(int paquete) {
        int n = 0;
        for (Sonido s : TODOS) if (s.paquete == paquete) n++;
        return n;
    }

    /** Borra los sonidos descargados de los dos paquetes. Los sonidos propios no se tocan. */
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
     * Descarga un paquete y guarda cada sonido conocido en la memoria de la app. Los nombres de
     * los archivos del zip se toman de la lista de arriba, nunca de lo que diga el zip, así que
     * nada puede escribirse fuera de la carpeta. Devuelve cuántos sonidos del paquete quedaron.
     */
    static int descargar(Context c, int paquete, Progreso progreso) throws Exception {
        File dir = carpeta(c);
        File zip = new File(dir, "paquete" + paquete + ".zip.part");
        HttpURLConnection con = (HttpURLConnection) new URL(URL_PAQUETES[paquete]).openConnection();
        try {
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);
            int cod = con.getResponseCode();
            if (cod == 404) {
                throw new Exception("Todavía no está publicado en GitHub " + NOMBRES_PAQUETES[paquete]
                        + " (error 404). Avísale a Dionisio.");
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
                Sonido conocido = POR_ARCHIVO.get(nombre);
                if (conocido == null || conocido.paquete != paquete) continue;
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
        return cuantosDisponibles(c, paquete);
    }

    // ---------- Sonidos propios ----------

    private static int contadorId = 0;

    private static synchronized String nuevoId() {
        return Long.toString(System.currentTimeMillis(), 36) + "-" + (contadorId++);
    }

    private static File archivoIndice(Context c) {
        return new File(carpetaPropios(c), "indice.txt");
    }

    /** Lee la lista de sonidos propios guardada. Se llama al abrir la app. */
    static void cargarPropios(Context c) {
        List<Sonido> nuevos = new ArrayList<>();
        File idx = archivoIndice(c);
        if (idx.isFile()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(idx), "UTF-8"))) {
                String linea;
                while ((linea = br.readLine()) != null) {
                    String[] p = linea.split("\t");
                    if (p.length < 3) continue;
                    if (p[0].contains("/") || p[0].contains("\\") || p[0].contains("..")) continue;
                    File f = new File(carpetaPropios(c), p[0]);
                    if (!f.isFile() || f.length() == 0) continue;
                    double seg;
                    try {
                        seg = Double.parseDouble(p[2]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    nuevos.add(new Sonido(p[0], p[1], new int[] {CAT_PROPIOS}, seg, true));
                }
            } catch (Exception ignorada) {
                // si la lista no se puede leer, se empieza con lo que haya
            }
        }
        synchronized (PROPIOS) {
            PROPIOS.clear();
            PROPIOS.addAll(nuevos);
        }
    }

    private static void guardarIndice(Context c) throws Exception {
        List<Sonido> copia;
        synchronized (PROPIOS) {
            copia = new ArrayList<>(PROPIOS);
        }
        File tmp = new File(carpetaPropios(c), "indice.txt.part");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
            for (Sonido s : copia) {
                w.write(s.archivo + "\t" + s.nombre + "\t" + s.segundos + "\n");
            }
        }
        File idx = archivoIndice(c);
        idx.delete();
        if (!tmp.renameTo(idx)) throw new Exception("No pude guardar la lista de tus sonidos.");
    }

    /** Deja solo letras, números y espacios, con un máximo de 40 caracteres. */
    static String limpiarNombre(String bruto) {
        String n = bruto == null ? "" : bruto;
        n = n.replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        if (n.length() > 40) n = n.substring(0, 40).trim();
        return n;
    }

    /** El nombre de un archivo, sin la extensión y limpio, para usarlo como nombre del sonido. */
    static String nombreDeArchivo(String nombreCompleto) {
        String n = nombreCompleto == null ? "" : nombreCompleto;
        int punto = n.lastIndexOf('.');
        if (punto > 0) n = n.substring(0, punto);
        n = limpiarNombre(n);
        return n.isEmpty() ? "Sonido mío" : n;
    }

    private static String extension(String nombreCompleto) {
        if (nombreCompleto != null) {
            int p = nombreCompleto.lastIndexOf('.');
            if (p >= 0 && p < nombreCompleto.length() - 1) {
                String e = nombreCompleto.substring(p + 1).toLowerCase(Locale.ROOT);
                if (e.matches("[a-z0-9]{1,5}")) return e;
            }
        }
        return "audio";
    }

    private static boolean etiquetaUsada(String etiqueta, String excluirArchivo) {
        if (POR_ETIQUETA.containsKey(etiqueta)) return true;
        synchronized (PROPIOS) {
            for (Sonido p : PROPIOS) {
                if (p.etiqueta.equals(etiqueta) && !p.archivo.equals(excluirArchivo)) return true;
            }
        }
        return false;
    }

    /** Un nombre cuya etiqueta no choque con otro sonido: si ya existe, agrega 2, 3... */
    static String nombreLibre(String base, String excluirArchivo) {
        String b = (base == null || normalizar(base).isEmpty()) ? "Sonido mío" : base;
        String nombre = b;
        int k = 2;
        while (etiquetaUsada(PREFIJO_ETIQUETA + normalizar(nombre), excluirArchivo)) {
            nombre = b + " " + (k++);
        }
        return nombre;
    }

    static String nombreDeUri(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (q != null && q.moveToFirst()) {
                String n = q.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignorada) {
            // se usa el final de la ruta
        }
        String u = uri.getLastPathSegment();
        return u == null ? "sonido" : u;
    }

    private static void copiar(Context c, Uri uri, File destino) throws Exception {
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(destino)) {
            if (in == null) throw new Exception("No pude abrir el archivo.");
            byte[] buf = new byte[32768];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PROPIO_BYTES) {
                    throw new Exception("Pesa más de " + (MAX_PROPIO_BYTES / 1024 / 1024)
                            + " megas. Recórtalo o conviértelo a MP3 y vuelve a intentarlo.");
                }
                out.write(buf, 0, n);
            }
        }
    }

    /** Duración en segundos según el archivo; si no la trae, se mide decodificándolo. */
    private static double duracionSegundos(File f) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat t = ex.getTrackFormat(i);
                String mime = t.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/") && t.containsKey(MediaFormat.KEY_DURATION)) {
                    long us = t.getLong(MediaFormat.KEY_DURATION);
                    if (us > 0) return us / 1000000.0;
                }
            }
        } finally {
            ex.release();
        }
        byte[] todo = decodificar(f, Efectos.TASA, (long) (MAX_PROPIO_S + 1) * Efectos.TASA);
        return todo.length / 2.0 / Efectos.TASA;
    }

    /** Comprueba que Android puede leer el archivo y devuelve lo que dura, en décimas de segundo. */
    private static double validarYMedir(File f) throws Exception {
        byte[] prueba;
        try {
            prueba = decodificar(f, Efectos.TASA, Efectos.TASA);
        } catch (Exception e) {
            throw new Exception("Android no pudo leerlo como audio. Prueba con un MP3, M4A, OGG o WAV.");
        }
        if (prueba.length < Efectos.TASA / 5) throw new Exception("No tiene audio.");
        double seg = duracionSegundos(f);
        if (seg < 0.1) throw new Exception("No tiene audio.");
        if (seg > MAX_PROPIO_S) {
            throw new Exception("Dura más de " + (MAX_PROPIO_S / 60) + " minutos. Recórtalo y vuelve a intentarlo.");
        }
        return Math.round(seg * 10) / 10.0;
    }

    /** Copia un archivo de audio elegido por la persona a la memoria de la app y lo registra. */
    static Sonido importarPropio(Context c, Uri uri) throws Exception {
        synchronized (PROPIOS) {
            if (PROPIOS.size() >= MAX_PROPIOS) {
                throw new Exception("Ya tienes " + MAX_PROPIOS + " sonidos propios, que es el máximo. "
                        + "Borra alguno para agregar otro.");
            }
        }
        String original = nombreDeUri(c, uri);
        String archivo = "propio-" + nuevoId() + "." + extension(original);
        File dir = carpetaPropios(c);
        File parte = new File(dir, archivo + ".part");
        File destino = new File(dir, archivo);
        try {
            copiar(c, uri, parte);
            if (!parte.renameTo(destino)) throw new Exception("No pude guardar el archivo.");
            double seg = validarYMedir(destino);
            String nombre = nombreLibre(nombreDeArchivo(original), "");
            Sonido s = new Sonido(archivo, nombre, new int[] {CAT_PROPIOS}, seg, true);
            synchronized (PROPIOS) {
                PROPIOS.add(s);
            }
            try {
                guardarIndice(c);
            } catch (Exception e) {
                synchronized (PROPIOS) {
                    PROPIOS.remove(s);
                }
                throw e;
            }
            return s;
        } catch (Exception e) {
            parte.delete();
            destino.delete();
            throw e;
        }
    }

    /** Cambia el nombre de un sonido propio y devuelve el sonido ya con el nombre nuevo. */
    static Sonido renombrarPropio(Context c, Sonido s, String nuevoNombre) throws Exception {
        String base = limpiarNombre(nuevoNombre);
        if (base.isEmpty()) throw new Exception("El nombre solo puede llevar letras, números y espacios.");
        String nombre = nombreLibre(base, s.archivo);
        Sonido nuevo = new Sonido(s.archivo, nombre, s.categorias, s.segundos, true);
        synchronized (PROPIOS) {
            int i = -1;
            for (int k = 0; k < PROPIOS.size(); k++) {
                if (PROPIOS.get(k).archivo.equals(s.archivo)) i = k;
            }
            if (i < 0) throw new Exception("Ese sonido ya no está en tu lista.");
            PROPIOS.set(i, nuevo);
        }
        guardarIndice(c);
        return nuevo;
    }

    static void borrarPropio(Context c, Sonido s) throws Exception {
        synchronized (PROPIOS) {
            for (int k = PROPIOS.size() - 1; k >= 0; k--) {
                if (PROPIOS.get(k).archivo.equals(s.archivo)) PROPIOS.remove(k);
            }
        }
        archivo(c, s).delete();
        guardarIndice(c);
    }

    // ---------- Decodificación ----------

    /** Decodifica un OGG, MP3, M4A o WAV a PCM de 16 bits, mono, a la frecuencia pedida. */
    static byte[] decodificar(File f, int hzDestino) throws Exception {
        return decodificar(f, hzDestino, Long.MAX_VALUE);
    }

    /**
     * Igual, pero se detiene al reunir maxMuestras muestras (ya a la frecuencia pedida), para no
     * decodificar una pista larga entera cuando el audio es corto.
     */
    static byte[] decodificar(File f, int hzDestino, long maxMuestras) throws Exception {
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
            String mimeElegido = fmt.getString(MediaFormat.KEY_MIME);
            if ("audio/raw".equals(mimeElegido)) {
                return leerCrudo(ex, fmt, hzDestino, maxMuestras, f.getName());
            }
            codec = MediaCodec.createDecoderByType(mimeElegido);
            codec.configure(fmt, null, null, 0);
            codec.start();

            int hz = entero(fmt, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int canales = entero(fmt, MediaFormat.KEY_CHANNEL_COUNT, 1);
            long tope = limiteBytes(maxMuestras, hz, hzDestino);
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
                    if (mono.size() >= tope) fin = true;
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    hz = entero(nf, MediaFormat.KEY_SAMPLE_RATE, hz);
                    canales = entero(nf, MediaFormat.KEY_CHANNEL_COUNT, canales);
                    tope = limiteBytes(maxMuestras, hz, hzDestino);
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

    /** Cuántos bytes mono de 16 bits, a la frecuencia de origen, valen maxMuestras a la de destino. */
    private static long limiteBytes(long maxMuestras, int hz, int hzDestino) {
        if (maxMuestras >= Long.MAX_VALUE / 4) return Long.MAX_VALUE;
        return (maxMuestras * hz / hzDestino + 2) * 2;
    }

    /** Los WAV sin comprimir: Android los entrega tal cual, sin decodificador. */
    private static byte[] leerCrudo(MediaExtractor ex, MediaFormat fmt, int hzDestino,
                                    long maxMuestras, String nombre) throws Exception {
        int hz = entero(fmt, MediaFormat.KEY_SAMPLE_RATE, 44100);
        int canales = Math.max(1, entero(fmt, MediaFormat.KEY_CHANNEL_COUNT, 1));
        int codif = entero(fmt, MediaFormat.KEY_PCM_ENCODING, 2);
        if (codif != 2 && codif != 3 && codif != 4) {
            throw new Exception("El formato de " + nombre + " no es compatible.");
        }
        long tope = limiteBytes(maxMuestras, hz, hzDestino);
        byte[] arr = new byte[1 << 18];
        ByteBuffer b = ByteBuffer.wrap(arr);
        ByteArrayOutputStream mono = new ByteArrayOutputStream(1 << 20);
        while (true) {
            int n = ex.readSampleData(b, 0);
            if (n < 0) break;
            byte[] m = aMono(aDieciseis(arr, n, codif), canales);
            mono.write(m, 0, m.length);
            ex.advance();
            if (mono.size() >= tope) break;
        }
        byte[] pcm = mono.toByteArray();
        return hz == hzDestino ? pcm : remuestrear(pcm, hz, hzDestino);
    }

    /** PCM de 8 bits, de 16 o decimal de 32, convertido a 16 bits. */
    private static byte[] aDieciseis(byte[] d, int n, int codif) {
        if (codif == 2) {
            byte[] r = new byte[n - (n % 2)];
            System.arraycopy(d, 0, r, 0, r.length);
            return r;
        }
        if (codif == 3) {
            byte[] r = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                int v = ((d[i] & 0xFF) - 128) << 8;
                r[2 * i] = (byte) (v & 0xFF);
                r[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
            }
            return r;
        }
        int m = n / 4;
        byte[] r = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            int bits = (d[4 * i] & 0xFF) | ((d[4 * i + 1] & 0xFF) << 8)
                    | ((d[4 * i + 2] & 0xFF) << 16) | ((d[4 * i + 3] & 0xFF) << 24);
            float x = Math.max(-1f, Math.min(1f, Float.intBitsToFloat(bits)));
            int v = Math.round(x * 32767f);
            r[2 * i] = (byte) (v & 0xFF);
            r[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return r;
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

    static short[] aCortos(byte[] pcm) {
        short[] r = new short[pcm.length / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        }
        return r;
    }

    static byte[] aBytes(short[] m) {
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

    /** Desde qué segundo del audio suena un fondo, y cuál; con archivo null, el fondo se quita. */
    static final class TramoFondo {
        final double segundos;
        final File archivo;

        TramoFondo(double segundos, File archivo) {
            this.segundos = segundos;
            this.archivo = archivo;
        }
    }

    private static final class Tramo {
        final int inicio;
        final File archivo;

        Tramo(int inicio, File archivo) {
            this.inicio = inicio;
            this.archivo = archivo;
        }
    }

    /**
     * Mezcla el fondo debajo de la voz, en todo el audio, y lo cambia de un sonido a otro donde
     * haya tramos nuevos. Cada fondo se repite las veces que haga falta, con un cruce de un segundo
     * para que no se note el salto. El primero entra con un desvanecimiento de uno y medio segundos
     * y el último sale con uno de dos y medio. Entre un fondo y el siguiente hay un fundido
     * cruzado de TRANSICION_S segundos: el anterior baja y el nuevo sube a la vez, sin que la voz
     * se detenga. La fuerza de cada fondo se mide contra la voz, así que suena parecido sin
     * importar cómo venga grabado el sonido. Cambia el arreglo de la voz y lo devuelve.
     */
    static byte[] mezclarFondos(byte[] voz, int hz, List<TramoFondo> tramos, double nivel)
            throws Exception {
        int n = voz.length / 2;
        if (n == 0 || tramos.isEmpty()) return voz;

        List<Tramo> lista = new ArrayList<>();
        for (TramoFondo t : tramos) {
            int inicio = (int) Math.max(0, Math.min(n - 1, Math.round(t.segundos * hz)));
            lista.add(new Tramo(inicio, t.archivo));
        }
        Collections.sort(lista, (a, b) -> Integer.compare(a.inicio, b.inicio));
        List<Tramo> unicos = new ArrayList<>();
        for (Tramo t : lista) {
            int ult = unicos.size() - 1;
            if (ult >= 0 && unicos.get(ult).inicio == t.inicio) unicos.set(ult, t);
            else unicos.add(t);
        }

        // La fuerza de la voz se mide antes de mezclar nada.
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

        final int transicion = Math.max(1, (int) (TRANSICION_S * hz));
        final int sale = (int) (2.5 * hz);
        for (int k = 0; k < unicos.size(); k++) {
            Tramo t = unicos.get(k);
            if (t.archivo == null) continue;
            boolean haySiguiente = k + 1 < unicos.size();
            int s = t.inicio;
            int e = haySiguiente ? unicos.get(k + 1).inicio : n;
            int fin = haySiguiente ? Math.min(n, e + transicion) : n;
            int pedido = fin - s;
            if (pedido <= 0) continue;

            short[] bg = aCortos(decodificar(t.archivo, hz, pedido));
            int largo = bg.length;
            if (largo < hz) continue;
            double sumaFondo = 0;
            for (short v : bg) sumaFondo += (double) v * v;
            double rmsFondo = Math.sqrt(sumaFondo / largo);
            if (rmsFondo < 1) continue;
            double ganancia = rmsVoz * nivel / rmsFondo;

            boolean recto = largo >= pedido;
            int cruce = Math.min(hz, largo / 4);
            int ciclo = Math.max(1, largo - cruce);
            boolean alInicio = s == 0;
            int entra = alInicio ? (int) (1.5 * hz) : transicion;
            for (int i = s; i < fin; i++) {
                int rel = i - s;
                double b;
                if (recto) {
                    b = bg[rel];
                } else {
                    int p = rel % ciclo;
                    b = bg[p];
                    if (rel >= ciclo && p < cruce) {
                        double a = (double) p / cruce * Math.PI / 2;
                        b = bg[p] * Math.sin(a) + bg[ciclo + p] * Math.cos(a);
                    }
                }
                double f = 1;
                if (rel < entra) {
                    f = alInicio ? (double) rel / entra : Math.sin(Math.PI / 2 * rel / entra);
                }
                if (haySiguiente) {
                    int d = i - e;
                    if (d >= 0) f = Math.min(f, Math.cos(Math.PI / 2 * d / transicion));
                } else {
                    int resta = n - i;
                    if (resta < sale) f = Math.min(f, (double) resta / sale);
                }
                int idx = 2 * i;
                int actual = (short) ((voz[idx] & 0xFF) | (voz[idx + 1] << 8));
                int q = (int) Math.max(-32768, Math.min(32767, Math.round(actual + b * ganancia * f)));
                voz[idx] = (byte) (q & 0xFF);
                voz[idx + 1] = (byte) ((q >> 8) & 0xFF);
            }
        }
        return voz;
    }
}
