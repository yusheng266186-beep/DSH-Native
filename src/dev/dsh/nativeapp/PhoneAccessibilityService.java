package dev.dsh.nativeapp;

import android.accessibilityservice.*;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** User-enabled access to the visible phone UI, through Android public APIs. */
public final class PhoneAccessibilityService extends AccessibilityService {
    private static volatile PhoneAccessibilityService connected;
    private static final PhoneControlSession session = new PhoneControlSession();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Entry> nodes = new LinkedHashMap<>();
    private final Set<CompletableFuture<Object>> waiting = new HashSet<>();
    private final ExecutorService images = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "phone-image"); t.setDaemon(true); return t; });
    private LinearLayout overlay;
    private Button pauseButton;
    private boolean busy, destroyed, receiverRegistered;
    private int windowId, width, height;
    private String packageName = "", lastAppPackage = "";
    private volatile String lastAction = "";
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { end("screen-off"); }
    };
    private final Runnable idle = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!session.enabled(now())) end(session.reason());
            else main.postDelayed(this, 5000);
        }
    };
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static NativeBridgeServer.Failure failure(String code, String message) { return new NativeBridgeServer.Failure(code, message); }

    @Override protected void onServiceConnected() {
        PhoneAccessibilityService previous = connected;
        if (previous != null && previous != this) previous.end("service-replaced");
        connected = this; destroyed = false; session.stop("service-connected");
        if (!receiverRegistered) { registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF)); receiverRegistered = true; }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            lastAppPackage = text(event.getPackageName(), 200);
        if (event != null && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED)) clearNodes();
    }
    @Override public void onInterrupt() { end("interrupted"); }
    @Override public boolean onUnbind(Intent intent) { if (connected == this) connected = null; end("service-disconnected"); return super.onUnbind(intent); }
    @Override public void onDestroy() {
        destroyed = true; if (connected == this) connected = null; end("service-destroyed"); images.shutdownNow();
        if (receiverRegistered) { unregisterReceiver(screen); receiverRegistered = false; }
        super.onDestroy();
    }
    static Map<String, Object> status() {
        PhoneAccessibilityService current = connected;
        boolean enabled = session.enabled(now());
        return NativeBridgePolicy.object("connected", current != null, "enabled", enabled, "paused", session.paused(),
                "reason", session.reason(), "screenshotSupported", Build.VERSION.SDK_INT >= 30,
                "lastAction", current == null ? "" : current.lastAction, "idleTimeoutSeconds", 180,
                "coordinates", "screen pixels", "canStartFromTool", false);
    }
    /** Called only by the local settings confirmation, never through the agent bridge. */
    static void startByUser() throws NativeBridgeServer.Failure {
        PhoneAccessibilityService current = connected;
        if (current == null) throw failure("ACCESSIBILITY_REQUIRED", "Enable DSH Phone control in Android Accessibility settings");
        current.unlocked(); current.end("new-session"); session.start(now());
        if (!current.showOverlay()) { current.end("overlay-unavailable"); throw failure("CONTROL_UNAVAILABLE", "Android could not show the Stop control; reopen Accessibility settings"); }
        current.main.postDelayed(current.idle, 5000);
    }
    static void stopByUser() { stop("user-stopped"); }
    static void stop(String reason) {
        long stopped = session.stop(reason);
        PhoneAccessibilityService current = connected;
        if (current != null) current.main.post(() -> {
            if (session.epoch() == stopped) current.end(reason);
        });
    }
    private void unlocked() throws NativeBridgeServer.Failure {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (keyguard == null || keyguard.isKeyguardLocked() || power == null || !power.isInteractive())
            throw failure("PHONE_LOCKED", "Keep the phone screen on and unlock it manually");
    }
    static Object execute(Map<?, ?> command, File root) throws Exception {
        String method = (String) command.get("method");
        if (method.equals("phone.status")) return status();
        if (method.equals("phone.stop")) { stopByUser(); return NativeBridgePolicy.object("stopped", true); }
        PhoneAccessibilityService current = connected;
        if (current == null) throw failure("ACCESSIBILITY_REQUIRED", "Enable DSH Phone control in Android Accessibility settings");
        CompletableFuture<Object> result = new CompletableFuture<>();
        Runnable work = () -> current.perform(command, root, result);
        if (!current.main.post(work)) throw failure("CONTROL_UNAVAILABLE", "Phone control dispatch is unavailable");
        try { return result.get(8000, TimeUnit.MILLISECONDS); }
        catch (ExecutionException error) { if (error.getCause() instanceof Exception) throw (Exception) error.getCause(); throw failure("CONTROL_UNAVAILABLE", "Phone operation failed"); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw failure("CANCELLED", "Phone operation cancelled"); }
        catch (TimeoutException timeout) { throw failure("PHONE_TIMEOUT", "Phone operation timed out; observe the screen before retrying"); }
        finally { result.cancel(false); current.main.removeCallbacks(work); current.main.post(() -> { current.waiting.remove(result); if (current.waiting.isEmpty()) current.busy = false; }); }
    }
    private void perform(Map<?, ?> command, File root, CompletableFuture<Object> result) {
        if (result.isDone()) return;
        try {
            if (destroyed || connected != this) throw failure("CONTROL_UNAVAILABLE", "Accessibility service changed");
            session.require(now()); unlocked();
            if (busy) throw failure("PHONE_BUSY", "Wait for the current phone operation to finish");
            busy = true; waiting.add(result);
            String method = (String) command.get("method"); lastAction = method;
            long epoch = session.epoch();
            if (method.equals("phone.observe")) { finish(result, observe()); return; }
            if (method.equals("phone.apps")) { finish(result, apps()); return; }
            if (method.equals("phone.screenshot")) { capture(root, result, epoch); return; }
            if (method.equals("phone.launch")) {
                String name = (String) command.get("package");
                if (PhoneControlPolicy.systemPackage(name) && !name.equals(getPackageName())) throw failure("USER_ACTION_REQUIRED", "Open system settings and installers manually");
                Intent intent = getPackageManager().getLaunchIntentForPackage(name);
                if (intent == null) throw failure("APP_UNAVAILABLE", "The App has no launchable activity");
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); clearNodes();
                finish(result, NativeBridgePolicy.object("opened", true, "package", name)); return;
            }
            if (Arrays.asList("phone.back", "phone.home", "phone.recents").contains(method)) {
                int action = method.equals("phone.back") ? GLOBAL_ACTION_BACK : method.equals("phone.home") ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_RECENTS;
                boolean performed = performGlobalAction(action); clearNodes();
                if (!performed) throw failure("ACTION_UNAVAILABLE", "Android refused this global action");
                finish(result, NativeBridgePolicy.object("performed", true)); return;
            }
            session.check((String) command.get("snapshot"), now()); verifyWindow();
            if (PhoneControlPolicy.systemPackage(packageName)) throw failure("USER_ACTION_REQUIRED", "Operate system settings, installers and DSH manually");
            if (method.equals("phone.swipe")) {
                gesture(number(command, "x"), number(command, "y"), number(command, "endX"), number(command, "endY"),
                        command.containsKey("durationMs") ? number(command, "durationMs") : 300, result, epoch); return;
            }
            Entry entry = command.containsKey("node") ? entry((String) command.get("node")) : at(number(command, "x"), number(command, "y"));
            if (entry != null && protectedNode(entry))
                throw failure("USER_ACTION_REQUIRED", "Enter passwords, verification codes and confirm payments manually");
            if (method.equals("phone.input")) {
                if (entry == null || !entry.node.isEditable()) throw failure("INPUT_UNAVAILABLE", "Choose an editable node from a fresh observation");
                Bundle values = new Bundle(); values.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, (String) command.get("text"));
                boolean ok = entry.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, values); clearNodes();
                if (!ok) throw failure("INPUT_UNAVAILABLE", "This field does not support accessibility text input");
                finish(result, NativeBridgePolicy.object("performed", true)); return;
            }
            if (method.equals("phone.scroll")) {
                boolean ok = entry != null && entry.node.performAction(command.get("direction").equals("forward")
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                clearNodes(); if (!ok) throw failure("SCROLL_UNAVAILABLE", "This node cannot scroll in that direction");
                finish(result, NativeBridgePolicy.object("performed", true)); return;
            }
            boolean hold = method.equals("phone.long_press");
            if (entry != null && command.containsKey("node")) {
                if (entry.node.performAction(hold ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK)) {
                    clearNodes(); finish(result, NativeBridgePolicy.object("performed", true)); return;
                }
                gesture(entry.bounds.centerX(), entry.bounds.centerY(), entry.bounds.centerX(), entry.bounds.centerY(), hold ? 650 : 60, result, epoch);
            } else gesture(number(command, "x"), number(command, "y"), number(command, "x"), number(command, "y"), hold ? 650 : 60, result, epoch);
        } catch (PhoneControlSession.Rejected rejected) { reject(result, failure(rejected.code, rejected.getMessage())); }
        catch (NativeBridgeServer.Failure error) { reject(result, error); }
        catch (RuntimeException error) { reject(result, failure("CONTROL_UNAVAILABLE", "Android could not complete this operation; observe the screen before retrying")); }
    }
    private static int number(Map<?, ?> value, String key) { return ((Number) value.get(key)).intValue(); }
    private void finish(CompletableFuture<Object> result, Object value) { waiting.remove(result); busy = !waiting.isEmpty(); result.complete(value); }
    private void reject(CompletableFuture<Object> result, Exception error) { waiting.remove(result); busy = !waiting.isEmpty(); result.completeExceptionally(error); }
    private void clearNodes() { for (Entry entry : nodes.values()) entry.node.recycle(); nodes.clear(); if (connected == this || connected == null) session.invalidate(); }
    private void end(String reason) {
        if (connected == this || connected == null) session.stop(reason); clearNodes(); main.removeCallbacks(idle);
        for (CompletableFuture<Object> result : new ArrayList<>(waiting)) reject(result, failure("PHONE_CONTROL_DISABLED", "Phone control stopped"));
        if (overlay != null) {
            try { WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE); if (manager != null) manager.removeView(overlay); }
            catch (RuntimeException error) { android.util.Log.w("DSHPhoneControl", "Stop overlay removal: " + error.getClass().getSimpleName()); }
            overlay = null;
        }
    }
    private void geometry() {
        DisplayMetrics metrics = new DisplayMetrics(); ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        width = metrics.widthPixels; height = metrics.heightPixels;
    }
    private static String text(CharSequence value, int limit) { if (value == null) return ""; String s = value.toString(); return s.substring(0, Math.min(s.length(), limit)); }
    private static boolean secret(AccessibilityNodeInfo node) {
        if (node.isPassword()) return true;
        if (!node.isEditable()) return false;
        String hint = text(node.getViewIdResourceName(), 180) + " " + text(node.getContentDescription(), 180);
        if (Build.VERSION.SDK_INT >= 26) hint += " " + text(node.getHintText(), 180);
        return PhoneControlPolicy.protectedTarget(false, hint) || hint.toLowerCase(Locale.ROOT).matches("(?s).*(\\botp\\b|otp_|_otp).*" );
    }
    private static String label(AccessibilityNodeInfo node) { return secret(node) ? " " : text(node.getText(), 180) + " " + text(node.getContentDescription(), 180); }
    private static boolean protectedNode(Entry entry) {
        return PhoneControlPolicy.protectedTarget(secret(entry.node), entry.label);
    }
    private static final class Entry {
        final AccessibilityNodeInfo node; final Rect bounds; final String label, view;
        Entry(AccessibilityNodeInfo node, Rect bounds, String label) { this.node = node; this.bounds = bounds; this.label = label; this.view = node.getViewIdResourceName(); }
    }
    private Object observe() throws PhoneControlSession.Rejected, NativeBridgeServer.Failure {
        clearNodes(); geometry(); AccessibilityNodeInfo root = getRootInActiveWindow();
        List<Object> output = new ArrayList<>();
        int[] budget = { 30000, 1200 };
        if (root == null) { windowId = activeWindow(); packageName = lastAppPackage; }
        else {
            windowId = root.getWindowId();
            packageName = text(root.getPackageName(), 200);
            if (PhoneControlPolicy.systemPackage(packageName)) root.recycle();
            else collect(root, 0, output, budget, SystemClock.uptimeMillis() + 180);
        }
        if (windowId < 0) throw failure("SCREEN_UNAVAILABLE", "Android has no active screen; open the target App and retry");
        boolean protectedWindow = PhoneControlPolicy.systemPackage(packageName);
        String snapshot = session.observe(now());
        return NativeBridgePolicy.object("snapshot", snapshot, "package", packageName, "width", width, "height", height,
                "coordinates", "screen pixels", "nodes", output, "protectedWindow", protectedWindow,
                "screenshotSuggested", nodes.isEmpty() && !protectedWindow,
                "truncated", nodes.size() >= PhoneControlPolicy.MAX_NODES || budget[0] <= 0 || budget[1] <= 0);
    }
    private void collect(AccessibilityNodeInfo node, int depth, List<Object> output, int[] budget, long until) {
        if (node == null) return;
        if (depth > PhoneControlPolicy.MAX_DEPTH || nodes.size() >= PhoneControlPolicy.MAX_NODES
                || budget[0] <= 0 || budget[1]-- <= 0 || SystemClock.uptimeMillis() > until) { node.recycle(); return; }
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        boolean visible = node.isVisibleToUser() && bounds.width() > 0 && bounds.height() > 0 && bounds.intersects(0, 0, width, height);
        if (visible) {
            boolean hidden = secret(node);
            String label = hidden ? "" : text(node.getText(), 180), description = hidden ? "" : text(node.getContentDescription(), 180);
            budget[0] -= label.length() + description.length() + 100;
            String id = "n" + (nodes.size() + 1); nodes.put(id, new Entry(node, new Rect(bounds), label + " " + description));
            output.add(NativeBridgePolicy.object("id", id, "text", label, "description", description,
                    "viewId", text(node.getViewIdResourceName(), 160), "class", text(node.getClassName(), 100),
                    "bounds", Arrays.asList(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    "clickable", node.isClickable(), "editable", node.isEditable(), "scrollable", node.isScrollable(), "enabled", node.isEnabled(), "password", node.isPassword(), "protected", hidden));
        }
        int count = Math.min(node.getChildCount(), 150);
        for (int i = 0; i < count && nodes.size() < PhoneControlPolicy.MAX_NODES && budget[0] > 0
                && budget[1] > 0 && SystemClock.uptimeMillis() <= until; i++) {
            collect(node.getChild(i), depth + 1, output, budget, until);
        }
        if (!visible) node.recycle();
    }
    private void verifyWindow() throws NativeBridgeServer.Failure {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            if (root == null ? activeWindow() != windowId || packageName.isEmpty() || !packageName.equals(lastAppPackage)
                    : root.getWindowId() != windowId || !packageName.equals(text(root.getPackageName(), 200))) throw failure("STALE_SCREEN", "The active App changed; observe it again");
            DisplayMetrics metrics = new DisplayMetrics(); ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
            if (metrics.widthPixels != width || metrics.heightPixels != height) throw failure("STALE_SCREEN", "The display rotated; observe it again");
        }
        finally { if (root != null) root.recycle(); }
    }
    private int activeWindow() {
        List<AccessibilityWindowInfo> windows = getWindows(); int result = -1;
        if (windows != null) for (AccessibilityWindowInfo window : windows) {
            if (window.isActive()) result = window.getId(); window.recycle();
        }
        return result;
    }
    private Entry entry(String id) throws NativeBridgeServer.Failure {
        Entry entry = nodes.get(id);
        if (entry == null || !entry.node.refresh() || !entry.node.isEnabled() || !entry.node.isVisibleToUser()) throw failure("STALE_SCREEN", "The screen node is no longer available");
        Rect bounds = new Rect(); entry.node.getBoundsInScreen(bounds);
        if (!bounds.equals(entry.bounds) || !Objects.equals(entry.view, entry.node.getViewIdResourceName()) || !entry.label.equals(label(entry.node))) throw failure("STALE_SCREEN", "The screen node changed; observe it again");
        return entry;
    }
    private Entry at(int x, int y) throws NativeBridgeServer.Failure {
        point(x, y); Entry best = null;
        for (Map.Entry<String, Entry> item : nodes.entrySet()) {
            if (!item.getValue().bounds.contains(x, y)) continue;
            Entry candidate = entry(item.getKey());
            if (protectedNode(candidate)) return candidate;
            best = candidate;
        }
        return best;
    }
    private void point(int x, int y) throws NativeBridgeServer.Failure {
        if (x < 0 || y < 0 || x >= width || y >= height) throw failure("INVALID_COORDINATES", "Use coordinates inside the observed screen");
    }
    private void gesture(int x, int y, int endX, int endY, int duration, CompletableFuture<Object> result, long epoch) throws NativeBridgeServer.Failure {
        point(x, y);
        point(endX, endY);
        Rect area = new Rect(Math.min(x, endX), Math.min(y, endY), Math.max(x, endX) + 1, Math.max(y, endY) + 1);
        if (overlay != null) {
            int[] origin = new int[2];
            overlay.getLocationOnScreen(origin);
            if (area.intersects(origin[0], origin[1], origin[0] + overlay.getWidth(), origin[1] + overlay.getHeight()))
                throw failure("USER_ACTION_REQUIRED", "The floating controls are reserved for you");
        }
        for (Entry entry : nodes.values()) {
            if (Rect.intersects(area, entry.bounds) && protectedNode(entry))
                throw failure("USER_ACTION_REQUIRED", "Handle the protected field or action manually");
        }
        Path path = new Path();
        path.moveTo(x, y);
        if (x != endX || y != endY) path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription value) {
                if (session.current(epoch, now()) && !result.isDone()) {
                    clearNodes();
                    finish(result, NativeBridgePolicy.object("performed", true));
                }
                else reject(result, failure("CANCELLED", "Phone control changed during the gesture"));
            }
            @Override public void onCancelled(GestureDescription value) {
                if (session.current(epoch, now()) && !result.isDone()) clearNodes();
                reject(result, failure("GESTURE_CANCELLED", "Android cancelled this gesture; observe before retrying"));
            }
        }, main);
        if (!accepted) throw failure("GESTURE_UNAVAILABLE", "Android refused the accessibility gesture");
    }
    private Object apps() {
        List<Object> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String name = app.activityInfo.packageName;
            if (seen.add(name) && !PhoneControlPolicy.systemPackage(name) && result.size() < 250)
                result.add(NativeBridgePolicy.object("package", name, "label", text(app.loadLabel(getPackageManager()), 100)));
        }
        return NativeBridgePolicy.object("apps", result);
    }
    private boolean showOverlay() {
        try {
            overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.HORIZONTAL);
            overlay.setPadding(DshUi.dp(this, 6), DshUi.dp(this, 3), DshUi.dp(this, 6), DshUi.dp(this, 3)); overlay.setBackground(DshUi.cardBg(this));
            pauseButton = DshUi.button(this, UiText.t("暂停操控", "Pause"), false);
            Button stop = DshUi.button(this, UiText.t("停止操控", "Stop"), true);
            pauseButton.setOnClickListener(v -> { session.pause(!session.paused()); clearNodes(); pauseButton.setText(session.paused() ? UiText.t("继续操控", "Resume") : UiText.t("暂停操控", "Pause")); });
            stop.setOnClickListener(v -> end("user-stopped"));
            overlay.addView(pauseButton); overlay.addView(stop);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.END; params.y = DshUi.dp(this, 36);
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(overlay, params); return true;
        } catch (RuntimeException error) { android.util.Log.w("DSHPhoneControl", "Stop overlay unavailable: " + error.getClass().getSimpleName()); return false; }
    }
    private void capture(File root, CompletableFuture<Object> result, long epoch) throws PhoneControlSession.Rejected, NativeBridgeServer.Failure {
        if (Build.VERSION.SDK_INT < 30) throw failure("SCREENSHOT_UNAVAILABLE", "Accessibility screenshots require Android 11 or newer; use the existing android screenshot tool from DSH");
        Object observed = observe(); String snapshot = (String) ((Map<?, ?>) observed).get("snapshot");
        if (packageName.isEmpty() || PhoneControlPolicy.systemPackage(packageName))
            throw failure("USER_ACTION_REQUIRED", "Open the target App before taking a Phone control screenshot");
        for (Entry entry : nodes.values()) if (secret(entry.node))
            throw failure("USER_ACTION_REQUIRED", "Close the protected input before taking a screenshot");
        int captureWindow = windowId;
        Capture30.take(this, images, root, (value, error) -> main.post(() -> {
            if (result.isDone() || destroyed || !session.current(epoch, now())) { discardCapture(value); reject(result, failure("CANCELLED", "Phone control stopped during capture")); return; }
            try {
                verifyWindow(); if (windowId != captureWindow) throw failure("STALE_SCREEN", "Screen changed during capture");
                session.check(snapshot, now());
                if (error != null) { reject(result, error); return; }
                value.put("snapshot", snapshot); value.put("screenWidth", width); value.put("screenHeight", height); value.put("coordinates", "screen pixels"); finish(result, value);
            } catch (NativeBridgeServer.Failure changed) { discardCapture(value); reject(result, changed); }
            catch (PhoneControlSession.Rejected changed) { discardCapture(value); reject(result, failure(changed.code, changed.getMessage())); }
        }));
    }
    private static void discardCapture(Map<String, Object> value) {
        if (value != null && value.get("path") instanceof String) { File file = new File((String) value.get("path")); if (file.exists() && !file.delete()) file.deleteOnExit(); }
    }
    private interface CaptureCallback { void done(Map<String, Object> value, NativeBridgeServer.Failure error); }
    /** Loaded only on API 30+, so the service remains usable on Android 7-10. */
    private static final class Capture30 {
        static void take(PhoneAccessibilityService service, Executor executor, File root, CaptureCallback callback) {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
                @Override public void onFailure(int code) { callback.done(null, failure("SCREENSHOT_UNAVAILABLE", "Android refused this capture (code " + code + "); secure screens cannot be captured")); }
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap image = null, source = null, scaled = null; File output = null;
                    try (android.hardware.HardwareBuffer buffer = result.getHardwareBuffer()) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("capture cancelled");
                        source = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (source == null) throw new IOException("capture unavailable");
                        image = source.copy(Bitmap.Config.ARGB_8888, false);
                        if (image == null) throw new IOException("capture unavailable");
                        int[] size = NativeBridgePolicy.captureSize(image.getWidth(), image.getHeight()); scaled = Bitmap.createScaledBitmap(image, size[0], size[1], true);
                        File directory = new File(root.getCanonicalFile(), "native-captures");
                        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.getAbsoluteFile().equals(directory.getCanonicalFile())) throw new IOException("capture directory unavailable");
                        output = File.createTempFile("phone-", ".png", directory);
                        try (FileOutputStream stream = new FileOutputStream(output)) { if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("capture encoding failed"); stream.getFD().sync(); }
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("capture cancelled");
                        callback.done(NativeBridgePolicy.object("path", output.getAbsolutePath(), "width", scaled.getWidth(), "height", scaled.getHeight(), "mimeType", "image/png"), null); output = null;
                    } catch (IOException | RuntimeException error) { callback.done(null, failure("SCREENSHOT_UNAVAILABLE", "Android could not save the capture")); }
                    finally {
                        if (output != null && output.exists() && !output.delete()) output.deleteOnExit();
                        if (scaled != null && scaled != image) scaled.recycle(); if (image != null) image.recycle(); if (source != null) source.recycle();
                    }
                }
            });
        }
    }
}
