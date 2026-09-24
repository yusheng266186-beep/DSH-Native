package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 插件管理面板：查看、启用、安装。
 *
 * <h3>机制（已端到端验证）</h3>
 * <pre>
 * npm install --prefix &lt;DSH_HOME&gt;/profiles/web &lt;spec&gt;
 *   → profiles/web/node_modules/&lt;name&gt;     ← DSH 从这里解析插件
 * 名字写进 --patch 覆盖层 → 下次启动加载
 * </pre>
 * 实测打印出 {@code [PLUGIN-LOADED]}，确认插件真的被 apply。
 *
 * <p>不用 DSH 自带的 {@code dsh plugin add}：它依赖 pnpm，
 * 而运行包里没有（也不值得为它多带 46MB）。npm 已在运行包里，
 * 且 profile 用的是 {@code nodeLinker: hoisted} —— 与 npm 的默认行为一致，落点相同。
 *
 * <h3>两个安全点</h3>
 * <ol>
 *   <li>插件规格是**用户输入**，会被用来调用 npm：校验交给
 *       {@link PluginSpecs#validateSpec}（64 项测试，专门挡命令注入）。</li>
 *   <li>启用前必须确认插件**真的解析得到** —— 引用不存在的插件
 *       会让 DSH 整个启动失败。</li>
 * </ol>
 *
 * <h3>安装 = 在本机执行第三方代码，必须先问一句</h3>
 * {@code npm install} 会执行包自带的 install / postinstall 脚本，
 * 那是与本应用同权限的本机代码。因此安装前用 {@link DshUi#confirm} 让用户
 * 看清「装的是什么、从哪来」，而不是点一下就静默执行。
 *
 * <h3>关掉面板 = 终止任务</h3>
 * 面板关闭时依次做三件事：置 {@code closed} 标记（此后所有 {@code ui.post}
 * 回调自行放弃，不再改已经消失的视图、也不再弹归属不明的提示）→
 * 杀掉 npm 进程（否则它会带着 postinstall 继续联网、继续写 node_modules）→
 * {@code io.shutdown()}（**不能**用 {@code shutdownNow}，它会排空尚未开始的任务）。
 */
public final class PluginPanel {

    /**
     * npm 安装的总时限。
     *
     * <p>3 分钟：够慢速网络装完一个小插件，又能挡住「卡住不输出也不退出」的
     * postinstall —— 没有时限时它会一直挂着，用户永远等不到结果。
     */
    private static final long INSTALL_TIMEOUT_MS = 180000L;

    private PluginPanel() { }

    /** 宿主需要提供的路径与回调。 */
    public interface Host {
        /** DSH 安装目录（内置插件所在）。 */
        File dshDir();
        /** 应用私有根。 */
        File root();
        /** 工具链目录（npm 所在）。 */
        File toolsDir();
        /** Node 可执行文件。 */
        File node();
        /** 当前选择（会被修改并持久化）。 */
        Set<String> selection();
        /** 保存选择。 */
        void saveSelection(Set<String> names);
    }

    /** 打开发布说明面板。 */
    public static void show(final Activity act, final Host host) {
        final File profileDir = new File(new File(host.root(), ".dsh"), "profiles/web");
        final File profileModules = new File(profileDir, "node_modules");
        final File builtinModules = new File(host.dshDir(), "node_modules");

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "插件"));

        final TextView hint = DshUi.hint(act,
                "支持 npm 包名、GitHub 简写（owner/repo）与绝对路径。"
                + "安装会在本机执行该包的安装脚本，请只安装可信来源。启用后需重启 App 生效。");
        body.addView(hint, DshUi.fullWidth(act, 6));

        // ── 推荐（只放实测装得上的）──
        body.addView(DshUi.sectionLabel(act, "推荐"), DshUi.fullWidth(act, 14));
        LinearLayout recBox = new LinearLayout(act);
        recBox.setOrientation(LinearLayout.VERTICAL);
        recBox.setBackground(DshUi.cardBg(act));
        recBox.setPadding(0, DshUi.dp(act, 4), 0, DshUi.dp(act, 4));
        recBox.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        recBox.setDividerDrawable(DshUi.divider(act));
        body.addView(recBox, DshUi.fullWidth(act, 6));

        // ── 安装 ──
        final EditText spec = DshUi.input(act, "", false);
        spec.setHint("npm 包名，如 dsh-foo 或 @scope/pkg");
        spec.setSingleLine(true);
        body.addView(spec, DshUi.fullWidth(act, 10));

        final Button install = DshUi.button(act, "安装", false);
        body.addView(install, DshUi.fullWidth(act, 6));

        // 主按钮与推荐列表里的每个「安装」都要一起进入忙碌态：
        // 之前只有主按钮被 setEnabled(false)，推荐项的按钮毫无变化 ——
        // 第一个 npm 还在跑时点第二个，两次安装会并发写同一个 node_modules。
        final List<Button> installButtons = new ArrayList<Button>();
        installButtons.add(install);

        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setBackground(DshUi.cardBg(act));
        listBox.setPadding(0, DshUi.dp(act, 4), 0, DshUi.dp(act, 4));
        listBox.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        listBox.setDividerDrawable(DshUi.divider(act));

        ScrollView scroll = new ScrollView(act);
        scroll.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 10);
        body.addView(scroll, slp);

        Button close = DshUi.button(act, "关闭", true);
        final Dialog dlg = DshUi.dialogFill(act, body, DshUi.footer(act, close), 820);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        final Handler ui = new Handler(Looper.getMainLooper());
        final ExecutorService io = Executors.newSingleThreadExecutor();

        // 关闭标记。onDismiss 里的 removeCallbacksAndMessages 只能清掉**当时已入队**
        // 的消息，后台线程此后新 post 的回调照样会执行 —— 那会继续 setText/addView，
        // 甚至在面板消失几秒后弹出「安装失败：null」。所以每个回调都要自己看这个标记。
        final boolean[] closed = {false};

        // npm 进程句柄。用 AtomicReference 而不是普通数组：它由后台线程写入、
        // 由主线程（onDismiss）读取，两者之间没有 happens-before 边缘 ——
        // 普通字段有可能读到 null，于是「关面板就杀进程」会静默失效。
        final AtomicReference<Process> procRef = new AtomicReference<Process>();

        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                closed[0] = true;
                Process p = procRef.get();
                if (p != null) {
                    // 必须杀进程，而不只是中断线程：线程几乎总是阻塞在管道的
                    // readLine() 上，而管道读**不可中断**。只关面板的话，
                    // npm 与它的 postinstall 会继续联网下载、继续写 node_modules。
                    killQuietly(p);
                    DshUi.log("插件面板已关闭，已终止 npm 进程");
                }
                // shutdown 而不是 shutdownNow：shutdownNow 会**排空尚未开始**的任务，
                // 用户看到的就是「点了没反应 —— 没提示、没日志、没异常」。
                // shutdown 只停止接收新任务，已入队的照常跑完。
                io.shutdown();
                ui.removeCallbacksAndMessages(null);
            }
        });

        // 推荐项的安装按钮与手动安装走同一条路径
        final Runnable[] doInstall = new Runnable[1];

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            @Override public void run() {
                listBox.removeAllViews();
                Set<String> sel = host.selection();

                // 内置可选插件
                for (String name : PluginSpecs.builtinPlugins()) {
                    boolean present = new File(builtinModules, name).isDirectory();
                    listBox.addView(buildRow(act, name, present ? "内置" : "运行包中缺失",
                                    present, sel.contains(name), host, refresh[0]),
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                // 用户安装的
                List<String> installed = PluginSpecs.installedPlugins(profileDir);
                for (String name : installed) {
                    // 把类型告诉用户：两种类型的启用方式不同，
                    // 出问题时这是第一个要看的线索
                    File dir = new File(profileModules, name);
                    String kind = PluginSpecs.pluginKind(dir) == PluginSpecs.KIND_BUNDLE
                            ? "已安装 · bundle" : "已安装";
                    listBox.addView(buildRow(act, name, kind, true,
                                    sel.contains(name), host, refresh[0]),
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (installed.isEmpty()) {
                    TextView t = DshUi.hint(act, "尚未安装第三方插件。"
                            + "可在上方填入 npm 包名安装（社区插件见 awesome-dsh-plugin 清单）。");
                    t.setPadding(DshUi.dp(act, 12), DshUi.dp(act, 10),
                            DshUi.dp(act, 12), DshUi.dp(act, 10));
                    listBox.addView(t);
                }
            }
        };

        // 已校验、待确认的安装规格。校验放在确认**之前**：
        // 确认框里要写清「解析后的包名与来源」，非法输入根本走不到确认这一步。
        final String[] pending = new String[1];
        final Runnable[] installNow = new Runnable[1];

        // 真正执行安装（只在用户确认之后调用）
        installNow[0] = new Runnable() {
            @Override public void run() {
                final String name = pending[0];
                // 面板已关或执行器已停：不再启动后台任务。
                // 这里必须自己判 —— DshUi.confirm 会吞掉 onConfirm 的异常，
                // 已 shutdown 的池提交任务抛 RejectedExecutionException，
                // 表现就又回到「点了没反应」。
                if (name == null || closed[0] || io.isShutdown()) return;

                for (int i = 0; i < installButtons.size(); i++) {
                    DshUi.setBusy(installButtons.get(i), "安装", "安装中…", true);
                }
                hint.setText("正在安装 " + name + " …（可能需要一会儿）");

                io.execute(new Runnable() {
                    @Override public void run() {
                        String m;
                        boolean ok = false;
                        try {
                            String out = runNpmInstall(host, profileDir, name, procRef);
                            ok = true;
                            m = "已安装 " + name;
                            DshUi.log("插件安装成功: " + name + "\n" + out);
                        } catch (Throwable t) {
                            // getMessage() 可能是 null（线程被中断、进程被销毁等），
                            // 直接拼接就会出现「安装失败：null」——
                            // 那是用户唯一能看到的错误信息，必须有内容。
                            m = "安装失败：" + errText(t);
                            DshUi.log("插件安装失败: " + name + " → " + t);
                        }
                        final String msg = m;
                        final boolean good = ok;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                // 面板已关：不再改它的视图，也不再弹归属不明的提示。
                                // 日志在上面那段里已经记过，排查不受影响。
                                if (closed[0]) return;
                                for (int i = 0; i < installButtons.size(); i++) {
                                    DshUi.setBusy(installButtons.get(i), "安装", "安装中…", false);
                                }
                                hint.setText(msg + (good ? "　请在下方列表中勾选启用" : ""));
                                if (good) {
                                    spec.setText("");
                                    refresh[0].run();
                                }
                                DshUi.toast(act, msg);
                            }
                        });
                    }
                });
            }
        };

        doInstall[0] = new Runnable() {
            @Override public void run() {
                final String raw = spec.getText() == null ? "" : spec.getText().toString().trim();
                String bad = PluginSpecs.validateSpec(raw);
                if (bad != null) {
                    DshUi.toast(act, bad);
                    return;
                }
                pending[0] = raw;
                // npm install 会执行该包自带的 install / postinstall 脚本 ——
                // 那是与本应用同权限的本机代码。装之前必须让用户看清装的是什么、
                // 从哪来；推荐列表里的「安装」也走这里，不能因为「是我们推荐的」
                // 就跳过确认（推荐项同样来自第三方仓库）。
                String resolved = PluginSpecs.baseName(raw);
                String body = "安装规格：" + raw + "\n"
                        + "来源：" + describeSource(raw) + "\n"
                        + (resolved.equals(raw) ? "" : "解析后的包名：" + resolved + "\n")
                        + "安装过程会在本机执行该包自带的 install / postinstall 脚本，"
                        + "脚本拥有与本应用相同的权限。请只安装可信来源。";
                DshUi.confirm(act, "安装插件？", body, "安装", new Runnable() {
                    @Override public void run() { installNow[0].run(); }
                });
            }
        };
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doInstall[0].run(); }
        });

        // 构建推荐项
        for (final PluginSpecs.Recommended r : PluginSpecs.recommended()) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rp = DshUi.dp(act, 12);
            row.setPadding(rp, rp, rp, rp);

            LinearLayout text = new LinearLayout(act);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(act);
            t.setText(r.title);
            t.setTextSize(12.5f);
            t.setTextColor(DshUi.TEXT());
            t.setSingleLine(true);
            text.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView d = DshUi.hint(act, r.desc);
            d.setTextSize(10.5f);
            d.setSingleLine(true);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(d, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(text, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final Button b = DshUi.toggleButton(act, "安装", false);
            installButtons.add(b);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    spec.setText(r.spec);
                    doInstall[0].run();
                }
            });
            row.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            recBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        dlg.show();
        refresh[0].run();
    }

    /** 一行插件：名称 + 来源，右侧启用开关。 */
    private static View buildRow(final Activity act, final String name, final String source,
                                 final boolean present, final boolean enabled,
                                 final Host host, final Runnable refresh) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = DshUi.dp(act, 12);
        row.setPadding(p, p, p, p);

        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);

        TextView n = new TextView(act);
        n.setText(name.contains("/") ? name.substring(name.indexOf('/') + 1) : name);
        n.setTextSize(12.5f);
        n.setTextColor(present ? DshUi.TEXT() : DshUi.TEXT_3());
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        text.addView(n, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView s = DshUi.hint(act, source);
        s.setTextSize(10.5f);
        text.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Button toggle = DshUi.toggleButton(act, enabled ? "已启用" : "启用", enabled);
        toggle.setEnabled(present);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Set<String> sel = new HashSet<String>(host.selection());
                if (sel.contains(name)) sel.remove(name); else sel.add(name);
                host.saveSelection(sel);
                refresh.run();
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * 执行 npm 安装。
     *
     * <p>用 npm（运行包自带）而不是 {@code dsh plugin add}（需要 pnpm）。
     * 以 {@code --prefix} 指向 profile，包会落到
     * {@code profiles/web/node_modules/} —— 与 DSH 的解析位置一致。
     *
     * <p>命令用**数组形式**传入（不经过 shell），参数已经过
     * {@link PluginSpecs#validateSpec} 校验。
     *
     * @param procRef 出参，回传进程句柄 —— 面板关闭时要能杀掉它
     */
    private static String runNpmInstall(Host host, File profileDir, String spec,
                                       AtomicReference<Process> procRef) throws Exception {
        // 防御性检查：宿主引用可能尚未就绪（启动未完成）。
        // 明确抛出比 NPE 好 —— NPE 的信息对用户毫无意义。
        if (host.node() == null || host.toolsDir() == null || host.root() == null) {
            throw new Exception("运行环境尚未就绪");
        }
        File npmCli = new File(host.toolsDir(),
                "lib/node_modules/npm/bin/npm-cli.js");
        if (!npmCli.isFile()) throw new Exception("未找到 npm（运行包不完整）");
        if (!profileDir.isDirectory() && !profileDir.mkdirs()) {
            throw new Exception("无法创建 profile 目录");
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(host.node().getAbsolutePath());
        cmd.add(npmCli.getAbsolutePath());
        cmd.add("install");
        cmd.add("--prefix");
        cmd.add(profileDir.getAbsolutePath());
        cmd.add("--no-audit");
        cmd.add("--no-fund");
        cmd.add("--loglevel=error");
        cmd.add(spec);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(profileDir);
        pb.redirectErrorStream(true);
        // npm 在 postinstall 里会调 node，因此 node 的目录必须在 PATH 上
        String path = host.node().getParentFile().getAbsolutePath()
                + ":" + new File(host.toolsDir(), "bin").getAbsolutePath()
                + ":" + System.getenv("PATH");
        pb.environment().put("PATH", path);
        pb.environment().put("HOME", host.root().getAbsolutePath());
        pb.environment().put("npm_config_prefix", host.toolsDir().getAbsolutePath());
        pb.environment().put("LC_ALL", "C");

        final Process proc = pb.start();
        // 立刻交给调用方：面板随时可能被关掉，句柄必须在那之前就可见
        procRef.set(proc);

        // 超时看门狗。
        //
        // 为什么不能只给 waitFor 加时限：线程绝大多数时间阻塞在 r.readLine() 上，
        // 而管道读不可中断。npm 一旦卡在下载或 postinstall（既不再输出、也不退出），
        // 读循环永远不会返回，waitFor 的时限根本轮不到执行。
        // 所以从启动时刻起算，到点直接杀进程 —— 流一关，读循环自然退出。
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(INSTALL_TIMEOUT_MS); }
                catch (InterruptedException e) { return; }   // 正常结束时会打断它
                if (done.get()) return;
                timedOut.set(true);
                DshUi.log("npm 安装超过 " + (INSTALL_TIMEOUT_MS / 1000) + " 秒，强制终止");
                killQuietly(proc);
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (out.length() < 20000) out.append(line).append('\n');
            }
            r.close();

            // 看门狗已经杀过的话这里会立刻返回；这一层时限只是兜底
            // （例如孙进程仍持有管道，导致进程退出了但流没关）。
            boolean exited;
            try {
                exited = proc.waitFor(30, TimeUnit.SECONDS);
            } catch (Throwable t) {
                // API 26 以下没有带时限的重载；此时读循环已退出，
                // 且看门狗保证进程最迟在 INSTALL_TIMEOUT_MS 时被杀，不会真等死
                proc.waitFor();
                exited = true;
            }
            if (timedOut.get()) {
                throw new Exception("npm 在 " + (INSTALL_TIMEOUT_MS / 1000)
                        + " 秒内没有结束，已强制终止（可能是网络过慢或安装脚本卡住）\n"
                        + tail(out.toString(), 300));
            }
            if (!exited) {
                killQuietly(proc);
                throw new Exception("输出已结束但 npm 进程 30 秒内未退出，已强制终止\n"
                        + tail(out.toString(), 300));
            }
            int code = proc.exitValue();
            if (code != 0) {
                throw new Exception("npm 退出码 " + code + "\n" + tail(out.toString(), 300));
            }
            return tail(out.toString(), 200);
        } finally {
            done.set(true);
            watchdog.interrupt();
            // 句柄清空：安装已结束，面板再关闭时不必（也不该）去杀一个旧进程
            procRef.compareAndSet(proc, null);
        }
    }

    /**
     * 尽力终止进程。
     *
     * <p>{@code destroyForcibly()} 从 API 26 才有，低版本上会抛
     * {@code NoSuchMethodError} —— 那是 Error，不接住就会把整个后台线程带走，
     * 用户只看到「安装中…」永远停在那里。回退到 {@code destroy()}。
     */
    private static void killQuietly(Process p) {
        if (p == null) return;
        try {
            p.destroyForcibly();
        } catch (Throwable t) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 异常文案。
     *
     * <p>{@code getMessage()} 为 null 时退化到异常类名：中断、进程被销毁这类
     * 情况下 message 就是 null，直接拼接只会得到「安装失败：null」。
     */
    private static String errText(Throwable t) {
        if (t == null) return "未知错误";
        String msg = t.getMessage();
        if (msg == null || msg.trim().length() == 0) return t.getClass().getSimpleName();
        return msg;
    }

    /**
     * 这个规格会从哪里取包 —— 二次确认的正文必须写清来源。
     *
     * <p>只写「将执行安装脚本」而不写来源，用户无从判断是否可信；
     * 而「npm 包名」与「GitHub 仓库」的可信度判断方式完全不同。
     */
    private static String describeSource(String spec) {
        if (spec.startsWith("/")) return "本地路径（不联网，但脚本照常执行）";
        if (spec.startsWith("http://") || spec.startsWith("https://")
                || spec.startsWith("git+") || spec.startsWith("git://")
                || spec.startsWith("ssh://") || spec.startsWith("github:")
                || spec.startsWith("gitlab:") || spec.startsWith("bitbucket:")) {
            return "远程 git 仓库（" + spec + "）";
        }
        // owner/repo 简写。npm 包名不含 /，除非是 @scope/name（以 @ 开头）
        if (spec.indexOf('/') > 0 && !spec.startsWith("@")) return "GitHub 仓库 " + spec;
        return "npm registry（registry.npmjs.org）";
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
