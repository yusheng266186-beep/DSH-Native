package dev.dsh.nativeapp;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Bounded parallel staging. No runtime directory is changed until every archive verifies. */
final class PayloadDownloads {
    interface Fetcher { void fetch(String name, File out, ReleaseDownloader.Control control) throws IOException; }
    interface Progress { void changed(long done, long total); }
    static final class Part {
        final String name, sha256; final long size;
        Part(String name, long size, String sha256) { this.name = name; this.size = size; this.sha256 = sha256; }
    }
    private final List<Part> parts;
    private final Map<String, Long> progress = new HashMap<String, Long>();
    private final Progress observer;
    private final long total;
    PayloadDownloads(List<Part> parts, Progress observer) {
        this.parts = new ArrayList<Part>(parts); this.observer = observer;
        long sum = 0;
        for (Part part : parts) {
            if (!part.name.matches("[A-Za-z0-9_-]+\\.tar\\.zst") || part.size <= 0
                    || !part.sha256.matches("[0-9a-f]{64}") || progress.containsKey(part.name))
                throw new IllegalArgumentException("Invalid download plan");
            progress.put(part.name, 0L); sum += part.size;
        }
        total = sum;
    }
    synchronized void progress(String name, long done) {
        if (!progress.containsKey(name)) return;
        for (Part part : parts) if (part.name.equals(name)) {
            progress.put(name, Math.max(progress.get(name), Math.min(part.size, Math.max(0L, done)))); break;
        }
        long sum = 0; for (long value : progress.values()) sum += value;
        observer.changed(sum, total);
    }
    LinkedHashMap<String, File> stage(final File root, final Fetcher fetcher) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(3, Math.max(1, parts.size())), new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "payload-staging"); thread.setDaemon(true); return thread;
            }
        });
        CompletionService<String> finished = new ExecutorCompletionService<String>(workers);
        List<Future<String>> tasks = new ArrayList<Future<String>>();
        final Map<String, ReleaseDownloader.Control> controls = new HashMap<String, ReleaseDownloader.Control>();
        for (Part part : parts) controls.put(part.name, new ReleaseDownloader.Control());
        try {
            for (final Part part : parts) tasks.add(finished.submit(new Callable<String>() {
                @Override public String call() throws Exception {
                    File out = new File(root, part.name);
                    ReleaseDownloader.Control control = controls.get(part.name);
                    if (!valid(out, part, control)) {
                        if (out.exists() && !out.delete()) throw new IOException("Cannot remove invalid archive: " + part.name);
                        fetcher.fetch(part.name, out, control);
                        if (!valid(out, part, control)) {
                            if (out.exists() && !out.delete()) throw new IOException("Cannot remove invalid download: " + part.name);
                            throw new IOException("运行包校验失败: " + part.name);
                        }
                    }
                    control.check(); progress(part.name, part.size); return part.name;
                }
            }));
            for (int i = 0; i < parts.size(); i++) finished.take().get();
            LinkedHashMap<String, File> result = new LinkedHashMap<String, File>();
            for (Part part : parts) result.put(part.name, new File(root, part.name));
            return result;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Payload staging failed", cause);
        } finally {
            for (ReleaseDownloader.Control control : controls.values()) control.cancel();
            for (Future<String> task : tasks) task.cancel(true);
            workers.shutdownNow();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        }
    }
    private static boolean valid(File file, Part part, ReleaseDownloader.Control control) throws Exception {
        if (!file.isFile() || file.length() != part.size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[262144]; int n;
            while ((n = input.read(buffer)) != -1) { control.check(); digest.update(buffer, 0, n); }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return part.sha256.equals(hex.toString());
    }
}
