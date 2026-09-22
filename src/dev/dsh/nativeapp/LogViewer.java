package dev.dsh.nativeapp;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行日志查看器。
 *
 * <p>设计要点（对应旧实现的三个问题）：
 * <ul>
 *   <li><b>默认只看本次启动</b> —— 日志是追加写的，累计了二十多次启动。
 *       旧实现把最后 400 行一股脑倒出来，用户根本分不清哪条是当前的。
 *       这里按启动分隔行切分，默认只显示最近一次。</li>
 *   <li><b>能筛能搜</b> —— 按级别（警告 / 错误）与关键字过滤，
 *       排查时不必在几百行里用眼睛找。</li>
 *   <li><b>级别可视化</b> —— 日志用 ✅ / ⚠️ / ✗ 表示状态，
 *       提取出来作为颜色标记，扫一眼就能定位问题行。</li>
 * </ul>
 *
 * <p>不含时间戳（日志本身就没有），因此顺序即时间顺序。
 */
public final class LogViewer {

    /** 启动分隔行，用于切分不同次启动。 */
    private static final String LAUNCH_MARK = "=== DSH Native 启动日志 ===";

    /** 单次渲染的最大行数，避免超长日志拖慢界面。 */
    private static final int RENDER_CAP = 1500;

    private static final int LV_INFO = 0;
    private static final int LV_OK = 1;
    private static final int LV_WARN = 2;
    private static final int LV_ERROR = 3;

    private LogViewer() { }

    /** 一行日志及其级别。 */
    private static final class Line {
        final String text;
        final int level;
        Line(String text, int level) { this.text = text; this.level = level; }
    }

    private static int levelOf(String s) {
        if (s.indexOf('✗') >= 0 || s.indexOf('❌') >= 0) return LV_ERROR;
        if (s.indexOf('⚠') >= 0) return LV_WARN;
        if (s.indexOf('✅') >= 0) return LV_OK;
        return LV_INFO;
    }

