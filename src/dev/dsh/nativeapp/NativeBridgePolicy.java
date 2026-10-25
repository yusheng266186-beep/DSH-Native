package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closed native command vocabulary; shared by Android and JVM boundary tests. */
final class NativeBridgePolicy {
    static final int MAX_BODY = 128 * 1024, MAX_TEXT = 16000;
    static final long MAX_FILE = 32L * 1024 * 1024;
    static final List<String> METHODS = Arrays.asList("capabilities", "clipboard.get", "clipboard.set",
            "notification.post", "share.text", "share.file", "url.open", "file.open", "app.open",
            "keepalive.status", "screenshot");

    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Map<?, ?> request(String json) {
        Object parsed = JsonValue.parse(json);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Request must be an object");
        Map<?, ?> value = (Map<?, ?>) parsed;
        String method = text(value, "method", 64, true);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("Unknown native method");
        List<String> fields = Arrays.asList("method");
        if (Arrays.asList("clipboard.set", "share.text").contains(method)) {
            text(value, "text", MAX_TEXT, false); fields = Arrays.asList("method", "text");
        } else if ("notification.post".equals(method)) {
            text(value, "title", 120, true); text(value, "text", 4096, false);
            fields = Arrays.asList("method", "title", "text");
        } else if ("share.file".equals(method) || "file.open".equals(method)) {
            text(value, "path", 4096, true); fields = Arrays.asList("method", "path");
        } else if ("url.open".equals(method)) {
            String url = text(value, "url", 4096, true);
            try {
                URI uri = new URI(url);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException("Only HTTP(S) links are supported");
            } catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("Invalid link"); }
            fields = Arrays.asList("method", "url");
        } else if ("app.open".equals(method)) {
            if (!text(value, "package", 200, true).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
                throw new IllegalArgumentException("Invalid App package");
            fields = Arrays.asList("method", "package");
        }
        for (Object key : value.keySet()) if (!fields.contains(key)) throw new IllegalArgumentException("Unsupported field");
        return value;
    }

    static String text(Map<?, ?> value, String key, int max, boolean nonempty) {
        Object item = value.get(key);
        if (!(item instanceof String) || ((String) item).length() > max || ((String) item).indexOf('\0') >= 0
                || (nonempty && ((String) item).trim().length() == 0)) throw new IllegalArgumentException("Invalid " + key);
        return (String) item;
    }

    static File exportFile(String path, List<File> roots) throws IOException {
        File input = new File(path);
        if (!input.isAbsolute()) throw new IOException("Use an absolute file path");
        File file = input.getCanonicalFile();
        String normalized = file.getPath().toLowerCase(java.util.Locale.ROOT);
        for (String segment : normalized.split("/")) {
            if (segment.equals(".dsh") || segment.equals(".git") || segment.equals(".ssh")
                    || segment.equals(".native-bin") || segment.equals(".native-bridge.json")
                    || segment.startsWith(".env.") || segment.startsWith(".credentials"))
                throw new IOException("Private configuration cannot be exported");
        }
        if (!ShareTargets.isShareable(file, roots) || file.length() > MAX_FILE)
            throw new IOException("Choose a regular workspace file no larger than 32 MiB");
        return file;
    }

    static int[] captureSize(int width, int height) {
        if (width < 1 || height < 1 || width > 32768 || height > 32768)
            throw new IllegalArgumentException("Invalid capture dimensions");
        double scale = Math.min(1.0, 2048.0 / Math.max(width, height));
        return new int[] {Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale))};
    }

    static ByteBuffer captureBuffer(ByteBuffer input, int width, int height, int rowStride, int pixelStride) {
        long bytes = (long) rowStride * height;
        long required = (long) (height - 1) * rowStride + (long) width * 4;
        if (input == null || width < 1 || height < 1 || pixelStride != 4 || rowStride < (long) width * 4
                || rowStride % 4 != 0 || bytes > 24000000L || input.remaining() < required)
            throw new IllegalArgumentException("Unsupported or incomplete screen frame");
        ByteBuffer source = input.duplicate();
        if (source.remaining() >= bytes) {
            source.limit(source.position() + (int) bytes); return source.slice();
        }
        // Android may omit only the final row's trailing padding, never its visible pixels.
        ByteBuffer padded = ByteBuffer.allocate((int) bytes);
        padded.put(source); padded.position(0); return padded;
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(json(item.getKey().toString())).append(':').append(json(item.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) { if (out.length() > 1) out.append(','); out.append(json(item)); }
            return out.append(']').toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
