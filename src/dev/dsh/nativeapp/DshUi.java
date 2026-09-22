package dev.dsh.nativeapp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * DeepSeek Harness 的原生 UI 组件层。
 *
 * <p><b>项目约定：所有原生界面必须使用本类构建，不得直接使用 Android 默认控件样式。</b>
 * 目的是让设置页、日志页等原生界面与 DSH 的 Web 界面视觉统一 ——
 * 系统默认的 Material 风格（下划线输入框、水波纹按钮、灰色对话框）
 * 与 DSH 的浅色卡片风格放在一起会明显割裂。
 *
 * <p>视觉参数取自 DSH 前端实际使用的设计变量：
 * <ul>
 *   <li>卡片：白底、圆角 16dp、极细边框 #00000014</li>
 *   <li>输入框：浅灰底 #F5F6F8、圆角 10dp、无下划线</li>
 *   <li>按钮：圆角 10dp；主按钮品牌蓝 #4D6BFE，次按钮浅灰 #F3F4F6</li>
 *   <li>文字层级：#1F2329 / #6B7280 / #9CA3AF</li>
 * </ul>
 */
public final class DshUi {

    // ---------------------------------------------------------------- 设计变量
    public static final int BG          = 0xFFF7F8FA;   // 页面底色
    public static final int CARD        = 0xFFFFFFFF;   // 卡片
    public static final int BORDER      = 0x14000000;   // 8% 黑，极细描边
    public static final int FIELD       = 0xFFF5F6F8;   // 输入框底
    public static final int FIELD_FOCUS = 0xFFEDEFF3;
    public static final int BTN         = 0xFFF3F4F6;   // 次按钮底
    public static final int BTN_PRESS   = 0xFFE8EAEE;
    public static final int ACCENT      = 0xFF4D6BFE;   // 品牌蓝
    public static final int ACCENT_DARK = 0xFF3D59E8;

    public static final int TEXT        = 0xFF1F2329;   // 主文字
    public static final int TEXT_2      = 0xFF6B7280;   // 次要
    public static final int TEXT_3      = 0xFF9CA3AF;   // 弱化
    public static final int ON_ACCENT   = 0xFFFFFFFF;

    private DshUi() { }

    // ---------------------------------------------------------------- 工具
    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable round(int fill, int strokeColor, float radiusPx, float strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke((int) Math.max(1, strokePx), strokeColor);
        return d;
    }

    /** 卡片背景：白底 + 极细描边 + 大圆角。 */
    public static GradientDrawable cardBg(Context c) {
        return round(CARD, BORDER, dp(c, 16), dp(c, 1));
    }

