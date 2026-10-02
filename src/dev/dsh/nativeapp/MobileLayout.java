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
     * 视口宽度：正常情况下等于屏幕实际宽度，上限 1440。
     *
     * <p><b>为什么不再给窄屏兜底一个更大的值</b>
     *
     * <p>旧实现在 {@code screenWidthDp < 480} 时返回 480，理由是「避免桌面优先
     * 组件被挤坏」。实测这正是挤压类缺陷的根源：一台 436dp 的手机拿到
     * 480 CSS px 的视口，页面比屏幕宽约 10%，于是每个 flex 容器里的子项
     * 都被压缩 —— 附件 chip 的文字盖住 × 按钮、模型选择器尾字被切掉。
     *
     * <p>「撑大视口」并不能让组件不被挤：它只是把挤压从一处挪到每一处。
     * 正确做法是让视口忠实于屏幕宽度，再用 CSS 收拾窄屏下的布局。
     */
    static int viewportWidth(int screenWidthDp) {
        int width = screenWidthDp > 0 ? screenWidthDp : 400;
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
                + "html{-webkit-text-size-adjust:100%;}"
                + "@media(max-width:840px){"
                + "[role=dialog]{box-sizing:border-box!important;"
                + "max-width:calc(100vw - 16px)!important;"
                + "max-height:calc(100vh - 16px)!important;}"
                + "textarea,input,select{box-sizing:border-box;max-width:100%;}"
                + "img,svg{max-width:100%;}"
                // 所有按钮都不允许被 flex 兄弟节点压扁。
                //
                // 关键事实：上游类名经 CSS Module 编译后是**哈希短名**
                // （实测形如 Di.close、Ee.itemIcon），不再包含 remove / send /
                // thumbnail 之类语义词 —— 按源码变量名写 [class*=remove] 这类
                // 选择器在真实页面上一个都匹配不到，而构建日志照样显示
                // 「已写入」，看着一切正常。因此这里只用与类名无关的选择器：
                // 元素类型、ARIA 属性、以及稳定的 data-* 锚点。
                //
                // 缺 flex-shrink:0 时，方形图标按钮就是被邻居挤扁的那个 ——
                // 这与它叫什么名字无关。
                + "button{flex-shrink:0!important;}"
                + "button[aria-label]{min-width:0;}"
                + "[role=dialog] [role=button],[role=dialog] button{"
                + "max-width:100%;overflow-wrap:anywhere;}"
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
                + "[data-shortcut-modal=\"settings\"]>nav>div:last-child]{"
                + "flex-direction:row!important;overflow-x:auto!important;"
                + "overflow-y:hidden!important;}"
                + "[data-shortcut-modal=\"settings\"]>nav>div:last-child>button]{"
                + "flex:0 0 auto!important;padding-left:10px!important;"
                + "padding-right:10px!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child]{min-height:0!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child>div:first-child]{"
                + "height:auto!important;min-height:48px!important;padding:10px 8px 6px!important;}"
                + "[data-shortcut-modal=\"settings\"]>div:last-child>div:last-child]{"
                + "padding:0 16px 16px!important;}"
                + "}"
                + "@media(max-height:520px) and (orientation:landscape){"
                + "[role=dialog]{max-height:calc(100vh - 8px)!important;}"
                + "}"
                + "@media(pointer:coarse){"
                + "button:not([role=switch]),[role=button]:not([role=switch]){"
                + "min-height:44px;}"
                + "}"
                + "@media(prefers-reduced-motion:reduce){"
                + "*,*::before,*::after{animation-duration:.01ms!important;"
                + "animation-iteration-count:1!important;transition-duration:.01ms!important;"
                + "scroll-behavior:auto!important;}"
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
