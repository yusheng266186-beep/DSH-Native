package dev.dsh.nativeapp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Bounded localhost index probe with DSH's launch-token cookie exchange. */
final class LocalServerProbe {
    private LocalServerProbe() { }

    static String readAuthenticatedIndex(String address, int timeoutMs) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL original = new URL(address);
            if (!"http".equals(original.getProtocol())
                    || !"127.0.0.1".equals(original.getHost()) || original.getPort() <= 0) return null;
            URL current = original;
            String cookie = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(false);
                if (cookie != null) connection.setRequestProperty("Cookie", cookie);
                int status = connection.getResponseCode();
                if (status == 200) {
                    input = connection.getInputStream();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while (bytes.size() < 65536 && (count = input.read(buffer, 0,
                            Math.min(buffer.length, 65536 - bytes.size()))) >= 0) {
                        bytes.write(buffer, 0, count);
                    }
                    return new String(bytes.toByteArray(), "UTF-8");
                }
                if (attempt != 0 || (status != 302 && status != 303)) return null;
                String location = connection.getHeaderField("Location");
                String setCookie = connection.getHeaderField("Set-Cookie");
                if (location == null || setCookie == null || setCookie.length() > 8192) return null;
                URL next = new URL(current, location);
                // The launch token and signed cookie must never leave this server.
                if (!original.getProtocol().equals(next.getProtocol())
                        || !original.getHost().equals(next.getHost())
                        || original.getPort() != next.getPort() || next.getUserInfo() != null) return null;
                cookie = setCookie.split(";", 2)[0];
                if (cookie.indexOf('=') <= 0 || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0) return null;
                connection.disconnect();
                connection = null;
                current = next;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
        return null;
    }
}
