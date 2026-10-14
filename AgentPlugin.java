package com.roohu.phoneagent;

import android.Manifest;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.speech.tts.Voice;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.speech.tts.Voice;
import com.getcapacitor.JSArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

@CapacitorPlugin(name = "Agent", permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "mic")})
public class AgentPlugin extends Plugin {
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer sr;

    private AgentAccessibilityService svc() { return AgentAccessibilityService.instance; }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", svc() != null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void run(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        s.setHeal(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""));
        if (!s.runPlan(call.getString("plan", "[]"))) { call.reject("Agent is busy or the plan is bad"); return; }
        call.resolve();
    }

    @PluginMethod
    public void runAgent(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runAgent(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""))) {
            call.reject("Busy"); return;
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        AgentAccessibilityService s = svc();
        JSObject r = new JSObject();
        r.put("status", s == null ? "{}" : s.getStatusJson());
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopNow();
        call.resolve();
    }

    @PluginMethod
    public void botStart(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        s.startBot(call.getString("token", ""), call.getString("code", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""));
        call.resolve();
    }

    @PluginMethod
    public void botStop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopBot();
        call.resolve();
    }

    private MediaPlayer mp;
    private Runnable pending;

    private SharedPreferences vp() { return getContext().getSharedPreferences("roohu", 0); }

    private void withTts(final Runnable r) {
        if (tts != null && ttsReady) { r.run(); return; }
        pending = r;
        if (tts == null) {
            tts = new TextToSpeech(getContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    applyVoice();
                    Runnable x = pending;
                    pending = null;
                    if (x != null) x.run();
                }
            });
        }
    }

    private void applyVoice() {
        try {
            SharedPreferences p = vp();
            String name = p.getString("vname", "");
            if (!name.isEmpty()) for (Voice v : tts.getVoices()) if (v.getName().equals(name)) { tts.setVoice(v); break; }
            tts.setPitch(p.getFloat("vpitch", 1.0f));
            tts.setSpeechRate(p.getFloat("vrate", 1.0f));
        } catch (Exception e) { }
    }

    private void local(final String t) {
        withTts(() -> tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "r"));
    }

    private void cloud(final String t, final String key, final String voice) {
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/text-to-speech/" + voice).openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("xi-api-key", key);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "audio/mpeg");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                OutputStream o = c.getOutputStream();
                o.write(new JSONObject().put("text", t).toString().getBytes("UTF-8"));
                o.close();
                if (c.getResponseCode() >= 400) throw new Exception("voice");
                File f = new File(getContext().getCacheDir(), "v.mp3");
                FileOutputStream fo = new FileOutputStream(f);
                InputStream is = c.getInputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = is.read(b)) > 0) fo.write(b, 0, n);
                fo.close();
                if (mp != null) { try { mp.release(); } catch (Exception e) { } }
                mp = new MediaPlayer();
                mp.setDataSource(f.getPath());
                mp.prepare();
                mp.start();
            } catch (Exception e) {
                local(t);
            }
        }).start();
    }

    @PluginMethod
    public void speak(PluginCall call) {
        String t = call.getString("text", "");
        SharedPreferences p = vp();
        String key = p.getString("ekey", ""), voice = p.getString("evoice", "");
        if (!key.isEmpty() && !voice.isEmpty()) cloud(t, key, voice);
        else local(t);
        call.resolve();
    }

    @PluginMethod
    public void shutUp(PluginCall call) {
        if (tts != null) tts.stop();
        try { if (mp != null) mp.stop(); } catch (Exception e) { }
        call.resolve();
    }

    @PluginMethod
    public void voices(final PluginCall call) {
        withTts(() -> {
            JSArray arr = new JSArray();
            try {
                String lang = Locale.getDefault().getLanguage();
                for (Voice v : tts.getVoices()) {
                    if (!v.getLocale().getLanguage().equals(lang)) continue;
                    JSObject o = new JSObject();
                    o.put("name", v.getName());
                    o.put("label", v.getLocale().getDisplayCountry() + " " + v.getName().replaceAll(".*-x-", "") + (v.isNetworkConnectionRequired() ? " (online)" : ""));
                    o.put("q", v.getQuality());
                    arr.put(o);
                }
            } catch (Exception e) { }
            JSObject r = new JSObject();
            r.put("voices", arr);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void setVoice(PluginCall call) {
        vp().edit().putString("vname", call.getString("name", "")).putFloat("vpitch", call.getFloat("pitch", 1.0f))
                .putFloat("vrate", call.getFloat("rate", 1.0f)).putString("ekey", call.getString("ekey", ""))
                .putString("evoice", call.getString("evoice", "")).apply();
        if (tts != null && ttsReady) applyVoice();
        call.resolve();
    }

    @PluginMethod
    public void setAuto(PluginCall call) {
        vp().edit().putInt("auto", call.getInt("level", 1)).apply();
        call.resolve();
    }

    @PluginMethod
    public void sound(PluginCall call) {
        String k = call.getString("k", "send");
        int[] f = k.equals("start") ? new int[]{523, 659, 784} : k.equals("done") ? new int[]{784, 1047} : k.equals("err") ? new int[]{330, 247} : new int[]{880};
        play(f);
        call.resolve();
    }

    private void play(final int[] f) {
        new Thread(() -> {
            try {
                int rate = 22050;
                int n = rate * 120 / 1000;
                short[] buf = new short[n * f.length];
                for (int k = 0; k < f.length; k++) {
                    for (int i = 0; i < n; i++) {
                        double env = Math.min(1.0, Math.min(i / 400.0, (n - i) / 600.0));
                        buf[k * n + i] = (short) (Math.sin(2 * Math.PI * f[k] * i / rate) * env * 7000);
                    }
                }
                AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, buf.length * 2, AudioTrack.MODE_STATIC);
                t.write(buf, 0, buf.length);
                t.play();
                Thread.sleep(120L * f.length + 250);
                t.release();
            } catch (Exception e) { }
        }).start();
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micDone");
        } else {
            startListen(call);
        }
    }

    @PermissionCallback
    private void micDone(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) startListen(call);
        else call.reject("Microphone permission was denied");
    }

    private void startListen(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                call.reject("Speech recognition is not available on this phone");
                return;
            }
            if (tts != null) tts.stop();
            if (sr != null) sr.destroy();
            sr = SpeechRecognizer.createSpeechRecognizer(getContext());
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onPartialResults(Bundle b) { }
                public void onEvent(int t, Bundle b) { }
                public void onError(int e) { call.reject("I could not hear you (code " + e + ")"); }
                public void onResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    JSObject r = new JSObject();
                    r.put("text", l != null && !l.isEmpty() ? l.get(0) : "");
                    call.resolve(r);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            sr.startListening(i);
        });
    }
}
