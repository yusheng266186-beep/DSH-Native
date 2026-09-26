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
 * <p>打包与安全校验在纯逻辑类 {@link ConfigBackup} 里（40 项离线测试，
 * 含构造恶意 zip 验证的 zip-slip 防护）。
 *
 * <p>新版备份默认使用口令加密；旧版明文 ZIP 仍可恢复，但会明确标识风险。
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

        TextView warn = DshUi.hint(act, "备份包含账户密钥。新导出的 .dshbak 文件会使用"
                + "口令加密；请牢记口令，遗失后无法恢复。旧版 ZIP 会标为明文。");
        warn.setTextColor(DshUi.WARN());
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
                askNewPassword(act, "设置备份口令",
                        "口令至少 8 位。它不会保存到 App 中，请自行牢记。",
                        new PasswordAction() {
                    @Override public void run(final char[] password) {
                        if (closed[0] || io.isShutdown()) {
                            java.util.Arrays.fill(password, '\0');
                            return;
                        }
                        DshUi.setBusy(export, "导出", "导出中…", true);
                        status.setText("正在加密导出…");
                        io.execute(new Runnable() {
                            @Override public void run() {
                                String message;
                                boolean good = false;
                                try {
                                    File out = new File(backupDir,
                                            ConfigBackup.fileName(System.currentTimeMillis()));
                                    int n = ConfigBackup.exportEncrypted(dshHome, out, password);
                                    good = n > 0;
                                    message = good ? "已加密导出 " + n + " 个文件　" + out.getName()
                                            : "没有可导出的配置文件";
                                    if (good) DshUi.log("加密配置已导出: " + out.getAbsolutePath());
                                } catch (Throwable t) {
                                    message = "导出失败：" + readable(t);
                                    DshUi.log("配置导出失败: " + t);
                                } finally {
                                    java.util.Arrays.fill(password, '\0');
                                }
                                final String result = message;
                                final boolean ok = good;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (closed[0]) return;
                                        DshUi.setBusy(export, "导出", "导出中…", false);
                                        status.setText(result);
                                        DshUi.toast(act, ok ? "加密备份已保存" : result);
                                        if (ok) refresh[0].run();
                                    }
                                });
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
        name.setText(f.getName().replace("dsh-config-", "")
                .replace(".dshbak", "").replace(".zip", ""));
        name.setTextSize(12.5f);
        name.setTextColor(DshUi.TEXT());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView meta = new TextView(act);
        meta.setText(ConfigBackup.describe(f));
        meta.setTextSize(10.5f);
        meta.setTextColor(DshUi.TEXT_3());
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
    private static void confirmRestore(final Activity act, final File backup, final Dialog parent,
                                       final File dshHome, final ExecutorService io,
                                       final Handler ui, final boolean[] closed) {
        if (ConfigBackup.isEncrypted(backup)) {
            askPassword(act, "输入备份口令", "解密并验证这份备份。",
                    new PasswordAction() {
                @Override public void run(char[] password) {
                    String bad = ConfigBackup.validateEncrypted(backup, password);
                    if (bad != null) {
                        java.util.Arrays.fill(password, '\0');
                        DshUi.toast(act, "无法解密：" + bad);
                        return;
                    }
                    confirmRestoreReady(act, backup, parent, dshHome, io, ui, closed,
                            password, true);
                }
            });
            return;
        }

        String bad = ConfigBackup.validate(backup);
        if (bad != null) {
            DshUi.toast(act, "无法使用：" + bad);
            return;
        }
        // 旧 ZIP 没有口令；先让用户为恢复前的安全副本设置一个新口令。
        askNewPassword(act, "旧版明文备份",
                "这份 ZIP 未加密。恢复前将创建一份加密安全副本，请设置口令。",
                new PasswordAction() {
            @Override public void run(char[] password) {
                confirmRestoreReady(act, backup, parent, dshHome, io, ui, closed,
                        password, false);
            }
        });
    }

    private static void confirmRestoreReady(final Activity act, final File backup,
                                            final Dialog parent, final File dshHome,
                                            final ExecutorService io, final Handler ui,
                                            final boolean[] closed, final char[] password,
                                            final boolean encrypted) {
        DshUi.confirm(act, "恢复这份配置？",
                backup.getName() + "\n"
              + "将覆盖当前的账户密钥与模型配置。\n"
              + "恢复前会自动创建一份加密安全副本。\n"
              + "恢复后需要重启 App 才会生效。",
                "恢复", new Runnable() {
                    @Override public void run() {
                        if (closed[0] || io.isShutdown()) {
                            java.util.Arrays.fill(password, '\0');
                            return;
                        }
                        io.execute(new Runnable() {
                            @Override public void run() {
                                String message;
                                try {
                                    ConfigBackup.safetyCopy(dshHome, backup.getParentFile(),
                                            System.currentTimeMillis(), password);
                                    int n = encrypted
                                            ? ConfigBackup.restoreEncrypted(backup, dshHome, password)
                                            : ConfigBackup.restoreFrom(backup, dshHome);
                                    message = "已恢复 " + n + " 个文件，重启后生效";
                                    DshUi.log("配置已恢复: " + backup.getName()
                                            + " → " + n + " 个文件");
                                } catch (Throwable t) {
                                    message = "恢复失败：" + readable(t);
                                    DshUi.log("配置恢复失败: " + t);
                                } finally {
                                    java.util.Arrays.fill(password, '\0');
                                }
                                final String result = message;
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        DshUi.toast(act, result);
                                    }
                                });
                            }
                        });
                        parent.dismiss();
                    }
                }, new Runnable() {
                    @Override public void run() {
                        java.util.Arrays.fill(password, '\0');
                    }
                });
    }

    private interface PasswordAction {
        void run(char[] password);
    }

    private static void askNewPassword(final Activity act, String title, String description,
                                       final PasswordAction action) {
        final LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, title));
        body.addView(DshUi.hint(act, description), DshUi.fullWidth(act, 8));
        body.addView(DshUi.label(act, "口令"), DshUi.fullWidth(act, 8));
        final android.widget.EditText first = DshUi.input(act, "", true);
        body.addView(first, DshUi.fullWidth(act, 6));
        body.addView(DshUi.label(act, "再次输入"), DshUi.fullWidth(act, 8));
        final android.widget.EditText second = DshUi.input(act, "", true);
        body.addView(second, DshUi.fullWidth(act, 6));
        final TextView error = DshUi.hint(act, "");
        error.setTextColor(DshUi.WARN());
        body.addView(error, DshUi.fullWidth(act, 4));

        Button cancel = DshUi.button(act, "取消", false);
        Button next = DshUi.button(act, "继续", true);
        final Dialog dialog = DshUi.dialog(act, body, DshUi.footer(act, cancel, next), 460);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String a = first.getText().toString();
                String b = second.getText().toString();
                if (a.length() < 8) {
                    error.setText("口令至少 8 位");
                    return;
                }
                if (!a.equals(b)) {
                    error.setText("两次输入不一致");
                    return;
                }
                char[] password = a.toCharArray();
                first.setText("");
                second.setText("");
                dialog.dismiss();
                action.run(password);
            }
        });
        dialog.show();
    }

    private static void askPassword(final Activity act, String title, String description,
                                    final PasswordAction action) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, title));
        body.addView(DshUi.hint(act, description), DshUi.fullWidth(act, 8));
        final android.widget.EditText input = DshUi.input(act, "", true);
        body.addView(input, DshUi.fullWidth(act, 8));
        final TextView error = DshUi.hint(act, "");
        error.setTextColor(DshUi.WARN());
        body.addView(error, DshUi.fullWidth(act, 4));
        Button cancel = DshUi.button(act, "取消", false);
        Button next = DshUi.button(act, "继续", true);
        final Dialog dialog = DshUi.dialog(act, body, DshUi.footer(act, cancel, next), 380);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String value = input.getText().toString();
                if (value.length() < 8) {
                    error.setText("口令至少 8 位");
                    return;
                }
                char[] password = value.toCharArray();
                input.setText("");
                dialog.dismiss();
                action.run(password);
            }
        });
        dialog.show();
    }

    private static String readable(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }
}
