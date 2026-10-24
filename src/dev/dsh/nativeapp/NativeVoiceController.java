package dev.dsh.nativeapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import org.json.JSONObject;

/** Foreground-only system speech recognition; audio never passes through the WebView. */
final class NativeVoiceController {
    static final int REQUEST_MICROPHONE = 0x2002;
    static final int REQUEST_SYSTEM_VOICE = 0x2003;
    interface Host {
        boolean canListen();
        void event(String json);
    }
    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler();
    private SpeechRecognizer recognizer;
    private VoiceUi.Command active;
    private boolean pendingPermission, onDevice, fallback;
    private boolean external;
    private boolean resumePending;
    private long resumeUntil;
    private int generation;
    private Runnable deadline;
    private volatile String diagnostic = "not used";
    String diagnostic() { return diagnostic; }

    NativeVoiceController(Activity activity, Host host) { this.activity = activity; this.host = host; }

    void command(VoiceUi.Command command) {
        if (command == null) return;
        if ("check".equals(command.action)) {
            emit(command.id, "capabilities", "", available() ? 0 : 11);
        } else if ("settings".equals(command.action)) {
            try { activity.startActivity(new Intent("android.settings.VOICE_INPUT_SETTINGS")); }
            catch (RuntimeException unavailable) { emit(command.id, "error", "", 11); }
        } else if ("start".equals(command.action)) {
            if (!host.canListen()) { emit(command.id, "error", "", 9); return; }
            if (active != null) { emit(command.id, "error", "", 8); return; }
            if (!available()) { emit(command.id, "error", "", 11); return; }
            active = command; fallback = false;
            if (!recognitionServiceAvailable()) { openSystemVoice(); return; }
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = true;
                emit(command.id, "permission", "", 0);
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            } else begin();
        } else if (active != null && active.id.equals(command.id)) {
            if ("cancel".equals(command.action)) cancel();
            else if ("stop".equals(command.action) && recognizer != null) {
                stopListening();
            }
        }
    }

    private boolean available() {
        try { return recognitionServiceAvailable()
                || new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(activity.getPackageManager()) != null; }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean recognitionServiceAvailable() {
        try { return SpeechRecognizer.isRecognitionAvailable(activity); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void stopListening() {
        try {
            recognizer.stopListening(); emit(active.id, "recognizing", "", 0); timeout(15000L);
        } catch (RuntimeException unavailable) { finish("error", "", 5); }
    }

    void permissionResult(boolean granted) {
        if (!pendingPermission || active == null) return;
        pendingPermission = false;
        if (!granted) finish("error", "", 9);
        else if (host.canListen()) begin();
        else {
            // Some ROMs return the grant before Activity.onResume. Wait only
            // for that immediate foreground return, never record in background.
            resumePending = true;
            resumeUntil = android.os.SystemClock.uptimeMillis() + 2000L;
            deadline = new Runnable() {
                @Override public void run() { if (resumePending) cancel(); }
            };
            handler.postDelayed(deadline, 2000L);
        }
    }

    void resume() {
        if (resumePending && active != null && host.canListen()
                && android.os.SystemClock.uptimeMillis() <= resumeUntil) {
            resumePending = false; begin();
        }
    }

    private void begin() {
        if (active == null || !host.canListen()) { cancel(); return; }
        final int current = ++generation;
        onDevice = false;
        try {
            if (!fallback && Build.VERSION.SDK_INT >= 31) {
                try {
                    boolean supported = (Boolean) SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable",
                            android.content.Context.class).invoke(null, activity);
                    if (supported) {
                        recognizer = (SpeechRecognizer) SpeechRecognizer.class.getMethod("createOnDeviceSpeechRecognizer",
                                android.content.Context.class).invoke(null, activity);
                        onDevice = true;
                    }
                } catch (ReflectiveOperationException unavailable) { recognizer = null; }
            }
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean valid() { return current == generation && active != null; }
                @Override public void onReadyForSpeech(Bundle params) { if (valid()) emit(active.id, "listening", "", 0); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    if (valid()) { emit(active.id, "recognizing", "", 0); timeout(15000L); }
                }
                @Override public void onError(int error) {
                    if (!valid()) return;
                    if (onDevice && !fallback && (error == 11 || error == 12 || error == 13 || error == 5)) {
                        destroyRecognizer(); fallback = true; begin(); return;
                    }
                    finish("error", "", error);
                }
                @Override public void onResults(Bundle results) {
                    if (!valid()) return;
                    ArrayList<String> texts = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = texts == null || texts.isEmpty() || texts.get(0) == null ? "" : texts.get(0).trim();
                    if (text.length() > 16000) finish("error", "", 5);
                    else finish("result", text, 0);
                }
                @Override public void onPartialResults(Bundle results) { }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, active.language)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            emit(active.id, "starting", "", 0);
            recognizer.startListening(request);
            if (deadline != null) handler.removeCallbacks(deadline);
            deadline = new Runnable() {
                @Override public void run() {
                    if (active != null && current == generation && recognizer != null) {
                        stopListening();
                    }
                }
            };
            handler.postDelayed(deadline, 60000L);
        } catch (RuntimeException unavailable) { finish("error", "", 5); }
    }

    private void timeout(long delay) {
        if (deadline != null) handler.removeCallbacks(deadline);
        final int current = generation;
        deadline = new Runnable() {
            @Override public void run() { if (active != null && current == generation) finish("error", "", 6); }
        };
        handler.postDelayed(deadline, delay);
    }

    private void emit(String id, String state, String text, int error) {
        try {
            diagnostic = "state=" + state + ", error=" + error + ", available=" + available()
                    + ", mode=" + (external ? "system-window" : onDevice ? "on-device" : "system-service");
            JSONObject result = new JSONObject().put("id", id).put("state", state).put("text", text)
                    .put("error", error).put("available", available()).put("onDevice", onDevice)
                    .put("mode", recognitionServiceAvailable() ? "system-service" : "system-window")
                    .put("permission", Build.VERSION.SDK_INT < 23 || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED);
            host.event(result.toString());
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("voice result unavailable"); }
    }

    private void finish(String state, String text, int error) {
        if (active == null) return;
        String id = active.id;
        active = null; pendingPermission = false; resumePending = false; generation++;
        destroyRecognizer();
        emit(id, state, text, error);
        external = false;
    }

    private void destroyRecognizer() {
        if (deadline != null) handler.removeCallbacks(deadline);
        deadline = null;
        SpeechRecognizer previous = recognizer;
        recognizer = null;
        if (previous != null) {
            try { previous.cancel(); }
            catch (RuntimeException unavailable) { /* The service may already have disconnected. */ }
            finally {
                try { previous.destroy(); }
                catch (RuntimeException disconnected) {
                    android.util.Log.w("DSHNative", "语音服务清理失败: " + disconnected.getClass().getSimpleName());
                }
            }
        }
    }

    private void openSystemVoice() {
        try {
            external = true;
            activity.startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, active.language)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1), REQUEST_SYSTEM_VOICE);
        } catch (RuntimeException unavailable) { external = false; finish("error", "", 11); }
    }

    void activityResult(int resultCode, Intent data) {
        if (!external || active == null) return;
        ArrayList<String> values = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode != Activity.RESULT_OK) finish("cancelled", "", 0);
        else {
            String text = values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0).trim();
            if (text.length() > 16000) finish("error", "", 5);
            else finish("result", text, 0);
        }
    }

    void pause() { if (!pendingPermission && !resumePending && !external) cancel(); }
    void cancel() { finish("cancelled", "", 0); }
    void dispose() { active = null; pendingPermission = false; resumePending = false; generation++; destroyRecognizer(); }
}
