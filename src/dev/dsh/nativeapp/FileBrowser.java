package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用内文件浏览器。
 *
 * <p>为什么需要它：应用的工作目录位于私有存储
 * （{@code /data/user/0/<包名>/files/...}），**任何第三方文件管理器都看不到**。
 * 出错时要翻日志、改配置、看运行包内容，没有这个入口就只能靠猜。
 *
 * <p>交互设计要点：
 * <ul>
 *   <li><b>面包屑可点</b> —— 路径每一段都能直接跳回去，不用逐级返回</li>
 *   <li><b>常用位置一键直达</b> —— 私有目录 / 工作区 / 共享存储 / 配置</li>
 *   <li><b>目录优先排序</b>，同名时按自然序，隐藏文件默认可见</li>
 *   <li><b>权限错误就地提示</b>，不弹系统对话框（Android 上
 *       {@code listFiles()} 失败只返回 null，必须显式判断并说明原因）</li>
 * </ul>
 */
public final class FileBrowser {

    /** 单目录最多列出多少项，避免超大目录卡住界面。 */
    private static final int LIST_CAP = 500;

    private FileBrowser() { }

    /** 一个「常用位置」快捷入口。 */
    private static final class Root {
        final String label;
        final File dir;
        Root(String label, File dir) { this.label = label; this.dir = dir; }
    }

