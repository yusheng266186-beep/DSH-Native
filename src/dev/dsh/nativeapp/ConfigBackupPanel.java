package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配置备份与恢复面板。
 *
 * <p>打包与安全校验在纯逻辑类 {@link ConfigBackup} 里（29 项离线测试，
 * 含构造恶意 zip 验证的 zip-slip 防护）。
 *
 * <p>界面上必须讲清的一件事：**备份里含明文密钥**。
 * 导出位置在共享存储，任何应用都能读 —— 不说明就等于替用户做了个危险决定。
 *
 * <h3>关闭面板之后</h3>
 * 面板关闭时置 {@code closed} 标记，所有 {@code ui.post} 回调据此放弃 ——
 * {@code removeCallbacksAndMessages} 只能清掉当时已入队的消息，清不掉
 * 「后台线程此后新 post 的」那部分。
 *
 * <p><b>恢复配置这条路径有个顺序要求</b>：{@code io.execute(...)} 必须在
 * {@code parent.dismiss()} **之前**。onDismiss 里会 {@code io.shutdown()}，
 * 之后再提交任务会被拒绝（RejectedExecutionException）—— 而那里恰好是
 * 「用户点了恢复，却什么都没发生」的来源。
 */
public final class ConfigBackupPanel {

    private ConfigBackupPanel() { }

