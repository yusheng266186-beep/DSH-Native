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
    /** 常驻通知渠道：状态看板，低优先级，不打扰。 */
    public static final String CHANNEL_ID = "dsh_harness";
    /**
     * 提醒渠道：只在「等待批准」时用，高优先级。
     *
     * <p>必须单独一个渠道 —— Android 不允许创建后修改渠道重要性，
     * 而常驻看板若做成高优先级会被用户直接关掉，那就什么都看不到了。
     */
    public static final String ALERT_CHANNEL_ID = "dsh_alerts";
    public static final int NOTIFICATION_ID = 0x4453;   // "DS"
    /** 提醒通知单独一个 id，避免覆盖掉常驻看板。 */
    public static final int ALERT_NOTIFICATION_ID = 0x4454;

    public static final String ACTION_STOP = "dev.dsh.nativeapp.STOP";
    public static final String ACTION_SETTINGS = "dev.dsh.nativeapp.SETTINGS";
    /** 由 MainActivity 推入新的状态，更新通知看板。 */
    public static final String ACTION_STATUS = "dev.dsh.nativeapp.STATUS";
    /** 重新连接：网络切换后对话可能挂在死连接上，用它恢复。 */
    public static final String ACTION_RECONNECT = "dev.dsh.nativeapp.RECONNECT";
    public static final String EXTRA_NETWORK_CHANGED = "networkChanged";
    public static final String EXTRA_STATUS_STATE = "state";
    public static final String EXTRA_STATUS_NETWORK = "networkOk";
    public static final String EXTRA_STATUS_NETWORK_LABEL = "networkLabel";
    public static final String EXTRA_STATUS_SINCE = "since";

    /** 由 MainActivity 注入：收到"停止"时如何收尾。 */
    public static Runnable onStopRequested;
    /** 由 MainActivity 注入：收到"设置"时如何打开设置。 */
    public static Runnable onSettingsRequested;
    /** 由 MainActivity 注入：收到"重新连接"时如何重启服务。 */
    public static Runnable onReconnectRequested;

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
        if (ACTION_RECONNECT.equals(action)) {
            Log.i(TAG, "通知栏请求重新连接");
            if (onReconnectRequested != null) {
                try { onReconnectRequested.run(); } catch (Throwable ignored) { }
            }
            return START_STICKY;
        }
        if (ACTION_STATUS.equals(action)) {
            // 状态更新走 Service 的 action 而不是静态回调：
            // 进程被系统重启后静态字段是 null，那样推送会静默失效。
            applyStatus(intent);
            return START_STICKY;
        }

        // 常驻通知
        try {
            // 文案里点一下"展开"，因为部分 ROM 会折叠动作按钮（用户已实测遇到）
            startForeground(NOTIFICATION_ID,
                    buildNotification("正在运行 · 展开通知可设置", null, false));

        } catch (Throwable t) {
            Log.w(TAG, "startForeground 失败", t);
        }
        // 被系统回收后自动重建，agent 的存活性更高
        return START_STICKY;
    }

    /** 上一次的状态，用于判断是否需要提醒（只在**进入**等待批准时打扰）。 */
    private int lastState = SessionStatus.UNKNOWN;

    /** 应用一次状态更新：常驻看板总是更新，提醒只在需要时发。 */
    private void applyStatus(Intent intent) {
        try {
            int state = intent.getIntExtra(EXTRA_STATUS_STATE, SessionStatus.UNKNOWN);
            boolean netOk = intent.getBooleanExtra(EXTRA_STATUS_NETWORK, true);
            String netLabel = intent.getStringExtra(EXTRA_STATUS_NETWORK_LABEL);
            long since = intent.getLongExtra(EXTRA_STATUS_SINCE, 0L);

            boolean netChanged = intent.getBooleanExtra(EXTRA_NETWORK_CHANGED, false);
            String title = SessionStatus.title(state, System.currentTimeMillis() - since);
            String text = SessionStatus.text(state, netOk, netLabel);
            if (netChanged) {
                // 网络切换后 DSH 到模型服务的长连接可能已经断了，
                // 而它挂在死连接上时既不报错也不超时 —— 用户看到的就是「没反应」。
                // 至少把这件事说出来，并给出恢复入口。
                text = text + "　网络已切换，若无响应可点「重连」";
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIFICATION_ID, buildNotification(text, title, false));

            // 进入「等待批准」时额外发一条高优先级提醒 ——
            // 这是唯一真的需要用户动手的状态，其余变化不该打扰
            if (SessionStatus.shouldAlert(lastState, state)) {
                nm.notify(ALERT_NOTIFICATION_ID,
                        buildAlert("需要你的批准", "DSH 正在等你确认后继续"));
            }
            lastState = state;
        } catch (Throwable t) {
            Log.w(TAG, "状态更新失败", t);
        }
    }

    /** 高优先级提醒：用它自己的渠道，不会把常驻看板一起变成打扰项。 */
    private Notification buildAlert(String title, String text) {
        int icon = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (icon == 0) icon = android.R.drawable.stat_notify_sync;

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 3, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, ALERT_CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(icon)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
    }

    /** 更新通知文案（例如显示当前阶段）。 */
    public void updateText(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text, null, false));
        } catch (Throwable ignored) { }
    }

    private void createChannel() {
        // 与任务完成通知共用同一份实现，避免两处逻辑分叉
        DshUi.ensureChannel(this, CHANNEL_ID, "运行状态",
                "常驻的状态看板：是否在运行、是否结束、网络是否正常",
                android.app.NotificationManager.IMPORTANCE_LOW);
        // 单独一个高优先级渠道，只用于「等待批准」
        DshUi.ensureChannel(this, ALERT_CHANNEL_ID, "需要批准",
                "DSH 等待你确认时提醒（其余状态不会打扰）",
                android.app.NotificationManager.IMPORTANCE_HIGH);
    }

    private Notification buildNotification(String text, String customTitle, boolean alert) {
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

        Intent reconnect = new Intent(this, HarnessService.class).setAction(ACTION_RECONNECT);
        PendingIntent reconnectPi = PendingIntent.getService(this, 4, reconnect, flags);

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
        b.setContentTitle(customTitle != null ? customTitle : "DeepSeek Harness")
         .setContentText(text)
         .setSmallIcon(icon)
         .setContentIntent(content)
         .setOngoing(true)
         .addAction(android.R.drawable.ic_menu_preferences, "设置", settingsPi)
         .addAction(android.R.drawable.ic_menu_rotate, "重连", reconnectPi)
         .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        // 展开后能看到完整状态（部分 ROM 会把正文截断）
        b.setStyle(new Notification.BigTextStyle().bigText(text));
        return b.build();
    }
}
