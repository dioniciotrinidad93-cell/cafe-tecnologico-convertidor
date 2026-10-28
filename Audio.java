package com.cafetecnologico.convertidor;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lógica de audio y texto sin dependencias de Android.
 * Audio PCM de 16 bits: lectura de WAV, fundido de salida, silencio y unión de bloques.
 */
final class Audio {

    final byte[] pcm;
    final int hz;
    final int canales;

    Audio(byte[] pcm, int hz, int canales) {
        this.pcm = pcm;
        this.hz = hz;
        this.canales = canales;
    }

    // ---------- Lectura ----------

    static boolean esWav(byte[] d) {
        return d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E';
    }

    /** Acepta un WAV completo o PCM sin cabecera (24 kHz, mono, 16 bits). */
    static Audio desdeBytes(byte[] datos) {
        if (datos == null || datos.length == 0) {
            throw new IllegalArgumentException("El audio llegó vacío.");
        }
        if (!esWav(datos)) {
            return new Audio(recortarPar(datos, 1), 24000, 1);
        }
        int hz = 24000, canales = 1, bits = 16;
        int ini = -1, fin = datos.length;
        ByteBuffer b = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN);
        long pos = 12;
        while (pos + 8 <= datos.length) {
            int p = (int) pos;
            String id = new String(datos, p, 4, StandardCharsets.US_ASCII);
            long tam = b.getInt(p + 4) & 0xFFFFFFFFL;
            if (id.equals("fmt ") && p + 8 + 16 <= datos.length) {
                canales = b.getShort(p + 10);
                hz = b.getInt(p + 12);
                bits = b.getShort(p + 22);
            } else if (id.equals("data")) {
                ini = p + 8;
                long f = ini + tam;
                boolean sinTamano = tam == 0 || tam == 0xFFFFFFFFL || f > datos.length;
                fin = sinTamano ? datos.length : (int) f;
                break;
            }
            pos = pos + 8 + tam + (tam & 1);
        }
        if (ini < 0) {
            ini = Math.min(44, datos.length);
            fin = datos.length;
        }
        if (bits != 16) {
            throw new IllegalArgumentException("Formato de audio no compatible: " + bits + " bits.");
        }
        if (canales < 1) canales = 1;
        if (hz < 8000) hz = 24000;
        byte[] pcm = new byte[fin - ini];
        System.arraycopy(datos, ini, pcm, 0, pcm.length);
        return new Audio(recortarPar(pcm, canales), hz, canales);
    }

    private static byte[] recortarPar(byte[] pcm, int canales) {
        int marco = 2 * canales;
        int largo = pcm.length - (pcm.length % marco);
        if (largo == pcm.length) return pcm;
        byte[] r = new byte[largo];
        System.arraycopy(pcm, 0, r, 0, largo);
        return r;
    }

    // ---------- Procesado ----------

    /** Fundido de salida tipo Hann sobre los últimos ms milisegundos. */
    static byte[] fundidoSalida(byte[] pcm, int hz, int canales, int ms) {
        byte[] r = pcm.clone();
        int marcos = r.length / (2 * canales);
        int n = Math.min(marcos, hz * ms / 1000);
        if (n < 2) return r;
        ByteBuffer b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            double w = 0.5 * (1 + Math.cos(Math.PI * i / (n - 1)));
            int marco = marcos - n + i;
            for (int c = 0; c < canales; c++) {
                int idx = (marco * canales + c) * 2;
                b.putShort(idx, (short) Math.round(b.getShort(idx) * w));
            }
        }
        return r;
    }

    static byte[] silencio(int hz, int canales, int ms) {
        return new byte[(hz * ms / 1000) * canales * 2];
    }

    /**
     * Une los bloques: fundido de 50 ms al final de cada uno, pausa entre bloques
     * y silencio final. Devuelve un WAV completo.
     */
    static byte[] unir(List<Audio> bloques, int pausaMs, int silencioFinalMs) {
        if (bloques.isEmpty()) throw new IllegalArgumentException("No hay audio que unir.");
        int hz = bloques.get(0).hz;
        int canales = bloques.get(0).canales;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < bloques.size(); i++) {
            Audio a = bloques.get(i);
            if (a.hz != hz || a.canales != canales) {
                throw new IllegalArgumentException("Los bloques de audio no coinciden en formato.");
            }
            byte[] f = fundidoSalida(a.pcm, hz, canales, 50);
            out.write(f, 0, f.length);
            if (i < bloques.size() - 1 && pausaMs > 0) {
                byte[] s = silencio(hz, canales, pausaMs);
                out.write(s, 0, s.length);
            }
        }
        byte[] fin = silencio(hz, canales, silencioFinalMs);
        out.write(fin, 0, fin.length);
        return aWav(out.toByteArray(), hz, canales);
    }

    static byte[] aWav(byte[] pcm, int hz, int canales) {
        int bits = 16;
        ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + pcm.length);
        bb.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) canales);
        bb.putInt(hz);
        bb.putInt(hz * canales * bits / 8);
        bb.putShort((short) (canales * bits / 8));
        bb.putShort((short) bits);
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(pcm.length);
        bb.put(pcm);
        return bb.array();
    }

    // ---------- Texto ----------

    static int bytesUtf8(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            i += Character.charCount(cp);
        }
        return n;
    }

    /** Parte el texto en bloques de como máximo maxBytes bytes UTF-8, cortando en frases si puede. */
    static List<String> dividir(String texto, int maxBytes) {
        List<String> r = new ArrayList<>();
        String resto = texto == null ? "" : texto.trim();
        while (!resto.isEmpty()) {
            if (bytesUtf8(resto) <= maxBytes) {
                r.add(resto);
                break;
            }
            int limite = 0, acumulado = 0;
            while (limite < resto.length()) {
                int cp = resto.codePointAt(limite);
                int tam = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
                if (acumulado + tam > maxBytes) break;
                acumulado += tam;
                limite += Character.charCount(cp);
            }
            if (limite <= 0) limite = Math.min(resto.length(), 1);
            int minimo = limite / 3;
            int corte = -1;
            for (int i = limite - 1; i >= minimo; i--) {
                char c = resto.charAt(i);
                if (c == '\n') { corte = i + 1; break; }
                if ((c == '.' || c == '!' || c == '?' || c == '…')
                        && (i + 1 >= limite || Character.isWhitespace(resto.charAt(i + 1)))) {
                    corte = i + 1;
                    break;
                }
            }
            if (corte < 0) {
                for (int i = limite - 1; i >= minimo; i--) {
                    if (Character.isWhitespace(resto.charAt(i))) { corte = i + 1; break; }
                }
            }
            if (corte < 0) corte = limite;
            String trozo = resto.substring(0, corte).trim();
            if (!trozo.isEmpty()) r.add(trozo);
            resto = resto.substring(corte).trim();
        }
        return r;
    }

    /** Quita lo que Android no admite en nombres de archivo. Devuelve "" si no queda nada. */
    static String nombreSeguro(String entrada) {
        String s = entrada == null ? "" : entrada.trim();
        if (s.toLowerCase(Locale.ROOT).endsWith(".wav")) s = s.substring(0, s.length() - 4);
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.startsWith(".") || s.endsWith(".")) {
            if (s.startsWith(".")) s = s.substring(1).trim();
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        }
        if (s.length() > 100) s = s.substring(0, 100).trim();
        return s;
    }
}
