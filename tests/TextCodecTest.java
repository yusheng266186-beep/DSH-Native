package dev.dsh.nativeapp;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * TextCodec 的离线测试。
 *
 * 重点是**往返一致性**：decode → encode 必须还原出原始字节。
 * 编码判断错了不会报错，只会静默损坏文件 —— 所以必须用字节级断言守住。
 */
public class TextCodecTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static byte[] cat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static String CN = "这是中文配置\nkey: 值\n";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. BOM detection ===");
        byte[] utf8Bom = cat(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF}, CN.getBytes("UTF-8"));
        TextCodec.Decoded d1 = TextCodec.decode(utf8Bom);
        check("UTF-8 BOM recognised", d1.hasBom && "UTF-8".equals(d1.charset), d1.describe());
        check("BOM stripped from text", d1.text.startsWith("这是中文"), d1.text.substring(0, Math.min(6, d1.text.length())));
        check("UTF-8 BOM round-trip", Arrays.equals(TextCodec.encode(d1.text, d1), utf8Bom), "mismatch");

        byte[] u16le = cat(new byte[]{(byte)0xFF,(byte)0xFE}, CN.getBytes("UTF-16LE"));
        TextCodec.Decoded d2 = TextCodec.decode(u16le);
        check("UTF-16LE BOM recognised", d2.hasBom && "UTF-16LE".equals(d2.charset), d2.describe());
        check("UTF-16LE round-trip", Arrays.equals(TextCodec.encode(d2.text, d2), u16le), "mismatch");

        byte[] u16be = cat(new byte[]{(byte)0xFE,(byte)0xFF}, CN.getBytes("UTF-16BE"));
        TextCodec.Decoded d3 = TextCodec.decode(u16be);
        check("UTF-16BE BOM recognised", d3.hasBom && "UTF-16BE".equals(d3.charset), d3.describe());
        check("UTF-16BE round-trip", Arrays.equals(TextCodec.encode(d3.text, d3), u16be), "mismatch");

        System.out.println("=== 2. no BOM: UTF-8 vs GBK ===");
        byte[] utf8 = CN.getBytes("UTF-8");
        TextCodec.Decoded d4 = TextCodec.decode(utf8);
        check("plain UTF-8 detected", "UTF-8".equals(d4.charset) && !d4.hasBom, d4.describe());
        check("plain UTF-8 round-trip", Arrays.equals(TextCodec.encode(d4.text, d4), utf8), "mismatch");

        byte[] gbk = CN.getBytes("GB18030");
        TextCodec.Decoded d5 = TextCodec.decode(gbk);
        check("GBK detected as GB18030 (not garbled)", "GB18030".equals(d5.charset), d5.describe());
        check("GBK decoded correctly", d5.text.startsWith("这是中文"), d5.text.substring(0, Math.min(6, d5.text.length())));
        check("GBK round-trip", Arrays.equals(TextCodec.encode(d5.text, d5), gbk), "mismatch");

        System.out.println("=== 3. newline style ===");
        check("LF detected", "\n".equals(TextCodec.detectNewline("a\nb\nc")), "?");
        check("CRLF detected", "\r\n".equals(TextCodec.detectNewline("a\r\nb\r\nc")), "?");
        check("CR detected", "\r".equals(TextCodec.detectNewline("a\rb\rc")), "?");
        check("majority wins (2 CRLF + 1 LF)", "\r\n".equals(TextCodec.detectNewline("a\r\nb\r\nc\nd")), "?");
        check("no newline defaults to LF", "\n".equals(TextCodec.detectNewline("abc")), "?");
        check("empty defaults to LF", "\n".equals(TextCodec.detectNewline("")), "?");

        byte[] crlfBytes = "a\r\nb\r\n".getBytes("UTF-8");
        TextCodec.Decoded d6 = TextCodec.decode(crlfBytes);
        check("CRLF preserved on round-trip", Arrays.equals(TextCodec.encode(d6.text, d6), crlfBytes), "mismatch");

        System.out.println("=== 4. line count ===");
        check("3 lines", TextCodec.countLines("a\nb\nc") == 3, String.valueOf(TextCodec.countLines("a\nb\nc")));
        check("trailing newline counts as extra line", TextCodec.countLines("a\n") == 2, String.valueOf(TextCodec.countLines("a\n")));
        check("empty is 0", TextCodec.countLines("") == 0, String.valueOf(TextCodec.countLines("")));
        check("single line", TextCodec.countLines("abc") == 1, String.valueOf(TextCodec.countLines("abc")));

        System.out.println("=== 5. binary detection ===");
        check("NUL -> binary", TextCodec.looksBinary(new byte[]{0x7F,0x45,0x4C,0x46,0x00,0x01}), "should be true");
        check("text -> not binary", !TextCodec.looksBinary("hello 中文".getBytes("UTF-8")), "should be false");
        // 注意：Java 的 new byte[n] 全是 0，直接用它测不出「8KB 之后」——
        // 必须显式填成文本，再把 NUL 放到 8KB 之外。
        byte[] far = new byte[9000];
        Arrays.fill(far, (byte) 'a');
        far[8500] = 0;
        check("NUL beyond 8KB window ignored", !TextCodec.looksBinary(far), "should be false");
        byte[] near = new byte[9000];
        Arrays.fill(near, (byte) 'a');
        near[100] = 0;
        check("NUL inside 8KB window detected", TextCodec.looksBinary(near), "should be true");

        System.out.println("=== 6. edge cases ===");
        check("null decode does not throw", TextCodec.decode(null) != null, "null");
        check("empty round-trip", Arrays.equals(TextCodec.encode("", TextCodec.decode(new byte[0])), new byte[0]), "mismatch");
        TextCodec.Decoded dn = TextCodec.decode(null);
        check("null metadata encode does not throw", TextCodec.encode("x", null) != null, "null");
        check("describe() readable", dn.describe() != null && dn.describe().length() > 0, "empty");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
