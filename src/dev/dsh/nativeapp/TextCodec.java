package dev.dsh.nativeapp;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 文本文件的编码与换行处理：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>为什么单独抽出来：这几件事都很容易做错，而做错的后果是**静默损坏用户的文件**——
 * 用 UTF-8 去读 GBK 文本会整篇变成乱码，保存时把 CRLF 统一成 LF 会让
 * Windows 上的脚本 / 配置失效。它们又完全不需要设备就能验证，所以独立成类。
 *
 * <h3>处理的规则</h3>
 * <ol>
 *   <li><b>BOM 优先</b>：开头的 {@code EF BB BF} / {@code FF FE} / {@code FE FF}
 *       直接决定编码，并从正文中去掉。</li>
 *   <li><b>无 BOM 时严格试 UTF-8</b>：用 {@code REPORT} 模式而非默认的替换模式 ——
 *       默认解码器会把非法字节静默换成 U+FFFD，那样 GBK 文本会被读成乱码却不报错。</li>
 *   <li><b>UTF-8 失败退回 GB18030</b>：中文环境里大量文本仍是 GBK 系。</li>
 *   <li><b>换行风格按多数判定并原样写回</b>：不统一转换。</li>
 * </ol>
 */
final class TextCodec {

    private TextCodec() { }

    /** 解码结果：正文 + 原始编码信息，保存时据此还原。 */
    static final class Decoded {
        final String text;
        /** 用于写回的编码名。 */
        final String charset;
        final boolean hasBom;
        /** 主换行符：{@code "\n"} / {@code "\r\n"} / {@code "\r"}。 */
        final String newline;

        Decoded(String text, String charset, boolean hasBom, String newline) {
            this.text = text;
            this.charset = charset;
            this.hasBom = hasBom;
            this.newline = newline;
        }

        /** 编码的可读名称（含 BOM 标记），用于界面展示。 */
        String describe() {
            String n = charset;
            if ("GB18030".equals(charset)) n = "GB18030（GBK 系）";
            return n + (hasBom ? " + BOM" : "")
                     + ("\r\n".equals(newline) ? " · CRLF"
                        : "\r".equals(newline) ? " · CR" : " · LF");
        }
    }

    /** 解码字节。永远不会失败：最坏情况退回 GB18030，再不行用平台默认。 */
    static Decoded decode(byte[] data) {
        if (data == null) data = new byte[0];

        // ① BOM
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            String t = new String(data, 3, data.length - 3, Charset.forName("UTF-8"));
            return new Decoded(t, "UTF-8", true, detectNewline(t));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            String t = new String(data, 2, data.length - 2, Charset.forName("UTF-16LE"));
            return new Decoded(t, "UTF-16LE", true, detectNewline(t));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            String t = new String(data, 2, data.length - 2, Charset.forName("UTF-16BE"));
            return new Decoded(t, "UTF-16BE", true, detectNewline(t));
        }

        // ② 无 BOM：严格试 UTF-8
        try {
            CharsetDecoder d = Charset.forName("UTF-8").newDecoder();
            d.onMalformedInput(CodingErrorAction.REPORT);
            d.onUnmappableCharacter(CodingErrorAction.REPORT);
            String t = d.decode(ByteBuffer.wrap(data)).toString();
            return new Decoded(t, "UTF-8", false, detectNewline(t));
        } catch (Throwable ignored) { }

        // ③ 退回 GB18030（GBK 的超集）
        try {
            String t = new String(data, "GB18030");
            return new Decoded(t, "GB18030", false, detectNewline(t));
        } catch (Throwable ignored) { }

        String t = new String(data);
        return new Decoded(t, "UTF-8", false, detectNewline(t));
    }

    /** 按保存时的编码与 BOM 还原字节。 */
    static byte[] encode(String text, Decoded meta) {
        if (text == null) text = "";
        String cs = meta == null || meta.charset == null ? "UTF-8" : meta.charset;
        boolean bom = meta != null && meta.hasBom;
        byte[] body;
        try {
            body = text.getBytes(cs);
        } catch (Throwable t) {
            body = text.getBytes(Charset.forName("UTF-8"));
        }
        if (!bom) return body;
        byte[] prefix;
        if ("UTF-16LE".equals(cs)) prefix = new byte[]{ (byte) 0xFF, (byte) 0xFE };
        else if ("UTF-16BE".equals(cs)) prefix = new byte[]{ (byte) 0xFE, (byte) 0xFF };
        else prefix = new byte[]{ (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        return out;
    }

    /**
     * 判定主换行符：{@code "\r\n"} / {@code "\r"} / {@code "\n"}。
     *
     * <p>按**多数**判定而非「出现即算」，混合换行的文件取占多数的那种，
     * 保存时不至于把整篇风格翻过来。
     */
    static String detectNewline(String text) {
        if (text == null) return "\n";
        int crlf = 0, lf = 0, cr = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') { crlf++; i++; }
                else cr++;
            } else if (c == '\n') {
                lf++;
            }
        }
        if (crlf == 0 && cr == 0) return "\n";
        if (crlf >= lf && crlf >= cr) return "\r\n";
        if (cr > lf) return "\r";
        return "\n";
    }

    /** 统计行数（用于界面展示；末尾无换行也算一行）。 */
    static int countLines(String text) {
        if (text == null || text.length() == 0) return 0;
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * 是否为二进制内容。
     *
     * <p>只看前 8KB 是否含 NUL —— 文本文件里几乎不会出现 NUL，
     * 而可执行文件 / 压缩包开头通常就有。
     */
    static boolean looksBinary(byte[] data) {
        if (data == null) return false;
        int scan = Math.min(data.length, 8192);
        for (int i = 0; i < scan; i++) if (data[i] == 0) return true;
        return false;
    }
}
