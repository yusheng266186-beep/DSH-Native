package dev.dsh.nativeapp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
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
        void diagnostic(String detail);
    }
    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler();
    private SpeechRecognizer recognizer;
    private VoiceUi.Command active;
    private boolean pendingPermission, onDevice, fallback;
    private boolean external, ready, stopping, windowAttempted;
    private String stage = "idle", reason = "", failureClass = "", failures = "";
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
            active = command; fallback = false; windowAttempted = false; ready = false; stopping = false;
            reason = ""; failureClass = ""; failures = ""; onDevice = false; external = false; stage = "availability";
            if (!available()) {
                stage = "availability"; recordFailure(11, "default-service-unavailable", null);
                finish("error", "", 11); return;
            }
            if (!onDeviceAvailable() && !defaultServiceAvailable()) { openSystemVoice(); return; }
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = true;
                stage = "permission";
                emit(command.id, "permission", "", 0);
                try { activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE); }
                catch (RuntimeException unavailable) {
                    recordFailure(9, "permission-request", unavailable); finish("error", "", 9);
                }
            } else begin();
        } else if (active != null && active.id.equals(command.id)) {
            if ("cancel".equals(command.action)) cancel();
            else if ("stop".equals(command.action) && recognizer != null) {
                stopListening();
            }
        }
    }

    private boolean available() { return onDeviceAvailable() || defaultServiceAvailable() || windowAvailable(); }

    private boolean windowAvailable() {
        try { return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .resolveActivity(activity.getPackageManager()) != null; }
        catch (RuntimeException unavailable) { return false; }
    }

    private boolean onDeviceAvailable() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { return (Boolean) SpeechRecognizer.class.getMethod("isOnDeviceRecognitionAvailable",
                android.content.Context.class).invoke(null, activity); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    private String selectedService() {
        try {
            String value = Settings.Secure.getString(activity.getContentResolver(), "voice_recognition_service");
            return value == null || value.length() > 240 ? "" : value;
        } catch (RuntimeException unavailable) { return ""; }
    }

    private boolean defaultServiceAvailable() {
        ComponentName component = ComponentName.unflattenFromString(selectedService());
        if (component == null) return false;
        try {
            for (ResolveInfo info : activity.getPackageManager().queryIntentServices(
                    new Intent("android.speech.RecognitionService").setComponent(component), 0)) {
                if (info.serviceInfo != null && info.serviceInfo.enabled) return true;
            }
        } catch (RuntimeException unavailable) { return false; }
        return false;
    }

    private void stopListening() {
        // Calling stop before onReady, or twice while processing, can itself
        // produce ERROR_CLIENT on ROM recognizers. Only stop an active recording.
        if (!ready || stopping || active == null || recognizer == null) return;
        stopping = true;
        try {
            recognizer.stopListening(); stage = "recognizing";
            emit(active.id, "recognizing", "", 0); timeout(15000L);
        } catch (RuntimeException unavailable) { failed(5, "stop-listening", unavailable); }
    }

    void permissionResult(boolean granted) {
        if (!pendingPermission || active == null) return;
        pendingPermission = false;
        if (!granted) { recordFailure(9, "permission-denied", null); finish("error", "", 9); }
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
        onDevice = false; ready = false; stopping = false; stage = "engine-create";
        try {
            if (!fallback && onDeviceAvailable()) {
                try {
                    recognizer = (SpeechRecognizer) SpeechRecognizer.class.getMethod("createOnDeviceSpeechRecognizer",
                            android.content.Context.class).invoke(null, activity);
                    onDevice = true;
                } catch (ReflectiveOperationException unavailable) {
                    recordFailure(5, "on-device-create", unavailable); fallback = true;
                }
            }
            if (recognizer == null) {
                if (!defaultServiceAvailable()) { openSystemVoice(); return; }
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            }
            recognizer.setRecognitionListener(new RecognitionListener() {
                private boolean valid() { return current == generation && active != null; }
                @Override public void onReadyForSpeech(Bundle params) {
                    if (valid()) {
                        ready = true; stage = "listening"; emit(active.id, "listening", "", 0);
                        if (deadline != null) handler.removeCallbacks(deadline);
                        deadline = new Runnable() {
                            @Override public void run() { if (valid()) stopListening(); }
                        };
                        handler.postDelayed(deadline, 60000L);
                    }
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    if (valid()) { stopping = true; stage = "recognizing"; emit(active.id, "recognizing", "", 0); timeout(15000L); }
                }
                @Override public void onError(int error) {
                    if (!valid()) return;
                    failed(error, "service-error", null);
                }
                @Override public void onResults(Bundle results) {
                    if (!valid()) return;
                    ArrayList<String> texts = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = texts == null || texts.isEmpty() || texts.get(0) == null ? "" : texts.get(0).trim();
                    if (text.length() > 16000) { recordFailure(1001, "result-too-long", null); finish("error", "", 1001); }
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
            stage = "engine-start"; emit(active.id, "starting", "", 0);
            // Install before startListening so a synchronous ROM callback cannot
            // have its listening/recognition deadline overwritten on return.
            if (deadline != null) handler.removeCallbacks(deadline);
            deadline = new Runnable() {
                @Override public void run() {
                    if (active != null && current == generation && !ready)
                        failed(5, "engine-start-timeout", null);
                }
            };
            handler.postDelayed(deadline, 12000L);
            recognizer.startListening(request);
        } catch (RuntimeException unavailable) { failed(5, "engine-exception", unavailable); }
    }

    private void recordFailure(int error, String why, Throwable failure) {
        reason = why;
        Throwable detail = failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
        failureClass = detail == null ? "" : detail.getClass().getSimpleName();
        String item = mode() + "/" + stage + ":" + error + "/" + why;
        failures = (failures.length() == 0 ? "" : failures + " > ") + item;
        if (failures.length() > 600) failures = failures.substring(failures.length() - 600);
        host.diagnostic("error=" + error + ", stage=" + stage + ", mode=" + mode()
                + ", reason=" + reason + ", exception=" + failureClass);
    }

    private void failed(int error, String why, Throwable failure) {
        if (active == null) return;
        recordFailure(error, why, failure);
        boolean compatible = error == 5 || error == 11 || error == 12 || error == 13;
        boolean retryDefault = compatible && onDevice && !fallback && defaultServiceAvailable();
        boolean retryWindow = compatible && !windowAttempted && windowAvailable();
        if (host.canListen() && (retryDefault || retryWindow)) {
            final int next = ++generation;
            destroyRecognizer(); fallback = true; ready = false; stopping = false;
            stage = "fallback";
            emit(active.id, "fallback", "", error);
            final boolean useDefault = retryDefault;
            deadline = new Runnable() {
                @Override public void run() {
                    if (active == null || next != generation) return;
                    if (!host.canListen()) { cancel(); return; }
                    if (useDefault) begin(); else openSystemVoice();
                }
            };
            handler.postDelayed(deadline, 250L);
        } else finish("error", "", error);
    }

    private String mode() { return external ? "system-window" : onDevice ? "on-device" : "system-service"; }

    private void timeout(long delay) {
        if (deadline != null) handler.removeCallbacks(deadline);
        final int current = generation;
        deadline = new Runnable() {
            @Override public void run() {
                if (active != null && current == generation) {
                    recordFailure(6, "result-timeout", null); finish("error", "", 6);
                }
            }
        };
        handler.postDelayed(deadline, delay);
    }

    private void emit(String id, String state, String text, int error) {
        try {
            diagnostic = "state=" + state + ", error=" + error + ", available=" + available()
                    + ", mode=" + mode() + ", stage=" + stage + ", reason=" + reason
                    + ", exception=" + failureClass + ", default=" + selectedService() + ", attempts=" + failures;
            JSONObject result = new JSONObject().put("id", id).put("state", state).put("text", text)
                    .put("error", error).put("available", available()).put("onDevice", onDevice)
                    .put("mode", mode()).put("stage", stage).put("reason", reason)
                    .put("defaultService", defaultServiceAvailable()).put("defaultSelected", selectedService().length() > 0)
                    .put("onDeviceAvailable", onDeviceAvailable()).put("windowAvailable", windowAvailable())
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
        if (active == null || !host.canListen()) { cancel(); return; }
        if (windowAttempted || !windowAvailable()) {
            recordFailure(5, defaultServiceAvailable() ? "window-unavailable" : "default-service-unavailable", null);
            finish("error", "", 5); return;
        }
        try {
            windowAttempted = true; external = true; onDevice = false; stage = "system-window";
            emit(active.id, "system-window", "", 0);
            timeout(90000L);
            activity.startActivityForResult(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, active.language)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1), REQUEST_SYSTEM_VOICE);
        } catch (RuntimeException unavailable) {
            recordFailure(5, "window-launch", unavailable); finish("error", "", 5);
        }
    }

    void activityResult(int resultCode, Intent data) {
        if (!external || active == null) return;
        ArrayList<String> values = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode == Activity.RESULT_CANCELED) finish("cancelled", "", 0);
        else if (resultCode != Activity.RESULT_OK) {
            int error = resultCode == RecognizerIntent.RESULT_NETWORK_ERROR ? 2
                    : resultCode == RecognizerIntent.RESULT_NO_MATCH ? 7
                    : resultCode == RecognizerIntent.RESULT_AUDIO_ERROR ? 3
                    : resultCode == RecognizerIntent.RESULT_SERVER_ERROR ? 4 : 5;
            recordFailure(error, "window-result", null); finish("error", "", error);
        }
        else {
            String text = values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0).trim();
            if (text.length() > 16000) { recordFailure(1001, "result-too-long", null); finish("error", "", 1001); }
            else finish("result", text, 0);
        }
    }

    void pause() { if (!pendingPermission && !resumePending && !external) cancel(); }
    void cancel() { finish("cancelled", "", 0); }
    void dispose() { active = null; pendingPermission = false; resumePending = false; generation++; destroyRecognizer(); }
}
