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
        b.setPadding(dp(c, 16), dp(c, 11), dp(c, 16), dp(c, 11));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        try {
            b.setStateListAnimator(null);      // 去掉 Material 的抬升动画
        } catch (Throwable ignored) { }
        return b;
    }

    /** 撑满宽度的按钮布局参数。 */
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
    public static android.app.Dialog dialog(Context c, View body, View footer,
                                            int maxHeightDp) {
        android.app.Dialog d = new android.app.Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = column(c);
        card.setBackground(cardBg(c));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (footer != null) card.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            // 宽度：屏幕宽度减去两侧留白；高度自适应但不超过上限
            int screenW = c.getResources().getDisplayMetrics().widthPixels;
            int screenH = c.getResources().getDisplayMetrics().heightPixels;
            int width = Math.min(screenW - dp(c, 32), dp(c, 520));
            int maxH = Math.min(dp(c, maxHeightDp), (int) (screenH * 0.86f));
            w.setLayout(width, maxH > 0 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
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