    /** 打开面板。 */
    public static void show(final Activity act, final File appRoot, final File dshHome) {
        final File backupDir = new File("/sdcard/DSHNative/backup");

        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "配置备份"));

        // 明文密钥的提示放在最显眼的位置，而不是藏在小字里
        TextView warn = DshUi.hint(act, "备份包含账户密钥（明文），导出到共享存储后"
                + "任何应用都能读取，请妥善保管。");
        warn.setTextColor(0xFFB26A00);
        body.addView(warn, DshUi.fullWidth(act, 6));

        final TextView status = DshUi.hint(act, "");
        body.addView(status, DshUi.fullWidth(act, 10));

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
        body.addView(scroll, slp);

        final Button export = DshUi.button(act, "导出", true);
        Button close = DshUi.button(act, "关闭", false);

        final Dialog dlg = DshUi.dialogFill(act, body, DshUi.footer(act, export, close), 780);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        final Handler ui = new Handler(Looper.getMainLooper());
        final ExecutorService io = Executors.newSingleThreadExecutor();

        // 关闭标记：onDismiss 的 removeCallbacksAndMessages 只清得掉**当时已入队**
        // 的消息，后台线程此后新 post 的回调照样会跑 —— 会继续 setText，
        // 并在面板早已消失后弹出「已导出」这类归属不明的提示。
        final boolean[] closed = {false};

        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                closed[0] = true;
                // shutdown 而不是 shutdownNow：shutdownNow 会**排空尚未开始**的任务，
                // 与「先 dismiss 再 execute」一起用，任务会凭空消失 ——
                // 用户看到的是「点了没反应：没提示、没日志、没异常」。
                // shutdown 只停止接收新任务，已入队的照常跑完。
                io.shutdown();
                ui.removeCallbacksAndMessages(null);
            }
        });

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            @Override public void run() {
                listBox.removeAllViews();
                List<File> backups = ConfigBackup.listBackups(backupDir);
                if (backups.isEmpty()) {
                    status.setText("暂无备份。点「导出」会在 "
                            + backupDir.getAbsolutePath() + " 生成一个。");
                    return;
                }
                status.setText("共 " + backups.size() + " 个备份，点任意一项恢复");
                for (final File f : backups) {
                    listBox.addView(buildRow(act, f, dlg, dshHome, io, ui, closed),
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        };

        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 忙碌期禁用并换文案：导出要遍历/打包多个配置，连点会排队导出
                // 好几份一模一样的备份（每份都含明文密钥，落盘就多一份泄露面）。
                DshUi.setBusy(export, "导出", "导出中…", true);
                status.setText("正在导出…");
                io.execute(new Runnable() {
                    @Override public void run() {
                        // 先算后定：final 变量不能在 try 与 catch 里各赋一次
                        String m;
                        boolean good = false;
                        try {
                            File out = new File(backupDir,
                                    ConfigBackup.fileName(System.currentTimeMillis()));
                            int n = ConfigBackup.exportTo(dshHome, out);
                            if (n <= 0) {
                                m = "没有可导出的配置文件";
                            } else {
                                good = true;
                                m = "已导出 " + n + " 个文件　" + out.getName();
                                DshUi.log("配置已导出: " + out.getAbsolutePath());
                            }
                        } catch (Throwable t) {
                            m = "导出失败：" + t.getClass().getSimpleName();
                            DshUi.log("配置导出失败: " + t);
                        }
                        final String msg = m;
                        final boolean ok = good;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                // 面板已关：不再改它的视图、也不再弹「已导出」——
                                // 那种事后提示和用户当下的操作对不上号。
                                if (closed[0]) return;
                                DshUi.setBusy(export, "导出", "导出中…", false);
                                status.setText(msg);
                                DshUi.toast(act, ok ? "已导出到 " + backupDir.getName() + "/" : msg);
                                if (ok) refresh[0].run();
                            }
                        });
                    }
                });
            }
        });

        dlg.show();
        refresh[0].run();
    }

    /** 一行备份：文件名 + 大小时间，点击进入恢复确认。 */
    private static View buildRow(final Activity act, final File f, final Dialog parent,
                                 final File dshHome, final ExecutorService io,
                                 final Handler ui, final boolean[] closed) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = DshUi.dp(act, 12);
        row.setPadding(p, p, p, p);
        row.setBackground(DshUi.rowBg(act));

        TextView name = new TextView(act);
        name.setText(f.getName().replace("dsh-config-", "").replace(".zip", ""));
        name.setTextSize(12.5f);
        name.setTextColor(DshUi.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(act);
        meta.setText(ConfigBackup.describe(f));
        meta.setTextSize(10.5f);
        meta.setTextColor(DshUi.TEXT_3);
        meta.setSingleLine(true);
        meta.setGravity(Gravity.END);
        row.addView(meta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmRestore(act, f, parent, dshHome, io, ui, closed);
            }
        });
        return row;
    }

    /** 恢复确认：说明会覆盖什么、会先存一份当前配置。 */
    private static void confirmRestore(final Activity act, final File zip, final Dialog parent,
                                       final File dshHome, final ExecutorService io,
                                       final Handler ui, final boolean[] closed) {
        final String bad = ConfigBackup.validate(zip);
        if (bad != null) {
            LinearLayout box = DshUi.paddedBody(act);
            box.addView(DshUi.title(act, "恢复这份配置？"));
            box.addView(DshUi.hint(act, "无法使用：" + bad), DshUi.fullWidth(act, 8));
            Button close = DshUi.button(act, "关闭", true);
            final Dialog d = DshUi.dialog(act, box, DshUi.footer(act, close), 300);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            d.show();
            return;
        }
        // 与插件安装走同一个确认入口（DshUi.confirm）：破坏性操作的确认样式与
        // 话术必须一致，用户才知道哪些操作需要小心 —— 此前只有恢复有确认框，
        // 标准不统一比"少一个确认框"更容易让人误判风险。
        DshUi.confirm(act, "恢复这份配置？",
                zip.getName() + "\n"
              + "将覆盖当前的账户密钥与模型配置。\n"
              + "恢复前会自动把当前配置另存一份，以便退回。\n"
              + "恢复后需要重启 App 才会生效。",
                "恢复", new Runnable() {
                    @Override public void run() {
                        // 顺序关键：先入队，再关面板。
                        // parent.dismiss() 会触发 onDismiss 里的 io.shutdown()，
                        // 那之后再提交任务会被拒绝（RejectedExecutionException）——
                        // 恢复就变成"点了没反应"，正是本轮要修的问题之一。
                        // 这里同时显式判 closed / 已停止：确认框的父面板若在别处
                        // 被关掉，也不该再往一个已经停掉的池里提交。
                        if (closed[0] || io.isShutdown()) return;
                        io.execute(new Runnable() {
                            @Override public void run() {
                                String m;
                                try {
                                    // 先给当前配置留一份退路：恢复是覆盖操作，
                                    // 万一这份备份不合用，还能退回原状
                                    ConfigBackup.safetyCopy(dshHome, zip.getParentFile(),
                                            System.currentTimeMillis());
                                    int n = ConfigBackup.restoreFrom(zip, dshHome);
                                    m = "已恢复 " + n + " 个文件，重启后生效";
                                    DshUi.log("配置已恢复: " + zip.getName() + " → " + n + " 个文件");
                                } catch (Throwable t) {
                                    m = "恢复失败：" + t.getClass().getSimpleName();
                                    DshUi.log("配置恢复失败: " + t);
                                }
                                final String msg = m;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        // 这条回调**故意不判 closed**：收起面板本来就是
                                        // 「恢复」这个已确认动作的一部分，而这是唯一的
                                        // 回执渠道 —— 判掉它，用户点完「恢复」就永远
                                        // 收不到结果。它也不碰面板内的任何视图，
                                        // 不会出现"事后改已经消失的界面"。
                                        DshUi.toast(act, msg);
                                    }
                                });
                            }
                        });
                        // 关掉列表面板：恢复期间它的备份清单已经过期
                        parent.dismiss();
                    }
                });
    }
}
