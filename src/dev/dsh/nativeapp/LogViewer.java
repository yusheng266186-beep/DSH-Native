package dev.dsh.nativeapp;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
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
import java.util.Locale;

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
 *   <li><b>级别可视化</b> —— 日志用文字前缀区分级别，
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

    /**
     * 搜索去抖窗口（毫秒）。
     *
     * <p>每敲一个字符都全量过滤 + 重建最多 {@link #RENDER_CAP} 行 Spannable
     * （还要带颜色 span），中文输入法一次上屏还会连发几次回调 ——
     * 不设窗口的话，输入一个词就是十几次全量重建，输入框会明显发涩。
     */
    private static final long SEARCH_DEBOUNCE_MS = 160;

    /**
     * 「滚到最新」最多等多少帧。
     *
     * <p>见 {@link #scrollToLatest}：首帧布局之前内容高度是 0，滚动是空操作，
     * 需要等到布局完成。上限只是防止窗口根本没显示时无限重排。
     * 120Hz 下 20 帧约 166ms，足够覆盖对话框的首帧布局。
     */
    private static final int SCROLL_MAX_FRAMES = 20;

    private static final int LV_INFO = 0;
    private static final int LV_OK = 1;
    private static final int LV_WARN = 2;
    private static final int LV_ERROR = 3;

    private LogViewer() { }

    /** 一行日志及其级别。 */
    private static final class Line {
        final String text;
        final int level;
        /**
         * 预存的小写文本。
         *
         * <p>搜索原先在每次输入时对命中范围里的每一行现算 {@code toLowerCase()}，
         * 尾部 1MB 日志最多几万行 —— 一次输入就是几万次字符串分配。
         * 小写形式不会变，构造时算一次即可。
         *
         * <p>用 {@link Locale#ROOT} 而不是默认 Locale：土耳其语环境下
         * {@code "I".toLowerCase()} 得到的是 "ı"，同一个关键字在不同系统语言下
         * 会搜出不同结果。
         */
        final String lower;

        Line(String text, int level) {
            this.text = text;
            this.level = level;
            this.lower = text.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 判定一行日志的级别。
     *
     * <p>日志不再使用图形符号（显得廉价，且与 DSH 的克制风格不符），
     * 改为文字前缀：[错误] / [警告] / [通过] / [信息]。
     */
    private static int levelOf(String s) {
        if (s.indexOf("[错误]") >= 0) return LV_ERROR;
        if (s.indexOf("[警告]") >= 0) return LV_WARN;
        if (s.indexOf("[通过]") >= 0) return LV_OK;
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

        final Handler ui = new Handler(Looper.getMainLooper());
        /**
         * 面板是否已关闭。
         *
         * <p>后台读日志的线程可能在面板关掉之后才 post 回来，
         * 而 onDismiss 里的 {@code removeCallbacksAndMessages(null)}
         * **只能清掉当时已经入队的消息**，之后新 post 的 Runnable 照样会执行 ——
         * 于是它会去改一批已经没用的视图。所有 ui.post 回调都以这个旗标开头。
         */
        final boolean[] closed = { false };
        /** 日志数据尚未读完时为 false：此时界面显示占位文案而不是"没有匹配的行"。 */
        final boolean[] loaded = { false };
        /** 用户在读完之前就点了「清空」：结果回来也不要再回填。 */
        final boolean[] cleared = { false };

        // 结果集。读文件与解析都在后台线程完成，解析完再整体发布到这两个列表
        // （Handler.post 本身建立起可见性），UI 线程只负责渲染。
        final List<Line> all = new ArrayList<Line>();
        final List<Line> current = new ArrayList<Line>();

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
        view.setTextColor(DshUi.TEXT());
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setLineSpacing(DshUi.dp(act, 1.5f), 1f);
        int pad = DshUi.dp(act, 14);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(DshUi.fieldBg(act));

        final ScrollView scroll = new ScrollView(act);
        scroll.setBackground(DshUi.fieldBg(act));
        scroll.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 10);
        body.addView(scroll, slp);

        final Runnable render = new Runnable() {
            @Override public void run() {
                if (closed[0]) return;
                if (!loaded[0]) {
                    // 还没读完：明确说"在读"，不要显示成"没有匹配的行"
                    // （那会让人以为日志是空的）
                    view.setText("正在读取日志…");
                    meta.setText("正在读取日志…");
                    return;
                }
                List<Line> src = scopeCurrent[0] ? current : all;
                String q = query[0].trim().toLowerCase(Locale.ROOT);
                List<Line> kept = new ArrayList<Line>();
                for (Line l : src) {
                    if (levelFilter[0] == LV_WARN && l.level < LV_WARN) continue;
                    if (levelFilter[0] == LV_ERROR && l.level < LV_ERROR) continue;
                    // 用预存的小写文本比较，不再现场 toLowerCase
                    if (q.length() > 0 && l.lower.indexOf(q) < 0) continue;
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

        /**
         * 去抖后的搜索渲染。
         *
         * <p>切范围/级别是离散操作，必须**立即**生效，所以它们直接调 render；
         * 只有连续输入的搜索走这个延迟入口。
         */
        final Runnable searchRun = new Runnable() {
            @Override public void run() {
                render.run();
            }
        };

        // 分档按钮：整组重建，渲染只依赖选中索引（同设置页缩放档位的做法）
        final int[] scopeSel = { 0 };            // 0=本次启动 1=全部
        rebuildToggle(act, scopeRow, new String[]{ "本次启动", "全部" }, scopeSel,
                new Runnable() {
                    @Override public void run() {
                        scopeCurrent[0] = scopeSel[0] == 0;
                        // 丢掉挂起的搜索渲染：它会在同一份数据上重复重建一遍
                        ui.removeCallbacks(searchRun);
                        render.run();
                    }
                });

        final int[] levelSel = { 0 };            // 0=全部 1=警告 2=错误
        rebuildToggle(act, levelRow, new String[]{ "全部", "警告", "错误" }, levelSel,
                new Runnable() {
                    @Override public void run() {
                        levelFilter[0] = levelSel[0] == 0 ? LV_INFO
                                       : (levelSel[0] == 1 ? LV_WARN : LV_ERROR);
                        ui.removeCallbacks(searchRun);
                        render.run();
                    }
                });

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                query[0] = e == null ? "" : e.toString();
                // 去抖：窗口内的连续输入只渲染最后一次
                ui.removeCallbacks(searchRun);
                ui.postDelayed(searchRun, SEARCH_DEBOUNCE_MS);
            }
        });

        // 先给占位内容，让对话框立刻可显示 —— 读文件不占首帧
        view.setText("正在读取日志…");
        meta.setText("正在读取日志…");

        /**
         * 读 + 解析一起挪到后台。
         *
         * <p>原来这里连着调用两次 {@code parse(logFile, ...)}：**同一份 1MB 尾巴
         * 读了两遍**，每遍还要 {@code split("\n", -1)} 出几万行字符串、
         * 每行再 new 一个对象 —— 全在 UI 线程，且期间没有任何加载反馈。
         * 现在只读一次，由同一份文本切出"全部 / 本次启动"两个视图，重活全在后台。
         */
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                final List<Line> a = new ArrayList<Line>();
                final List<Line> c = new ArrayList<Line>();
                try {
                    String text = readAll(logFile);
                    a.addAll(parseText(text, false));
                    c.addAll(parseText(text, true));
                } catch (Throwable t) {
                    a.add(new Line("无法读取日志: " + t, LV_ERROR));
                    c.add(new Line("无法读取日志: " + t, LV_ERROR));
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        // 面板已关 / 已清空：结果直接丢掉，绝不再碰界面
                        if (closed[0] || cleared[0]) return;
                        all.addAll(a);
                        current.addAll(c);
                        loaded[0] = true;
                        render.run();
                        // 数据到位后再滚一次：onShow 那次可能滚在"正在读取"的短内容上
                        scrollToLatest(scroll, view, closed);
                        DshUi.log("日志已加载：全部 " + all.size() + " 行，本次启动 "
                                + current.size() + " 行");
                    }
                });
            }
        }, "dsh-log-read");
        // 守护线程：面板关掉后它最多跑完一次读取就结束，不该拖住进程
        reader.setDaemon(true);
        reader.start();

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
                // 标记已清空：后台可能还在读，回来时不要把这些行又填回界面
                cleared[0] = true;
                loaded[0] = true;
                all.clear();
                current.clear();
                render.run();
                DshUi.toast(act, "日志已清空");
            }
        });

        Button close = DshUi.button(act, "关闭", true);
        final android.app.Dialog dlg = DshUi.dialogFill(act, body,
                DshUi.footer(act, copy, clear, close), 820);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        /**
         * 自动滚到最新一行。
         *
         * <p>**必须挂在 onShow 之后**：{@code dlg.show()} 之前视图还没布局，
         * 那时 post 出去的滚动读到的高度是 0，{@code fullScroll} 等于什么都没做 ——
         * 这正是"打开日志停在最旧一行"的原因。放到 onShow 里也还不够稳
         * （首帧布局可能仍在下一个 vsync 才发生），所以
         * {@link #scrollToLatest} 会等到内容真的有高度再滚。
         */
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                scrollToLatest(scroll, view, closed);
            }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                // 先立旗标，再清队列：清队列只对"已经入队"的消息有效，
                // 后台线程此后新 post 的回调只能靠旗标挡住
                closed[0] = true;
                try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
            }
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
     * 解析日志文本为行列表。
     *
     * <p>接收已经读好的文本而不是文件：同一份文本要切出"全部 / 本次启动"
     * 两个视图，读两遍文件（原来就是这么做的）纯属浪费 ——
     * 尾部 1MB、几万行，多读一遍就是多一次 split 与几万个对象。
     *
     * @param onlyCurrent 只取最近一次启动的内容
     */
    private static List<Line> parseText(String text, boolean onlyCurrent) {
        List<Line> out = new ArrayList<Line>();
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

    /**
     * 滚到最新一行。
     *
     * <p>时序是这里唯一的坑：视图没完成布局时内容高度是 0，
     * {@code fullScroll} 就是把 0 滚到 0 —— 等于什么都没做，日志会停在最旧一行。
     * 而"布局完成"的时刻并不确定（对话框在下一个 vsync 才首次布局，
     * 120Hz 上这个窗口尤其容易撞上），所以这里不假设一次就能滚成：
     * 内容还没高度就下一帧再试，最多试 {@link #SCROLL_MAX_FRAMES} 帧。
     *
     * @param closed 面板关闭旗标：关闭后立即停手，别对着已销毁的视图重排
     */
    private static void scrollToLatest(final ScrollView scroll, final View content,
                                       final boolean[] closed) {
        final int[] frames = { 0 };
        final Runnable step = new Runnable() {
            @Override public void run() {
                if (closed[0]) return;
                if (content.getHeight() > 0 && scroll.getHeight() > 0) {
                    scroll.fullScroll(View.FOCUS_DOWN);
                    return;
                }
                if (++frames[0] >= SCROLL_MAX_FRAMES) return;   // 窗口没在显示，别无限重排
                scroll.postOnAnimation(this);
            }
        };
        scroll.postOnAnimation(step);
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
