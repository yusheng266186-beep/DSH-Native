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
import java.nio.charset.Charset;
import java.util.Locale;

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

        // 二进制判定：前 8KB 内出现 NUL 基本可以断定不是文本
        boolean binary = false;
        int scan = Math.min(head.length, 8192);
        for (int i = 0; i < scan; i++) {
            if (head[i] == 0) { binary = true; break; }
        }
        if (binary) {
            showInfo(act, f, "二进制文件", "无法以文本方式编辑。");
            return;
        }

        Decoded dec = decode(head);
        boolean readOnly = truncated;
        showEditor(act, f, dec.text, dec.charset, readOnly, truncated);
    }

    /** 解码结果。 */
    private static final class Decoded {
        final String text;
        final String charset;
        Decoded(String text, String charset) { this.text = text; this.charset = charset; }
    }

    /**
     * 解码字节：优先 UTF-8，失败退回 GB18030。
     *
     * <p>用「严格解码」判断成败 —— 默认的 UTF-8 解码器会把非法字节替换成
     * U+FFFD 而不报错，那样 GBK 文本会被静默读成乱码。
     */
    private static Decoded decode(byte[] data) {
        try {
            java.nio.charset.CharsetDecoder d = Charset.forName("UTF-8").newDecoder();
            d.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            d.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            return new Decoded(d.decode(java.nio.ByteBuffer.wrap(data)).toString(), "UTF-8");
        } catch (Throwable ignored) { }
        try {
            return new Decoded(new String(data, "GB18030"), "GB18030");
        } catch (Throwable t) {
            return new Decoded(new String(data), "未知");
        }
    }

    private static void showInfo(Activity act, File f, String title, String detail) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, title));
        body.addView(DshUi.hint(act, f.getName() + "\n" + FileBrowser.humanSize(f.length())
                + "\n\n" + detail), DshUi.fullWidth(act, 8));
        Button close = DshUi.button(act, "关闭", true);
        final Dialog dlg = DshUi.dialog(act, body, DshUi.footer(act, close), 400);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.show();
    }

    private static void showEditor(final Activity act, final File f, String initial,
                                   String charset, final boolean readOnly,
                                   boolean truncated) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, f.getName()));

        int lines = 1;
        for (int i = 0; i < initial.length(); i++) if (initial.charAt(i) == '\n') lines++;
        String info = f.getAbsolutePath() + "\n"
                + FileBrowser.humanSize(f.length()) + " · " + lines + " 行"
                + " · " + charset
                + (truncated ? " · 文件过大，仅预览前 " + FileBrowser.humanSize(PREVIEW_BYTES) : "");
        TextView meta = DshUi.hint(act, info);
        body.addView(meta, DshUi.fullWidth(act, 4));

        final EditText ed = new EditText(act);
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

        final Dialog dlg = readOnly
                ? DshUi.dialog(act, body, DshUi.footer(act, close), 780)
                : DshUi.dialog(act, body, DshUi.footer(act, close, save), 780);

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
                String text = ed.getText().toString();
                try {
                    // 原样写回（编码保持不变）；先写临时文件再改名，
                    // 避免写入中断把原文件截断成半截内容。
                    File tmp = new File(f.getParentFile(), f.getName() + ".dsh-tmp");
                    FileOutputStream os = new FileOutputStream(tmp);
                    try {
                        os.write(text.getBytes(charset.equals("UTF-8") ? "UTF-8" : "GB18030"));
                        os.flush();
                        os.getFD().sync();   // 落盘后再改名，避免断电留下半截文件
                    } finally {
                        os.close();
                    }
                    if (!tmp.renameTo(f)) {
                        // rename 失败（跨挂载点等）时退回直接覆盖
                        copyFile(tmp, f);
                        tmp.delete();
                    }
                    DshUi.toast(act, "已保存 " + FileBrowser.humanSize(f.length()));
                    dlg.dismiss();
                } catch (Throwable t) {
                    DshUi.toast(act, "保存失败: " + t.getMessage());
                }
            }
        });

        dlg.show();
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

    /** 供日志等场景复用的大小格式化。 */
    static String size(long n) {
        return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
    }
}
