package dev.dsh.nativeapp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Private loopback RPC. Browser origins are rejected even with valid credentials. */
final class NativeBridgeServer implements AutoCloseable {
    interface Host { Object execute(Map<?, ?> command) throws Exception; }
    static final class Failure extends Exception {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    private final ServerSocket listener;
    private final Host host;
    private final String token;
    private final File descriptor;
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(4), new ThreadFactory() {
                @Override public Thread newThread(Runnable job) {
                    Thread thread = new Thread(job, "dsh-native-operation"); thread.setDaemon(true); return thread;
                }
            });
    private volatile boolean closed;

    NativeBridgeServer(File root, Host host) throws IOException {
        this.host = host;
        root = root.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Native runtime directory unavailable");
        descriptor = new File(root, ".native-bridge.json");
        if (!descriptor.getCanonicalFile().equals(descriptor.getAbsoluteFile())) throw new IOException("Linked bridge descriptor refused");
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder hex = new StringBuilder(); for (byte b : random) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        token = hex.toString();
        listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        try {
            File temporary = File.createTempFile(".native-bridge-", ".next", root);
            try {
                if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false)
                        || !temporary.setReadable(true, true) || !temporary.setWritable(true, true))
                    throw new IOException("Cannot protect native credentials");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(NativeBridgePolicy.json(NativeBridgePolicy.object("version", 1, "port", port(), "token", token))
                            .getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
                }
                if (!temporary.renameTo(descriptor)) throw new IOException("Cannot publish native bridge");
            } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
        } catch (IOException failure) { listener.close(); throw failure; }
        Thread thread = new Thread(new Runnable() { @Override public void run() { accept(); } }, "dsh-native-bridge");
        thread.setDaemon(true); thread.start();
    }
    int port() { return listener.getLocalPort(); }
    private void accept() {
        while (!closed) try {
            final Socket socket = listener.accept(); socket.setSoTimeout(5000); sockets.add(socket);
            final long accepted = System.nanoTime();
            try { workers.execute(new Runnable() { @Override public void run() {
                if (System.nanoTime() - accepted > TimeUnit.SECONDS.toNanos(5)) {
                    sockets.remove(socket); try { socket.close(); } catch (IOException expired) { /* Expired queued request. */ } return;
                }
                serve(socket);
            } }); }
            catch (RejectedExecutionException full) { sockets.remove(socket); socket.close(); }
        } catch (IOException failure) { if (!closed) close(); }
    }
    private String line(InputStream input, int[] count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int b;
        while ((b = input.read()) != -1) {
            if (++count[0] > 8192) throw new IOException("Header too large");
            if (b == '\n') {
                byte[] value = bytes.toByteArray();
                if (value.length == 0 || value[value.length - 1] != '\r') throw new IOException("Invalid HTTP line");
                return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
            }
            bytes.write(b);
        }
        throw new EOFException();
    }
    private void serve(Socket socket) {
        try {
            InputStream input = socket.getInputStream(); int[] count = {0};
            String first = line(input, count);
            Map<String, String> headers = new HashMap<String, String>();
            for (;;) {
                String row = line(input, count); if (row.length() == 0) break;
                int colon = row.indexOf(':'); if (colon <= 0) throw new IOException("Invalid header");
                String key = row.substring(0, colon).toLowerCase(Locale.ROOT);
                if (headers.put(key, row.substring(colon + 1).trim()) != null) throw new IOException("Duplicate header");
            }
            if (!"POST /native HTTP/1.1".equals(first) || headers.containsKey("origin")
                    || headers.containsKey("transfer-encoding") || headers.containsKey("sec-fetch-site")
                    || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
                        headers.getOrDefault("authorization", "").getBytes(StandardCharsets.US_ASCII))
                    || !("127.0.0.1:" + port()).equals(headers.get("host"))) {
                reply(socket, 403, NativeBridgePolicy.object("ok", false, "error", "FORBIDDEN", "message", "Native authentication required")); return;
            }
            int size;
            try { size = Integer.parseInt(headers.getOrDefault("content-length", "-1")); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid body size"); }
            if (size < 2 || size > NativeBridgePolicy.MAX_BODY
                    || !"application/json".equals(headers.get("content-type"))) throw new IOException("Invalid body");
            byte[] bytes = new byte[size]; int offset = 0;
            while (offset < size) { int read = input.read(bytes, offset, size - offset); if (read < 0) throw new EOFException(); offset += read; }
            Map<?, ?> command;
            try { command = NativeBridgePolicy.request(new String(bytes, StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException invalid) { reply(socket, 400, NativeBridgePolicy.object("ok", false, "error", "INVALID_REQUEST", "message", invalid.getMessage())); return; }
            // A caller may have timed out while queued. Observe EOF before any
            // Android action; an active request has no bytes after its JSON body.
            socket.setSoTimeout(25);
            try { input.read(); return; }
            catch (SocketTimeoutException connected) { /* Peer still present; begin dispatch. */ }
            final AtomicBoolean completed = new AtomicBoolean();
            final Thread executing = Thread.currentThread();
            final InputStream peer = input;
            socket.setSoTimeout(250);
            Thread cancellation = new Thread(new Runnable() { @Override public void run() {
                while (!completed.get()) try {
                    if (peer.read() == -1) { if (!completed.get()) executing.interrupt(); return; }
                    if (!completed.get()) executing.interrupt(); return;
                } catch (SocketTimeoutException waiting) { /* Poll caller lifetime while an operation waits for Android. */ }
                catch (IOException disconnected) { if (!completed.get()) executing.interrupt(); return; }
            } }, "dsh-native-caller");
            cancellation.setDaemon(true); cancellation.start();
            try {
                Object result = host.execute(command);
                reply(socket, 200, NativeBridgePolicy.object("ok", true, "result", result));
            } catch (Failure failure) {
                reply(socket, 200, NativeBridgePolicy.object("ok", false, "error", failure.code, "message", failure.getMessage()));
            } catch (Exception failure) {
                reply(socket, 200, NativeBridgePolicy.object("ok", false, "error", "NATIVE_UNAVAILABLE", "message", "Native operation unavailable; return to DSH and retry"));
            } finally {
                completed.set(true);
                try { socket.shutdownInput(); } catch (IOException disconnected) { /* Wake the caller watcher. */ }
                boolean joined = false;
                while (!joined) try { cancellation.join(1000L); joined = true; }
                catch (InterruptedException cancelled) { /* Drain the watcher before this worker serves a new request. */ }
                Thread.interrupted();
            }
        } catch (IOException malformed) {
            // Never log headers, credentials, clipboard text or native payloads.
        } finally {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException closedSocket) { /* Already disconnected. */ }
        }
    }
    private void reply(Socket socket, int status, Object body) throws IOException {
        byte[] bytes = NativeBridgePolicy.json(body).getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                + "Connection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes); output.flush();
    }
    @Override public void close() {
        if (closed) return; closed = true;
        try { listener.close(); } catch (IOException alreadyClosed) { /* Shutdown. */ }
        synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException disconnected) { /* Shutdown. */ } sockets.clear(); }
        workers.shutdownNow();
        // Only remove this instance's descriptor, never a replacement service's.
        try {
            byte[] bytes = new byte[2048];
            if (descriptor.isFile() && descriptor.length() <= bytes.length) try (FileInputStream input = new FileInputStream(descriptor)) {
                int read = input.read(bytes);
                if (read > 0 && new String(bytes, 0, read, StandardCharsets.UTF_8).contains(token)
                        && !descriptor.delete()) descriptor.deleteOnExit();
            }
        } catch (IOException unavailable) { /* Stale credentials cannot authenticate to a closed server. */ }
    }
}
