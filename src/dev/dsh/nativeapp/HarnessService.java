package dev.dsh.nativeapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：让 agent 在后台也保持运行。
 *
 * <p>没有它时，App 切到后台或锁屏后 Android 会冻结甚至回收进程，
 * 正在跑的长任务会中断。前台服务 + 常驻通知可以避免这一点。
 *
 * <p>通知栏提供两个动作：
 * <ul>
 *   <li>停止 —— 结束 agent 并退出</li>
 *   <li>设置 —— 回到 App 打开设置（编辑 API Key / 模型）</li>
 * </ul>
 */
public class HarnessService extends Service {

    private static final String TAG = "DSHNative";
    public static final String CHANNEL_ID = "dsh_harness";
    public static final int NOTIFICATION_ID = 0x4453;   // "DS"

    public static final String ACTION_STOP = "dev.dsh.nativeapp.STOP";
    public static final String ACTION_SETTINGS = "dev.dsh.nativeapp.SETTINGS";

    /** 由 MainActivity 注入：收到"停止"时如何收尾。 */
    public static Runnable onStopRequested;
    /** 由 MainActivity 注入：收到"设置"时如何打开设置。 */
    public static Runnable onSettingsRequested;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "通知栏请求停止");
            if (onStopRequested != null) {
                try { onStopRequested.run(); } catch (Throwable ignored) { }
            }
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SETTINGS.equals(action)) {
            Log.i(TAG, "通知栏请求打开设置");
            if (onSettingsRequested != null) {
                try { onSettingsRequested.run(); } catch (Throwable ignored) { }
            }
            return START_STICKY;
        }

        // 常驻通知
        try {
            // 文案里点一下"展开"，因为部分 ROM 会折叠动作按钮（用户已实测遇到）
            startForeground(NOTIFICATION_ID, buildNotification("正在运行 · 展开通知可设置"));

        } catch (Throwable t) {
            Log.w(TAG, "startForeground 失败", t);
        }
        // 被系统回收后自动重建，agent 的存活性更高
        return START_STICKY;
    }

    /** 更新通知文案（例如显示当前阶段）。 */
    public void updateText(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DeepSeek Harness",
                    NotificationManager.IMPORTANCE_LOW);   // 低优先级：不打扰、无声音
            ch.setDescription("保持 agent 在后台运行");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) { }
    }

    private Notification buildNotification(String text) {
        // 应用图标（资源 id 运行时解析，编译期没有 R 类）
        int icon = getResources().getIdentifier(
                "ic_launcher", "mipmap", getPackageName());
        if (icon == 0) icon = android.R.drawable.stat_notify_sync;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Intent stop = new Intent(this, HarnessService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, flags);

        // 「设置」直接拉起 MainActivity 并带上标记。
        // 之前走 Service + 静态回调，进程被系统重启后那个回调是 null，点击毫无反应。
        Intent settings = new Intent(this, MainActivity.class);
        settings.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        settings.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
        PendingIntent settingsPi = PendingIntent.getActivity(this, 2, settings, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("DeepSeek Harness")
         .setContentText(text)
         .setSmallIcon(icon)
         .setContentIntent(content)
         .setOngoing(true)
         .addAction(android.R.drawable.ic_menu_preferences, "设置", settingsPi)
         .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        return b.build();
    }
}
