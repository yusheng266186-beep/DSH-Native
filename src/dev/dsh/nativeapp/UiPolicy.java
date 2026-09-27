package dev.dsh.nativeapp;

/**
 * 原生界面的纯逻辑策略：语义色、动画开关与安全区合并。
 *
 * <p>本类不依赖 Android，保证深浅主题、系统动画缩放和键盘边界判断
 * 可以在普通 JVM 上完整回归。</p>
 */
final class UiPolicy {
    private UiPolicy() { }

    static int success(boolean dark) {
        return dark ? 0xFF22C55E : 0xFF1A7F37;
    }

    static int warning(boolean dark) {
        return dark ? 0xFFF59E0B : 0xFFB26A00;
    }

    static int error(boolean dark) {
        return dark ? 0xFFF25A5A : 0xFFD93025;
    }

    static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** 系统动画缩放为 0 时，所有装饰性动画都应立即完成。 */
    static boolean animationsEnabled(float animatorScale) {
        return !Float.isNaN(animatorScale)
                && !Float.isInfinite(animatorScale)
                && animatorScale > 0f;
    }

    /** WindowInsets 路径推导出的键盘底部内边距。 */
    static int imeFromInsets(int systemBottom, int navigationBar) {
        return Math.max(0, systemBottom - Math.max(0, navigationBar));
    }

    /**
     * 可见窗口路径推导出的键盘底部内边距。
     * 小于屏幕高度 15% 的变化视为状态栏、导航栏或舍入噪声。
     */
    static int imeFromVisibleFrame(int screenHeight, int visibleBottom,
                                   int navigationBar) {
        if (screenHeight <= 0) return 0;
        int hidden = Math.max(0, screenHeight - visibleBottom);
        int keyboard = Math.max(0, hidden - Math.max(0, navigationBar));
        return keyboard < screenHeight * 0.15f ? 0 : keyboard;
    }

    /** 两条键盘探测路径互为兜底，取较大值且绝不返回负数。 */
    static int mergedIme(int fromInsets, int fromVisibleFrame) {
        return Math.max(0, Math.max(fromInsets, fromVisibleFrame));
    }

    static int safeSide(int systemInset, int cutoutInset) {
        return Math.max(0, Math.max(systemInset, cutoutInset));
    }
}
