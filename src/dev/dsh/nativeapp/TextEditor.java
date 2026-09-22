package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

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
 *       避免一次性载入几十 MB 把界面拖死</li>
 *   <li><b>编码</b>：先按 UTF-8 严格解码；失败则退回 GB18030 ——
 *       中文环境里大量文本文件仍是 GBK 系，按 UTF-8 硬读会整篇乱码</li>
 *   <li><b>未保存保护</b>：关闭时若内容有改动，先确认再丢弃</li>
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
        if (f == null || !f.isFile()) {
            DshUi.toast(act, "不是常规文件");
            return;
        }
        final byte[] head;
        final boolean truncated;
        try {
            long len = f.length();
            truncated = len > MAX_EDIT_BYTES;
            head = readHead(f, truncated ? PREVIEW_BYTES : (int) Math.min(len, MAX_EDIT_BYTES));
        } catch (Throwable t) {
            DshUi.toast(act, "读取失败: " + t.getMessage());
            return;
        }

        // 二进制判定与解码交给 TextCodec（纯逻辑、有测试覆盖）：
        // 二进制、BOM、UTF-8/GB18030、换行风格都在那里处理。
        if (TextCodec.looksBinary(head)) {
            showInfo(act, f, "二进制文件", "无法以文本方式编辑。");
            return;
        }
        final TextCodec.Decoded dec = TextCodec.decode(head);
        final boolean readOnly = truncated;
        showEditor(act, f, dec.text, dec, readOnly, truncated);
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

    private static void showEditor(final Activity act, final File f, String initial,
                                   final TextCodec.Decoded meta, final boolean readOnly,
                                   final boolean truncated) {
        // 记录打开时的文件状态：保存前比对，防止覆盖别的程序（或 agent）
        // 在此期间写入的内容。
        final long openMtime = f.lastModified();
        final long openLength = f.length();
        final int totalLines = TextCodec.countLines(initial);
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, f.getName()));

        final String head = f.getAbsolutePath() + "\n"
                + FileListing.humanSize(f.length()) + " · " + totalLines + " 行"
                + " · " + meta.describe()
                + (truncated ? " · 文件过大，仅预览前 " + FileListing.humanSize(PREVIEW_BYTES) : "");
        final TextView infoView = DshUi.hint(act, head + "　·　第 1 行");
        body.addView(infoView, DshUi.fullWidth(act, 4));

        // 用匿名子类是为了拿到 onSelectionChanged —— 光标移动不触发 TextWatcher，
        // 只有覆写这个方法才能实时更新「第 N 行」。
        final EditText ed = new EditText(act) {
            @Override protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                if (selStart < 0) return;
                int line = 1;
                CharSequence cs = getText();
                int end = Math.min(selStart, cs.length());
                for (int i = 0; i < end; i++) if (cs.charAt(i) == '\n') line++;
                infoView.setText(head + "　·　第 " + line + " 行");
            }
        };
        ed.setText(initial);
        ed.setTextSize(12f);
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setTextColor(DshUi.TEXT);
        ed.setBackground(DshUi.fieldBg(act));
        ed.setGravity(Gravity.TOP | Gravity.START);
        ed.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ed.setHorizontallyScrolling(false);      // 自动换行，手机上更易读
        ed.setEnabled(!readOnly);
        int pad = DshUi.dp(act, 12);
        ed.setPadding(pad, pad, pad, pad);

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

        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!readOnly && !ed.getText().toString().equals(initial)) {
                    confirmDiscard(act, dlg);
                } else {
                    dlg.dismiss();
                }
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 打开后文件被别的程序改过 → 先确认，别直接覆盖别人的写入
                if (f.lastModified() != openMtime || f.length() != openLength) {
                    confirmOverwrite(act, f, ed, meta, dlg);
                    return;
                }
                doSave(act, f, ed.getText().toString(), meta, dlg);
            }
        });

        dlg.show();
    }

    /** 覆盖确认：文件在编辑期间被外部改动过。 */
    private static void confirmOverwrite(final Activity act, final File f, final EditText ed,
                                         final TextCodec.Decoded meta, final Dialog parent) {
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
                doSave(act, f, ed.getText().toString(), meta, parent);
            }
        });
        ask.show();
    }

    /** 实际写入：先写临时文件并落盘，再改名替换。 */
    private static void doSave(final Activity act, final File f, String text,
                               final TextCodec.Decoded meta, final Dialog dlg) {
        try {
            // 用原编码 + 原 BOM 写回（避免把 GBK 文件悄悄转成 UTF-8、或丢掉 BOM）；
            // 先写临时文件并 fsync，再改名替换 —— 中途失败也不会把原文件截断。
            byte[] bytes = TextCodec.encode(text, meta);
            File tmp = new File(f.getParentFile(), f.getName() + ".dsh-tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(bytes);
                os.flush();
                os.getFD().sync();   // 落盘后再改名，避免断电留下半截文件
            } finally {
                os.close();
            }
            if (!tmp.renameTo(f)) {
                copyFile(tmp, f);    // rename 失败（跨挂载点等）时退回直接覆盖
                tmp.delete();
            }
            DshUi.toast(act, "已保存 " + FileListing.humanSize(f.length()));
            dlg.dismiss();
        } catch (Throwable t) {
            DshUi.toast(act, "保存失败: " + t.getMessage());
        }
    }

    private static void confirmDiscard(final Activity act, final Dialog dlg) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "放弃修改？"));
        body.addView(DshUi.hint(act, "当前改动尚未保存，关闭后将丢失。"),
                DshUi.fullWidth(act, 8));
        Button keep = DshUi.button(act, "继续编辑", false);
        Button drop = DshUi.button(act, "放弃", true);
        final Dialog ask = DshUi.dialog(act, body, DshUi.footer(act, keep, drop), 320);
        keep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { ask.dismiss(); }
        });
        drop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ask.dismiss();
                dlg.dismiss();
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

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        OutputStream os = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
        } finally {
            os.close();
            in.close();
        }
    }

}
