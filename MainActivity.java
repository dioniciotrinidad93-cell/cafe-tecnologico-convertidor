package com.cafetecnologico.convertidor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    static final String REPO = "https://github.com/dioniciotrinidad93-cell/cafe-tecnologico-convertidor";
    static final String URL_VERSION = REPO + "/releases/latest/download/version.json";
    static final String URL_APK = REPO + "/releases/latest/download/Convertidor_Cafe_Tecnologico.apk";

    static final int BYTES_POR_BLOQUE = 2500;
    static final int MAX_BLOQUES = 12;
    static final int PAUSA_ENTRE_BLOQUES_MS = 400;
    static final int SILENCIO_FINAL_MS = 3000;
    static final String MSG_DETENIDA = "Conversión detenida.";

    static final String[] MODELOS = {
        "gemini-3.8-flash-tts",
        "gemini-3.8-flash-lite-tts"
    };

    static final String[] VOCES = {
        "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
        "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
        "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
        "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    };

    SharedPreferences prefs;
    EditText campoClave, campoEstilo, campoTexto, campoNombre;
    Spinner listaModelo, listaVoz;
    Button botonConvertir, botonDetener, botonReproducir, botonContar,
            botonActualizar, botonBuscar;
    TextView estado;
    Handler ui = new Handler(Looper.getMainLooper());
    Uri ultimoAudio;
    String ultimoNombre = "";
    MediaPlayer reproductor;
    boolean ocupado = false;
    volatile boolean cancelar = false;
    long codigoVersion = 0;
    String nombreVersion = "?";

    static class ApiException extends Exception {
        final int codigo;
        final String cuerpo;
        ApiException(int codigo, String mensaje, String cuerpo) {
            super(mensaje);
            this.codigo = codigo;
            this.cuerpo = cuerpo == null ? "" : cuerpo;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Convertidor de texto a voz, Café Tecnológico");
        prefs = getSharedPreferences("cafe_tecnologico_convertidor", MODE_PRIVATE);

        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            codigoVersion = pi.getLongVersionCode();
            if (pi.versionName != null) nombreVersion = pi.versionName;
        } catch (Exception ignorada) {
            // se queda con los valores por defecto
        }

        int p = dp(16);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(p, p, p, p);
        sv.addView(raiz);

        TextView titulo = new TextView(this);
        titulo.setText("Convertidor de texto a voz con Gemini. Versión " + nombreVersion);
        titulo.setTextSize(22);
        titulo.setAccessibilityHeading(true);
        raiz.addView(titulo);

        botonActualizar = new Button(this);
        botonActualizar.setText("Descargar la actualización");
        botonActualizar.setVisibility(View.GONE);
        raiz.addView(botonActualizar);

        campoClave = new EditText(this);
        campoClave.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        campoClave.setSingleLine(true);
        campoClave.setText(prefs.getString("clave", ""));
        agregar(raiz, "Clave API de Google Gemini", campoClave);

        listaModelo = new Spinner(this);
        listaModelo.setAdapter(adaptador(MODELOS));
        listaModelo.setSelection(indice(MODELOS, prefs.getString("modelo", MODELOS[0])));
        agregar(raiz, "Modelo de voz", listaModelo);

        listaVoz = new Spinner(this);
        listaVoz.setAdapter(adaptador(VOCES));
        listaVoz.setSelection(indice(VOCES, prefs.getString("voz", VOCES[0])));
        agregar(raiz, "Voz", listaVoz);

        campoEstilo = new EditText(this);
        campoEstilo.setInputType(InputType.TYPE_CLASS_TEXT);
        campoEstilo.setSingleLine(true);
        campoEstilo.setHint("Ejemplo: en tono alegre y pausado");
        campoEstilo.setText(prefs.getString("estilo", ""));
        agregar(raiz, "Estilo de la voz, opcional", campoEstilo);

        campoNombre = new EditText(this);
        campoNombre.setInputType(InputType.TYPE_CLASS_TEXT);
        campoNombre.setSingleLine(true);
        campoNombre.setHint("Si lo dejas vacío se usa la fecha y la hora");
        agregar(raiz, "Nombre del archivo de audio, opcional", campoNombre);

        campoTexto = new EditText(this);
        campoTexto.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        campoTexto.setMinLines(6);
        campoTexto.setGravity(Gravity.TOP | Gravity.START);
        campoTexto.setText(prefs.getString("borrador", ""));
        agregar(raiz, "Texto que quieres convertir en audio", campoTexto);

        botonContar = new Button(this);
        botonContar.setText("Contar caracteres");
        raiz.addView(botonContar);

        botonConvertir = new Button(this);
        botonConvertir.setText("Convertir a audio");
        raiz.addView(botonConvertir);

        botonDetener = new Button(this);
        botonDetener.setText("Detener la conversión");
        botonDetener.setEnabled(false);
        raiz.addView(botonDetener);

        botonReproducir = new Button(this);
        botonReproducir.setText("Reproducir o detener el último audio");
        botonReproducir.setEnabled(false);
        raiz.addView(botonReproducir);

        botonBuscar = new Button(this);
        botonBuscar.setText("Buscar actualizaciones");
        raiz.addView(botonBuscar);

        estado = new TextView(this);
        estado.setTextSize(16);
        estado.setTextIsSelectable(true);
        estado.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        estado.setText("Listo. Escribe el texto y pulsa Convertir a audio.");
        estado.setPadding(0, p, 0, 0);
        raiz.addView(estado);

        botonContar.setOnClickListener(v -> contar());
        botonConvertir.setOnClickListener(v -> convertir());
        botonDetener.setOnClickListener(v -> {
            cancelar = true;
            estado.setText("Deteniendo. Se detendrá al terminar el bloque que se está convirtiendo.");
        });
        botonReproducir.setOnClickListener(v -> reproducir());
        botonBuscar.setOnClickListener(v -> {
            estado.setText("Buscando actualizaciones.");
            buscarActualizacion(true);
        });
        botonActualizar.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(URL_APK)));
                estado.setText("Se abrió el navegador para descargar la actualización. "
                        + "Cuando termine, abre el archivo descargado para instalarla.");
            } catch (Exception e) {
                estado.setText("No se pudo abrir el navegador. " + e.getMessage());
            }
        });

        setContentView(sv);
        buscarActualizacion(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        guardarPrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelar = true;
        if (reproductor != null) {
            reproductor.release();
            reproductor = null;
        }
    }

    // ---------- Interfaz ----------

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    void agregar(LinearLayout raiz, String etiqueta, View campo) {
        TextView t = new TextView(this);
        t.setText(etiqueta);
        t.setTextSize(16);
        t.setPadding(0, dp(12), 0, 0);
        campo.setId(View.generateViewId());
        t.setLabelFor(campo.getId());
        raiz.addView(t);
        raiz.addView(campo);
    }

    ArrayAdapter<String> adaptador(String[] datos) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, datos);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    int indice(String[] lista, String valor) {
        for (int i = 0; i < lista.length; i++) {
            if (lista[i].equals(valor)) return i;
        }
        return 0;
    }

    void guardarPrefs() {
        prefs.edit()
                .putString("clave", campoClave.getText().toString().trim())
                .putString("modelo", (String) listaModelo.getSelectedItem())
                .putString("voz", (String) listaVoz.getSelectedItem())
                .putString("estilo", campoEstilo.getText().toString())
                .putString("borrador", campoTexto.getText().toString())
                .apply();
    }

    void contar() {
        String t = campoTexto.getText().toString().trim();
        int n = campoTexto.getText().toString().length();
        int bloques = Audio.dividir(t, BYTES_POR_BLOQUE).size();
        String m = "El texto tiene " + n + (n == 1 ? " carácter." : " caracteres.");
        if (bloques > 1) m += " Se convertirá en " + bloques + " bloques.";
        if (bloques > MAX_BLOQUES) {
            m += " Es demasiado largo: el máximo es " + MAX_BLOQUES + " bloques.";
        }
        estado.setText(m);
    }

    void avisar(String m) {
        ui.post(() -> estado.setText(m));
    }

    // ---------- Conversión ----------

    void convertir() {
        if (ocupado) return;
        guardarPrefs();
        final String clave = campoClave.getText().toString().trim();
        final String texto = campoTexto.getText().toString().trim();
        final String estilo = campoEstilo.getText().toString().trim();
        final String nombre = campoNombre.getText().toString();
        final String modelo = (String) listaModelo.getSelectedItem();
        final String voz = (String) listaVoz.getSelectedItem();

        if (clave.isEmpty()) {
            estado.setText("Falta la clave API. Escríbela o pégala en el primer campo.");
            campoClave.requestFocus();
            return;
        }
        if (texto.isEmpty()) {
            estado.setText("No hay texto. Escribe lo que quieres convertir.");
            campoTexto.requestFocus();
            return;
        }
        final List<String> bloques = Audio.dividir(texto, BYTES_POR_BLOQUE);
        if (bloques.size() > MAX_BLOQUES) {
            estado.setText("El texto es demasiado largo: tiene " + texto.length()
                    + " caracteres y se convertiría en " + bloques.size() + " bloques. "
                    + "El máximo es " + MAX_BLOQUES + " bloques. Conviértelo en partes.");
            return;
        }

        ocupado = true;
        cancelar = false;
        botonConvertir.setEnabled(false);
        botonDetener.setEnabled(true);
        estado.setText(bloques.size() > 1
                ? "Convirtiendo en " + bloques.size() + " bloques, espera un momento."
                : "Convirtiendo, espera un momento.");

        new Thread(() -> {
            try {
                List<Audio> partes = new ArrayList<>();
                for (int i = 0; i < bloques.size(); i++) {
                    if (cancelar) throw new Exception(MSG_DETENIDA);
                    if (bloques.size() > 1 && i > 0) {
                        avisar("Convirtiendo el bloque " + (i + 1) + " de " + bloques.size() + ".");
                    }
                    byte[] datos = pedirConReintentos(clave, modelo, voz, estilo, bloques.get(i));
                    partes.add(Audio.desdeBytes(datos));
                }
                byte[] wav = Audio.unir(partes, PAUSA_ENTRE_BLOQUES_MS, SILENCIO_FINAL_MS);
                Uri u = guardar(wav, nombre);
                ui.post(() -> terminado(u, null));
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> terminado(null, m));
            }
        }).start();
    }

    void terminado(Uri u, String error) {
        ocupado = false;
        botonConvertir.setEnabled(true);
        botonDetener.setEnabled(false);
        if (error != null) {
            if (MSG_DETENIDA.equals(error)) {
                estado.setText(MSG_DETENIDA);
            } else {
                estado.setText("No se pudo convertir. " + error);
            }
            return;
        }
        ultimoAudio = u;
        botonReproducir.setEnabled(true);
        estado.setText("Listo. El audio se guardó en la carpeta Descargas con el nombre "
                + ultimoNombre + ".");
    }

    void reproducir() {
        if (ultimoAudio == null) return;
        try {
            if (reproductor != null) {
                boolean sonando = reproductor.isPlaying();
                reproductor.release();
                reproductor = null;
                if (sonando) {
                    estado.setText("Reproducción detenida.");
                    return;
                }
            }
            MediaPlayer mp = new MediaPlayer();
            reproductor = mp;
            mp.setDataSource(this, ultimoAudio);
            mp.setOnCompletionListener(m -> {
                m.release();
                if (reproductor == m) reproductor = null;
                estado.setText("Terminó la reproducción.");
            });
            mp.prepare();
            mp.start();
            estado.setText("Reproduciendo.");
        } catch (Exception e) {
            reproductor = null;
            estado.setText("No se pudo reproducir. " + e.getMessage());
        }
    }

    // ---------- Google Gemini ----------

    byte[] pedirConReintentos(String clave, String modelo, String voz, String estilo, String texto)
            throws Exception {
        int intento = 0;
        while (true) {
            if (cancelar) throw new Exception(MSG_DETENIDA);
            try {
                return pedirAudio(clave, modelo, voz, estilo, texto);
            } catch (ApiException e) {
                if (e.codigo != 429 || limiteDiario(e.cuerpo) || intento >= 3) throw e;
                int espera = segundosDeEspera(e.cuerpo, intento);
                intento++;
                avisar("Google pidió esperar por el límite de uso. Reintento " + intento
                        + " de 3 en " + espera + " segundos.");
                for (int s = 0; s < espera; s++) {
                    if (cancelar) throw new Exception(MSG_DETENIDA);
                    Thread.sleep(1000);
                }
            }
        }
    }

    static boolean limiteDiario(String cuerpo) {
        return cuerpo.contains("PerDay") || cuerpo.contains("limit: 0");
    }

    static int segundosDeEspera(String cuerpo, int intento) {
        Matcher m = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)").matcher(cuerpo);
        if (m.find()) {
            try {
                int s = Integer.parseInt(m.group(1)) + 1;
                return Math.max(5, Math.min(s, 90));
            } catch (NumberFormatException ignorada) {
                // usa los tiempos por defecto
            }
        }
        int[] tiempos = {15, 30, 60};
        return tiempos[Math.min(intento, tiempos.length - 1)];
    }

    byte[] pedirAudio(String clave, String modelo, String voz, String estilo, String texto)
            throws Exception {
        String error1;
        try {
            return viaInteractions(clave, modelo, voz, estilo, texto);
        } catch (ApiException e) {
            if (e.codigo != 400 && e.codigo != 404) throw e;
            error1 = e.getMessage();
        }
        try {
            return viaGenerateContent(clave, modelo, voz, estilo, texto);
        } catch (ApiException e2) {
            if (e2.codigo == 429) throw e2;
            throw new Exception("La primera forma de pedir el audio falló: " + error1
                    + " La segunda también: " + e2.getMessage());
        }
    }

    byte[] viaInteractions(String clave, String modelo, String voz, String estilo, String texto)
            throws Exception {
        JSONObject parte = new JSONObject();
        parte.put("type", "text");
        parte.put("text", texto);
        if (!estilo.isEmpty()) {
            JSONObject meta = new JSONObject();
            meta.put("type", "speech_metadata");
            meta.put("style", estilo);
            parte.put("annotations", new JSONArray().put(meta));
        }
        JSONObject entrada = new JSONObject();
        entrada.put("type", "user_input");
        entrada.put("content", new JSONArray().put(parte));

        JSONObject formato = new JSONObject();
        formato.put("type", "audio");

        JSONObject vozCfg = new JSONObject();
        vozCfg.put("voice", voz);
        JSONObject gen = new JSONObject();
        gen.put("speech_config", new JSONArray().put(vozCfg));

        JSONObject cuerpo = new JSONObject();
        cuerpo.put("model", modelo);
        cuerpo.put("input", new JSONArray().put(entrada));
        cuerpo.put("response_format", formato);
        cuerpo.put("generation_config", gen);
        cuerpo.put("stream", false);

        String resp = post(BASE + "/interactions", clave, cuerpo.toString());
        JSONObject r = new JSONObject(resp);
        JSONArray pasos = r.optJSONArray("steps");
        if (pasos != null) {
            for (int i = 0; i < pasos.length(); i++) {
                JSONObject paso = pasos.optJSONObject(i);
                if (paso == null) continue;
                JSONArray cont = paso.optJSONArray("content");
                if (cont == null) continue;
                for (int j = 0; j < cont.length(); j++) {
                    JSONObject item = cont.optJSONObject(j);
                    if (item == null) continue;
                    if ("audio".equals(item.optString("type")) && item.has("data")) {
                        return decodificar(item.getString("data"));
                    }
                }
            }
        }
        throw new Exception("Google respondió, pero sin audio.");
    }

    byte[] viaGenerateContent(String clave, String modelo, String voz, String estilo, String texto)
            throws Exception {
        String t = estilo.isEmpty() ? texto : (estilo + ": " + texto);
        JSONObject parte = new JSONObject();
        parte.put("text", t);
        JSONObject contenido = new JSONObject();
        contenido.put("parts", new JSONArray().put(parte));

        JSONObject prebuilt = new JSONObject();
        prebuilt.put("voiceName", voz);
        JSONObject vozCfg = new JSONObject();
        vozCfg.put("prebuiltVoiceConfig", prebuilt);
        JSONObject habla = new JSONObject();
        habla.put("voiceConfig", vozCfg);
        JSONObject gen = new JSONObject();
        gen.put("responseModalities", new JSONArray().put("AUDIO"));
        gen.put("speechConfig", habla);

        JSONObject cuerpo = new JSONObject();
        cuerpo.put("contents", new JSONArray().put(contenido));
        cuerpo.put("generationConfig", gen);

        String resp = post(BASE + "/models/" + modelo + ":generateContent", clave,
                cuerpo.toString());
        JSONObject r = new JSONObject(resp);
        JSONArray cands = r.optJSONArray("candidates");
        if (cands != null) {
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i);
                if (c == null) continue;
                JSONObject cont = c.optJSONObject("content");
                if (cont == null) continue;
                JSONArray partes = cont.optJSONArray("parts");
                if (partes == null) continue;
                for (int j = 0; j < partes.length(); j++) {
                    JSONObject pt = partes.optJSONObject(j);
                    if (pt == null) continue;
                    JSONObject inl = pt.optJSONObject("inlineData");
                    if (inl == null) inl = pt.optJSONObject("inline_data");
                    if (inl != null && inl.has("data")) {
                        return decodificar(inl.getString("data"));
                    }
                }
            }
        }
        throw new Exception("Google respondió, pero sin audio.");
    }

    String post(String direccion, String clave, String cuerpo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(direccion).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", clave);
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(cuerpo.getBytes("UTF-8"));
        os.close();
        int codigo = c.getResponseCode();
        InputStream is = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = leer(is);
        c.disconnect();
        if (codigo >= 400) throw new ApiException(codigo, mensajeError(resp, codigo), resp);
        return resp;
    }

    String leer(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    String mensajeError(String resp, int codigo) {
        String detalle = "";
        try {
            JSONObject e = new JSONObject(resp).optJSONObject("error");
            if (e != null) detalle = e.optString("message", "");
        } catch (Exception ignorada) {
            // la respuesta no era JSON
        }
        String m = "Google respondió con el error " + codigo;
        if (!detalle.isEmpty()) m += ": " + detalle;
        if (codigo == 429) {
            m += limiteDiario(resp)
                    ? " Se agotó el límite diario de tu clave. Inténtalo mañana o usa otro modelo."
                    : " Se alcanzó el límite de uso de tu clave. Espera un minuto e inténtalo de nuevo.";
        }
        return m;
    }

    byte[] decodificar(String base64) {
        return Base64.decode(base64, Base64.DEFAULT);
    }

    // ---------- Guardar en Descargas ----------

    Uri guardar(byte[] wav, String nombrePedido) throws Exception {
        String base = Audio.nombreSeguro(nombrePedido);
        if (base.isEmpty()) {
            base = "Cafe_Tecnologico_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        String nombre = base + ".wav";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, nombre);
        v.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
        ContentResolver cr = getContentResolver();
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("No se pudo crear el archivo en Descargas.");
        OutputStream os = cr.openOutputStream(u);
        if (os == null) throw new Exception("No se pudo abrir el archivo en Descargas.");
        os.write(wav);
        os.close();
        ultimoNombre = nombreFinal(cr, u, nombre);
        return u;
    }

    /** Android cambia el nombre si ya existe uno igual; aquí se lee el nombre real. */
    String nombreFinal(ContentResolver cr, Uri u, String porDefecto) {
        try (Cursor c = cr.query(u, new String[] {MediaStore.MediaColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignorada) {
            // se usa el nombre pedido
        }
        return porDefecto;
    }

    // ---------- Actualizaciones ----------

    void buscarActualizacion(boolean manual) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(URL_VERSION).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(true);
                int cod = c.getResponseCode();
                if (cod != 200) {
                    c.disconnect();
                    throw new Exception("El servidor respondió con el código " + cod + ".");
                }
                String txt = leer(c.getInputStream());
                c.disconnect();
                JSONObject j = new JSONObject(txt);
                final long vc = j.getLong("versionCode");
                final String vn = j.optString("versionName", "");
                final String notas = j.optString("notas", "");
                ui.post(() -> mostrarActualizacion(vc > codigoVersion, vn, notas, manual));
            } catch (Exception e) {
                if (manual) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    ui.post(() -> estado.setText("No se pudo buscar actualizaciones. " + m));
                }
            }
        }).start();
    }

    void mostrarActualizacion(boolean hay, String vn, String notas, boolean manual) {
        if (hay) {
            botonActualizar.setVisibility(View.VISIBLE);
            String m = "Hay una versión nueva" + (vn.isEmpty() ? "." : ": " + vn + ".");
            if (!notas.isEmpty()) m += " Novedades: " + notas;
            m += " Pulsa el botón Descargar la actualización, que está debajo del título.";
            estado.setText(m);
        } else if (manual) {
            estado.setText("Tu versión está al día. Tienes la versión " + nombreVersion + ".");
        }
    }
}
