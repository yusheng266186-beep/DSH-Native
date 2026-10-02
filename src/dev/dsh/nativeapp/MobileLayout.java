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
     * 窄屏保留 480 CSS px。
     *
     * <p><b>为什么不能改成「忠实于屏幕宽度」</b>
     *
     * <p>0.33.4 试过：设备 436dp 就返回 436。数学上确实不再溢出，但**视口越小，
     * 同样的 CSS 像素占的物理宽度越大** —— 436 比 480 窄 9%，于是所有元素整体
     * 放大约 1.10~1.20 倍，一屏能看的内容少两成。用户反馈是「整个页面被放大、
     * 可视内容偏小」，而且不止设置一处，是全局观感问题。
     *
     * <p>也就是说：视口过大会挤压个别组件，视口过小会放大一切。
     * 两者都不是好结果，而**放大一切的负面范围明显更大**。
     * 因此这里维持 480，把窄屏适配交给 CSS 处理，而不是缩放整个页面。
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
                + "html{-webkit-text-size-adjust:100%;}"
                + "@media(max-width:840px){"
                + "[role=dialog]{box-sizing:border-box!important;"
                + "max-width:calc(100vw - 16px)!important;max-height:calc(100vh - 16px)!important;}"
                + "textarea,input,select{box-sizing:border-box;max-width:100%;}"
                // 编辑器区域（附件 chip、模型选择器、任务栏）在窄屏下被挤压：
                // 图片附件的 × 按钮被 thumbnail 压变形，文件名盖住取消按钮。
                // 上游这些节点没有 data-* 锚点、类名又是 CSS Module 哈希化的，
                // 所以只能用**通用防御规则**命中，不依赖任何具体类名。
                + "img,svg{max-width:100%;}"
                // 横向排列的按钮组：窄屏下允许换行，且按钮本身不被压缩。
                // flex-shrink:0 是关键 —— 缺了它，× 按钮就是被邻居挤扁的那个。
                + "[role=dialog] [role=button],"
                + "[class*=Attachment] button,"
                + "[class*=Card] button{flex:0 0 auto!important;min-width:0;}"
                // 文件名与路径：超长时截断而不是把兄弟节点顶出去。
                + "[class*=Card] [class*=name],[class*=Card] [class*=meta],"
                + "[class*=Attach] [class*=name]{"
                + "min-width:0!important;overflow:hidden!important;"
                + "text-overflow:ellipsis!important;white-space:nowrap!important;}"
                // 带图标的行：让文字区可收缩，图标固定。
                + "[class*=Card] [class*=body],[class*=Card] [class*=meta]{min-width:0!important;}"
                + "[class*=Card] [class*=icon]{flex:0 0 auto!important;}"
                // 附件缩略图：限制尺寸并允许收缩，× 按钮才不会被顶变形。
                + "[class*=thumbnail]{flex:0 1 auto!important;max-width:100%!important;"
                + "min-width:0!important;}"
                + "[class*=remove]{flex:0 0 auto!important;}"
                + "[data-shortcut-modal=\"settings\"] [role=switch]{flex-shrink:0!important;}"
                + "pre,code{max-width:100%;overflow-wrap:anywhere;}"
                + "[role=dialog] button,[role=dialog] [role=button]{"
                + "max-width:100%;overflow-wrap:anywhere;}"
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
