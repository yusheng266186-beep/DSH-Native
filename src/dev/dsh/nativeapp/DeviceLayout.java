package dev.dsh.nativeapp;

/**
 * 原生界面的多设备布局策略：纯 Java，无 Android 依赖。
 *
 * <p>把屏幕宽度、字体缩放与按钮排布的判断放在这里，避免各对话框各自写一套
 * “看起来差不多”的阈值。手机、横屏和平板都走同一组可离线测试的规则。</p>
 */
final class DeviceLayout {
    static final int MIN_TOUCH_TARGET_DP = 48;
    static final int DIALOG_MAX_WIDTH_DP = 720;

    private DeviceLayout() { }

    static int dialogWidthPx(int screenWidthPx, float density) {
        float safeDensity = density > 0f ? density : 1f;
        int sideSpace = Math.max(1, Math.round(24f * safeDensity));
        int available = Math.max(1, screenWidthPx - sideSpace);
        int cap = Math.max(1, Math.round(DIALOG_MAX_WIDTH_DP * safeDensity));
        return Math.min(available, cap);
    }

    static int dialogHeightPx(int screenHeightPx, int requestedDp, float density) {
        float safeDensity = density > 0f ? density : 1f;
        int requested = Math.max(1, Math.round(Math.max(1, requestedDp) * safeDensity));
        int available = Math.max(1, Math.round(Math.max(1, screenHeightPx) * 0.86f));
        return Math.min(requested, available);
    }

    /**
     * 大字体或窄屏时把底部按钮改为纵向，避免文字被省略到无法区分。
     * 四个及以上操作始终纵向；三个操作在普通手机上也纵向。
     */
    static boolean stackFooter(int screenWidthDp, float fontScale, int buttonCount) {
        if (buttonCount <= 1) return false;
        int width = screenWidthDp > 0 ? screenWidthDp : 360;
        float scale = fontScale > 0f ? fontScale : 1f;
        if (buttonCount >= 4) return true;
        if (buttonCount == 3) return width < 600 || scale >= 1.15f;
        return width < 320 || scale >= 1.55f;
    }

    static String profile(int screenWidthDp, int screenHeightDp, float fontScale) {
        int width = Math.max(0, screenWidthDp);
        int height = Math.max(0, screenHeightDp);
        String size = width >= 840 ? "large-tablet"
                : width >= 600 ? "tablet"
                : width >= 480 ? "wide-phone" : "phone";
        String orientation = width > height ? "landscape" : "portrait";
        float scale = fontScale > 0f ? fontScale : 1f;
        return size + "/" + orientation + "/font="
                + String.format(java.util.Locale.ROOT, "%.2f", scale);
    }
}
