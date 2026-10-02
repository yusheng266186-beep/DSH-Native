package dev.dsh.nativeapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Authenticated, bounded RPC transport to the App's own loopback core. */
final class CoreRpcClient {
    private static final Set<String> METHODS = new HashSet<String>(Arrays.asList(
            "account/getState", "account/startSignIn", "account/cancelSignIn",
            "account/hasRunningAccountTasks", "account/signOut", "account/getProfile",
            "account/getBalance", "session/modelCatalog", "settings/describe",
            "credentials/set", "credentials/unset"));
    private final String origin;
    private final String token;
    private String cookie;
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    CoreRpcClient(int port, String token) {
        if (port <= 0 || port > 65535 || token == null || token.length() == 0
                || token.length() > 4096 || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            throw new IllegalArgumentException("core authentication unavailable");
        this.origin = "http://127.0.0.1:" + port;
        this.token = token;
    }

    String origin() { return origin; }

    static final class Reply {
        final String id;
        final String body;
        Reply(String id, String body) { this.id = id; this.body = body; }
    }

    /** One core-owned session serializes native writes; no redirect is followed. */
    synchronized Reply call(String method, String argsJson) throws IOException {
        if (!METHODS.contains(method) || argsJson == null || argsJson.length() > 1024 * 1024)
            throw new IOException("invalid native RPC request");
        checkOpen();
        if (cookie == null) authenticate();
        String id = "native-" + UUID.randomUUID();
        String body = "{\"type\":\"client-request\",\"rpcId\":\"" + id
                + "\",\"method\":\"" + method + "\",\"payload\":{\"args\":" + argsJson + "}}";
        HttpURLConnection connection = open(origin + "/api/" + method);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Cookie", cookie);
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            int status = connection.getResponseCode();
            if (status != 200) {
                if (status == 401 || status == 403) cookie = null;
                throw new IOException("core RPC HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream()) {
                return new Reply(id, readLimited(stream));
            }
        } catch (IOException failure) {
            // Platform URLs, cookies and request bodies must never enter error messages.
            throw new IOException("local core request failed");
        } finally { connection.disconnect(); active = null; }
    }

    private void authenticate() throws IOException {
        HttpURLConnection connection = open(origin + "/?token=" + URLEncoder.encode(token, "UTF-8"));
        try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            String header = connection.getHeaderField("Set-Cookie");
            if ((status != 302 && status != 303) || location == null || header == null
                    || header.length() > 8192) throw new IOException("core authentication failed");
            URL target = new URL(new URL(origin + "/"), location);
            if (!origin.equals(target.getProtocol() + "://" + target.getHost() + ":" + target.getPort())
                    || target.getUserInfo() != null) throw new IOException("core authentication redirect rejected");
            String next = header.split(";", 2)[0];
            if (next.indexOf('=') <= 0 || next.indexOf('\r') >= 0 || next.indexOf('\n') >= 0)
                throw new IOException("invalid core cookie");
            checkOpen();
            cookie = next;
        } catch (IOException failure) { throw new IOException("core authentication unavailable"); }
        finally { connection.disconnect(); active = null; }
    }

    private HttpURLConnection open(String url) throws IOException {
        checkOpen();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        active = connection;
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        if (closed) { connection.disconnect(); throw new IOException("core client closed"); }
        return connection;
    }

    private String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkOpen();
            if (output.size() + count > 2 * 1024 * 1024) throw new IOException("core response too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), "UTF-8");
    }

    private void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("core client closed");
    }

    void close() {
        closed = true;
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }
}
