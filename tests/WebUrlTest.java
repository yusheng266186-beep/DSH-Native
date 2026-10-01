package dev.dsh.nativeapp;

/**
 * WebUrl 的离线测试。
 *
 * 两条取向必须钉死：
 *   ① **误拒正常链接比放行一个坏链接更糟** —— 所以不限制域名、路径、长度；
 *   ② 控制字符、空格、构造不出 host 的一律不算链接。
 */
public class WebUrlTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 基本可识别 ===");
        check("http", WebUrl.looksLikeRealUrl("http://example.com"), "rejected");
        check("https", WebUrl.looksLikeRealUrl("https://example.com/a/b?c=d"), "rejected");
        check("uppercase scheme", WebUrl.looksLikeRealUrl("HTTPS://EXAMPLE.COM"), "rejected");
        check("surrounding space trimmed", WebUrl.looksLikeRealUrl("  https://example.com  "), "rejected");

        System.out.println("=== 2. 交给系统的非网页 scheme ===");
        String[] handled = {"mailto:a@b.c", "tel:+8613800000000", "sms:+86138",
                "intent://x#Intent;end", "market://details?id=x", "geo:0,0?q=x"};
        for (String s : handled) {
            check("handled: " + s, WebUrl.looksLikeRealUrl(s), "rejected");
        }
        check("MAILTO uppercase", WebUrl.looksLikeRealUrl("MAILTO:a@b.c"), "rejected");

        System.out.println("=== 3. 明显不是链接 ===");
        check("null", !WebUrl.looksLikeRealUrl(null), "accepted");
        check("empty", !WebUrl.looksLikeRealUrl(""), "accepted");
        check("blank", !WebUrl.looksLikeRealUrl("   "), "accepted");
        check("plain text", !WebUrl.looksLikeRealUrl("just some text"), "accepted");
        check("no scheme", !WebUrl.looksLikeRealUrl("example.com"), "accepted");
        check("file scheme", !WebUrl.looksLikeRealUrl("file:///etc/passwd"), "accepted");
        check("javascript scheme", !WebUrl.looksLikeRealUrl("javascript:alert(1)"), "accepted");

        System.out.println("=== 4. 控制字符与空格（构造不出 URL）===");
        check("inner space", !WebUrl.looksLikeRealUrl("http://exa mple.com"), "accepted");
        check("newline", !WebUrl.looksLikeRealUrl("http://example.com\nx"), "accepted");
        check("tab", !WebUrl.looksLikeRealUrl("http://exam\tple.com"), "accepted");
        check("del char", !WebUrl.looksLikeRealUrl("http://example.com\u007F"), "accepted");

        System.out.println("=== 5. 不因为「看起来奇怪」而误拒（设计取向）===");
        check("localhost", WebUrl.looksLikeRealUrl("http://localhost:3080"), "rejected");
        check("ip host", WebUrl.looksLikeRealUrl("http://127.0.0.1:3080/?token=x"), "rejected");
        check("no dot in host", WebUrl.looksLikeRealUrl("http://intranet/path"), "rejected");
        check("long query", WebUrl.looksLikeRealUrl(
                "https://example.com/?q=" + repeat("a", 300)), "rejected");
        check("port only", WebUrl.looksLikeRealUrl("http://example.com:8080"), "rejected");

        System.out.println("=== 6. brief 截断 ===");
        check("short url untouched", WebUrl.brief("https://a.co").equals("https://a.co"), "changed");
        check("null -> empty", WebUrl.brief(null).equals(""), "wrong");
        String longUrl = "https://example.com/" + repeat("b", 200);
        String brief = WebUrl.brief(longUrl);
        check("long url shortened", brief.length() < longUrl.length(), "not shortened");
        check("brief keeps head", brief.startsWith("https://example.com/"), brief);
        check("brief keeps tail", brief.endsWith(longUrl.substring(longUrl.length() - 15)), brief);
        check("brief has ellipsis", brief.contains("…"), brief);

        System.out.println("=== 7. HTML 转义 ===");
        check("null -> empty", WebUrl.escapeHtml(null).equals(""), "wrong");
        check("ampersand escaped", WebUrl.escapeHtml("a&b").equals("a&amp;b"), "wrong");
        check("angle escaped", WebUrl.escapeHtml("<b>").equals("&lt;b&gt;"), "wrong");
        check("quote escaped", WebUrl.escapeHtml("\"x\"").equals("&quot;x&quot;"), "wrong");
        check("apostrophe escaped", WebUrl.escapeHtml("it's").equals("it&#39;s"), "wrong");
        // & 必须最先替换，否则 &lt; 会被二次转义成 &amp;lt;
        check("no double escaping", WebUrl.escapeHtml("<").equals("&lt;"), "wrong");
        check("existing entity not double-escaped",
                WebUrl.escapeHtml("&lt;").equals("&amp;lt;"), "wrong");
        check("tag cannot break page",
                !WebUrl.escapeHtml("</script><script>").contains("<"), "still has <");

        System.out.println();
        System.out.println("WebUrlTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
