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
 */
public final class PluginPanel {

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
                "支持 npm 包名、GitHub 简写（owner/repo）与绝对路径。启用后需重启 App 生效。");
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

        Button install = DshUi.button(act, "安装", false);
        body.addView(install, DshUi.fullWidth(act, 6));

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
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                io.shutdownNow();
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

        doInstall[0] = new Runnable() {
            @Override public void run() {
                String raw = spec.getText() == null ? "" : spec.getText().toString().trim();
                String bad = PluginSpecs.validateSpec(raw);
                if (bad != null) {
                    DshUi.toast(act, bad);
                    return;
                }
                hint.setText("正在安装 " + raw + " …（可能需要一会儿）");
                install.setEnabled(false);
                final String name = raw;
                io.execute(new Runnable() {
                    @Override public void run() {
                        String m;
                        boolean ok = false;
                        try {
                            String out = runNpmInstall(host, profileDir, name);
                            ok = true;
                            m = "已安装 " + name;
                            DshUi.log("插件安装成功: " + name + "\n" + out);
                        } catch (Throwable t) {
                            m = "安装失败：" + t.getMessage();
                            DshUi.log("插件安装失败: " + name + " → " + t);
                        }
                        final String msg = m;
                        final boolean good = ok;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                hint.setText(msg + (good ? "　请在下方列表中勾选启用" : ""));
                                install.setEnabled(true);
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
            t.setTextColor(DshUi.TEXT);
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
        n.setTextColor(present ? DshUi.TEXT : DshUi.TEXT_3);
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
     */
    private static String runNpmInstall(Host host, File profileDir, String spec)
            throws Exception {
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

        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(proc.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            if (out.length() < 20000) out.append(line).append('\n');
        }
        r.close();
        int code = proc.waitFor();
        if (code != 0) {
            throw new Exception("npm 退出码 " + code + "\n" + tail(out.toString(), 300));
        }
        return tail(out.toString(), 200);
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
