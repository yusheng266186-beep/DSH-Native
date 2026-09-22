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

        Button export = DshUi.button(act, "导出", true);
        Button close = DshUi.button(act, "关闭", false);

        final Dialog dlg = DshUi.dialogFill(act, body, DshUi.footer(act, export, close), 780);
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
                    listBox.addView(buildRow(act, f, dlg, dshHome, io, ui, refresh[0]),
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        };

        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
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
                                 final Handler ui, final Runnable refresh) {
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
                confirmRestore(act, f, parent, dshHome, io, ui, refresh);
            }
        });
        return row;
    }

    /** 恢复确认：说明会覆盖什么、会先存一份当前配置。 */
    private static void confirmRestore(final Activity act, final File zip, final Dialog parent,
                                       final File dshHome, final ExecutorService io,
                                       final Handler ui, final Runnable refresh) {
        final String bad = ConfigBackup.validate(zip);
        LinearLayout box = DshUi.paddedBody(act);
        box.addView(DshUi.title(act, "恢复这份配置？"));
        if (bad != null) {
            box.addView(DshUi.hint(act, "无法使用：" + bad), DshUi.fullWidth(act, 8));
            Button close = DshUi.button(act, "关闭", true);
            final Dialog d = DshUi.dialog(act, box, DshUi.footer(act, close), 300);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            d.show();
            return;
        }
        box.addView(DshUi.hint(act, zip.getName() + "\n"
                + "将覆盖当前的账户密钥与模型配置。\n"
                + "恢复前会自动把当前配置另存一份，以便退回。\n"
                + "恢复后需要重启 App 才会生效。"), DshUi.fullWidth(act, 8));
        Button cancel = DshUi.button(act, "取消", false);
        Button ok = DshUi.button(act, "恢复", true);
        final Dialog d = DshUi.dialog(act, box, DshUi.footer(act, cancel, ok), 380);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                parent.dismiss();
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
                            @Override public void run() { DshUi.toast(act, msg); }
                        });
                    }
                });
            }
        });
        d.show();
    }
}
