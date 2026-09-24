package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.LayoutAnimationController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

    /**
     * 纵向间距节奏（dp）。
     *
     * <p>原则：**同一组信息挨紧，组与组之间留松**。
     * 标题 / 路径 / 统计属于同一组（都在说明「这是哪里」），所以间距很小；
     * 常用位置、面包屑、列表是三个不同的操作区，之间要留出呼吸感。
     *
     * <p>集中成常量而不是散落在各处写数字：调整观感时改一处即可，
     * 也不会出现「列表上边距是 0 而其它都是 8~10」这种不一致
     * —— 实测那个 0 让面包屑与列表挤在了一起。
     */
    private static final int GAP_TITLE = 4;    // 标题 → 路径
    private static final int GAP_META  = 2;    // 路径 → 统计
    private static final int GAP_ROOTS = 14;   // 统计 → 常用位置
    private static final int GAP_CRUMB = 12;   // 常用位置 → 面包屑
    private static final int GAP_LIST  = 16;   // 面包屑 → 列表

    /**
     * 分批渲染的批量（行）。
     *
     * <p>为什么必须分批：单目录上限 {@link FileListing#LIST_CAP} = 800 项，
     * 每行 4 个 View —— 一次建完就是 3200 个 View 的构造 + measure + layout
     * 全压在同一帧里，首屏要空白几百毫秒。先铺一批让内容立刻可见，
     * 剩下的用 {@code post} 每帧补一批。
     *
     * <p>取 60 而不是「一屏的行数」：一屏约 12~15 行，60 行能覆盖甩一下的滑动距离，
     * 用户滑到那里时后面的行早就补好了，看不出是分批加载的。
     */
    private static final int ROW_BATCH = 60;

    /**
     * 超过这个行数就不做逐行入场动画。
     *
     * <p>{@link LayoutAnimationController} 是**逐行错开**的（0.04 ≈ 一行一帧多），
     * 800 行等于把首屏拉长到十几秒；小目录才值得用它换一次"列表长出来"的观感。
     */
    private static final int ANIM_ROW_LIMIT = 60;

    /** 触摸目标下限（dp）。低于它单手操作容易点到相邻行。 */
    private static final int ROW_MIN_HEIGHT = 44;

    /** 状态持久化的偏好文件名与键（见 {@link Browser} 构造函数的说明）。 */
    private static final String PREFS = "dsh_filebrowser";
    private static final String KEY_HIDDEN = "show_hidden";
    private static final String KEY_SORT = "sort_mode";

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

        /** 偏好存储：隐藏文件开关与排序方式是长期选择，必须跨次打开保留。 */
        final SharedPreferences prefs;

        /**
         * 行背景的圆角（px）。
         *
         * <p>行背景不再用 {@link DshUi#rowBg}（每行 3 个 GradientDrawable），
         * 换成轻量的 {@link RowBgDrawable}，每行只建 1 个对象。
         * 圆角只在这里算一次，不必每行都过一遍 dp 换算。
         */
        final float rowRadius;

        /** 加载中盖在列表上的遮罩与转圈（见 {@link #setLoading}）。 */
        View loadingMask;
        ProgressBar loadingSpin;

        /** 对话框已关闭。后台线程之后 post 回来的结果一律丢弃。 */
        boolean closed;

        /** 允许写入的根（其余位置只读）。私有目录之外一律不改，避免误删。 */
        final java.util.List<File> writeRoots = new java.util.ArrayList<File>();

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

        /** 布局自检用的探针视图（列表滚动容器）。 */
        android.view.View layoutProbe;
        /** 自检所需的其他视图。 */
        LinearLayout probeRootRow;
        android.widget.HorizontalScrollView probeCrumb;
        Runnable runLayoutCheck;

        File cwd;
        boolean showHidden = false;
        int sortMode = FileListing.SORT_NAME;
        /** 每次导航自增，用于丢弃过期的后台结果。 */
        int generation = 0;

        Browser(Activity act) {
            this.act = act;
            // 这两个开关是用户的长期选择：每次打开都回默认值（隐藏文件关闭、
            // 按名称排序），等于让人反复重设同一个偏好。
            prefs = act.getSharedPreferences(PREFS, 0);
            showHidden = prefs.getBoolean(KEY_HIDDEN, false);
            sortMode = prefs.getInt(KEY_SORT, FileListing.SORT_NAME);
            pathView = new TextView(act);
            meta = DshUi.hint(act, "");
            crumbRow = new LinearLayout(act);
            crumbScroll = new android.widget.HorizontalScrollView(act);
            listBox = new LinearLayout(act);
            // 文案与空目录提示保持一致：提示让用户点「隐藏文件」，
            // 按钮就必须真的叫「隐藏文件」——否则提示的是一个不存在的按钮。
            hiddenToggle = DshUi.toggleButton(act, "隐藏文件", false);
            sortToggle = DshUi.toggleButton(act, "排序", false);
            rowRadius = DshUi.dp(act, 8);
            // 「隐藏文件」比其它底部按钮多两个字，而 footer 是等权重平分宽度：
            // 窄屏上 4 个汉字会被省略成「隐藏文…」，提示里点名的按钮就找不到了。
            // 自动缩放让它在放不下时缩字号而不是丢字（API 26+，低版本保持原样）。
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                try {
                    hiddenToggle.setAutoSizeTextTypeUniformWithConfiguration(
                            9, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                } catch (Throwable ignored) { }
            }
        }

        /** 导航到目录。读取放后台，避免大目录阻塞界面。 */
        void navigate(File dir) {
            if (dir == null) return;
            cwd = dir;
            final int gen = ++generation;
            renderChrome();
            // **不清空列表**：清空会让界面白一下再重建，点按钮时像在闪。
            // 保留旧内容直到新结果到达，但必须同时把旧内容标成"不可用"——
            // 读大目录要几百毫秒，这期间点第一行，打开的是**上一个目录**的同名文件。
            setLoading(true);
            meta.setText("正在读取…");

            final boolean hidden = showHidden;
            final int sort = sortMode;
            submit(new Runnable() {
                @Override public void run() {
                    final FileListing.Listing listing =
                            FileListing.listDirectory(dir, hidden, sort, links);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            // 面板已关：视图已经没用了
                            if (closed) return;
                            // 已导航到别处：丢弃过期结果。**不解除加载态**——
                            // 更新的那次导航已经把它重新置上了，由它自己解除。
                            if (gen != generation) return;
                            setLoading(false);
                            renderList(listing);
                        }
                    });
                }
            });
        }

        /**
         * 加载态：给列表盖一层可点遮罩 + 居中转圈，并把过期内容压暗。
         *
         * <p>遮罩必须是**可点**的 View：可点的 View 会消费掉落在它范围内的触摸，
         * 事件不会穿透到下面那些已经不属于当前目录的行。比起遍历子 View
         * {@code setClickable(false)}，这样不用管"哪些行是新的、哪些是旧的"，
         * 也不会漏掉渲染中途刚补进来的行。
         *
         * <p>透明度 0.45 是给"内容已过期"一个可见的信号：
         * 只转圈不变暗的话，用户会以为列表还是当前目录的。
         */
        void setLoading(boolean on) {
            if (loadingMask == null || loadingSpin == null) return;
            loadingMask.setVisibility(on ? View.VISIBLE : View.GONE);
            loadingSpin.setVisibility(on ? View.VISIBLE : View.GONE);
            listBox.setAlpha(on ? 0.45f : 1f);
        }

        void refresh() { navigate(cwd); }

        /**
         * 提交一个后台任务。
         *
         * <p>不能直接 {@code io.execute}：执行器在面板关闭时被
         * {@code shutdownNow()} 过，之后再提交会抛 {@code RejectedExecutionException}。
         * 而"面板关了还有任务要提交"是真实存在的路径 —— 例如编辑器保存完回调
         * {@code refresh()} 时，文件浏览器可能已经被关掉了。
         *
         * @return 已提交返回 true；面板已关、任务被丢弃返回 false
         */
        private boolean submit(Runnable task) {
            if (closed) return false;
            try {
                io.execute(task);
                return true;
            } catch (Throwable ignored) {
                // 执行器已经停了：任务丢掉即可，界面已经没了
                return false;
            }
        }

        /**
         * 布局自检：把关键尺寸写进日志。
         *
         * <p>目的很实际 —— 界面问题只能靠截图发现，而截图往往看不出
         * 「是内容真的少了，还是被裁掉了」。这里在布局完成后实测各区域尺寸，
         * 从日志就能判断：列表拿到多少高度、一屏能显示几行、面包屑是否超宽、
         * 底部按钮有没有挤出卡片。
         *
         * @param listArea 列表区容器（ScrollView 外面那层 FrameLayout）。
         *                 探针用它而不是里面的 ScrollView：两者尺寸相同，
         *                 但只有容器是 body 的直接子 View，{@code getTop()}
         *                 量出来的「面包屑→列表」间距才是对的。
         */
        void reportLayout(Dialog dlg, final View listArea,
                          final LinearLayout rootRow,
                          final android.widget.HorizontalScrollView crumb) {
            final android.view.View decor = dlg.getWindow() == null
                    ? null : dlg.getWindow().getDecorView();
            if (decor == null) return;
            layoutProbe = listArea;
            probeRootRow = rootRow;
            probeCrumb = crumb;
            DshUi.log("布局自检已排入队列（等待布局完成）");
            runLayoutCheck = new Runnable() {
                @Override public void run() {
                    try {
                        if (decor.getWidth() == 0 || decor.getHeight() == 0) {
                            DshUi.log("布局自检跳过：视图尚未完成布局");
                            return;
                        }
                        int dw = decor.getWidth(), dh = decor.getHeight();
                        int lw = listArea.getWidth(), lh = listArea.getHeight();
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
                        int crumbContent = probeCrumb.getChildCount() > 0
                                ? probeCrumb.getChildAt(0).getWidth() : 0;
                        sb.append("，面包屑 ").append(crumbContent)
                          .append("/").append(probeCrumb.getWidth());
                        // 常用位置：逐个按钮比较「文字宽 + 内边距」与「实际宽度」。
                        // 只看总和没有意义（六个按钮等权重，总和必然等于行宽）——
                        // 真正要发现的是某个标签被省略号截断。
                        StringBuilder tight = new StringBuilder();
                        for (int i = 0; i < probeRootRow.getChildCount(); i++) {
                            android.widget.TextView c =
                                    (android.widget.TextView) probeRootRow.getChildAt(i);
                            int need = (int) c.getPaint().measureText(String.valueOf(c.getText()))
                                     + c.getPaddingLeft() + c.getPaddingRight();
                            if (need > c.getWidth()) {
                                if (tight.length() > 0) tight.append("/");
                                tight.append(c.getText());
                            }
                        }
                        sb.append("，常用位置 ");
                        if (tight.length() == 0) {
                            sb.append("标签均完整");
                        } else {
                            sb.append("警告: 这些标签被省略: ").append(tight);
                        }

                        // 底部按钮：逐个比较「文字宽 + 内边距」与实际宽度。
                        // 这正是「关闭」曾被压缩换行的原因 —— 只看总宽不够，
                        // 必须能指出是哪一个按钮放不下。
                        android.view.View parent = (android.view.View) probeRootRow.getParent();
                        if (parent instanceof LinearLayout) {
                            LinearLayout col = (LinearLayout) parent;
                            for (int i = 0; i < col.getChildCount(); i++) {
                                android.view.View child = col.getChildAt(i);
                                if (!(child instanceof LinearLayout)) continue;
                                LinearLayout rowC = (LinearLayout) child;
                                if (rowC.getChildCount() == 0) continue;
                                if (!(rowC.getChildAt(0) instanceof android.widget.Button)) continue;

                                StringBuilder tightBtn = new StringBuilder();
                                for (int k = 0; k < rowC.getChildCount(); k++) {
                                    android.widget.TextView b2 =
                                            (android.widget.TextView) rowC.getChildAt(k);
                                    int need2 = (int) b2.getPaint()
                                            .measureText(String.valueOf(b2.getText()))
                                            + b2.getPaddingLeft() + b2.getPaddingRight();
                                    if (need2 > b2.getWidth()) {
                                        if (tightBtn.length() > 0) tightBtn.append("/");
                                        tightBtn.append(b2.getText());
                                    }
                                }
                                sb.append("，底部按钮 ");
                                sb.append(tightBtn.length() == 0
                                        ? "均完整" : "被截断: " + tightBtn);

                                // 底部按钮是否被挤出卡片（超出可视区域）
                                int cardBottom = col.getHeight();
                                int rowBottom = rowC.getTop() + rowC.getHeight();
                                sb.append(rowBottom > cardBottom ? "，底部被挤出卡片" : "，底部可见");
                                break;
                            }
                        }

                        // 各区块的实际间距：直接验证「挤不挤」，
                        // 不必靠截图目测（目测很容易把 3dp 与 16dp 看混）
                        int crumbBottom = probeCrumb.getTop() + probeCrumb.getHeight();
                        int listTop = listArea.getTop();
                        sb.append("，面包屑→列表 ").append(listTop - crumbBottom)
                          .append("px");
                        int rootBottom = probeRootRow.getTop() + probeRootRow.getHeight();
                        sb.append("，常用位置→面包屑 ")
                          .append(probeCrumb.getTop() - rootBottom).append("px");

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
            };
            decor.post(runLayoutCheck);
        }

        void shutdown() {
            // 先立旗标再清队列：removeCallbacksAndMessages 只能清掉**当时已入队**的消息，
            // 后台线程在此之后 post 的 Runnable（读目录、统计、删除）照样会执行。
            closed = true;
            try { io.shutdownNow(); } catch (Throwable ignored) { }
            try { ui.removeCallbacksAndMessages(null); } catch (Throwable ignored) { }
        }

        // ---------------------------------------------------------- 渲染

        /** 路径、面包屑、常用位置高亮：只依赖 cwd，与列表内容无关。 */
        void renderChrome() {
            if (cwd == null) return;

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
            // 当前目录在最右：渲染后滚到末尾，保证它始终可见。
            // 用平滑滚动而不是 fullScroll：后者是瞬移，深路径下会让人失去方向感。
            crumbRow.post(new Runnable() {
                @Override public void run() {
                    // 目标取内容宽（= 最右那段的位置）而不是固定 300px：
                    // 路径深时内容宽度远超 300，滚到 300 反而把当前目录留在屏幕外，
                    // 正好破坏上面"保证它始终可见"的意图。
                    int target = crumbRow.getWidth();
                    // 首帧布局还没跑完时 getWidth() 仍是 0，此时退回测量宽，避免滚动变空操作
                    if (target <= 0) target = crumbRow.getMeasuredWidth();
                    crumbScroll.smoothScrollTo(target, 0);
                }
            });

            // 只读位置明确标出来 —— 否则用户点了「新建」才被告知不行
            pathView.setText(cwd.getAbsolutePath()
                    + (cwdWritable() ? "" : "　（只读）"));

            // 常用位置高亮：只依赖 cwd 一个来源
            for (int i = 0; i < roots.size() && i < rootButtons.size(); i++) {
                Button btn = rootButtons.get(i);
                DshUi.setButtonActive(btn, samePath(cwd, roots.get(i).dir));
            }
            hiddenToggle.setText("隐藏文件");
            DshUi.setButtonActive(hiddenToggle, showHidden);
            sortToggle.setText(FileListing.sortLabel(sortMode));
            DshUi.setButtonActive(sortToggle, sortMode != FileListing.SORT_NAME);
        }

        /** 当前目录是否可写。 */
        boolean cwdWritable() {
            return FileOps.isWritable(cwd, writeRoots);
        }

        /**
         * 长按条目的操作菜单。
         *
         * <p>不可写的位置**不禁用菜单本身**，而是把「重命名 / 删除」置灰并说明原因 ——
         * 直接不给按钮会让用户以为功能不存在。
         */
        void showItemMenu(final FileListing.Entry e) {
            final boolean writable = FileOps.isWritable(e.file, writeRoots);

            LinearLayout box = DshUi.paddedBody(act);
            box.addView(DshUi.title(act, e.name));
            box.addView(DshUi.hint(act, e.file.getAbsolutePath()
                    + (writable ? "" : "\n该位置只读，不能修改")), DshUi.fullWidth(act, 6));

            // 分享要求文件存在且在白名单内 —— 与可写范围一致
            final boolean shareable = ShareTargets.isShareable(e.file, writeRoots);
            Button copy = DshUi.button(act, "复制路径", false);
            Button share = DshUi.button(act, "分享", false);
            Button rename = DshUi.button(act, "重命名", false);
            Button del = DshUi.button(act, "删除", false);
            share.setEnabled(shareable);
            rename.setEnabled(writable);
            del.setEnabled(writable);
            if (!shareable) share.setTextColor(DshUi.TEXT_3);
            if (!writable) {
                rename.setTextColor(DshUi.TEXT_3);
                del.setTextColor(DshUi.TEXT_3);
            }

            final Dialog menu = DshUi.dialog(act, box,
                    DshUi.footer(act, copy, share, rename, del), 360);
            share.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    menu.dismiss();
                    shareFile(e.file);
                }
            });
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    menu.dismiss();
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    act.getSystemService(Activity.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "路径", e.file.getAbsolutePath()));
                        DshUi.toast(act, "已复制路径");
                    }
                }
            });
            rename.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    menu.dismiss();
                    promptName("重命名", e.name, new NameSink() {
                        @Override public void onName(String name) {
                            String err = FileOps.rename(e.file, name, writeRoots);
                            if (err == null) {
                                DshUi.toast(act, "已重命名");
                                refresh();
                            } else {
                                DshUi.toast(act, err);
                            }
                        }
                    });
                }
            });
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    menu.dismiss();
                    confirmDelete(e);
                }
            });
            menu.show();
        }

        /**
         * 用系统分享面板把文件发出去。
         *
         * <p>走 {@code content://} 而不是文件路径 —— API 24 起把
         * {@code file://} 交给别的应用会抛 {@code FileUriExposedException}。
         * 接收方拿到的是一次性读授权，看不到真实路径。
         */
        private void shareFile(File f) {
            try {
                android.net.Uri uri = android.net.Uri.parse("content://"
                        + UpdateProvider.AUTHORITY + "/"
                        + ShareTargets.PREFIX + ShareTargets.encode(f));
                android.content.Intent send = new android.content.Intent(
                        android.content.Intent.ACTION_SEND);
                send.setType(ShareTargets.mimeOf(f.getName()));
                send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                send.putExtra(android.content.Intent.EXTRA_TITLE,
                        ShareTargets.displayName(f));
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                android.content.Intent chooser =
                        android.content.Intent.createChooser(send, "分享 " + f.getName());
                chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(chooser);
                DshUi.log("分享文件: " + f.getAbsolutePath());
            } catch (Throwable t) {
                DshUi.toast(act, "分享失败：" + t.getClass().getSimpleName());
                DshUi.log("分享失败: " + t);
            }
        }

        /** 输入名称的回调。 */
        interface NameSink { void onName(String name); }

        /** 弹一个名称输入框（新建与重命名共用）。 */
        void promptName(String title, String initial, final NameSink sink) {
            LinearLayout box = DshUi.paddedBody(act);
            box.addView(DshUi.title(act, title));
            final android.widget.EditText input = DshUi.input(act, initial == null ? "" : initial, false);
            input.setSingleLine(true);
            box.addView(input, DshUi.fullWidth(act, 10));
            Button cancel = DshUi.button(act, "取消", false);
            Button ok = DshUi.button(act, "确定", true);
            final Dialog d = DshUi.dialog(act, box, DshUi.footer(act, cancel, ok), 340);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String name = input.getText() == null ? "" : input.getText().toString();
                    // 先本地校验，错误就地提示；通过后再交给上层执行
                    String bad = FileOps.validateName(name);
                    if (bad != null) { DshUi.toast(act, bad); return; }
                    d.dismiss();
                    sink.onName(name.trim());
                }
            });
            d.show();
        }

        /**
         * 删除确认。
         *
         * <p>目录会说明将删除多少条目 —— 递归删除不该只问一句「确定吗」。
         *
         * <p>但**统计条目数本身也是递归遍历**：运行包解压后的残留目录动辄几万项，
         * 在主线程算这个就是几百毫秒的冻结，用户看到的是"点了删除没反应"，
         * 严重时直接 ANR。所以先弹确认框、数量在 {@link #io} 上算完再补进文案 ——
         * 不让用户为一句提示先等一秒。
         */
        void confirmDelete(final FileListing.Entry e) {
            LinearLayout box = DshUi.paddedBody(act);
            box.addView(DshUi.title(act, "删除 " + e.name + "？"));
            final TextView detail = DshUi.hint(act, e.dir
                    ? "这是一个文件夹，将删除其中的全部内容（正在统计条目数…）。\n此操作不可恢复。"
                    : "此操作不可恢复。");
            box.addView(detail, DshUi.fullWidth(act, 8));
            Button cancel = DshUi.button(act, "取消", false);
            Button ok = DshUi.button(act, "删除", true);
            final Dialog d = DshUi.dialog(act, box, DshUi.footer(act, cancel, ok), 340);

            // 统计结果（0 = 还没算出来）。删除完成后用它给出「已删除 N 个条目」的反馈。
            final int[] counted = { 0 };
            if (e.dir) {
                io.execute(new Runnable() {
                    @Override public void run() {
                        final int n = FileOps.countEntries(e.file);
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (closed) return;
                                // 即使确认框已经被取消也留下数字：用户可能马上再点一次删除
                                counted[0] = n;
                                if (d.isShowing()) {
                                    detail.setText("这是一个文件夹，将连同其中 " + n
                                            + " 个条目一起删除。\n此操作不可恢复。");
                                }
                            }
                        });
                    }
                });
            }
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            ok.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    // 递归删除同样在后台跑，界面交给进度框（见 deleteAsync 的说明）
                    deleteAsync(e, counted[0]);
                }
            });
            d.show();
        }

        /**
         * 删除（目录含整棵子树）—— 一律在后台执行。
         *
         * <p>递归删除一个几万项的目录要好几秒，放主线程必然 ANR。
         * 期间用**不可取消**的进度框占住界面：
         * <ul>
         *   <li>不可取消是刻意的 —— 删除已经开始，这时允许"取消"只会让用户
         *       要么以为没删（其实删了一半），要么以为删了（其实还在跑）；</li>
         *   <li>body 里放转圈而不是只有文字 —— 不确定时长的任务光有文字
         *       看不出"还在跑"还是"已经卡死"。</li>
         * </ul>
         */
        void deleteAsync(final FileListing.Entry e, final int count) {
            final Dialog progress = progressDialog("正在删除…",
                    e.dir ? "正在删除 " + e.name + " 及其中的内容" : "正在删除 " + e.name);
            progress.show();
            io.execute(new Runnable() {
                @Override public void run() {
                    final String err = FileOps.delete(e.file, writeRoots);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            // 进度框无论如何都要收掉：面板已关时 ui 队列被清空过，
                            // 没有别人会替我们关它，它会一直挂在窗口上
                            dismissQuietly(progress);
                            if (closed) return;
                            if (err == null) {
                                DshUi.toast(act, e.dir && count > 0
                                        ? "已删除 " + count + " 个条目" : "已删除");
                                refresh();
                            } else {
                                DshUi.toast(act, err);
                            }
                        }
                    });
                }
            });
        }

        /**
         * 不可取消的进度框。
         *
         * <p>用 {@link DshUi#dialog} 而不是 {@code AlertDialog}：后者是系统原生样式，
         * 与 DSH 的卡片语言不一致（项目约定，构建期也会检查）。
         */
        Dialog progressDialog(String title, String body) {
            LinearLayout box = DshUi.paddedBody(act);
            box.addView(DshUi.title(act, title));
            if (body != null && body.length() > 0) {
                box.addView(DshUi.hint(act, body), DshUi.fullWidth(act, 6));
            }
            ProgressBar spin = new ProgressBar(act);
            tintProgress(spin);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    DshUi.dp(act, 28), DshUi.dp(act, 28));
            lp.topMargin = DshUi.dp(act, 14);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            box.addView(spin, lp);
            Dialog d = DshUi.dialog(act, box, null, 240);
            // 点外部/按返回键都不能关：删除已经开始，关掉只会留下"删了一半"的未知状态
            d.setCancelable(false);
            return d;
        }

        /** 关掉对话框，忽略「已经关了」这类异常（关闭路径上不值得再抛一次）。 */
        private void dismissQuietly(Dialog d) {
            try { d.dismiss(); } catch (Throwable ignored) { }
        }

        /** 居中的状态提示（空目录 / 无权限 / 出错）。 */
        private View centeredHint(String text) {
            TextView tv = DshUi.hint(act, text);
            tv.setGravity(Gravity.CENTER);
            int v = DshUi.dp(act, 28);
            tv.setPadding(v, v, v, v);
            return tv;
        }

        /** 列表区。 */
        void renderList(final FileListing.Listing listing) {
            // 这一次渲染的批次标记：分批补行时用它判断自己是否已经过期
            // （用户中途导航到别处会自增 generation）。
            final int gen = generation;
            listBox.removeAllViews();
            // 先清掉上一次的入场动画：大目录逐行动画会把首屏拖慢，
            // 而 setLayoutAnimation 是"粘"的，不清就会跟着新列表继续跑。
            listBox.setLayoutAnimation(null);
            if (listing.error != null) {
                meta.setText("无法读取");
                listBox.addView(centeredHint(listing.error));
                scheduleLayoutCheck();
                return;
            }
            if (listing.entries.isEmpty()) {
                meta.setText(listing.dirCount + " 个目录 · " + listing.fileCount + " 个文件");
                listBox.addView(centeredHint(listing.total == 0
                        ? "（空目录）"
                        : "（" + listing.total + " 项被隐藏，点「隐藏文件」可显示）"));
                scheduleLayoutCheck();
                return;
            }

            // 列表切换的过渡：逐行淡入，避免"啪"地整块换掉。
            // **只在行数少时开**：动画是逐行错开的，行数一多（> ANIM_ROW_LIMIT）
            // 光等动画就比渲染本身还慢。必须在 addView 之前设，否则本轮子 View
            // 拿不到动画参数。
            if (listing.entries.size() <= ANIM_ROW_LIMIT) {
                listBox.setLayoutAnimation(new LayoutAnimationController(
                        new AlphaAnimation(0f, 1f), 0.04f));
            }
            meta.setText(FileListing.summaryOf(listing));

            final List<FileListing.Entry> entries = listing.entries;
            final int[] next = { 0 };
            appendRows(entries, next, gen);
        }

        /**
         * 分帧追加一批行（见 {@link FileBrowser#ROW_BATCH}）。
         *
         * <p>每批之间检查 {@code gen}：补行期间用户可能已经导航到别的目录，
         * 旧目录剩下的行绝不能再插进新列表里 —— 那会变成两个目录的内容混在一起。
         *
         * @param next 已补到第几行（跨批次的游标，用数组是因为匿名类只能捕获 final 引用）
         * @param gen  开始渲染这批数据时的 generation
         */
        void appendRows(final List<FileListing.Entry> entries, final int[] next, final int gen) {
            if (closed || gen != generation) return;
            int end = Math.min(next[0] + ROW_BATCH, entries.size());
            for (int i = next[0]; i < end; i++) {
                // 不再用外边距分隔（改由分割线承担），否则线两侧会多出一条缝
                listBox.addView(buildRow(entries.get(i)), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            next[0] = end;
            if (end < entries.size()) {
                // 下一帧再补：让这一帧先完成布局把已有内容显示出来，而不是攒着一次性显示
                listBox.post(new Runnable() {
                    @Override public void run() { appendRows(entries, next, gen); }
                });
                return;
            }
            // 行全部就位后再自检：尺寸只在布局完成后才有效，
            // 分批渲染下"全部就位"发生在最后一帧
            scheduleLayoutCheck();
        }

        /** 排入一次布局自检（尺寸/间距只能等布局完成后才测得准）。 */
        void scheduleLayoutCheck() {
            if (layoutProbe == null || runLayoutCheck == null) return;
            layoutProbe.post(new Runnable() {
                @Override public void run() { runLayoutCheck.run(); }
            });
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
            // 触摸目标下限：8dp 内边距 + 20dp 图标只有 36dp 高，
            // 低于 Android 无障碍建议的 44dp —— 单手操作时容易点到上下相邻的行。
            row.setMinimumHeight(DshUi.dp(act, ROW_MIN_HEIGHT));
            // 行本身可聚焦：TalkBack 与方向键/键盘才能把它当成一个整体停住。
            // 顺带让行背景的 state_focused 分支真正生效 ——
            // 在此之前行不可聚焦，那个分支是永远不会走到的死代码。
            row.setFocusable(true);
            // 一整行只报一句话。默认 TalkBack 会逐个读子 TextView，
            // 既读不出"这是文件夹还是文件"（能不能点进去），也要滑好几次才过一个条目。
            row.setContentDescription(e.dir
                    ? e.name + "，文件夹"
                    : e.name + "，文件，" + FileListing.infoText(e));

            // 图标用自绘 View（不受系统字体影响），名称另起一个 TextView
            FileIconView icon = new FileIconView(act, FileListing.iconTypeOf(e));
            // 图标是纯装饰：它表达的信息（文件夹/文件/软链）已经并入行的 contentDescription，
            // 再让它单独可聚焦只会多一次无意义的朗读。
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // 与 44dp 的触摸目标配套：图标从 18dp 提到 20dp，行变高后视觉上不显空。
            int iconBox = DshUi.dp(act, 20);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconBox, iconBox);
            iconLp.rightMargin = DshUi.dp(act, 10);
            // FileIconView.onMeasure 里写死了 18dp 的自测尺寸（那个文件不在本次改动范围），
            // 只把 LayoutParams 调到 20dp 是**看不出效果**的；
            // 等比缩放 20/18 让实际绘制尺寸跟上。矢量绘制放大不糊，
            // 图形四周本来就有 14% 留白，外扩的 1dp 不会碰到文字。
            final float iconScale = 20f / 18f;
            icon.setScaleX(iconScale);
            icon.setScaleY(iconScale);
            row.addView(icon, iconLp);

            TextView name = new TextView(act);
            name.setText(e.name);
            name.setTextSize(12.5f);
            name.setTextColor(e.dir ? DshUi.TEXT : DshUi.TEXT_2);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            // 名称与说明是行内容的视觉拆分，无障碍上已由行的 contentDescription
            // 一并报出；再让它们各自可聚焦，TalkBack 会把同一行念三遍。
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            TextView info = new TextView(act);
            info.setText(FileListing.infoText(e));
            info.setTextSize(10.5f);
            info.setTextColor(DshUi.TEXT_3);
            info.setSingleLine(true);
            info.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.setGravity(Gravity.END);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            // 名称 : 说明 = 1 : 0.85（约 54% : 46%），两者都不会把对方挤没
            row.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f);
            infoLp.leftMargin = DshUi.dp(act, 8);
            row.addView(info, infoLp);

            // 行背景：每行 1 个轻量 drawable（旧实现是每行 3 个 GradientDrawable）
            row.setBackground(new RowBgDrawable(rowRadius));
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
                    showItemMenu(e);
                    return true;
                }
            });
            return row;
        }
    }

    /**
     * 列表行的背景：默认透明，按下/聚焦时淡淡一层。
     *
     * <p>为什么不用 {@link DshUi#rowBg}：那是 StateListDrawable，
     * <b>每调用一次就新建 3 个 GradientDrawable</b>（按下态、聚焦态、常态各一个）。
     * 列表上限 {@link FileListing#LIST_CAP} = 800 行，等于 2400 个 drawable
     * 在主线程分配，而它们除了状态之外长得一模一样 —— 纯浪费。
     * 这里每行只建 1 个对象，画笔与矩形还是静态共享的。
     *
     * <p><b>但必须每行一个实例，不能全列表共用一个。</b>
     * Drawable 的状态是**实例级字段**（{@code setState} 存的就是"当前是否按下"）：
     * 共用一份的话，某一行被按下后所有行重绘时都会读到"按下"，整片列表一起高亮；
     * 而且 {@code setCallback} 只认最后一个 View，按下行的重绘通知会发给别的行。
     * 状态必须跟着行走 —— 这正是原先"每行 new 一个"的原因，这里只是把它做轻。
     *
     * <p>配色与圆角与 {@code DshUi.rowBg} 一致（按下/聚焦同色、8dp 圆角），
     * 换的是实现，不是外观。
     */
    private static final class RowBgDrawable extends Drawable {

        /**
         * 画笔与矩形静态共享。
         *
         * <p>Drawable 的绘制发生在 UI 线程的绘制阶段，且是顺序的
         * （一个画完再画下一个），共享一份临时对象不会互相踩；
         * 换来的是"画一帧零分配"——800 行滚动时这条路径每帧都要走。
         */
        private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final RectF BOX = new RectF();

        static { PAINT.setColor(DshUi.BTN_PRESS); }

        private final float radius;

        RowBgDrawable(float radiusPx) {
            radius = radiusPx;
        }

        @Override public void draw(Canvas cv) {
            // 常态是透明的：连一次绘制都省掉 —— 800 行里绝大多数时候走的就是这条路
            if (!isActive()) return;
            BOX.set(getBounds());
            cv.drawRoundRect(BOX, radius, radius, PAINT);
        }

        /** 当前状态是否需要画底色（按下或聚焦）。 */
        private boolean isActive() {
            int[] st = getState();
            for (int i = 0; i < st.length; i++) {
                if (st[i] == android.R.attr.state_pressed) return true;
                if (st[i] == android.R.attr.state_focused) return true;
            }
            return false;
        }

        /**
         * 状态变化时重画自己。
         *
         * <p>{@link #isStateful} 必须返回 true —— 否则 View 在
         * {@code drawableStateChanged} 里根本不会把新状态传下来。
         */
        @Override protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override public boolean isStateful() { return true; }

        @Override public void setAlpha(int alpha) { }

        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * 把系统进度条染成品牌色。
     *
     * <p>不定量进度条的默认颜色来自主题（Material 主题下偏紫），
     * 与 DSH 的强调色放在一起明显不是一套配色；
     * 给 indeterminate drawable 上 SRC_IN 滤镜是最省事且不改主题的做法。
     * 取不到 drawable 就保持原样 —— 这只是观感，不值得为它崩界面。
     */
    private static void tintProgress(ProgressBar pb) {
        try {
            Drawable ind = pb.getIndeterminateDrawable();
            if (ind != null) {
                ind.setColorFilter(DshUi.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) { }
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
        // 写入白名单：只有应用自己的目录可增删改，其余位置只读。
        // 刻意不含 / 与整个 /sdcard —— 那些位置含系统文件与其它应用数据。
        b.writeRoots.addAll(FileOps.writableRoots(appDir));
        b.cwd = appDir != null && appDir.isDirectory() ? appDir : b.roots.get(0).dir;
        DshUi.log("打开文件浏览: " + b.cwd.getAbsolutePath()
                + "（可用位置 " + b.roots.size() + " 个）");

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "文件浏览"));

        // 路径：单行 + 中间省略。深路径换行会把列表往下挤。
        b.pathView.setTextSize(11f);
        b.pathView.setTypeface(Typeface.MONOSPACE);
        b.pathView.setTextColor(DshUi.TEXT_2);
        b.pathView.setSingleLine(true);
        b.pathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(b.pathView, DshUi.fullWidth(act, GAP_TITLE));
        body.addView(b.meta, DshUi.fullWidth(act, GAP_META));

        // 常用位置
        LinearLayout rootRow = new LinearLayout(act);
        rootRow.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(rootRow, DshUi.fullWidth(act, GAP_ROOTS));
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
        body.addView(b.crumbScroll, DshUi.fullWidth(act, GAP_CRUMB));

        // 列表
        b.listBox.setOrientation(LinearLayout.VERTICAL);
        // 白卡片 + 行间细分割线（与 DSH 的卡片语言一致）。
        // 原先是灰底 + 2dp 外边距，行与行糊在一起、没有节奏感。
        b.listBox.setBackground(DshUi.cardBg(act));
        b.listBox.setPadding(0, DshUi.dp(act, 4), 0, DshUi.dp(act, 4));
        b.listBox.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        b.listBox.setDividerDrawable(DshUi.divider(act));
        int pad = DshUi.dp(act, 6);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(b.listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 列表区外面套一层 FrameLayout：导航时要在列表**上面**盖一层遮罩 + 转圈。
        // 不能直接给 ScrollView 加子 View（ScrollView 只接受一个子 View），
        // 也不能把它加进外层纵向布局（那会占掉一块真实高度，把列表挤扁）。
        FrameLayout listArea = new FrameLayout(act);
        listArea.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 遮罩：可点 → 落在它范围内的触摸被它消费，不会穿透到下面那些**已过期**的行。
        // 没有它的话，读大目录的几百毫秒里点第一行，打开的是上一个目录的同名文件。
        b.loadingMask = new View(act);
        b.loadingMask.setClickable(true);
        b.loadingMask.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 只为吃掉触摸，不做任何事（点空白处不该有任何副作用）
            }
        });
        b.loadingMask.setVisibility(View.GONE);
        listArea.addView(b.loadingMask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        b.loadingSpin = new ProgressBar(act);
        tintProgress(b.loadingSpin);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(
                DshUi.dp(act, 32), DshUi.dp(act, 32));
        spinLp.gravity = Gravity.CENTER;
        b.loadingSpin.setVisibility(View.GONE);
        listArea.addView(b.loadingSpin, spinLp);

        // 列表与面包屑之间必须留白：原先这里是 0，两者挤在一起（实测仅 3dp）
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, GAP_LIST);
        body.addView(listArea, slp);

        // 底部：隐藏文件开关 + 刷新 + 关闭
        b.hiddenToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.showHidden = !b.showHidden;
                // 立刻落盘：这两个开关跨次打开要保留，不能只在内存里活着
                b.prefs.edit().putBoolean(KEY_HIDDEN, b.showHidden).apply();
                b.refresh();
            }
        });
        b.sortToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                b.sortMode = FileListing.nextSortMode(b.sortMode);
                b.prefs.edit().putInt(KEY_SORT, b.sortMode).apply();
                b.refresh();
            }
        });
        Button newBtn = DshUi.button(act, "新建", false);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!b.cwdWritable()) {
                    DshUi.toast(act, "该位置只读，不能新建");
                    return;
                }
                b.promptName("新建文件夹", "", new Browser.NameSink() {
                    @Override public void onName(String name) {
                        String err = FileOps.mkdir(b.cwd, name, b.writeRoots);
                        if (err == null) {
                            DshUi.toast(act, "已创建 " + name);
                            b.refresh();
                        } else {
                            DshUi.toast(act, err);
                        }
                    }
                });
            }
        });
        Button refreshBtn = DshUi.button(act, "刷新", false);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { b.refresh(); }
        });
        Button close = DshUi.button(act, "关闭", true);

        final Dialog dlg = DshUi.dialogFill(act, body,
                DshUi.footer(act, newBtn, b.hiddenToggle, b.sortToggle, refreshBtn, close), 820);
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
        b.reportLayout(dlg, listArea, rootRow, b.crumbScroll);
    }
}