    /** 输入框背景（含按下态）。 */
    public static StateListDrawable fieldBg(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                round(FIELD_FOCUS, BORDER, dp(c, 10), dp(c, 1)));
        s.addState(new int[]{}, round(FIELD, BORDER, dp(c, 10), dp(c, 1)));
        return s;
    }

    /** 按钮背景（主/次 + 按下态）。 */
    public static StateListDrawable buttonBg(Context c, boolean primary) {
        StateListDrawable s = new StateListDrawable();
        if (primary) {
            s.addState(new int[]{android.R.attr.state_pressed},
                    round(ACCENT_DARK, 0, dp(c, 10), 0));
            s.addState(new int[]{}, round(ACCENT, 0, dp(c, 10), 0));
        } else {
            s.addState(new int[]{android.R.attr.state_pressed},
                    round(BTN_PRESS, 0, dp(c, 10), 0));
            s.addState(new int[]{}, round(BTN, 0, dp(c, 10), 0));
        }
        return s;
    }

    // ---------------------------------------------------------------- 组件
    /** 对话框标题。 */
    public static TextView title(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(17f);
        tv.setTextColor(TEXT);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    /** 区块小标题（如「更新」）。 */
    public static TextView sectionLabel(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(TEXT);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    /** 字段标签。 */
    public static TextView label(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(TEXT_2);
        return tv;
    }

    /** 说明/次要文字。 */
    public static TextView hint(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(TEXT_3);
        tv.setLineSpacing(dp(c, 2), 1f);
        return tv;
    }

    /** 状态文字（可被异步更新）。 */
    public static TextView status(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(11.5f);
        tv.setTextColor(TEXT_2);
        tv.setLineSpacing(dp(c, 2), 1f);
        return tv;
    }

    /** 输入框：浅灰圆角底、无下划线、内边距舒适。 */
    public static EditText input(Context c, String value, boolean secret) {
        EditText et = new EditText(c);
        et.setText(value == null ? "" : value);
        et.setTextSize(14f);
        et.setTextColor(TEXT);
        et.setSingleLine(true);
        et.setBackground(fieldBg(c));
        int ph = dp(c, 12), pv = dp(c, 10);
        et.setPadding(ph, pv, ph, pv);
        et.setHintTextColor(TEXT_3);
        if (secret) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return et;
    }

    /** 按钮：圆角矩形，去 Material 阴影与水波纹。 */
    public static Button button(Context c, String text, boolean primary) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(13.5f);
        b.setAllCaps(false);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setBackground(buttonBg(c, primary));
        // 清掉主题可能附加的背景着色（backgroundTint）——
        // 否则 setBackground 设的颜色会被 tint 覆盖，
        // 导致「切回次按钮样式却仍显示主按钮色」这类不一致。
        try { b.setBackgroundTintList(null); } catch (Throwable ignored) { }
        b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        try {
            b.setStateListAnimator(null);      // 去掉 Material 的抬升动画
        } catch (Throwable ignored) { }
        return b;
    }

    /** 撑满宽度的按钮布局参数。 */
    /**
     * 切换按钮的「主按钮 / 次按钮」外观。
     *
     * <p>用于互斥选择（例如显示缩放的档位）：切换时必须把所有兄弟按钮
     * 一并重置，只让选中项保持高亮 —— 否则会出现多个同时高亮。
     */
    public static void setButtonActive(Button b, boolean primary) {
        if (b == null) return;
        b.setBackground(buttonBg(b.getContext(), primary));
        b.setTextColor(primary ? ON_ACCENT : TEXT);
    }

    /**
     * 分档选择按钮（例如显示缩放的 100% / 115% / 130% / 150%）。
     *
     * <p><b>必须保证单行</b>：选中项的文字形如 {@code "100% ✓"}，
     * 比未选中的 {@code "115%"} 更长 —— 一旦宽度不够就会把对勾折到第二行，
     * 按钮随之被撑高，看起来就像「溢出」到同级按钮之外（实测踩过）。
     * 这里用 {@code setSingleLine(true)} 从根上杜绝换行，并收窄内边距
     * 与字号，给标记留出空间。
     */
    public static Button toggleButton(Context c, String text, boolean primary) {
        Button b = button(c, text, primary);
        b.setSingleLine(true);
        // 单行 + 省略号：权重分配的按钮在标签过长时应显示「…」，
        // 而不是把字硬切一半
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setIncludeFontPadding(false);
        b.setTextSize(13f);
        b.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
        return b;
    }

    /**
     * 内容区背景：纯色圆角，无状态变化。
     *
     * <p>与 {@link #fieldBg} 的区别很重要：那是**输入框**背景，
     * 带 focus/disabled 状态；拿它当列表容器背景会在获得焦点时变色。
     */
    public static GradientDrawable surfaceBg(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(FIELD);
        d.setCornerRadius(dp(c, 10));
        return d;
    }

    /**
     * 列表行之间的细分割线。
     *
     * <p>两端用 {@link android.graphics.drawable.InsetDrawable} 留白，
     * 不顶到卡片圆角 —— 分割线触碰圆角会显得毛糙。
     * 配合 {@code LinearLayout.setShowDividers(SHOW_DIVIDER_MIDDLE)} 使用，
     * **不产生额外 View**（八百个条目加八百条线会明显拖慢布局）。
     */
    public static android.graphics.drawable.Drawable divider(Context c) {
        android.graphics.drawable.ShapeDrawable line =
                new android.graphics.drawable.ShapeDrawable(
                        new android.graphics.drawable.shapes.RectShape());
        line.getPaint().setColor(BORDER);
        line.setIntrinsicHeight(Math.max(1, dp(c, 1)));
        int inset = dp(c, 14);
        return new android.graphics.drawable.InsetDrawable(line, inset, 0, inset, 0);
    }

    /**
     * 列表行的背景：默认透明，按下/聚焦时淡淡一层。
     *
     * <p>文件列表这类行式条目用它，保持与卡片一致的圆角与配色。
     */
    public static StateListDrawable rowBg(Context c) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable press = new GradientDrawable();
        press.setColor(BTN_PRESS);
        press.setCornerRadius(dp(c, 8));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0x00000000);
        normal.setCornerRadius(dp(c, 8));
        d.addState(new int[]{ android.R.attr.state_pressed }, press);
        d.addState(new int[]{ android.R.attr.state_focused }, press);
        d.addState(new int[]{}, normal);
        return d;
    }

    /**
     * 轻提示。
     *
     * <p><b>必须显示在顶部。</b>系统 Toast 默认贴近底部，而对话框的操作按钮
     * 也在底部 —— 实测每次提示都会盖住「取消 / 保存」按钮，用户点不到。
     * 而对话框上方的留白区（约屏幕高 15%）正好空着，放那里互不遮挡。
     */
    /** 日志接收方：由宿主 Activity 注册，把各组件日志汇入统一日志文件。 */
    public interface LogSink {
        void log(String msg);
    }

    private static volatile LogSink logSink;

    /** 注册日志接收方（宿主启动时调用一次）。 */
    public static void setLogSink(LogSink sink) { logSink = sink; }

    /**
     * 记录一条日志。
     *
     * <p>各 UI 组件（文件浏览、编辑器、日志查看器）通过它把「布局自检」这类
     * 诊断信息汇入统一日志 —— 否则这些信息只能靠截图看，
     * 而截图恰恰看不出「是内容真的少了，还是被裁掉了」。
     */
    public static void log(String msg) {
        LogSink sink = logSink;
        if (sink != null && msg != null) {
            try { sink.log(msg); } catch (Throwable ignored) { }
        }
    }

    public static void toast(Context c, CharSequence msg) {
        if (c == null || msg == null) return;
        try {
            android.widget.Toast t = android.widget.Toast.makeText(
                    c, msg, android.widget.Toast.LENGTH_SHORT);
            int y = (int) (48 * c.getResources().getDisplayMetrics().density);
            t.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL, 0, y);
            t.show();
        } catch (Throwable ignored) { }
    }

    public static LinearLayout.LayoutParams fullWidth(Context c, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topMarginDp);
        return lp;
    }

    /** 纵向容器。 */
    public static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    // ---------------------------------------------------------------- 对话框
    /**
     * 构建一个 DSH 风格的对话框：透明窗口 + 自绘圆角卡片。
     *
     * <p>不用 AlertDialog —— 它的窗口背景、按钮栏、分隔线都是系统原生样式，
     * 无法与 DSH 视觉统一。这里完全自绘。
     *
     * @param body     卡片内的内容（调用方负责内边距）
     * @param footer   底部操作区（可为 null）
     * @param maxHeightDp 最大高度，超出则内部滚动
     */
    /**
     * 列表类对话框：窗口**占满**可用高度。
     *
     * <p>为什么需要单独一个方法：这类对话框的内容里有
     * {@code 0dp + weight=1} 的填充区（文件列表、日志列表）。
     * 「贴合内容」的高度取决于测量时序，实测会出现列表被压成 0 高度
     * （对话框只剩标题与按钮那样一条）。
     * 这里直接给窗口一个确定的高度，内部权重区自然拿到剩余空间 ——
     * 不依赖测量，行为可预测。
     */
    public static android.app.Dialog dialogFill(Context c, View body, View footer,
                                                int maxHeightDp) {
        return buildDialog(c, body, footer, maxHeightDp, true);
    }

    public static android.app.Dialog dialog(Context c, View body, View footer,
                                            int maxHeightDp) {
        return buildDialog(c, body, footer, maxHeightDp, false);
    }

    private static android.app.Dialog buildDialog(Context c, View body, View footer,
                                                  int maxHeightDp, boolean fillHeight) {
        android.app.Dialog d = new android.app.Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = column(c);
        card.setBackground(cardBg(c));
        // ⚠️ body 必须用 WRAP_CONTENT，**不能**用 0dp + weight=1：
        // 权重子视图在「未指定高度」下测量结果是 0，
        // 会把 body 内部的权重区域（文件列表、日志列表）整块压扁。
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (footer != null) card.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 键盘弹出时收缩对话框窗口，而不是把它顶出屏幕
        d.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int screenW = c.getResources().getDisplayMetrics().widthPixels;
            int screenH = c.getResources().getDisplayMetrics().heightPixels;
            // 宽度：两侧各留 24dp，上限放宽到 720dp。
            // 早先上限 520dp，横屏时（屏幕宽约 869dp）只能用到六成，很浪费；
            // 竖屏仍受屏幕限制（400dp 屏 → 352dp），几乎满宽。
            int width = Math.min(screenW - dp(c, 24), dp(c, 720));
            int maxH = Math.min(dp(c, maxHeightDp), (int) (screenH * 0.86f));

            int height = maxH;
            if (!fillHeight) {
                // 贴合内容：先按最终宽度测量，再取 min(内容高, 上限)。
                // 必须用 AT_MOST —— UNSPECIFIED 会让内部权重区域测成 0 高度。
                try {
                    card.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
                    int measured = card.getMeasuredHeight();
                    if (measured > 0) height = Math.min(measured, maxH);
                } catch (Throwable ignored) { }
            }
            // fillHeight 时 height 就是 maxH：给窗口一个确定高度，
            // 内部 0dp+weight 的列表区必然拿到剩余空间。
            w.setLayout(width, height);
        }
        return d;
    }

    /** 把内容包进可滚动区域。 */
    public static ScrollView scroll(Context c, View content) {
        ScrollView sc = new ScrollView(c);
        sc.setFillViewport(true);
        sc.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return sc;
    }

    /** 底部操作区：右对齐的按钮行。 */
    public static LinearLayout footer(Context c, Button... buttons) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        int pad = dp(c, 20);
        row.setPadding(pad, dp(c, 12), pad, pad);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 8);
            row.addView(b, lp);
        }
        return row;
    }

    /** 卡片内边距（正文区域）。 */
    public static LinearLayout paddedBody(Context c) {
        LinearLayout col = column(c);
        int pad = dp(c, 20);
        col.setPadding(pad, dp(c, 20), pad, dp(c, 8));
        return col;
    }
}
