package dev.dsh.nativeapp;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded private diagnostics. Redaction happens before both logcat and the disk queue. */
final class AppLog {
    interface Sink { void write(String message); }
    private final Sink system;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(1024);
    private final AtomicInteger dropped = new AtomicInteger();
    private final Object disk = new Object();
    private volatile File file;
    private boolean closed;
    private Thread writer;

    AppLog(Sink system) { this.system = system; }
    void bind(File file) { this.file = file; }
    void line(String message) {
        if (message == null) return;
        String bounded = message.length() > 8192 ? message.substring(0, 8192) + " [truncated]" : message;
        String safe = SecretMasker.mask(bounded);
        system.write(safe);
        synchronized (this) {
            if (closed) return;
            if (writer == null) {
                writer = new Thread(new Runnable() { @Override public void run() { consume(); } }, "dsh-private-log");
                writer.setDaemon(true); writer.start();
            }
            if (!queue.offer(safe)) dropped.incrementAndGet();
        }
    }
    private void consume() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String next = queue.take();
                int lost = dropped.getAndSet(0);
                if (lost > 0) append("Log queue full: " + lost + " lines omitted");
                append(next);
            }
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
        finally { queue.clear(); }
    }
    private void append(String line) {
        synchronized (disk) {
            File target = file;
            if (target == null) return;
            try {
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create private log directory");
                if (target.length() > 512 * 1024) {
                    File previous = new File(target + ".1");
                    if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate private log");
                    if (!target.renameTo(previous)) throw new IOException("Cannot rotate private log");
                }
                try (Writer output = new OutputStreamWriter(new FileOutputStream(target, true), "UTF-8")) {
                    output.write(line); output.write('\n');
                }
            } catch (IOException unavailable) { system.write("Private log write failed: " + unavailable.getClass().getSimpleName()); }
        }
    }
    void clear() throws IOException {
        synchronized (disk) {
            queue.clear();
            if (file != null) try (FileOutputStream out = new FileOutputStream(file)) { out.getFD().sync(); }
        }
    }
    synchronized void close() {
        closed = true;
        if (writer != null) writer.interrupt();
    }
}
