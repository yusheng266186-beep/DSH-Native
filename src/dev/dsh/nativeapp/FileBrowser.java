package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用内文件浏览器（仅界面层）。
 *
 * <p>为什么需要它：应用的工作目录位于私有存储
 * （{@code /data/user/0/<包名>/files/...}），**任何第三方文件管理器都看不到**。
 * 出错时要翻日志、改配置、看运行包内容，没有这个入口就只能靠猜。
 *
 * <p>目录读取、排序、格式化全部在 {@link FileListing} 里（纯 Java，可离线测试）；
 * 本类只做三件事：把快照渲染成行、把点击变成导航、把 I/O 挪到后台线程。
 *
 * <h3>布局上必须守住的两条</h3>
 * <ul>
 *   <li><b>长内容必须限宽并省略</b>：路径、软链目标都可能任意长。
 *       软链目标不限宽会把同行的文件名挤到看不见。</li>
 *   <li><b>列表用占满高度的对话框</b>：列表区是 {@code 0dp + weight=1}，
 *       只有窗口高度确定时才能拿到剩余空间（见 {@link DshUi#dialogFill}）。</li>
 * </ul>
 */
public final class FileBrowser {

    private FileBrowser() { }

    /** 常用位置。 */
    private static final class Root {
        final String label;
        final File dir;
        Root(String label, File dir) { this.label = label; this.dir = dir; }
    }

    /** 浏览器状态与视图引用（避免用一长串参数在方法间传递）。 */
    private static final class Browser {
        final Activity act;
        final List<Root> roots = new ArrayList<Root>();
        final List<Button> rootButtons = new ArrayList<Button>();

        final TextView pathView;
        final TextView meta;
        final LinearLayout crumbRow;
        final android.widget.HorizontalScrollView crumbScroll;
        final LinearLayout listBox;
        final Button hiddenToggle;
        final Button sortToggle;

        final Handler ui = new Handler(Looper.getMainLooper());
        final ExecutorService io = Executors.newSingleThreadExecutor();

        /** Android 的软链判定实现（{@code java.io.File} 会跟随链接，判断不出）。 */
        final FileListing.LinkResolver links = new FileListing.LinkResolver() {
            @Override public String linkTargetOf(File f) {
                try {
                    android.system.StructStat st = android.system.Os.lstat(f.getAbsolutePath());
                    if (!android.system.OsConstants.S_ISLNK(st.st_mode)) return null;
                    return android.system.Os.readlink(f.getAbsolutePath());
                } catch (Throwable t) {
                    return null;
                }
            }
        };

        File cwd;
        boolean showHidden = false;
        int sortMode = FileListing.SORT_NAME;
        /** 每次导航自增，用于丢弃过期的后台结果。 */
        int generation = 0;

        Browser(Activity act) {
            this.act = act;
            pathView = new TextView(act);
            meta = DshUi.hint(act, "");
            crumbRow = new LinearLayout(act);
            crumbScroll = new android.widget.HorizontalScrollView(act);
            listBox = new LinearLayout(act);
            hiddenToggle = DshUi.toggleButton(act, "隐藏文件", false);
            sortToggle = DshUi.toggleButton(act, "排序", false);
        }

        /** 导航到目录。读取放后台，避免大目录阻塞界面。 */
        void navigate(File dir) {
            if (dir == null) return;
            cwd = dir;
            final int gen = ++generation;
            renderChrome();
            meta.setText("正在读取…");
            listBox.removeAllViews();

            final boolean hidden = showHidden;
            final int sort = sortMode;
            io.execute(new Runnable() {
                @Override public void run() {
                    final FileListing.Listing listing =
                            FileListing.listDirectory(dir, hidden, sort, links);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation) return;      // 已导航到别处，丢弃过期结果
                            renderList(listing);
                        }
                    });
                }
            });
        }

        void refresh() { navigate(cwd); }

        /**
         * 布局自检：把关键尺寸写进日志。
         *
         * <p>目的很实际 —— 界面问题只能靠截图发现，而截图往往看不出
         * 「是内容真的少了，还是被裁掉了」。这里在布局完成后实测各区域尺寸，
         * 从日志就能判断：列表拿到多少高度、一屏能显示几行、面包屑是否超宽、
         * 底部按钮有没有挤出卡片。
         */
        void reportLayout(Dialog dlg, final ScrollView listScroll,
                          final LinearLayout rootRow,
                          final android.widget.HorizontalScrollView crumb) {
            final android.view.View decor = dlg.getWindow() == null
                    ? null : dlg.getWindow().getDecorView();
            if (decor == null) return;
            decor.post(new Runnable() {
                @Override public void run() {
                    try {
                        int dw = decor.getWidth(), dh = decor.getHeight();
                        int lw = listScroll.getWidth(), lh = listScroll.getHeight();
                        int rows = listBox.getChildCount();
                        int rowH = rows > 0 ? listBox.getChildAt(0).getHeight() : 0;
                        int visible = rowH > 0 ? lh / rowH : 0;

                        StringBuilder sb = new StringBuilder("布局自检: ");
                        sb.append("对话框 ").append(dw).append("x").append(dh);
                        sb.append("，列表 ").append(lw).append("x").append(lh);
                        sb.append("，条目 ").append(rows).append(" 个");
                        sb.append("，行高 ").append(rowH).append("px");
                        sb.append("，可见 ").append(visible).append(" 行");
                        // 面包屑内容宽 vs 可视宽：超出说明需要横向滚动（正常，不是缺陷）
                        int crumbContent = crumb.getChildCount() > 0
                                ? crumb.getChildAt(0).getWidth() : 0;
                        sb.append("，面包屑 ").append(crumbContent)
                          .append("/").append(crumb.getWidth());
                        // 常用位置一行是否放得下
                        int rootNeeded = 0;
                        for (int i = 0; i < rootRow.getChildCount(); i++) {
                            android.view.View c = rootRow.getChildAt(i);
                            rootNeeded += c.getWidth();
                            android.view.ViewGroup.MarginLayoutParams lp =
                                    (android.view.ViewGroup.MarginLayoutParams) c.getLayoutParams();
                            rootNeeded += lp.leftMargin + lp.rightMargin;
                        }
                        sb.append("，常用位置 ").append(rootNeeded)
                          .append("/").append(rootRow.getWidth());
                        sb.append(rootNeeded > rootRow.getWidth() ? " ⚠️ 放不下" : " ✓");

                        // 行内两列是否都被压到过窄（各占约一半为正常）
                        if (rows > 0 && rowH > 0) {
                            android.view.ViewGroup row =
                                    (android.view.ViewGroup) listBox.getChildAt(0);
                            if (row.getChildCount() >= 2) {
                                sb.append("，名称列 ").append(row.getChildAt(0).getWidth())
                                  .append(" / 说明列 ").append(row.getChildAt(1).getWidth());
                            }
                        }
                        DshUi.log(sb.toString());
                    } catch (Throwable t) {
                        DshUi.log("布局自检失败: " + t);
                    }
                }
            });
        }

        void shutdown() {
            try { io.shutdownNow(); } catch (Throwable ignored) { }
            try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        }

        // ---------------------------------------------------------- 渲染

        /** 路径、面包屑、常用位置高亮：只依赖 cwd，与列表内容无关。 */
        void renderChrome() {
            if (cwd == null) return;
            pathView.setText(cwd.getAbsolutePath());

            // 面包屑：每段可点。不截断文字 —— 外层是横向滚动容器，
            // 放不下可以滑，截断只会丢信息。
            crumbRow.removeAllViews();
            List<File> chain = new ArrayList<File>();
            File cur = cwd;
            while (cur != null) { chain.add(0, cur); cur = cur.getParentFile(); }
            for (int i = 0; i < chain.size(); i++) {
                final File target = chain.get(i);
                String label = target.getName();
                if (label == null || label.length() == 0) label = "/";
                Button seg = DshUi.toggleButton(act, label, i == chain.size() - 1);
                seg.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { navigate(target); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = DshUi.dp(act, 4);
                crumbRow.addView(seg, lp);
            }
            // 当前目录在最右：渲染后滚到末尾，保证它始终可见
            crumbRow.post(new Runnable() {
                @Override public void run() { crumbScroll.fullScroll(View.FOCUS_RIGHT); }
            });

            // 常用位置高亮：只依赖 cwd 一个来源
            for (int i = 0; i < roots.size() && i < rootButtons.size(); i++) {
                Button btn = rootButtons.get(i);
                DshUi.setButtonActive(btn, samePath(cwd, roots.get(i).dir));
            }
            hiddenToggle.setText("隐藏文件" + (showHidden ? " ✓" : ""));
            DshUi.setButtonActive(hiddenToggle, showHidden);
            sortToggle.setText(FileListing.sortLabel(sortMode));
            DshUi.setButtonActive(sortToggle, sortMode != FileListing.SORT_NAME);
        }

        /** 列表区。 */
        void renderList(FileListing.Listing listing) {
            listBox.removeAllViews();
            if (listing.error != null) {
                meta.setText("无法读取");
                listBox.addView(DshUi.hint(act, listing.error));
                return;
            }
            if (listing.entries.isEmpty()) {
                meta.setText(listing.dirCount + " 个目录 · " + listing.fileCount + " 个文件");
                listBox.addView(DshUi.hint(act,
                        listing.total == 0 ? "（空目录）"
                                : "（" + listing.total + " 项被隐藏，点「隐藏文件」可显示）"));
                return;
            }
            for (FileListing.Entry e : listing.entries) {
                listBox.addView(buildRow(e), DshUi.fullWidth(act, 2));
            }
            meta.setText(FileListing.summaryOf(listing));
        }

        /**
         * 单个条目行。
         *
         * <p>宽度分配是这里的关键：名称与说明列**按权重分配宽度**，
         * 说明列省略号截断。早先说明列用 {@code WRAP_CONTENT} 且不省略，
         * 软链目标一长就把名称挤到几乎不可见。
         */
        View buildRow(final FileListing.Entry e) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = DshUi.dp(act, 8);
            row.setPadding(rp, rp, rp, rp);

            TextView name = new TextView(act);
            name.setText(FileListing.iconOf(e) + e.name);
            name.setTextSize(12.5f);
            name.setTextColor(e.dir ? DshUi.TEXT : DshUi.TEXT_2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);

            TextView info = new TextView(act);
            info.setText(FileListing.infoText(e));
            info.setTextSize(10.5f);
            info.setTextColor(DshUi.TEXT_3);
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.setGravity(Gravity.END);

            // 名称 : 说明 = 1 : 0.85（约 54% : 46%），两者都不会把对方挤没
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
            infoLp.leftMargin = DshUi.dp(act, 8);
            row.addView(info, infoLp);

            row.setBackground(DshUi.rowBg(act));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (e.dir) {
                        navigate(e.file);
                    } else {
                        // 保存后刷新列表：否则大小与修改时间仍是旧的，
                        // 看起来像没保存成功
                        TextEditor.open(act, e.file, new Runnable() {
                            @Override public void run() { refresh(); }
                        });
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
                                "路径", e.file.getAbsolutePath()));
                        DshUi.toast(act, "已复制路径");
                    }
                    return true;
                }
            });
            return row;
        }
    }

    private static boolean samePath(File a, File b) {
        return a != null && b != null && a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    /** 打开文件浏览器。 */
    public static void show(final Activity act, final File appDir) {
        final Browser b = new Browser(act);

        if (appDir != null) b.roots.add(new Root("应用", appDir));
        File ws = new File("/sdcard/DSHNative/workspace");
        if (ws.isDirectory()) b.roots.add(new Root("工作区", ws));
        File shared = new File("/sdcard/DSHNative");
        if (shared.isDirectory()) b.roots.add(new Root("共享", shared));
        if (appDir != null) {
            File dshHome = new File(appDir, ".dsh");
            if (dshHome.isDirectory()) b.roots.add(new Root("配置", dshHome));
            File tools = new File(appDir, "tools");
            if (tools.isDirectory()) b.roots.add(new Root("工具链", tools));
        }
        b.roots.add(new Root("根", new File("/")));
        b.cwd = appDir != null && appDir.isDirectory() ? appDir : b.roots.get(0).dir;

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "文件浏览"));

        // 路径：单行 + 中间省略。深路径换行会把列表往下挤。
        b.pathView.setTextSize(11f);
        b.pathView.setTypeface(Typeface.MONOSPACE);
        b.pathView.setTextColor(DshUi.TEXT_2);
        b.pathView.setSingleLine(true);
        b.pathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(b.pathView, DshUi.fullWidth(act, 4));
        body.addView(b.meta, DshUi.fullWidth(act, 2));

        // 常用位置
        LinearLayout rootRow = new LinearLayout(act);
        rootRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(rootRow, DshUi.fullWidth(act, 10));
        for (final Root r : b.roots) {
            Button btn = DshUi.toggleButton(act, r.label, false);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { b.navigate(r.dir); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = DshUi.dp(act, 6);
            rootRow.addView(btn, lp);
            b.rootButtons.add(btn);
        }

        // 面包屑（横向滚动）
        b.crumbRow.setOrientation(LinearLayout.HORIZONTAL);
        b.crumbScroll.setHorizontalScrollBarEnabled(false);
        b.crumbScroll.addView(b.crumbRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(b.crumbScroll, DshUi.fullWidth(act, 8));

        // 列表
        b.listBox.setOrientation(LinearLayout.VERTICAL);
        b.listBox.setBackground(DshUi.surfaceBg(act));
        int pad = DshUi.dp(act, 6);
        b.listBox.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(b.listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        body.addView(scroll, slp);

        // 底部：隐藏文件开关 + 刷新 + 关闭
        b.hiddenToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.showHidden = !b.showHidden;
                b.refresh();
            }
        });
        b.sortToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.sortMode = FileListing.nextSortMode(b.sortMode);
                b.refresh();
            }
        });
        Button refreshBtn = DshUi.button(act, "刷新", false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { b.refresh(); }
        });
        Button close = DshUi.button(act, "关闭", true);

        final Dialog dlg = DshUi.dialogFill(act, body,
                DshUi.footer(act, b.hiddenToggle, b.sortToggle, refreshBtn, close), 820);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                b.shutdown();       // 停掉后台线程，避免泄漏
            }
        });

        // 返回键：先逐级退目录，退到顶层再关闭（与桌面文件管理器一致）
        dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int code,
                                           android.view.KeyEvent e) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    File up = b.cwd == null ? null : b.cwd.getParentFile();
                    if (up != null && !up.getAbsolutePath().equals(b.cwd.getAbsolutePath())) {
                        b.navigate(up);
                        return true;
                    }
                }
                return false;
            }
        });

        dlg.show();
        b.navigate(b.cwd);
        b.reportLayout(dlg, scroll, rootRow, b.crumbScroll);
    }
}
