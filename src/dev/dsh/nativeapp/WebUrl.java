package dev.dsh.nativeapp;

import java.net.URL;
import java.util.Locale;

/**
 * 外部链接的识别与展示（纯逻辑，可离线测试）。
 *
 * <p>抽出来的原因：这段判定原先写在 {@code MainActivity.looksLikeRealUrl} 里，
 * 决定「用户点开的到底是不是一个真链接」，却一行测试都跑不了。它一旦判错，
 * 用户看到的要么是点了没反应，要么是把不该外跳的文本当链接丢给系统。
 *
 * <p>设计取向：**误拒正常链接比放行一个坏链接更糟。** 因此这里只拒绝
 * 真正不成立的输入（空、有空格与控制字符、构造不出 host），
 * 不对域名、路径、长度做额外限制 —— 早期版本按 IP 可解析性一律拒绝，
 * 结果在代理或 VPN 的 DNS 下把正常地址也误伤了。
 */
final class WebUrl {

    /** 非网页 scheme：交给系统处理（邮件、电话、短信、应用跳转、地图）。 */
    private static final String[] HANDLED_SCHEMES = {
            "mailto:", "tel:", "sms:", "intent:", "market:", "geo:"
    };

    private WebUrl() { }

    /** 这个字符串是否值得当成外部链接交给系统。 */
    static boolean looksLikeRealUrl(String url) {
        try {
            if (url == null) return false;
            String u = url.trim();
            if (u.length() == 0) return false;
            // 控制字符与空格：这类地址一定构造不出可用 URL。
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c < 0x20 || c == 0x7F || c == ' ') return false;
            }
            String lower = u.toLowerCase(Locale.ROOT);
            for (String scheme : HANDLED_SCHEMES) {
                if (lower.startsWith(scheme)) return true;
            }
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                URL parsed = new URL(u);
                String host = parsed.getHost();
                return host != null && host.length() > 0;
            }
            return false;
        } catch (Throwable t) {
            // 构造不出来就不是可用的外链，按「不是链接」处理。
            return false;
        }
    }

    /** URL 太长时截断，只用于日志与提示。 */
    static String brief(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() <= 80) return u;
        return u.substring(0, 60) + "…" + u.substring(u.length() - 15);
    }

    /** 把要显示的文本编码进 HTML，避免异常文本破坏状态页结构。 */
    static String escapeHtml(String text) {
        if (text == null) return "";
        // & 必须最先替换，否则会把后面生成的实体二次转义。
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
