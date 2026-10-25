package dev.dsh.nativeapp;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Native operations owned by the foreground service, callable by the local agent. */
final class NativeBridge implements AutoCloseable, NativeBridgeServer.Host {
    private static WeakReference<MainActivity> activity = new WeakReference<MainActivity>(null);
    private final HarnessService service;
    private final File root;
    private final NativeBridgeServer server;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private long lastNotification;

    static synchronized void attach(MainActivity current) { activity = new WeakReference<MainActivity>(current); }
    static synchronized void detach(MainActivity current) {
        if (activity.get() == current) activity.clear(); NativeScreenCapture.detach(current);
    }
    private static synchronized MainActivity foreground() throws NativeBridgeServer.Failure {
        MainActivity current = activity.get();
        if (current == null || !current.nativeBridgeForeground())
            throw new NativeBridgeServer.Failure("FOREGROUND_REQUIRED", "Return to DSH, close system dialogs and retry");
        return current;
    }
    NativeBridge(HarnessService service) throws IOException {
        this.service = service; root = new File(service.getFilesDir().getCanonicalFile(), "dsh");
        if (!root.getCanonicalFile().equals(root)) throw new IOException("Native credentials require the private runtime directory");
        server = new NativeBridgeServer(root, this);
    }
    private <T> T onMain(final Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<T>(new Callable<T>() { @Override public T call() throws Exception {
            if (closed) throw new NativeBridgeServer.Failure("BRIDGE_STOPPED", "Native bridge stopped");
            return action.call();
        } });
        main.post(task);
        try { return task.get(5000, TimeUnit.MILLISECONDS); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause; throw new IOException("Native operation failed");
        } finally { task.cancel(false); }
    }
    @Override public Object execute(final Map<?, ?> command) throws Exception {
        final String method = (String) command.get("method");
        if ("screenshot".equals(method)) {
            final NativeScreenCapture.Request request = onMain(new Callable<NativeScreenCapture.Request>() {
                @Override public NativeScreenCapture.Request call() throws Exception { return NativeScreenCapture.request(foreground(), root); }
            });
            try { return request.result.get(92, TimeUnit.SECONDS); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof NativeBridgeServer.Failure) throw (NativeBridgeServer.Failure) failure.getCause();
                throw new NativeBridgeServer.Failure("CAPTURE_UNAVAILABLE", "Screen capture failed");
            } finally { NativeScreenCapture.fail(request.id, "CANCELLED", "Capture caller disconnected or timed out"); }
        }
        final File staged;
        if ("share.file".equals(method) || "file.open".equals(method)) {
            final List<File> roots = onMain(new Callable<List<File>>() {
                @Override public List<File> call() throws Exception {
                    List<File> paths = new ArrayList<File>(); paths.add(foreground().nativeBridgeWorkspace());
                    paths.add(new File(root, "native-captures")); return paths;
                }
            });
            File input;
            try { input = NativeBridgePolicy.exportFile((String) command.get("path"), roots); }
            catch (IOException refused) { throw new NativeBridgeServer.Failure("FILE_NOT_ALLOWED", "Use a regular file in the current workspace or native-captures, at most 32 MiB; private configuration is excluded"); }
            staged = stage(input);
        } else staged = null;
        try {
            return onMain(new Callable<Object>() { @Override public Object call() throws Exception { return perform(method, command, staged); } });
        } catch (Exception failure) {
            if (staged != null) {
                if (staged.exists() && !staged.delete()) staged.deleteOnExit();
                if (!staged.getParentFile().delete()) staged.getParentFile().deleteOnExit();
            }
            throw failure;
        }
    }
    private File stage(File source) throws Exception {
        File directory = new File(service.getCacheDir(), "native-share");
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
            throw new IOException("Cannot prepare native share cache");
        String name = ShareTargets.incomingName(source.getName(), "shared-file");
        File staging = File.createTempFile("share-", ".dir", directory);
        if (!staging.delete() || !staging.mkdir()) throw new IOException("Cannot prepare unique share directory");
        File output = new File(staging, name);
        boolean complete = false;
        try (InputStream input = new FileInputStream(source); FileOutputStream stream = new FileOutputStream(output)) {
            byte[] bytes = new byte[65536]; long total = 0; int count;
            while ((count = input.read(bytes)) != -1) {
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Native share cancelled");
                total += count; if (total > NativeBridgePolicy.MAX_FILE) throw new IOException("Share file exceeds limit");
                stream.write(bytes, 0, count);
            }
            stream.getFD().sync(); complete = true; return output;
        } finally {
            if (!complete) {
                if (output.exists() && !output.delete()) output.deleteOnExit();
                if (!staging.delete()) staging.deleteOnExit();
            }
        }
    }
    private Object perform(String method, Map<?, ?> args, File file) throws Exception {
        if ("capabilities".equals(method)) {
            MainActivity current = activity.get();
            boolean visible = current != null && current.nativeBridgeForeground();
            return NativeBridgePolicy.object("version", 1, "methods", NativeBridgePolicy.METHODS,
                    "foregroundReady", visible, "clipboardRequiresForeground", true,
                    "screenshotConsent", "per-request-system-prompt", "maxTextChars", NativeBridgePolicy.MAX_TEXT,
                    "maxFileBytes", NativeBridgePolicy.MAX_FILE);
        }
        if ("keepalive.status".equals(method)) return service.nativeKeepaliveStatus();
        if ("notification.post".equals(method)) return notifyAgent((String) args.get("title"), (String) args.get("text"));
        MainActivity current = foreground();
        if (method.startsWith("clipboard.")) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) service.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new NativeBridgeServer.Failure("CLIPBOARD_UNAVAILABLE", "Android clipboard is unavailable");
            if ("clipboard.set".equals(method)) {
                clipboard.setPrimaryClip(ClipData.newPlainText("DSH", (String) args.get("text")));
                return NativeBridgePolicy.object("copied", true, "chars", ((String) args.get("text")).length());
            }
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return NativeBridgePolicy.object("text", "", "hasText", false);
            CharSequence content = clip.getItemAt(0).getText();
            if (content == null) return NativeBridgePolicy.object("text", "", "hasText", false);
            if (content.length() > NativeBridgePolicy.MAX_TEXT)
                throw new NativeBridgeServer.Failure("TEXT_TOO_LONG", "Clipboard text exceeds 16000 characters");
            return NativeBridgePolicy.object("text", content.toString(), "hasText", true);
        }
        if ("share.text".equals(method)) {
            current.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, (String) args.get("text")), "分享文本"));
            return NativeBridgePolicy.object("chooserOpened", true, "delivered", false);
        }
        if (file != null) {
            Uri uri = Uri.parse("content://" + UpdateProvider.AUTHORITY + "/" + ShareTargets.PREFIX + ShareTargets.encode(file));
            Intent intent = new Intent("share.file".equals(method) ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
            if ("share.file".equals(method)) intent.setType(ShareTargets.mimeOf(file.getName())).putExtra(Intent.EXTRA_STREAM, uri);
            else intent.setDataAndType(uri, ShareTargets.mimeOf(file.getName()));
            intent.setClipData(ClipData.newRawUri("DSH file", uri)); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            current.startActivity(Intent.createChooser(intent, "share.file".equals(method) ? "分享文件" : "打开文件")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return NativeBridgePolicy.object("chooserOpened", true, "delivered", false);
        }
        if ("url.open".equals(method)) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse((String) args.get("url"))).addCategory(Intent.CATEGORY_BROWSABLE);
            current.startActivity(intent); return NativeBridgePolicy.object("opened", true);
        }
        if ("app.open".equals(method)) {
            Intent launch = service.getPackageManager().getLaunchIntentForPackage((String) args.get("package"));
            if (launch == null) throw new NativeBridgeServer.Failure("APP_UNAVAILABLE", "App has no available launch activity");
            current.startActivity(launch); return NativeBridgePolicy.object("opened", true);
        }
        throw new NativeBridgeServer.Failure("UNSUPPORTED", "Unknown native operation");
    }
    private Object notifyAgent(String title, String text) throws Exception {
        NotificationManager manager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled())
            throw new NativeBridgeServer.Failure("NOTIFICATIONS_DISABLED", "Allow DSH notifications in Android App settings");
        DshUi.ensureChannel(service, "dsh_agent_messages", "Agent 消息", "Agent 主动发送的任务消息", NotificationManager.IMPORTANCE_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel("dsh_agent_messages").getImportance() == 0)
            throw new NativeBridgeServer.Failure("NOTIFICATIONS_DISABLED", "Allow the Agent messages notification channel");
        long now = SystemClock.uptimeMillis();
        if (lastNotification > 0 && now - lastNotification < 2000L)
            throw new NativeBridgeServer.Failure("RATE_LIMITED", "Wait two seconds before posting another notification");
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(service, "dsh_agent_messages") : new Notification.Builder(service);
        manager.notify(0x4461, builder.setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setAutoCancel(true).setContentIntent(PendingIntent.getActivity(service, 0, new Intent(service, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        lastNotification = now; return NativeBridgePolicy.object("posted", true, "delivered", false);
    }
    @Override public void close() { closed = true; server.close(); NativeScreenCapture.close(); }
}
