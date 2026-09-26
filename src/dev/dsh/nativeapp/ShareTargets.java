package dev.dsh.nativeapp;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;

/**
 * 分享用的路径编解码与类型判定：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>背景：把应用私有目录里的文件交给别的应用（分享、用其它程序打开），
 * 必须通过 {@code content://} URI —— API 24 起 {@code file://} 会被对方拒绝。
 * 而这个 URI 里要携带文件的绝对路径。
 *
 * <h3>两个必须做对的地方</h3>
 * <ol>
 *   <li><b>路径必须转义。</b> 文件名里有空格、中文、{@code #}、{@code ?} 都很常见，
 *       直接拼进 URI 会被截断或解析错。转义后还要保证能被原样解回来。</li>
 *   <li><b>解码后必须再校验白名单。</b> URI 是**外部应用**传进来的 ——
 *       对方可以自己构造一个路径来读任意文件。转义只解决格式问题，
 *       权限判断必须独立做一遍。</li>
 * </ol>
 */
final class ShareTargets {

    /** URI 里路径段的固定前缀，便于与其它路径区分。 */
    static final String PREFIX = "f/";

    private ShareTargets() { }

    /**
     * 把文件路径编码成可安全放进 URI 的一段。
     *
     * <p>用 {@code URLEncoder} 之后再修正两处：它把空格编成 {@code +}
     * （在路径段里应保留为 {@code %20}），并且不转义 {@code *}。
     * 另外把 {@code /} 也转义掉 —— 否则路径里的分隔符会被当成层级。
     */
    static String encode(File f) {
        if (f == null) return null;
        String p = f.getAbsolutePath();
        String enc;
        try {
            enc = URLEncoder.encode(p, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        return enc.replace("+", "%20").replace("*", "%2A");
    }

    /**
     * 解码 URI 里的路径段。
     *
     * @return 文件对象；格式不合法时返回 null（不抛异常 —— 这是外部输入）
     */
    static File decode(String segment) {
        if (segment == null) return null;
        String s = segment;
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length());
        if (s.length() == 0) return null;
        try {
            String path = URLDecoder.decode(s, "UTF-8");
            // 只接受绝对路径：相对路径会随工作目录变化，不可预期
            if (!path.startsWith("/")) return null;
            // 去掉可能的重复斜杠与结尾斜杠，便于后续比较
            while (path.contains("//")) path = path.replace("//", "/");
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new File(path);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 这个文件是否可以分享出去。
     *
     * <p>只有白名单内的**常规文件**可以 —— 目录不分享（对方拿到目录 URI 没有意义），
     * 白名单外的位置不分享（那是把系统文件或别的应用数据递出去）。
     */
    static boolean isShareable(File f, List<File> allowedRoots) {
        if (f == null || !f.isFile()) return false;
        if (isSensitiveName(f.getName())) return false;
        return FileOps.isWritable(f, allowedRoots);
    }

    /** 外部 ContentProvider 给出的显示名不能直接当作磁盘路径。 */
    static String incomingName(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);

        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\0' || c < 0x20 || c == 0x7F || c == '/' || c == '\\') {
                clean.append('_');
            } else {
                clean.append(c);
            }
        }
        value = clean.toString().trim();
        while (value.startsWith(".")) value = "_" + value.substring(1);
        if (value.length() == 0 || value.equals(".") || value.equals("..")) {
            value = fallback == null || fallback.trim().length() == 0
                    ? "shared-file" : fallback.trim();
        }
        while (utf8Length(value) > 240 && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        if (FileOps.validateName(value) != null) return "shared-file";
        return value;
    }

    /** 同名文件不覆盖，依次生成“名称 (2).扩展名”。 */
    static File uniqueDestination(File directory, String safeName) {
        if (directory == null) return null;
        String name = incomingName(safeName, "shared-file");
        File direct = new File(directory, name);
        if (!direct.exists() && isContained(directory, direct)) return direct;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i <= 9999; i++) {
            String candidateName = incomingName(base + " (" + i + ")" + extension,
                    "shared-file-" + i);
            File candidate = new File(directory, candidateName);
            if (!candidate.exists() && isContained(directory, candidate)) return candidate;
        }
        return null;
    }

    static boolean isContained(File directory, File candidate) {
        if (directory == null || candidate == null) return false;
        try {
            String root = directory.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.startsWith(root.endsWith("/") ? root : root + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isSensitiveName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.equals(".credentials.yaml") || n.equals(".env")
                || n.equals(".npmrc") || n.equals("id_rsa") || n.equals("id_ed25519")
                || n.endsWith(".keystore") || n.endsWith(".jks") || n.endsWith(".p12")
                || n.endsWith(".pem") || n.endsWith(".key");
    }

    private static int utf8Length(String value) {
        try { return value.getBytes("UTF-8").length; }
        catch (Throwable t) { return value.length(); }
    }

    /** 分享时的 MIME 类型；无法判定时给 {@code application/octet-stream}。 */
    static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";

        if (ext.equals("txt")) return "text/plain";
        if (ext.equals("log")) return "text/plain";
        if (ext.equals("md")) return "text/markdown";
        if (ext.equals("json")) return "application/json";
        if (ext.equals("yaml") || ext.equals("yml")) return "text/yaml";
        if (ext.equals("xml")) return "text/xml";
        if (ext.equals("csv")) return "text/csv";
        if (ext.equals("html") || ext.equals("htm")) return "text/html";
        if (ext.equals("js") || ext.equals("mjs") || ext.equals("cjs")) return "text/javascript";
        if (ext.equals("ts")) return "text/plain";
        if (ext.equals("py")) return "text/x-python";
        if (ext.equals("sh")) return "text/x-shellscript";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("zip")) return "application/zip";
        if (ext.equals("gz") || ext.equals("tgz")) return "application/gzip";
        if (ext.equals("tar")) return "application/x-tar";
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("mp4")) return "video/mp4";
        if (ext.equals("mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    /**
     * 分享时的显示名。
     *
     * <p>不能直接用文件名：其中可能含 {@code /}、引号等，某些接收方会据此
     * 构造路径。这里把危险字符替换掉，同时保证不为空。
     */
    static String displayName(File f) {
        if (f == null) return "file";
        String n = f.getName();
        if (n == null || n.length() == 0) return "file";
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '/' || c == '\\' || c == '\0' || c < 0x20) sb.append('_');
            else sb.append(c);
        }
        String out = sb.toString();
        return out.length() > 120 ? out.substring(0, 120) : out;
    }
}
