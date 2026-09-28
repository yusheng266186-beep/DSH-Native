package dev.dsh.nativeapp;

/**
 * 原生交互反馈的纯逻辑策略。
 *
 * <p>动画时长、进度边界和延迟恢复的代次判定集中在这里，
 * 避免各面板各自写一套数字，也便于在普通 JVM 上验证。</p>
 */
final class InteractionFeedback {
    static final int DIALOG_ENTER_MS = 240;
    static final int DIALOG_EXIT_MS = 160;
    static final int BUTTON_PRESS_MS = 85;
    static final int BUTTON_RELEASE_MS = 145;
    static final int CONTENT_REVEAL_MS = 240;
    static final int CONTENT_STAGGER_MS = 24;
    static final int MAX_STAGGER_STEPS = 6;
    static final int CHOICE_CHANGE_MS = 180;
    static final int DIALOG_SWAP_MS = 110;
    static final int RESULT_HOLD_MS = 720;
    static final int SUBMIT_TIMEOUT_MS = 5000;
    static final float PRESSED_SCALE = 0.96f;

    private InteractionFeedback() { }

    static int progress(int completed, int total) {
        if (total <= 0) return 0;
        return Math.max(0, Math.min(total, completed));
    }

    static int percent(int completed, int total) {
        if (total <= 0) return 0;
        long done = progress(completed, total);
        return (int) Math.min(100L, (done * 100L) / total);
    }

    static int nextGeneration(int current) {
        return current == Integer.MAX_VALUE ? 1 : current + 1;
    }

    static boolean isCurrent(int expected, int current) {
        return expected > 0 && expected == current;
    }

    /** 分层进入的延迟有上限，长表单不会拖成逐项慢放。 */
    static int revealDelay(int index) {
        int step = Math.max(0, Math.min(MAX_STAGGER_STEPS, index));
        return step * CONTENT_STAGGER_MS;
    }

    /** 系统关闭动画时返回 0；自定义资源不可用时才回退系统样式。 */
    static int dialogAnimationStyle(boolean animationsEnabled,
                                    int resolvedStyle, int fallbackStyle) {
        if (!animationsEnabled) return 0;
        return resolvedStyle > 0 ? resolvedStyle : Math.max(0, fallbackStyle);
    }
}
