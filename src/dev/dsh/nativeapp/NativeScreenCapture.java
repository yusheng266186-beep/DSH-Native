package dev.dsh.nativeapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One user-authorized projection per screenshot; never caches projection tokens. */
final class NativeScreenCapture {
    static final int REQUEST_CAPTURE = 0x2004;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Request pending;
    static final class Request {
        final String id = UUID.randomUUID().toString();
        final File root;
        final WeakReference<Activity> owner;
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<Map<String, Object>>();
        boolean authorized;
        Request(Activity owner, File root) { this.owner = new WeakReference<Activity>(owner); this.root = root; }
    }
    static synchronized Request request(Activity activity, File root) throws NativeBridgeServer.Failure {
        if (pending != null) throw new NativeBridgeServer.Failure("BUSY", "A screenshot request is already pending");
        final Request request = new Request(activity, root); pending = request;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("No projection service");
            activity.startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
            MAIN.postDelayed(new Runnable() { @Override public void run() {
                fail(request.id, "CAPTURE_TIMEOUT", "Screen capture timed out; retry and approve the system prompt");
            } }, 90000L);
            return request;
        } catch (RuntimeException unavailable) {
            pending = null; throw new NativeBridgeServer.Failure("CAPTURE_UNAVAILABLE", "System screen capture is unavailable");
        }
    }
    static synchronized Request current(String id) { return pending != null && pending.id.equals(id) ? pending : null; }
    static synchronized void activityResult(Activity activity, int code, Intent data) {
        Request request = pending;
        if (request == null || request.authorized || request.owner.get() != activity) return;
        if (code != Activity.RESULT_OK || data == null) {
            fail(request.id, "CANCELLED", "Screen capture was cancelled"); return;
        }
        request.authorized = true;
        Intent service = new Intent(activity, NativeCaptureService.class).putExtra("requestId", request.id)
                .putExtra("permissionData", data);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
            else activity.startService(service);
        } catch (RuntimeException unavailable) { fail(request.id, "CAPTURE_UNAVAILABLE", "Screen capture service could not start"); }
    }
    static synchronized boolean complete(String id, Map<String, Object> value) {
        Request request = current(id); if (request == null) return false;
        pending = null; return request.result.complete(value);
    }
    static synchronized void fail(String id, String code, String message) {
        Request request = current(id); if (request == null) return;
        pending = null; request.result.completeExceptionally(new NativeBridgeServer.Failure(code, message));
        NativeCaptureService.cancel(id);
    }
    static synchronized void detach(Activity owner) {
        if (pending != null && !pending.authorized && pending.owner.get() == owner)
            fail(pending.id, "CANCELLED", "DSH closed before screen capture authorization");
    }
    static synchronized void close() {
        if (pending != null) fail(pending.id, "CANCELLED", "The native bridge stopped");
    }
}