    /**
     * 打开文件浏览器。
     *
     * @param act    宿主 Activity
     * @param appDir 应用私有根目录（可能为 null）
     */
    public static void show(final Activity act, final File appDir) {
        final List<Root> roots = new ArrayList<Root>();
        if (appDir != null) roots.add(new Root("应用", appDir));
        File ws = new File("/sdcard/DSHNative/workspace");
        if (ws.isDirectory()) roots.add(new Root("工作区", ws));
        File shared = new File("/sdcard/DSHNative");
        if (shared.isDirectory()) roots.add(new Root("共享", shared));
        if (appDir != null) {
            File dshHome = new File(appDir, ".dsh");
            if (dshHome.isDirectory()) roots.add(new Root("配置", dshHome));
            File tools = new File(appDir, "tools");
            if (tools.isDirectory()) roots.add(new Root("工具链", tools));
        }
        roots.add(new Root("根", new File("/")));

        // 起始目录：优先应用私有根，否则第一个可用位置
        File startDir = appDir != null && appDir.isDirectory()
                ? appDir : roots.get(0).dir;

        final File[] cwd = { startDir };

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "文件浏览"));

        final TextView pathView = new TextView(act);
        pathView.setTextSize(11f);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextColor(DshUi.TEXT_2);
        pathView.setPadding(0, DshUi.dp(act, 2), 0, 0);
        body.addView(pathView, DshUi.fullWidth(act, 4));

        final TextView meta = DshUi.hint(act, "");
        body.addView(meta, DshUi.fullWidth(act, 2));

        // ── 常用位置 ──
        final LinearLayout rootRow = new LinearLayout(act);
        rootRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(rootRow, DshUi.fullWidth(act, 10));

        // ── 面包屑 ──
        // 放进横向滚动容器：路径段数不定（/data/user/0/… 就有 7 段），
        // 一行放不下时必须能滑，否则后面的段会被直接裁掉（实测把文字压没）。
        final LinearLayout crumbRow = new LinearLayout(act);
        crumbRow.setOrientation(LinearLayout.HORIZONTAL);
        final android.widget.HorizontalScrollView crumbScroll =
                new android.widget.HorizontalScrollView(act);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.addView(crumbRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(crumbScroll, DshUi.fullWidth(act, 8));

        // ── 列表 ──
        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackground(DshUi.fieldBg(act));
        int pad = DshUi.dp(act, 6);
        listBox.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        body.addView(scroll, slp);

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                render(act, cwd[0], pathView, meta, crumbRow, crumbScroll, listBox, this);
            }
        };

        // ── 常用位置按钮（整组重建，状态只有「当前目录」一个来源）──
        rootRow.removeAllViews();
        for (final Root r : roots) {
            final boolean on = samePath(cwd[0], r.dir);
            Button b = DshUi.toggleButton(act, r.label, on);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cwd[0] = r.dir;
                    refresh.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = DshUi.dp(act, 6);
            rootRow.addView(b, lp);
        }

        Button close = DshUi.button(act, "关闭", true);
        final Dialog dlg = DshUi.dialog(act, body, DshUi.footer(act, close), 760);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        refresh.run();
        dlg.show();
    }

    private static boolean samePath(File a, File b) {
        return a != null && b != null && a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    /** 渲染当前目录：路径、面包屑、条目列表。 */
    private static void render(final Activity act, final File dir,
                               TextView pathView, TextView meta,
                               LinearLayout crumbRow,
                               final android.widget.HorizontalScrollView crumbScroll,
                               LinearLayout listBox,
                               final Runnable refresh) {
        pathView.setText(dir.getAbsolutePath());

        // ── 面包屑：每一段都可点 ──
        crumbRow.removeAllViews();
        List<File> chain = new ArrayList<File>();
        File cur = dir;
        while (cur != null) { chain.add(0, cur); cur = cur.getParentFile(); }
        for (int i = 0; i < chain.size(); i++) {
            final File target = chain.get(i);
            String label = target.getName();
            if (label == null || label.length() == 0) label = "/";
            Button seg = DshUi.toggleButton(act,
                    label.length() > 10 ? label.substring(0, 9) + "…" : label,
                    i == chain.size() - 1);
            seg.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    final File[] cwd = { target };
                    render(act, cwd[0], pathView, meta, crumbRow, crumbScroll, listBox, refresh);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = DshUi.dp(act, 4);
            crumbRow.addView(seg, lp);
        }
        // 当前目录在最右，渲染后滚到末尾，保证它始终可见
        crumbRow.post(new Runnable() {
            @Override public void run() { crumbScroll.fullScroll(View.FOCUS_RIGHT); }
        });

        // ── 列表 ──
        listBox.removeAllViews();
        if (!dir.isDirectory()) {
            meta.setText("不是目录");
            listBox.addView(DshUi.hint(act, "无法进入：" + dir.getAbsolutePath()));
            return;
        }
        File[] raw = dir.listFiles();
        if (raw == null) {
            // Android 上权限不足时 listFiles() 只返回 null，不会抛异常 ——
            // 必须显式说明原因，否则用户只会看到一片空白。
            meta.setText("无权限读取");
            listBox.addView(DshUi.hint(act,
                    "系统拒绝了访问（EACCES）。\n这是 Android 的沙箱限制，不是应用出错。"));
            return;
        }

        List<File> items = new ArrayList<File>(Arrays.asList(raw));
        java.util.Collections.sort(items, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean da = a.isDirectory(), db = b.isDirectory();
                if (da != db) return da ? -1 : 1;                 // 目录优先
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        int dirCount = 0, fileCount = 0;
        long totalSize = 0;
        int shown = 0;
        for (final File f : items) {
            if (shown >= LIST_CAP) break;
            shown++;
            final boolean isDir = f.isDirectory();
            if (isDir) dirCount++; else { fileCount++; totalSize += f.length(); }

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = DshUi.dp(act, 8);
            row.setPadding(rp, rp, rp, rp);

            TextView name = new TextView(act);
            name.setText((isDir ? "📁 " : iconFor(f)) + f.getName());
            name.setTextSize(12.5f);
            name.setTextColor(isDir ? DshUi.TEXT : DshUi.TEXT_2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView info = new TextView(act);
            info.setText(isDir ? "目录" : humanSize(f.length()) + "  " + shortTime(f.lastModified()));
            info.setTextSize(10.5f);
            info.setTextColor(DshUi.TEXT_3);
            info.setSingleLine(true);
            row.addView(info, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setBackground(DshUi.rowBg(act));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (isDir) {
                        render(act, f, pathView, meta, crumbRow, crumbScroll, listBox, refresh);
                    } else {
                        TextEditor.open(act, f);
                    }
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    act.getSystemService(Activity.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "路径", f.getAbsolutePath()));
                        DshUi.toast(act, "已复制路径");
                    }
                    return true;
                }
            });
            listBox.addView(row, DshUi.fullWidth(act, 2));
        }

        if (items.isEmpty()) {
            listBox.addView(DshUi.hint(act, "（空目录）"));
        }
        meta.setText(items.size() > shown
                ? "共 " + items.size() + " 项，仅显示前 " + shown + " 项"
                : dirCount + " 个目录 · " + fileCount + " 个文件 · " + humanSize(totalSize));
    }

    /** 按扩展名给个粗图标，便于快速辨认。 */
    private static String iconFor(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".md")) return "📄 ";
        if (n.endsWith(".json") || n.endsWith(".yml") || n.endsWith(".yaml")) return "⚙️ ";
        if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".ts")) return "📜 ";
        if (n.endsWith(".py")) return "🐍 ";
        if (n.endsWith(".sh")) return "🖥 ";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼 ";
        if (n.endsWith(".so") || n.endsWith(".node")) return "🔧 ";
        if (n.endsWith(".apk") || n.endsWith(".zst") || n.endsWith(".tar")) return "📦 ";
        return "📄 ";
    }

    static String humanSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    private static String shortTime(long ms) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }
}
