package dev.dsh.nativeapp;

import android.content.*;
import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Lifecycle/error boundary regression for real NativeBridge; no device actions. */
public class NativeActionReliabilityTest {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void denied(NativeBridge bridge, String method, String code, Object... fields) throws Exception {
        try { NativeBridgeAndroidTest.call(bridge, method, fields); throw new AssertionError(method + " succeeded unexpectedly"); }
        catch (NativeBridgeServer.Failure failure) {
            check(code.equals(failure.code), method + " wrong error: " + failure.code);
            check(!failure.getMessage().contains("SECRET"), "system exception details never enter tool output");
        }
    }
    private static int queued() { int count = 0; for (Handler handler : Handler.instances) count += handler.queued.size(); return count; }
    private static long files(Path root) throws Exception { try (java.util.stream.Stream<Path> paths = Files.walk(root)) { return paths.filter(Files::isRegularFile).count(); } }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("dsh-native-reliable-").toRealPath(); NativeBridge bridge = null;
        try {
            HarnessService service = new HarnessService(directory.toFile()); MainActivity ui = new MainActivity();
            ui.workspace = Files.createDirectory(directory.resolve("workspace-a")).toFile(); final File original = ui.workspace;
            Path other = Files.createDirectory(directory.resolve("workspace-b")); NativeBridge.attach(ui); bridge = new NativeBridge(service);
            Path report = original.toPath().resolve("report.txt"); Files.write(report, "synthetic report".getBytes("UTF-8"));
            NativeBridgeAndroidTest.call(bridge, "share.file", "path", report.toString());
            final Intent previous = ui.lastIntent; long retained = files(service.getCacheDir().toPath());
            Handler.postsBeforeHook = 2; Handler.beforePost = () -> ui.workspace = other.toFile();
            denied(bridge, "share.file", "CONTEXT_CHANGED", "path", report.toString());
            check(ui.lastIntent == previous && files(service.getCacheDir().toPath()) == retained, "project switch suppresses chooser and removes only rejected staging");
            ui.workspace = original; MainActivity recreated = new MainActivity(); recreated.workspace = original;
            Handler.postsBeforeHook = 2; Handler.beforePost = () -> NativeBridge.attach(recreated);
            denied(bridge, "file.open", "CONTEXT_CHANGED", "path", report.toString());
            check(recreated.lastIntent == null && files(service.getCacheDir().toPath()) == retained, "Activity replacement cannot open old request or leak cache"); NativeBridge.attach(ui);
            Handler.postsBeforeHook = 2; Handler.beforePost = () -> ui.ready = false;
            denied(bridge, "share.file", "FOREGROUND_REQUIRED", "path", report.toString()); ui.ready = true;
            check(files(service.getCacheDir().toPath()) == retained, "background transition cleans rejected staging");

            ui.launchFailure = new ActivityNotFoundException("SECRET link or file");
            denied(bridge, "url.open", "NO_ACTIVITY_HANDLER", "url", "https://example.com/");
            denied(bridge, "share.text", "NO_ACTIVITY_HANDLER", "text", "synthetic");
            denied(bridge, "file.open", "NO_ACTIVITY_HANDLER", "path", report.toString());
            check(files(service.getCacheDir().toPath()) == retained, "failed viewer launch removes staged file");
            ui.launchFailure = new SecurityException("SECRET package detail");
            denied(bridge, "app.open", "SYSTEM_DENIED", "package", "synthetic.available"); ui.launchFailure = null;
            service.clipboard.failure = new SecurityException("SECRET clipboard");
            denied(bridge, "clipboard.get", "SYSTEM_DENIED"); denied(bridge, "clipboard.set", "SYSTEM_DENIED", "text", "synthetic"); service.clipboard.failure = null;
            service.notifications.channel = null;
            denied(bridge, "notification.post", "NOTIFICATION_CHANNEL_UNAVAILABLE", "title", "synthetic", "text", "body");
            check(service.notifications.count == 0, "missing channel never reports notification posted");
            service.notifications.channel = new android.app.NotificationChannel(); service.notifications.failure = new SecurityException("SECRET notification");
            denied(bridge, "notification.post", "SYSTEM_DENIED", "title", "synthetic", "text", "body"); service.notifications.failure = null;
            NativeBridgeAndroidTest.call(bridge, "notification.post", "title", "synthetic", "text", "body");
            check(service.notifications.count == 1, "failed notification does not consume rate limit");
            android.os.Build.VERSION.SDK_INT = 24; service.notifications.channel = null; SystemClock.time += 3000;
            NativeBridgeAndroidTest.call(bridge, "notification.post", "title", "synthetic", "text", "body");
            check(service.notifications.count == 2, "Android 24 notifications do not require a channel"); android.os.Build.VERSION.SDK_INT = 37;

            // A failed file copy keeps the original and earlier successful cache entries.
            Path shareCache = service.getCacheDir().toPath().resolve("native-share");
            Path oldCache = service.getCacheDir().toPath().resolve("saved-native-share"); Files.move(shareCache, oldCache); Files.write(shareCache, new byte[] {1});
            denied(bridge, "share.file", "FILE_PREPARE_FAILED", "path", report.toString());
            Files.delete(shareCache); Files.move(oldCache, shareCache);
            check(Files.readAllBytes(report).length > 0 && files(shareCache) == retained, "file preparation failure preserves source and prior shares");

            Intent unchanged = ui.lastIntent; Thread.currentThread().interrupt();
            try { denied(bridge, "share.text", "CANCELLED", "text", "synthetic"); }
            finally { Thread.interrupted(); }
            check(ui.lastIntent == unchanged, "already cancelled caller cannot open UI even with an idle main thread");
            Handler.rejectPosts = true; denied(bridge, "clipboard.set", "BRIDGE_STOPPED", "text", "synthetic"); Handler.rejectPosts = false;
            int writes = service.clipboard.writes; Handler.queuePosts = true;
            denied(bridge, "clipboard.set", "UI_TIMEOUT", "text", "synthetic");
            check(queued() == 0 && service.clipboard.writes == writes, "UI timeout removes queued action without clipboard write"); Handler.queuePosts = false;

            final NativeBridge current = bridge; Handler.queuePosts = true; CompletableFuture<String> stopped = new CompletableFuture<>();
            Thread caller = new Thread(() -> { try { NativeBridgeAndroidTest.call(current, "clipboard.set", "text", "synthetic"); stopped.complete("unexpected"); }
                catch (NativeBridgeServer.Failure failure) { stopped.complete(failure.code); } catch (Exception failure) { stopped.complete(failure.getClass().getSimpleName()); } });
            caller.start(); for (int i = 0; i < 200 && queued() == 0; i++) Thread.sleep(5);
            check(queued() == 1, "caller waits for main-thread dispatch"); caller.interrupt();
            check(stopped.get(2, TimeUnit.SECONDS).equals("CANCELLED") && queued() == 0, "caller cancellation removes pending action"); caller.join();

            Handler.queuePosts = false; Handler.asyncPosts = true;
            CountDownLatch authorizationEntered = new CountDownLatch(1), continueAuthorization = new CountDownLatch(1);
            ui.captureHook = () -> { authorizationEntered.countDown(); try { continueAuthorization.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); } };
            CompletableFuture<String> captureCancelled = new CompletableFuture<>();
            Thread captureCaller = new Thread(() -> {
                try { NativeBridgeAndroidTest.call(current, "screenshot"); captureCancelled.complete("unexpected"); }
                catch (NativeBridgeServer.Failure failure) { captureCancelled.complete(failure.code); } catch (Exception failure) { captureCancelled.complete(failure.getClass().getSimpleName()); } });
            captureCaller.start(); check(authorizationEntered.await(2, TimeUnit.SECONDS), "capture authorization action already running");
            captureCaller.interrupt();
            check(captureCancelled.get(1, TimeUnit.SECONDS).equals("CANCELLED"), "caller can cancel during authorization dispatch");
            continueAuthorization.countDown(); captureCaller.join();
            NativeScreenCapture.Request fresh = null;
            for (int i = 0; i < 200 && fresh == null; i++) {
                try { fresh = NativeScreenCapture.request(ui, service.getFilesDir()); }
                catch (NativeBridgeServer.Failure busy) { if (!busy.code.equals("BUSY")) throw busy; Thread.sleep(5); }
            }
            check(fresh != null, "cancel during running UI dispatch leaves no orphaned capture request");
            NativeScreenCapture.fail(fresh.id, "CANCELLED", "synthetic cleanup"); ui.captureHook = null; Handler.asyncPosts = false;

            Handler.queuePosts = true;
            CompletableFuture<String> closed = new CompletableFuture<>(); caller = new Thread(() -> {
                try { NativeBridgeAndroidTest.call(current, "share.text", "text", "synthetic"); closed.complete("unexpected"); }
                catch (NativeBridgeServer.Failure failure) { closed.complete(failure.code); } catch (Exception failure) { closed.complete(failure.getClass().getSimpleName()); } });
            caller.start(); for (int i = 0; i < 200 && queued() == 0; i++) Thread.sleep(5);
            check(queued() == 1, "service stop has a pending UI action"); bridge.close(); bridge = null;
            check(closed.get(2, TimeUnit.SECONDS).equals("BRIDGE_STOPPED") && queued() == 0, "service stop cancels pending UI action promptly"); caller.join();
            System.out.println("Native action reliability: " + checks + " context, Android refusal, notification, staging, timeout and cancellation checks passed (Android API fakes; no device).");
        } finally {
            Handler.rejectPosts = false; Handler.queuePosts = false; Handler.asyncPosts = false; Handler.beforePost = null; android.os.Build.VERSION.SDK_INT = 37;
            if (bridge != null) bridge.close();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception error) { throw new RuntimeException(error); } }); }
        }
    }
}
