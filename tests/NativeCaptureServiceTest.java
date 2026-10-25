package dev.dsh.nativeapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutionException;

/** Runs the real service with deterministic Android boundaries; no device or real PNG. */
public class NativeCaptureServiceTest {
    private static int checks;
    private static NativeCaptureService active;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); checks++;
    }
    private static NativeScreenCapture.Request authorize(MainActivity ui, File root) throws Exception {
        NativeScreenCapture.Request request = NativeScreenCapture.request(ui, root);
        check(ui.lastRequestCode == request.requestCode && NativeScreenCapture.isRequestCode(request.requestCode), "unique code reaches Android authorization");
        NativeScreenCapture.activityResult(ui, request.requestCode, Activity.RESULT_OK, new Intent("synthetic-grant"));
        return request;
    }
    private static NativeCaptureService start(MainActivity ui) {
        active = new NativeCaptureService(); active.onCreate(); active.onStartCommand(ui.lastService, 0, 1); return active;
    }
    private static void destroy() { active.onDestroy(); active = null; }
    private static void busy(MainActivity ui, File root) throws Exception {
        try { NativeScreenCapture.request(ui, root); throw new AssertionError("capture cleanup admitted a new request"); }
        catch (NativeBridgeServer.Failure expected) { check(expected.code.equals("BUSY"), "cleanup barrier reports busy"); }
    }
    private static void error(NativeScreenCapture.Request request, String code) throws Exception {
        try { request.result.get(); throw new AssertionError("expected capture failure"); }
        catch (ExecutionException failure) { check(((NativeBridgeServer.Failure) failure.getCause()).code.equals(code), "capture error " + code); }
    }
    private static Image frame(ImageReader reader, int missing) {
        SystemClock.time += 400;
        int row = reader.width * 4 + 4, size = row * reader.height - missing;
        ByteBuffer bytes = ByteBuffer.allocate(size);
        for (int i = 0; i < size; i++) bytes.put((byte) (i % 127 + 1)); bytes.flip();
        return new Image(row, 4, bytes);
    }
    private static void bufferChecks() {
        ByteBuffer source = ByteBuffer.allocate(37);
        for (int i = 0; i < 37; i++) source.put((byte) i); source.position(5); source.limit(37);
        ByteBuffer padded = NativeBridgePolicy.captureBuffer(source, 2, 3, 12, 4);
        check(padded.remaining() == 36 && source.position() == 5 && source.limit() == 37, "final padding synthesized without consuming source");
        check(padded.get(0) == 5 && padded.get(31) == 36 && padded.get(35) == 0, "visible pixels retained; only absent final-row padding is zero-filled");
        ByteBuffer full = NativeBridgePolicy.captureBuffer(ByteBuffer.allocate(40), 2, 3, 12, 4);
        check(full.remaining() == 36, "full row layout exposes exact required buffer");
        int[][] invalid = {{2,3,12,4,27},{2,3,7,4,40},{2,3,10,4,40},{2,3,12,3,40},{0,3,12,4,40},{2,0,12,4,40},{2,3,Integer.MAX_VALUE-3,4,40}};
        for (int[] value : invalid) {
            try { NativeBridgePolicy.captureBuffer(ByteBuffer.allocate(value[4]), value[0], value[1], value[2], value[3]); throw new AssertionError("invalid frame accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("dsh-capture-service-").toRealPath();
        MainActivity ui = new MainActivity(); File root = directory.toFile();
        try {
            bufferChecks();
            NativeScreenCapture.Request request = authorize(ui, root); NativeCaptureService service = start(ui);
            check(service.starts == 1 && service.projectionManager.projection.displays == 1, "foreground entered before single projection creation");
            service.onStartCommand(ui.lastService, 0, 2);
            check(!service.stopRequested && service.starts == 1, "duplicate service start does not stop active capture or reuse grant");
            ImageReader reader = ImageReader.latest;
            Image early = new Image(reader.width * 4, 4, ByteBuffer.allocate(reader.width * reader.height * 4)); reader.emit(early);
            check(early.closed && !request.result.isDone(), "early transient frame closed without publishing");
            Image good = frame(reader, 4); reader.emit(good);
            check(good.closed && service.stopRequested && reader.closed, "one frame closes image and requests service stop");
            check(service.projectionManager.projection.stopped && service.projectionManager.projection.display.released, "projection and display released before result");
            check(!request.result.isDone(), "result waits for actual service teardown"); busy(ui, root);
            destroy(); Map<String,Object> result = request.result.get(); File delivered = new File((String) result.get("path"));
            check(delivered.isFile() && ((Number) result.get("width")).intValue() == 2 && ((Number) result.get("height")).intValue() == 3, "teardown delivers result once with dimensions");
            check(HandlerThread.active == 0 && Handler.pending() == 0 && Bitmap.live == 0, "success removes workers, timeouts and bitmaps");

            // Cancellation before Service.onCreate still reserves the cleanup slot for that Intent.
            request = authorize(ui, root); NativeScreenCapture.fail(request.id, "CANCELLED", "test cancellation"); busy(ui, root);
            service = start(ui); check(service.stopRequested && service.projectionManager.projection == null, "expired service intent never creates projection");
            destroy(); error(request, "CANCELLED");

            request = authorize(ui, root); service = start(ui); reader = ImageReader.latest;
            Image truncated = frame(reader, 5); reader.emit(truncated);
            check(service.stopRequested && truncated.closed && !request.result.isDone(), "truncated visible pixels fail without an image result");
            destroy(); error(request, "CAPTURE_UNAVAILABLE");

            request = authorize(ui, root); service = start(ui); ImageReader previous = ImageReader.latest;
            service.projectionManager.projection.callback.onCapturedContentResize(3, 2); reader = ImageReader.latest;
            check(previous.closed && reader.width == 3 && reader.height == 2 && service.projectionManager.projection.displays == 1, "rotation resizes existing display and replaces reader");
            check(service.projectionManager.projection.display.resizes == 1 && service.projectionManager.projection.display.surfaces == 1, "rotated surface applied once");
            reader.emit(frame(reader, 4)); destroy(); result = request.result.get();
            check(result.get("width").equals(3) && result.get("height").equals(2), "rotated capture reports new aspect and dimensions");

            request = authorize(ui, root); service = start(ui); reader = ImageReader.latest; reader.emit(frame(reader, 0));
            int filesBefore;
            try (java.util.stream.Stream<Path> paths = Files.list(directory.resolve("native-captures"))) { filesBefore = (int) paths.count(); }
            NativeScreenCapture.fail(request.id, "CANCELLED", "caller disconnected after PNG write"); busy(ui, root); destroy(); error(request, "CANCELLED");
            try (java.util.stream.Stream<Path> paths = Files.list(directory.resolve("native-captures"))) { check(paths.count() == filesBefore - 1, "cancelled undelivered capture file removed after teardown"); }
            check(delivered.isFile(), "cleanup preserves previously delivered screenshot");

            request = authorize(ui, root); service = start(ui); service.projectionManager.projection.callback.onStop();
            check(service.stopRequested && !request.result.isDone(), "system stop waits for teardown"); destroy(); error(request, "CAPTURE_STOPPED");

            request = authorize(ui, root); service = start(ui);
            Handler.instances.get(Handler.instances.size() - 1).fireDelayed(); destroy(); error(request, "CAPTURE_TIMEOUT");

            request = authorize(ui, root); service = start(ui); Bitmap.failMemory = true; reader = ImageReader.latest; reader.emit(frame(reader, 0));
            Bitmap.failMemory = false; destroy(); error(request, "CAPTURE_TOO_LARGE");

            request = authorize(ui, root); ImageReader.failMemory = true; service = start(ui);
            ImageReader.failMemory = false; destroy(); error(request, "CAPTURE_TOO_LARGE");

            request = authorize(ui, root); service = start(ui); reader = ImageReader.latest;
            ImageReader.failMemory = true; service.projectionManager.projection.callback.onCapturedContentResize(3, 2);
            ImageReader.failMemory = false; check(reader.closed, "resize allocation failure closes the existing reader");
            destroy(); error(request, "CAPTURE_TOO_LARGE");

            for (boolean surfaceFailure : new boolean[] {false, true}) {
                request = authorize(ui, root); service = start(ui); reader = ImageReader.latest;
                service.projectionManager.projection.display.failResize = !surfaceFailure;
                service.projectionManager.projection.display.failSurface = surfaceFailure;
                service.projectionManager.projection.callback.onCapturedContentResize(3, 2);
                check(reader.closed && ImageReader.latest.closed, "failed display resize closes both image readers");
                destroy(); error(request, "CAPTURE_UNAVAILABLE");
            }

            request = authorize(ui, root); active = new NativeCaptureService(); active.onCreate(); active.projectionManager.fail = true;
            active.onStartCommand(ui.lastService, 0, 1); destroy(); error(request, "CAPTURE_UNAVAILABLE");

            request = authorize(ui, root); start(ui); destroy(); error(request, "CANCELLED");
            check(HandlerThread.active == 0 && Handler.pending() == 0 && Bitmap.live == 0, "all failure paths release workers, callbacks and bitmaps");
            System.out.println("Native capture service: " + checks + " frame-layout, grant, lifecycle, cancellation, resize and cleanup checks passed (Android API fakes; no device or real PNG).");
        } finally {
            NativeScreenCapture.close(); if (active != null) destroy();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception failure) { throw new RuntimeException(failure); } });
            }
        }
    }
}
