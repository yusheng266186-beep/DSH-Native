package dev.dsh.nativeapp;

import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded temporary commands. Managed DSH processes are owned exclusively by HarnessService. */
final class ProcessRunner {
    interface Lines { void line(String line); }
    private ProcessRunner() { }
    static int run(ProcessBuilder builder, long timeoutMs, final Lines sink) throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        final Process child = builder.start();
        final AtomicReference<IOException> error = new AtomicReference<IOException>();
        Thread output = new Thread(new Runnable() {
            @Override public void run() {
                try (Reader in = new InputStreamReader(child.getInputStream(), "UTF-8")) {
                    StringBuilder line = new StringBuilder(); int c;
                    while ((c = in.read()) != -1) {
                        if (c == '\n') { sink.line(line.toString()); line.setLength(0); }
                        else if (line.length() < 8192) line.append((char)c);
                    }
                    if (line.length() > 0) sink.line(line.toString());
                } catch (IOException failure) { error.set(failure); }
            }
        }, "dsh-command-output");
        output.setDaemon(true); output.start();
        long until = System.nanoTime() + timeoutMs * 1000000L;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Command cancelled");
                try {
                    int code = child.exitValue();
                    output.join(2000L);
                    if (output.isAlive()) throw new IOException("Command output did not close");
                    if (error.get() != null) throw error.get();
                    return code;
                } catch (IllegalThreadStateException running) {
                    if (System.nanoTime() >= until) throw new IOException("Command timed out");
                    Thread.sleep(25L);
                }
            }
        } finally {
            child.destroy();
            // Closing the private command pipe unblocks its reader after cancellation.
            try { child.getInputStream().close(); } catch (IOException cleanup) { }
            output.interrupt();
        }
    }
}
