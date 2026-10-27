package com.cafetecnologico.convertidor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    static final String VERSION = "0.1";
    static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    static final int LIMITE_CARACTERES = 4000;

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
    EditText campoClave, campoEstilo, campoTexto;
    Spinner listaModelo, listaVoz;
    Button botonConvertir, botonReproducir, botonContar;
    TextView estado;
    Handler ui = new Handler(Looper.getMainLooper());
    Uri ultimoAudio;
    String ultimoNombre = "";
    MediaPlayer reproductor;
    boolean ocupado = false;

    static class ApiException extends Exception {
        final int codigo;
        ApiException(int codigo, String mensaje) {
            super(mensaje);
            this.codigo = codigo;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Convertidor de texto a voz, Café Tecnológico");
        prefs = getSharedPreferences("cafe_tecnologico_convertidor", MODE_PRIVATE);

        int p = dp(16);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(p, p, p, p);
        sv.addView(raiz);

        TextView titulo = new TextView(this);
        titulo.setText("Convertidor de texto a voz con Gemini. Versión " + VERSION);
        titulo.setTextSize(22);
        titulo.setAccessibilityHeading(true);
        raiz.addView(titulo);

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

        botonReproducir = new Button(this);
        botonReproducir.setText("Reproducir o detener el último audio");
        botonReproducir.setEnabled(false);
        raiz.addView(botonReproducir);

        estado = new TextView(this);
        estado.setTextSize(16);
        estado.setTextIsSelectable(true);
        estado.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        estado.setText("Listo. Escribe el texto y pulsa Convertir a audio.");
        estado.setPadding(0, p, 0, 0);
        raiz.addView(estado);

        botonContar.setOnClickListener(v -> {
            int n = campoTexto.getText().toString().length();
            estado.setText("El texto tiene " + n + (n == 1 ? " carácter." : " caracteres."));
        });
        botonConvertir.setOnClickListener(v -> convertir());
        botonReproducir.setOnClickListener(v -> reproducir());

        setContentView(sv);
    }

    @Override
    protected void onPause() {
        super.onPause();
        guardarPrefs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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

    // ---------- Conversión ----------

    void convertir() {
        if (ocupado) return;
        guardarPrefs();
        final String clave = campoClave.getText().toString().trim();
        final String texto = campoTexto.getText().toString().trim();
        final String estilo = campoEstilo.getText().toString().trim();
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
        if (texto.length() > LIMITE_CARACTERES) {
            estado.setText("En esta primera versión el texto puede tener hasta "
                    + LIMITE_CARACTERES + " caracteres. Ahora tiene " + texto.length() + ".");
            return;
        }

        ocupado = true;
        botonConvertir.setEnabled(false);
        estado.setText("Convirtiendo, espera un momento.");

        new Thread(() -> {
            try {
                byte[] wav = pedirAudio(clave, modelo, voz, estilo, texto);
                Uri u = guardar(wav);
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
        if (error != null) {
            estado.setText("No se pudo convertir. " + error);
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
        if (codigo >= 400) throw new ApiException(codigo, mensajeError(resp, codigo));
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
        if (codigo == 429) m += " Se alcanzó el límite de uso de tu clave. Espera un minuto e inténtalo de nuevo.";
        return m;
    }

    byte[] decodificar(String base64) {
        byte[] datos = Base64.decode(base64, Base64.DEFAULT);
        boolean esWav = datos.length > 4 && datos[0] == 'R' && datos[1] == 'I'
                && datos[2] == 'F' && datos[3] == 'F';
        return esWav ? datos : envolverWav(datos);
    }

    static byte[] envolverWav(byte[] pcm) {
        int frecuencia = 24000, canales = 1, bits = 16;
        ByteBuffer bb = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());
        bb.putInt(36 + pcm.length);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());
        bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) canales);
        bb.putInt(frecuencia);
        bb.putInt(frecuencia * canales * bits / 8);
        bb.putShort((short) (canales * bits / 8));
        bb.putShort((short) bits);
        bb.put("data".getBytes());
        bb.putInt(pcm.length);
        bb.put(pcm);
        return bb.array();
    }

    // ---------- Guardar en Descargas ----------

    Uri guardar(byte[] wav) throws Exception {
        String nombre = "Cafe_Tecnologico_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".wav";
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
        ultimoNombre = nombre;
        return u;
    }
}