    /**
     * 打开日志查看器。
     *
     * @param act     宿主 Activity
     * @param logFile 日志文件
     * @param onClear 清空回调（可为 null）
     */
    public static void show(final Activity act, final File logFile, final Runnable onClear) {
        if (logFile == null || !logFile.exists()) {
            DshUi.toast(act, "暂无日志文件");
            return;
        }
        final List<Line> all = parse(logFile, false);
        final List<Line> current = parse(logFile, true);

        final boolean[] scopeCurrent = { true };   // 默认只看本次启动
        final int[] levelFilter = { LV_INFO };     // LV_INFO = 不过滤
        final String[] query = { "" };

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "运行日志"));

        final TextView meta = DshUi.hint(act, "");
        body.addView(meta, DshUi.fullWidth(act, 4));

        // ── 范围：本次 / 全部 ──
        final LinearLayout scopeRow = new LinearLayout(act);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(scopeRow, DshUi.fullWidth(act, 12));

        // ── 级别：全部 / 警告 / 错误 ──
        final LinearLayout levelRow = new LinearLayout(act);
        levelRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(levelRow, DshUi.fullWidth(act, 8));

        // ── 搜索 ──
        final EditText search = DshUi.input(act, "", false);
        search.setHint("搜索关键字…");
        search.setSingleLine(true);
        body.addView(search, DshUi.fullWidth(act, 10));

        // ── 内容 ──
        final TextView view = new TextView(act);
        view.setTextSize(10.5f);
        view.setTextColor(DshUi.TEXT);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setLineSpacing(DshUi.dp(act, 1.5f), 1f);
        int pad = DshUi.dp(act, 14);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(DshUi.fieldBg(act));

        ScrollView scroll = new ScrollView(act);
        scroll.setBackground(DshUi.fieldBg(act));
        scroll.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 10);
        body.addView(scroll, slp);

        final Runnable render = new Runnable() {
            @Override public void run() {
                List<Line> src = scopeCurrent[0] ? current : all;
                String q = query[0].trim().toLowerCase();
                List<Line> kept = new ArrayList<Line>();
                for (Line l : src) {
                    if (levelFilter[0] == LV_WARN && l.level < LV_WARN) continue;
                    if (levelFilter[0] == LV_ERROR && l.level < LV_ERROR) continue;
                    if (q.length() > 0 && l.text.toLowerCase().indexOf(q) < 0) continue;
                    kept.add(l);
                }

                int shown = Math.min(kept.size(), RENDER_CAP);
                int from = kept.size() - shown;   // 超长时保留最新的

                SpannableStringBuilder sb = new SpannableStringBuilder();
                for (int i = from; i < kept.size(); i++) {
                    Line l = kept.get(i);
                    int start = sb.length();
                    sb.append(l.text).append('\n');
                    int end = sb.length();
                    if (l.level == LV_ERROR) {
                        sb.setSpan(new ForegroundColorSpan(0xFFD93025), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new BackgroundColorSpan(0x14D93025), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (l.level == LV_WARN) {
                        sb.setSpan(new ForegroundColorSpan(0xFFB26A00), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else if (l.level == LV_OK) {
                        sb.setSpan(new ForegroundColorSpan(0xFF1A7F37), start, end,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                if (sb.length() == 0) {
                    sb.append("（没有匹配的日志行）");
                }
                view.setText(sb);

                meta.setText((scopeCurrent[0] ? "本次启动" : "全部启动")
                        + " · 共 " + src.size() + " 行"
                        + " · 显示 " + kept.size() + " 行"
                        + (kept.size() > shown ? "（仅渲染最后 " + shown + " 行）" : ""));
            }
        };

        // 分档按钮：整组重建，渲染只依赖选中索引（同设置页缩放档位的做法）
        final int[] scopeSel = { 0 };            // 0=本次启动 1=全部
        rebuildToggle(act, scopeRow, new String[]{ "本次启动", "全部" }, scopeSel,
                new Runnable() {
                    @Override public void run() {
                        scopeCurrent[0] = scopeSel[0] == 0;
                        render.run();
                    }
                });

        final int[] levelSel = { 0 };            // 0=全部 1=警告 2=错误
        rebuildToggle(act, levelRow, new String[]{ "全部", "警告", "错误" }, levelSel,
                new Runnable() {
                    @Override public void run() {
                        levelFilter[0] = levelSel[0] == 0 ? LV_INFO
                                       : (levelSel[0] == 1 ? LV_WARN : LV_ERROR);
                        render.run();
                    }
                });

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                query[0] = e == null ? "" : e.toString();
                render.run();
            }
        });

        render.run();
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });

        Button copy = DshUi.button(act, "复制", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CharSequence cs = view.getText();
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                act.getSystemService(Activity.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("DSH 日志", cs));
                    DshUi.toast(act, "已复制 " + cs.length() + " 字");
                }
            }
        });

        Button clear = DshUi.button(act, "清空", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (onClear != null) onClear.run();
                all.clear();
                current.clear();
                render.run();
                DshUi.toast(act, "日志已清空");
            }
        });

        Button close = DshUi.button(act, "关闭", true);
        final android.app.Dialog dlg = DshUi.dialog(act, body,
                DshUi.footer(act, copy, clear, close), 720);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();
    }

    private static void rebuildToggle(final Activity act, final LinearLayout row,
                                      final String[] labels, final int[] selected,
                                      final Runnable onPick) {
        row.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == selected[0];
            Button b = DshUi.toggleButton(act, labels[i], on);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected[0] = idx;
                    rebuildToggle(act, row, labels, selected, onPick);
                    onPick.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = DshUi.dp(act, 6);
            row.addView(b, lp);
        }
    }

    /**
     * 解析日志为行列表。
     *
     * @param onlyCurrent 只取最近一次启动的内容
     */
    private static List<Line> parse(File f, boolean onlyCurrent) {
        List<Line> out = new ArrayList<Line>();
        String text;
        try {
            text = readAll(f);
        } catch (Throwable t) {
            out.add(new Line("无法读取日志: " + t, LV_ERROR));
            return out;
        }
        String[] lines = text.split("\n", -1);
        int start = 0;
        if (onlyCurrent) {
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].indexOf(LAUNCH_MARK) >= 0) { start = i; break; }
            }
        }
        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            if (onlyCurrent && i == start) continue;   // 跳过分隔行本身
            if (l.length() == 0 && out.isEmpty()) continue;
            out.add(new Line(l, levelOf(l)));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).text.length() == 0) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static String readAll(File f) throws Exception {
        // 只读尾部 1MB：日志可能很大，全量读入没有必要
        long len = f.length();
        long max = 1024 * 1024;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            if (len > max) raf.seek(len - max);
            byte[] buf = new byte[(int) Math.min(len, max)];
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } finally {
            raf.close();
        }
    }
}
