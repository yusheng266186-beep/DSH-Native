package dev.dsh.nativeapp;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short-lived foreground projection: one PNG, then release every capture resource. */
public final class NativeCaptureService extends Service {
    private static volatile NativeCaptureService current;
    private final AtomicBoolean finished = new AtomicBoolean();
    private HandlerThread thread;
    private Handler worker;
    private volatile String requestId;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private int density, width, height;
    private long frameAfter;
    private Map<String, Object> result;
    private File resultFile;
    private String failureCode = "CANCELLED", failureMessage = "Capture service stopped";

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate(); current = this;
        thread = new HandlerThread("dsh-one-shot-capture"); thread.start(); worker = new Handler(thread.getLooper());
    }
    static void cancel(String id) {
        final NativeCaptureService service = current;
        if (service != null && id.equals(service.requestId)) service.worker.post(new Runnable() {
            @Override public void run() { service.finish("CANCELLED", "Screen capture cancelled"); }
        });
    }
    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        if (requestId != null) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        requestId = intent.getStringExtra("requestId");
        if (NativeScreenCapture.current(requestId) == null) { stopSelf(); return START_NOT_STICKY; }
        try {
            DshUi.ensureChannel(this, "dsh_screen_capture", "屏幕捕获", "用户授权的单次屏幕截图", NotificationManager.IMPORTANCE_LOW);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "dsh_screen_capture") : new Notification.Builder(this);
            Notification notification = builder.setContentTitle("DSH 正在截取屏幕")
                    .setContentText("仅保存一张截图，完成后立即停止")
                    .setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) Service.class.getMethod("startForeground", int.class, Notification.class, int.class)
                    .invoke(this, 0x4460, notification, 32);
            else startForeground(0x4460, notification);
            worker.post(new Runnable() { @Override public void run() { begin((Intent) intent.getParcelableExtra("permissionData")); } });
        } catch (Exception unavailable) { finish("CAPTURE_UNAVAILABLE", "Could not start the capture foreground service"); }
        return START_NOT_STICKY;
    }
    private void begin(Intent data) {
        if (finished.get() || NativeScreenCapture.current(requestId) == null) { finish("CANCELLED", "Capture request expired"); return; }
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(Activity.RESULT_OK, data);
            if (projection == null) throw new IllegalStateException("Projection unavailable");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { finish("CAPTURE_STOPPED", "Screen capture stopped by Android"); }
                // Called by API 34+; declared without @Override to keep the API 28 build surface.
                public void onCapturedContentResize(int w, int h) { resize(w, h); }
            }, worker);
            WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
            density = getResources().getConfiguration().densityDpi;
            int[] size = NativeBridgePolicy.captureSize(metrics.widthPixels, metrics.heightPixels);
            width = size[0]; height = size[1]; frameAfter = SystemClock.uptimeMillis() + 300L;
            reader = imageReader();
            display = projection.createVirtualDisplay("DSH single screenshot", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
            worker.postDelayed(new Runnable() { @Override public void run() { finish("CAPTURE_TIMEOUT", "No screen frame received"); } }, 8000L);
        } catch (Exception unavailable) { finish("CAPTURE_UNAVAILABLE", "Android could not capture the selected screen"); }
        catch (OutOfMemoryError limit) { finish("CAPTURE_TOO_LARGE", "Insufficient memory to prepare screen capture"); }
    }
    private ImageReader imageReader() {
        final ImageReader value = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        value.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader available) {
                if (available != reader || finished.get()) return;
                frame(available);
            }
        }, worker); return value;
    }
    private void resize(int w, int h) {
        if (display == null || finished.get()) return;
        try {
            int[] size = NativeBridgePolicy.captureSize(w, h);
            if (width == size[0] && height == size[1]) return;
            width = size[0]; height = size[1];
            ImageReader old = reader; reader = imageReader();
            try { display.resize(width, height, density); display.setSurface(reader.getSurface()); }
            finally { old.close(); }
        } catch (RuntimeException invalid) { finish("CAPTURE_UNAVAILABLE", "Screen size changed during capture"); }
        catch (OutOfMemoryError limit) { finish("CAPTURE_TOO_LARGE", "Insufficient memory to resize screen capture"); }
    }
    private void frame(ImageReader available) {
        Bitmap padded = null, cropped = null; File output = null; boolean retained = false;
        try (Image image = available.acquireLatestImage()) {
            if (image == null || SystemClock.uptimeMillis() < frameAfter || finished.get()) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = NativeBridgePolicy.captureBuffer(plane.getBuffer(), width, height,
                    plane.getRowStride(), plane.getPixelStride());
            int paddedWidth = plane.getRowStride() / 4;
            padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888); padded.copyPixelsFromBuffer(buffer);
            cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            NativeScreenCapture.Request request = NativeScreenCapture.current(requestId);
            if (request == null) { finish("CANCELLED", "Capture request expired"); return; }
            File directory = new File(request.root, "native-captures");
            if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
                throw new IOException("Capture directory unavailable");
            output = File.createTempFile("screen-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG save failed");
                stream.getFD().sync();
            }
            Map<String, Object> value = NativeBridgePolicy.object("path", output.getAbsolutePath(), "mimeType", "image/png",
                    "width", width, "height", height, "bytes", output.length());
            if (!finished.compareAndSet(false, true)) return;
            result = value;
            resultFile = output; retained = true;
            release();
        } catch (Exception failure) { finish("CAPTURE_UNAVAILABLE", "Could not save the captured frame"); }
        catch (OutOfMemoryError limit) { finish("CAPTURE_TOO_LARGE", "Insufficient memory to capture the selected screen"); }
        finally {
            if (cropped != null && cropped != padded) cropped.recycle();
            if (padded != null) padded.recycle();
            if (!retained && output != null && output.exists() && !output.delete()) output.deleteOnExit();
        }
    }
    private void finish(String code, String message) {
        if (!finished.compareAndSet(false, true)) return;
        failureCode = code; failureMessage = message; release();
    }
    private void release() {
        try { if (display != null) display.release(); }
        catch (RuntimeException stopped) { android.util.Log.w("DSHNative", "截图显示释放: " + stopped.getClass().getSimpleName()); }
        finally { display = null; }
        try { if (reader != null) reader.close(); }
        catch (RuntimeException stopped) { android.util.Log.w("DSHNative", "截图帧释放: " + stopped.getClass().getSimpleName()); }
        finally { reader = null; }
        try { if (projection != null) { MediaProjection previous = projection; projection = null; previous.stop(); } }
        catch (RuntimeException stopped) { android.util.Log.w("DSHNative", "截图投影释放: " + stopped.getClass().getSimpleName()); }
        try { stopForeground(true); }
        catch (RuntimeException stopped) { android.util.Log.w("DSHNative", "截图通知释放: " + stopped.getClass().getSimpleName()); }
        finally { stopSelf(); }
    }
    @Override public void onDestroy() {
        if (current == this) current = null;
        worker.post(new Runnable() { @Override public void run() {
            finish("CANCELLED", "Capture service stopped");
            worker.removeCallbacksAndMessages(null);
            boolean delivered = NativeScreenCapture.serviceFinished(requestId, result, failureCode, failureMessage);
            if (!delivered && resultFile != null && resultFile.exists() && !resultFile.delete()) resultFile.deleteOnExit();
            result = null; resultFile = null; thread.quitSafely();
        } });
        super.onDestroy();
    }
}
