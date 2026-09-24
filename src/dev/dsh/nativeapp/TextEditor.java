package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 简易文本编辑器。
 *
 * <p>用于在应用内查看与修改配置文件、脚本、日志等纯文本，
 * 不必把文件导出到共享存储再用别的编辑器中转。
 *
 * <p>几个必须处理的边界（都来自真实的 Android/中文环境）：
 * <ul>
 *   <li><b>二进制文件</b>：内容含 NUL 字节则拒绝编辑，只提示类型与大小 ——
 *       否则一个误保存就能毁掉可执行文件</li>
 *   <li><b>大文件</b>：超过 {@link #MAX_EDIT_BYTES} 只读展示前若干行，
 *       避免一次性载入几十 MB 把界面拖死；读取与解码都在后台线程完成</li>
 *   <li><b>编码</b>：先按 UTF-8 严格解码；失败则退回 GB18030 ——
 *       中文环境里大量文本文件仍是 GBK 系，按 UTF-8 硬读会整篇乱码</li>
 *   <li><b>未保存保护</b>：关闭按钮、返回键都不会静默丢弃改动；
 *       点对话框外部也不再关掉编辑器</li>
 *   <li><b>原子保存</b>：临时文件 + fsync + 改名；原文件的权限位、以及
 *       「目标是符号链接」这件事都不会被悄悄改掉</li>
 * </ul>
 */
public final class TextEditor {

    /** 超过此大小不允许编辑（可只读预览）。 */
    static final long MAX_EDIT_BYTES = 2L * 1024 * 1024;

    /** 只读预览时最多展示的字节数。 */
    private static final int PREVIEW_BYTES = 256 * 1024;

    private TextEditor() { }

    /** 打开一个文件：能编辑就编辑，否则给只读预览或明确说明。 */
    public static void open(final Activity act, final File f) {
        open(act, f, null);
    }

    /**
     * 打开文件。
     *
     * @param onSaved 保存成功后的回调（可为 null）。
     *                文件浏览器用它刷新列表 —— 否则编辑保存后，
     *                列表里的大小与修改时间仍是旧的，看起来像没保存成功。
     */
    public static void open(final Activity act, final File f, final Runnable onSaved) {
        if (f == null || !f.isFile()) {
            DshUi.toast(act, "不是常规文件");
            return;
        }

        // 读取与解码全部放后台线程。
        //
        // open() 是从文件列表的行点击回调里同步调用的，原来这里把最多 2MB
        // 读进 byte[]、再 decode、再 setText 建 Layout，全在 UI 线程 ——
        // 大文件点一下要卡住肉眼可见的一段时间，极端情况下 OOM。
        // 现在 UI 线程只负责「弹提示」和「拿到结果后建编辑器」。
        final Dialog busy = showOpening(act, f);
        final boolean[] loaded = { false };
        final boolean[] userDismissed = { false };
        busy.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                // 存储很慢时用户有权退出这次打开（返回键）。
                // 读完之后我们自己关的不算 —— 否则读完还会再弹一个编辑器出来。
                if (!loaded[0]) userDismissed[0] = true;
            }
        });

        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] head = null;
                TextCodec.Decoded dec = null;
                boolean truncated = false;
                Throwable err = null;
                try {
                    long len = f.length();
                    truncated = len > MAX_EDIT_BYTES;
                    head = readHead(f, truncated ? PREVIEW_BYTES : (int) Math.min(len, MAX_EDIT_BYTES));
                    // 二进制判定与解码交给 TextCodec（纯逻辑、有测试覆盖）：
                    // 二进制、BOM、UTF-8/GB18030、换行风格都在那里处理。
                    // 解码（尤其是 GB18030 回退）同样可能吃掉几十毫秒，一起放后台。
                    if (!TextCodec.looksBinary(head)) dec = TextCodec.decode(head);
                } catch (Throwable t) {
                    err = t;
                }
                final TextCodec.Decoded decoded = dec;
                final boolean trunc = truncated;
                final Throwable fail = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        loaded[0] = true;
                        busy.dismiss();
                        if (userDismissed[0]) return;
                        // 结果是在任意延迟之后回来的：这期间用户可能已经退出界面。
                        // 在已销毁的 Activity 上 show 对话框会抛 BadTokenException，
                        // 而这是一次纯粹的「读完文件」，不该让应用崩掉。
                        if (act.isFinishing() || act.isDestroyed()) return;
                        if (fail != null) {
                            DshUi.toast(act, "读取失败: " + fail.getMessage());
                            return;
                        }
                        if (decoded == null) {
                            showInfo(act, f, "二进制文件", "无法以文本方式编辑。");
                            return;
                        }
                        showEditor(act, f, decoded.text, decoded, trunc, trunc, onSaved);
                    }
                });
            }
        }, "dsh-text-open").start();
    }

    /**
     * 「正在打开…」提示。
     *
     * <p>大文件的读取 + 解码要一小段时间，没有反馈的话用户会以为点击没生效、
     * 接着连点，于是同一个文件被读好几遍。转圈用 {@link ProgressBar}。
     */
    private static Dialog showOpening(Activity act, File f) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "正在打开…"));
        body.addView(DshUi.hint(act, f.getName() + "\n" + FileListing.humanSize(f.length())),
                DshUi.fullWidth(act, 8));

        ProgressBar bar = new ProgressBar(act);
        bar.setIndeterminate(true);
        try {
            // 系统默认的转圈用的是主题强调色（Material 品红），放在这套浅色卡片里
            // 是整屏唯一的异色元素。ProgressBar 没有公开的换色接口，
            // 只能给不确定进度的那张 Drawable 上色。
            if (bar.getIndeterminateDrawable() != null) {
                bar.getIndeterminateDrawable().setColorFilter(DshUi.ACCENT, PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) { }
        body.addView(bar, DshUi.fullWidth(act, 14));

        Dialog d = DshUi.dialog(act, body, null, 200);
        // 点外部不关：手滑碰一下卡片外面不该把正在打开的编辑器悄悄取消掉。
        // 要退出请按返回键（那是明确动作），见上面的 OnDismissListener。
        d.setCanceledOnTouchOutside(false);
        d.show();
        return d;
    }

    private static void showInfo(Activity act, File f, String title, String detail) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, title));
        body.addView(DshUi.hint(act, f.getName() + "\n" + FileListing.humanSize(f.length())
                + "\n\n" + detail), DshUi.fullWidth(act, 8));
        Button close = DshUi.button(act, "关闭", true);
        final Dialog dlg = DshUi.dialog(act, body, DshUi.footer(act, close), 400);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();
    }

    private static void showEditor(final Activity act, final File f, final String initial,
                                   final TextCodec.Decoded meta, final boolean readOnly,
                                   final boolean truncated, final Runnable onSaved) {
        // 记录打开时的文件状态：保存前比对，防止覆盖别的程序（或 agent）
        // 在此期间写入的内容。
        final long openMtime = f.lastModified();
        final long openLength = f.length();
        final int totalLines = TextCodec.countLines(initial);

        // 行首索引只建一次，之后每次光标移动只是二分查找（见 LineIndex）。
        final LineIndex index = new LineIndex(initial);

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, f.getName()));

        // head 描述的是**磁盘上这个文件**（大小、行数、编码），编辑过程中不变，
        // 所以缓存成 final String：原来光标每动一次都要把这一长串重新拼一遍，
        // 现在只拼一个行号。
        final String head = f.getAbsolutePath() + "\n"
                + FileListing.humanSize(f.length()) + " · " + totalLines + " 行"
                + " · " + meta.describe()
                + (truncated ? " · 文件过大，仅预览前 " + FileListing.humanSize(PREVIEW_BYTES)
                             + "（只读，可长按选中复制）" : "");
        final TextView infoView = DshUi.hint(act, head + "　·　第 1 行");
        body.addView(infoView, DshUi.fullWidth(act, 4));

        // 当前显示的行号（用数组是因为它要在几个匿名类之间共享并修改）。
        final int[] shownLine = { 1 };

        // 用匿名子类是为了拿到 onSelectionChanged —— 光标移动不触发 TextWatcher，
        // 只有覆写这个方法才能实时更新「第 N 行」。
        final EditText ed = new EditText(act) {
            @Override protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                showLineNumber(infoView, head, shownLine, index, getText(), selStart);
            }
        };
        ed.setText(initial);
        ed.setTextSize(12f);
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setTextColor(DshUi.TEXT);
        ed.setBackground(DshUi.fieldBg(act));
        ed.setGravity(Gravity.TOP | Gravity.START);
        ed.setHorizontallyScrolling(false);      // 自动换行，手机上更易读
        if (readOnly) {
            // 只读预览原来用 setEnabled(false)：文本既选不中也复制不出 ——
            // 而「看得见却拿不走」恰恰是大日志最需要复制的场景（拿去搜索、贴给别人看）。
            // 改成「可选中复制 + 摘掉按键监听」：滚动、长按选择、复制都还在，
            // 只是不再接受键盘输入（KeyListener 才是可编辑性的开关）。
            ed.setTextIsSelectable(true);
            ed.setKeyListener(null);
        } else {
            ed.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        int pad = DshUi.dp(act, 12);
        ed.setPadding(pad, pad, pad, pad);

        // 监听器最后挂：上面的 setText / setTextIsSelectable 自己也会发一次文本变更通知，
        // 挂早了就会拿这些初始化事件去动索引（结果一样，但没必要，也容易看错）。
        ed.addTextChangedListener(new TextWatcher() {
            /** 被替换掉的那段文本里是否含换行（beforeTextChanged 里才看得到）。 */
            private boolean removedNewline;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                removedNewline = containsNewline(s, start, count);
            }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                index.onReplace(s, start, before, count, removedNewline);
            }

            @Override public void afterTextChanged(Editable s) {
                // 索引更新完再刷一次行号。删掉一个换行会把两行并成一行，
                // 而「先收到文本变更还是先收到光标变更」并不是文档化的契约，
                // 刷一次的成本只是一次二分查找，换来的是行号不会停在旧值上。
                showLineNumber(infoView, head, shownLine, index, s, Selection.getSelectionStart(s));
            }
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(ed, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 8);
        body.addView(scroll, slp);

        Button close = DshUi.button(act, readOnly ? "关闭" : "取消", false);
        Button save = DshUi.button(act, "保存", true);
        save.setEnabled(!readOnly);

        // 编辑区需要空间，同样占满高度
        final Dialog dlg = readOnly
                ? DshUi.dialogFill(act, body, DshUi.footer(act, close), 820)
                : DshUi.dialogFill(act, body, DshUi.footer(act, close, save), 820);

        // 保存是否正在进行。它是跨线程状态：按钮已禁用，但返回键、取消按钮
        // 仍然能被点到，两个地方都要看它。
        final boolean[] saving = { false };
        /** 软链提示在本次编辑里只弹一次，用户确认过就不再重复问。 */
        final boolean[] linkConfirmed = { false };

        // 点击保存之后的公共流程：软链提示 -> 外部改动确认 -> 真正落盘
        final Runnable startSave = new Runnable() {
            @Override public void run() {
                // 打开后文件被别的程序改过 -> 先确认，别直接覆盖别人的写入
                if (f.lastModified() != openMtime || f.length() != openLength) {
                    confirmOverwrite(act, f, ed, meta, dlg, onSaved, save, saving);
                    return;
                }
                doSave(act, f, ed, meta, dlg, onSaved, save, saving);
            }
        };

        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 写入还在跑：关掉对话框只会让「到底存上没有」无处反馈
                if (saving[0]) {
                    DshUi.toast(act, "正在保存，请稍候");
                    return;
                }
                if (!readOnly && isDirty(ed, initial)) {
                    confirmLeaveUnsaved(act, dlg, startSave);
                } else {
                    dlg.dismiss();
                }
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!linkConfirmed[0] && isSymlink(f)) {
                    linkConfirmed[0] = true;
                    confirmLinkReplace(act, f, startSave);
                    return;
                }
                startSave.run();
            }
        });

        // 未保存保护的真正缺口在这里。
        //
        // 原来脏检查只挂在「取消」按钮上，而对话框默认 cancelable=true：
        // 返回键走 Dialog.onBackPressed -> cancel()，点卡片外部走
        // setCanceledOnTouchOutside，两条路都不经过任何确认 ——
        // 改了配置按一下返回键，改动就无声没了。
        // docs/FILE-BROWSER-RESEARCH.md 早就写明这里要「onBackPressed 弹三选一」，
        // 处理方式照 FileBrowser 的返回键写法（KEYCODE_BACK + ACTION_UP）。
        dlg.setCanceledOnTouchOutside(false);
        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int code, KeyEvent e) {
                if (code != KeyEvent.KEYCODE_BACK) return false;
                // 只认抬手：按下和抬手都会进这个方法，两处都处理会让一次按键弹两遍确认框
                if (e.getAction() != KeyEvent.ACTION_UP) return false;
                if (saving[0]) {
                    DshUi.toast(act, "正在保存，请稍候");
                    return true;
                }
                if (!readOnly && isDirty(ed, initial)) {
                    confirmLeaveUnsaved(act, dlg, startSave);
                } else {
                    dlg.dismiss();
                }
                return true;    // 已处理，别再让系统自己 cancel 掉对话框
            }
        });

        dlg.show();
    }

    /** 内容是否与打开时不同（只在返回/关闭时比一次，直接比内容最省事也最准）。 */
    private static boolean isDirty(EditText ed, String initial) {
        return !ed.getText().toString().equals(initial);
    }

    /** 刷新「第 N 行」。行号没变就不碰 TextView —— 同一行内移动光标不必重新排版。 */
    private static void showLineNumber(TextView infoView, String head, int[] shownLine,
                                       LineIndex index, CharSequence text, int offset) {
        if (offset < 0 || text == null) return;
        int line = index.lineOf(text, offset);
        if (line == shownLine[0]) return;
        shownLine[0] = line;
        infoView.setText(head + "　·　第 " + line + " 行");
    }

    /** 区间内是否含换行符（判断一次编辑有没有改变行的划分）。 */
    private static boolean containsNewline(CharSequence s, int start, int count) {
        if (s == null) return false;
        int from = Math.max(0, start);
        int to = Math.min(s.length(), start + count);
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') return true;
        }
        return false;
    }

    /**
     * 行首索引：把「第 N 行」的定位从 O(文本长度) 降到 O(log 行数)。
     *
     * <p>原来每次光标移动都从 0 扫到光标处数换行符，2MB 文本 = 每次按键扫上百万字符，
     * 输入直接掉帧。这里在打开时扫一遍建索引（2MB 文本约 200KB 量级），
     * 之后只做二分查找 —— 与文本长度无关。
     *
     * <p>索引必须在编辑时跟着走，否则行号会在插入/删除之后整体错位：
     * 显示一个错的「第 N 行」比显示得慢更糟，用户会照着它去改别的地方。
     *
     * <p>语义是**逻辑行**（按 {@code '\n'} 切）。不能用
     * {@code getLayout().getLineForOffset()}：自动换行下它返回的是**视觉行**，
     * 与界面上的「第 N 行」对不上。
     */
    private static final class LineIndex {

        /** 行数超过这个量级就不再建索引：索引本身要占几 MB，得不偿失。 */
        private static final int MAX_INDEXED_LINES = 200000;

        /** 行首偏移表；为 null 表示行数过多，退回线性统计（与旧实现相同）。 */
        private int[] starts;

        LineIndex(CharSequence text) {
            starts = scan(text);
        }

        /** 偏移落在第几行（1 起）。退化路径才需要 text 本身。 */
        int lineOf(CharSequence text, int offset) {
            int off = Math.max(0, offset);
            int[] a = starts;
            if (a == null) {
                int line = 1;
                int end = Math.min(off, text.length());
                for (int i = 0; i < end; i++) {
                    if (text.charAt(i) == '\n') line++;
                }
                return line;
            }
            int hit = Arrays.binarySearch(a, off);
            // 命中说明 off 正好是某一行行首（第 hit+1 行）；
            // 未命中时 binarySearch 返回 -(插入点)-1，而插入点 = 行首小于 off 的个数，
            // 也就是「off 所在行」的 0 基下标，所以行号是 -hit-1。
            return hit >= 0 ? hit + 1 : -hit - 1;
        }

        /** 文本区间 [start, start+before) 被换成了 [start, start+count)。 */
        void onReplace(CharSequence newText, int start, int before, int count,
                       boolean removedNewline) {
            if (starts == null) return;
            if (removedNewline || containsNewline(newText, start, count)) {
                // 增删了换行符：行的划分变了，重扫一遍。
                // 这类编辑（回车、粘贴多行、删整行）远比逐字输入少见。
                starts = scan(newText);
                return;
            }
            // 只是行内增删字符：行数没变，编辑点之后的行首整体平移。
            // 逐字输入走的就是这条路 —— 只移动数组元素，不再扫文本。
            int delta = count - before;
            if (delta == 0) return;
            int hit = Arrays.binarySearch(starts, start);
            // 恰好等于 start 的那个行首不动：在行首插入的字符属于这一行
            int i = hit >= 0 ? hit + 1 : -hit - 1;
            for (; i < starts.length; i++) {
                starts[i] += delta;
            }
        }

        private static int[] scan(CharSequence text) {
            int n = text.length();
            int lines = 1;
            for (int i = 0; i < n; i++) {
                if (text.charAt(i) == '\n') lines++;
            }
            if (lines > MAX_INDEXED_LINES) return null;
            int[] out = new int[lines];
            out[0] = 0;
            int k = 1;
            for (int i = 0; i < n; i++) {
                if (text.charAt(i) == '\n') out[k++] = i + 1;
            }
            return out;
        }
    }

    /** 覆盖确认：文件在编辑期间被外部改动过。 */
    private static void confirmOverwrite(final Activity act, final File f, final EditText ed,
                                         final TextCodec.Decoded meta, final Dialog parent,
                                         final Runnable onSaved, final Button saveBtn,
                                         final boolean[] saving) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "文件已被外部修改"));
        body.addView(DshUi.hint(act,
                f.getName() + " 在你编辑期间被其他程序改写过。\n\n"
              + "继续保存会覆盖对方的改动；取消则保留磁盘上的版本。"),
                DshUi.fullWidth(act, 8));
        Button cancel = DshUi.button(act, "取消", false);
        Button overwrite = DshUi.button(act, "覆盖保存", true);
        final Dialog ask = DshUi.dialog(act, body, DshUi.footer(act, cancel, overwrite), 360);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ask.dismiss(); }
        });
        overwrite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ask.dismiss();
                doSave(act, f, ed, meta, parent, onSaved, saveBtn, saving);
            }
        });
        ask.show();
    }

    /**
     * 符号链接确认。
     *
     * <p>保存走的是「写临时文件再改名替换」，而改名替换掉的是**链接本身**：
     * 运行包里 tools/bin/* 大量是软链，静默把链接换成普通文件会破坏这套结构
     * （别的链接仍指向原目标，从此外观一样、内容分叉）。
     * 这里至少把后果讲清楚，让用户自己决定；要保留链接就去编辑它指向的目标文件。
     */
    private static void confirmLinkReplace(final Activity act, final File f,
                                           final Runnable proceed) {
        String target = "?";
        try {
            target = android.system.Os.readlink(f.getAbsolutePath());
        } catch (Throwable ignored) { }
        DshUi.confirm(act, "这是符号链接",
                f.getName() + " 是指向\n" + target + "\n的符号链接。\n\n"
              + "保存会用普通文件替换这个链接本身：链接消失，其他指向同一目标的链接"
              + "也不会再跟着这次修改走。\n\n"
              + "想保留链接，请改为编辑链接指向的那个文件。",
                "仍要保存", proceed);
    }

    /**
     * 实际写入：编码 + 写盘 + fsync 全在后台线程。
     *
     * <p>慢闪存上一次 fsync 可能几百毫秒到秒级，原来从点击一路同步走完，
     * 全程主线程冻结、按钮外观毫无变化 —— 用户以为没点上，连点就会再存一遍。
     * 现在点下去立刻切成「保存中」并禁用（DshUi 的按钮禁用态现在看得见），
     * 回到 UI 线程后再提示、关闭、恢复按钮。
     */
    private static void doSave(final Activity act, final File f, final EditText ed,
                               final TextCodec.Decoded meta, final Dialog dlg,
                               final Runnable onSaved, final Button saveBtn,
                               final boolean[] saving) {
        if (saving[0]) return;
        saving[0] = true;
        DshUi.setBusy(saveBtn, "保存", "保存中", true);
        // 文本只能在主线程取：EditText 是 UI 对象，后台线程读它不安全。
        // 编码（2MB 文本的 GB18030 转换同样不便宜）和写盘一起丢给后台。
        final String text = ed.getText().toString();
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                Throwable err = null;
                long size = 0;
                try {
                    size = writeFileAtomically(f, TextCodec.encode(text, meta));
                } catch (Throwable t) {
                    err = t;
                }
                final Throwable fail = err;
                final long savedSize = size;
                ui.post(new Runnable() {
                    @Override public void run() {
                        saving[0] = false;
                        DshUi.setBusy(saveBtn, "保存", "保存中", false);
                        if (fail != null) {
                            // 失败时对话框保持打开：改动还在编辑器里，可以直接重试
                            DshUi.toast(act, "保存失败: " + fail.getMessage());
                            return;
                        }
                        DshUi.toast(act, "已保存 " + FileListing.humanSize(savedSize));
                        dlg.dismiss();
                        // 通知调用方刷新 —— 列表里的大小/时间依赖它
                        if (onSaved != null) {
                            try { onSaved.run(); } catch (Throwable ignored) { }
                        }
                    }
                });
            }
        }, "dsh-text-save").start();
    }

    /**
     * 原子写入：临时文件 -> fsync -> 改名替换，并保住原文件的权限位。
     *
     * <p>为什么不直接写目标：{@code new FileOutputStream(dst)} 会**先把目标截断**，
     * 拷贝到一半失败就同时失去原文件和新内容。改名是同一目录内的原子操作，
     * 任何一步失败，原文件都还是完整的。
     *
     * <p>为什么要在写 tmp 之前记下原文件的 mode：临时文件是按默认权限（0600 量级）
     * 新建的，改名覆盖之后原权限位就丢了 —— 编辑 tools/bin 下带 +x 的脚本
     * 会让它静默失去可执行位，脚本从此跑不起来。
     *
     * @return 写入后的文件长度
     */
    private static long writeFileAtomically(File f, byte[] bytes) throws Exception {
        File dir = f.getParentFile();
        if (dir == null) throw new IOException("无法确定所在目录");
        File tmp = new File(dir, f.getName() + ".dsh-tmp");
        File bak = new File(dir, f.getName() + ".dsh-bak");
        int mode = permissionBitsOf(f);
        try {
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(bytes);
                os.flush();
                os.getFD().sync();   // 先落盘再改名，避免断电留下半截文件
            } finally {
                os.close();
            }
            if (mode >= 0) {
                try {
                    android.system.Os.chmod(tmp.getAbsolutePath(), mode);
                } catch (Throwable ignored) {
                    // 权限位复原失败不该让保存失败：内容已经写对了
                }
            }
            if (tmp.renameTo(f)) return f.length();

            // 改名失败（极少数文件系统不支持覆盖式改名）：退回**两次改名**，
            // 而不是「直接覆盖目标」的拷贝 —— 后者会先把原文件截断，
            // 拷到一半失败就既没有原文件也没有新文件。
            // 第一次失败时把备份改回去，宁可保存失败也不能让文件消失。
            if (bak.exists() && !bak.delete()) throw new IOException("无法清理旧的备份文件");
            if (!f.renameTo(bak)) throw new IOException("无法备份原文件，未做任何修改");
            if (tmp.renameTo(f)) {
                bak.delete();
                return f.length();
            }
            if (!bak.renameTo(f)) {
                throw new IOException("原文件已备份为 " + bak.getName() + "，改名替换失败");
            }
            throw new IOException("改名替换失败");
        } finally {
            // 失败路径不能把 xxx.dsh-tmp 永久留在目录里：
            // 它既占空间，又会让用户以为目录里多了个奇怪的文件。
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * 原文件的权限位（含 setuid/setgid/sticky）；取不到返回 -1。
     *
     * <p>走 {@code android.system.Os}：java.io.File 没有暴露 mode 的接口，
     * 而这里的 Os.stat 与 lstat 是同一套系统调用（前者跟随软链，正是我们要的
     * 「链接指向的那个文件的权限」）。
     */
    private static int permissionBitsOf(File f) {
        try {
            return android.system.Os.stat(f.getAbsolutePath()).st_mode & 07777;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 目标路径本身是不是符号链接。
     *
     * <p>必须用 lstat：java.io.File 的所有判断都会跟随链接，看不出
     * 「这个路径本身是个链接」，而 rename 替换的恰恰是链接本身。
     */
    private static boolean isSymlink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(
                    android.system.Os.lstat(f.getAbsolutePath()).st_mode);
        } catch (Throwable t) {
            return false;   // 取不到就按普通文件处理，不因此阻断保存
        }
    }

    /**
     * 有未保存改动却要离开时的三选一：继续编辑 / 丢弃 / 保存。
     *
     * <p>为什么是三个而不是两个：这里是**唯一**能挡住「改了配置按返回键、
     * 改动无声消失」的地方（返回键、点卡片外部、取消按钮都汇到这里）。
     * 只给「继续编辑 / 丢弃」的话，想保存的用户得先取消、再点保存 ——
     * 多一步，中途还可能手滑点到丢弃。
     * 项目调研文档 docs/FILE-BROWSER-RESEARCH.md 里写的就是这个三选一。
     */
    private static void confirmLeaveUnsaved(final Activity act, final Dialog dlg,
                                            final Runnable save) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "改动尚未保存"));
        body.addView(DshUi.hint(act, "直接关闭会丢失这次的改动。"),
                DshUi.fullWidth(act, 8));
        Button keep = DshUi.button(act, "继续编辑", false);
        Button drop = DshUi.button(act, "丢弃", false);
        Button saveNow = DshUi.button(act, "保存", true);
        final Dialog ask = DshUi.dialog(act, body, DshUi.footer(act, keep, drop, saveNow), 340);
        keep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ask.dismiss(); }
        });
        drop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ask.dismiss();
                dlg.dismiss();
            }
        });
        saveNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ask.dismiss();
                // 保存流程成功后自己会关掉编辑器；失败时它保持打开、
                // 改动仍在编辑器里 —— 比「先关掉再去存」安全。
                save.run();
            }
        });
        ask.show();
    }

    /** 只读取前 n 字节（文件很大时不整体载入）。 */
    private static byte[] readHead(File f, int n) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, n));
            byte[] buf = new byte[65536];
            int left = n, r;
            while (left > 0 && (r = in.read(buf, 0, Math.min(buf.length, left))) > 0) {
                out.write(buf, 0, r);
                left -= r;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

}
