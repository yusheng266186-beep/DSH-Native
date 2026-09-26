package dev.dsh.nativeapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 移动端布局补丁的纯逻辑部分。 */
final class MobileLayout {
    private static final String STYLE_OPEN = "<style id=\"dsh-native-responsive\">";
    private static final String STYLE_CLOSE = "</style>";
    private static final Pattern VIEWPORT = Pattern.compile(
            "content=\\\"width=(?:device-width|[0-9]+)(?:,\\s*initial-scale=1(?:\\.0)?)?\\\"");

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
        String viewport = "content=\"width=" + viewportWidth + ", initial-scale=1\"";
        String out = matcher.replaceFirst(Matcher.quoteReplacement(viewport));

        String style = STYLE_OPEN
                + "@media(max-width:840px){"
                + "[role=dialog]{box-sizing:border-box!important;"
                + "max-width:calc(100vw - 16px)!important;max-height:calc(100vh - 16px)!important;}"
                + "textarea,input,select{font-size:16px!important;}"
                + "button,[role=button]{min-height:44px;}"
                + "pre,code{max-width:100%;overflow-wrap:anywhere;}"
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
        return out;
    }
}
