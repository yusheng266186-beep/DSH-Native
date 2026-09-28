package dev.dsh.nativeapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 移动端布局补丁的纯逻辑部分。 */
final class MobileLayout {
    private static final String STYLE_OPEN = "<style id=\"dsh-native-responsive\">";
    private static final String STYLE_CLOSE = "</style>";
    private static final String PROBE_OPEN = "<script id=\"dsh-native-session-probe\">";
    private static final String PROBE_CLOSE = "</script>";
    private static final String CONNECTION_OPEN =
            "<script id=\"dsh-native-connection-watch\">";
    private static final String DRAFT_OPEN =
            "<script id=\"dsh-native-draft-recovery\">";
    private static final Pattern VIEWPORT = Pattern.compile(
            "content=\\\"width=(?:device-width|[0-9]+)"
            + "(?:,\\s*initial-scale=[0-9]+(?:\\.[0-9]+)?)?\\\"");

    private MobileLayout() { }

    /**
     * 窄屏保留 480 CSS px，避免桌面优先组件被挤坏；更宽设备按真实宽度渲染。
     * 旧实现一律乘 1.5，横屏和平板也会缩小文字。
     */
    static int viewportWidth(int screenWidthDp) {
        int width = screenWidthDp > 0 ? screenWidthDp : 400;
        if (width < 480) return 480;
        return Math.min(width, 1440);
    }

    /** 给 DSH 的 index.html 写入可重复应用的 viewport 与移动端 CSS。 */
    static String patchHtml(String html, int viewportWidth) {
        if (html == null) return null;
        Matcher matcher = VIEWPORT.matcher(html);
        if (!matcher.find()) return null;
        // 不锁死 initial-scale=1。窄屏使用 480 CSS px 时，强制 1:1 会让页面
        // 超出物理屏幕，用户每次进入都得手动缩小；交给 WebView 的 overview
        // 模式计算 fit-to-width 初始比例，同时仍保留双指缩放。
        String viewport = "content=\"width=" + viewportWidth + "\"";
        String out = matcher.replaceFirst(Matcher.quoteReplacement(viewport));

        String style = STYLE_OPEN
                + "@media(max-width:840px){"
                + "[role=dialog]{box-sizing:border-box!important;"
                + "max-width:calc(100vw - 16px)!important;max-height:calc(100vh - 16px)!important;}"
                + "textarea,input,select{box-sizing:border-box;max-width:100%;}"
                + "[data-shortcut-modal=\"settings\"] [role=switch]{flex-shrink:0!important;}"
                + "pre,code{max-width:100%;overflow-wrap:anywhere;}"
                + "}"
                + "@media(max-width:520px){"
                + "[data-shortcut-modal=\"settings\"][role=dialog]{"
                + "width:calc(100vw - 16px)!important;flex-direction:column!important;}"
                + "[data-shortcut-modal=\"settings\"]>nav{box-sizing:border-box!important;"
                + "width:100%!important;gap:8px!important;padding:12px 8px 8px!important;"
                + "border-bottom:.5px solid var(--dsw-alias-border-l2)!important;}"
                + "[data-shortcut-modal=\"settings\"]>nav>div:first-child{padding:0 8px!important;}"
                + "[data-shortcut-modal=\"settings\"]>nav>div:last-child{"
                + "flex-direction:row!important;overflow-x:auto!important;overflow-y:hidden!important;}"
                + "[data-shortcut-modal=\"settings\"]>nav>div:last-child>button{"
                + "flex:0 0 auto!important;padding-left:10px!important;padding-right:10px!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child{min-height:0!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child>div:first-child{"
                + "height:auto!important;min-height:48px!important;padding:10px 8px 6px!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child>div:last-child{"
                + "padding:0 16px 16px!important;}"
                + "}"
                + "@media(max-height:520px) and (orientation:landscape){"
                + "[role=dialog]{max-height:calc(100vh - 8px)!important;}"
                + "}"
                + STYLE_CLOSE;

        int oldStart = out.indexOf(STYLE_OPEN);
        if (oldStart >= 0) {
            int oldEnd = out.indexOf(STYLE_CLOSE, oldStart);
            if (oldEnd < 0) return null;
            out = out.substring(0, oldStart) + style
                    + out.substring(oldEnd + STYLE_CLOSE.length());
        } else {
            int head = out.indexOf("</head>");
            if (head < 0) return null;
            out = out.substring(0, head) + style + out.substring(head);
        }

        // WebViewClient.onPageStarted 的 JavaScript 执行时机在不同内核上并不完全一致。
        // 把只读状态探针写进 index.html，且置于 deferred module 执行之前，才能保证
        // 首次 /api/session/list 请求不被漏掉；重复启动会原位替换而不是叠加。
        String probe = PROBE_OPEN + SessionProbe.script() + PROBE_CLOSE;
        int oldProbe = out.indexOf(PROBE_OPEN);
        if (oldProbe >= 0) {
            int oldProbeEnd = out.indexOf(PROBE_CLOSE, oldProbe);
            if (oldProbeEnd < 0) return null;
            out = out.substring(0, oldProbe) + probe
                    + out.substring(oldProbeEnd + PROBE_CLOSE.length());
        } else {
            int head = out.indexOf("</head>");
            if (head < 0) return null;
            out = out.substring(0, head) + probe + out.substring(head);
        }

        // 连接生命周期与草稿监听同样必须早于前端模块。两者只观察同源页面：
        // 前者不读取 WebSocket URL/消息，后者只写该 localhost origin 的
        // localStorage；均不向页面暴露原生权限。
        out = upsertScript(out, CONNECTION_OPEN, ConnectionRecovery.script());
        if (out == null) return null;
        out = upsertScript(out, DRAFT_OPEN, DraftRecovery.script());
        return out;
    }

    /** 在 head 尾部插入或原位替换一个具名脚本，确保补丁可重复应用。 */
    private static String upsertScript(String html, String open, String body) {
        if (html == null) return null;
        String script = open + body + PROBE_CLOSE;
        int oldStart = html.indexOf(open);
        if (oldStart >= 0) {
            int oldEnd = html.indexOf(PROBE_CLOSE, oldStart);
            if (oldEnd < 0) return null;
            return html.substring(0, oldStart) + script
                    + html.substring(oldEnd + PROBE_CLOSE.length());
        }
        int head = html.indexOf("</head>");
        if (head < 0) return null;
        return html.substring(0, head) + script + html.substring(head);
    }
}
