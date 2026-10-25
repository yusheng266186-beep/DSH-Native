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
    private static final int FIRST_REQUEST = 0x4000, LAST_REQUEST = 0x7fff;
    private static int nextRequest = FIRST_REQUEST;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Request pending;
    private static String serviceRequestId;
    static final class Request {
        final String id = UUID.randomUUID().toString();
        final File root;
        final WeakReference<Activity> owner;
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<Map<String, Object>>();
        final int requestCode;
        Runnable deadline;
        boolean authorized;
        Request(Activity owner, File root, int code) {
            this.owner = new WeakReference<Activity>(owner); this.root = root; requestCode = code;
        }
    }
    static boolean isRequestCode(int code) { return code >= FIRST_REQUEST && code <= LAST_REQUEST; }
    static synchronized Request request(Activity activity, File root) throws NativeBridgeServer.Failure {
        if (pending != null || serviceRequestId != null)
            throw new NativeBridgeServer.Failure("BUSY", "A screenshot request or its resource cleanup is still active");
        // Do not recycle a result code in this process: a cancelled system prompt can return late.
        if (nextRequest > LAST_REQUEST)
            throw new NativeBridgeServer.Failure("CAPTURE_UNAVAILABLE", "Restart the App before requesting more capture authorizations");
        final Request request = new Request(activity, root, nextRequest++); pending = request;
        try {
            MediaProjectionManager manager = (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("No projection service");
            activity.startActivityForResult(manager.createScreenCaptureIntent(), request.requestCode);
            request.deadline = new Runnable() { @Override public void run() {
                fail(request.id, "CAPTURE_TIMEOUT", "Screen capture timed out; retry and approve the system prompt");
            } };
            MAIN.postDelayed(request.deadline, 90000L);
            return request;
        } catch (RuntimeException unavailable) {
            pending = null; throw new NativeBridgeServer.Failure("CAPTURE_UNAVAILABLE", "System screen capture is unavailable");
        }
    }
    static synchronized Request current(String id) { return pending != null && pending.id.equals(id) ? pending : null; }
    static synchronized void activityResult(Activity activity, int requestCode, int code, Intent data) {
        Request request = pending;
        if (request == null || request.requestCode != requestCode || request.authorized || request.owner.get() != activity) return;
        if (code != Activity.RESULT_OK || data == null) {
            fail(request.id, "CANCELLED", "Screen capture was cancelled"); return;
        }
        request.authorized = true;
        serviceRequestId = request.id;
        Intent service = new Intent(activity, NativeCaptureService.class).putExtra("requestId", request.id)
                .putExtra("permissionData", data);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service);
            else activity.startService(service);
        } catch (RuntimeException unavailable) {
            serviceRequestId = null;
            fail(request.id, "CAPTURE_UNAVAILABLE", "Screen capture service could not start");
        }
    }
    // Called after Service.onDestroy has released its projection/reader/display and queued work.
    static synchronized boolean serviceFinished(String id, Map<String, Object> value, String code, String message) {
        if (id != null && id.equals(serviceRequestId)) serviceRequestId = null;
        Request request = current(id); if (request == null) return false;
        pending = null; clearDeadline(request);
        if (value != null) return request.result.complete(value);
        request.result.completeExceptionally(new NativeBridgeServer.Failure(code, message)); return false;
    }
    static synchronized void fail(String id, String code, String message) {
        Request request = current(id); if (request == null) return;
        pending = null; clearDeadline(request);
        request.result.completeExceptionally(new NativeBridgeServer.Failure(code, message));
        NativeCaptureService.cancel(id);
    }
    private static void clearDeadline(Request request) {
        if (request.deadline != null) MAIN.removeCallbacks(request.deadline);
        request.deadline = null;
    }
    static synchronized void detach(Activity owner) {
        if (pending != null && !pending.authorized && pending.owner.get() == owner)
            fail(pending.id, "CANCELLED", "DSH closed before screen capture authorization");
    }
    static synchronized void close() {
        if (pending != null) fail(pending.id, "CANCELLED", "The native bridge stopped");
    }
}
