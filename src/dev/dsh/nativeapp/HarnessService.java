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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    /** 后台任务完成提醒：独立于低优先级常驻看板，允许系统发声或震动。 */
    public static final String TASK_CHANNEL_ID = "dsh_task_complete";
    public static final int NOTIFICATION_ID = 0x4453;   // "DS"
    /** 提醒通知单独一个 id，避免覆盖掉常驻看板。 */
    public static final int ALERT_NOTIFICATION_ID = 0x4454;

    public static final String ACTION_STOP = "dev.dsh.nativeapp.STOP";
    public static final String ACTION_SETTINGS = "dev.dsh.nativeapp.SETTINGS";
    /** 由 MainActivity 推入新的状态，更新通知看板。 */
    public static final String ACTION_STATUS = "dev.dsh.nativeapp.STATUS";
    public static final String EXTRA_STATUS_STATE = "state";
    public static final String EXTRA_STATUS_NETWORK = "networkOk";
    public static final String EXTRA_STATUS_NETWORK_LABEL = "networkLabel";
    public static final String EXTRA_STATUS_SINCE = "since";
    public static final String EXTRA_CONNECTION_STATE = "connectionState";

    /** Node 进程由 Service 侧的监管器持有，不再属于任何 Activity 实例。 */
    private static final ProcessSupervisor PROCESS = new ProcessSupervisor();
    private static volatile String managedUrl;
    private static final Pattern URL_PATTERN = Pattern.compile(
            "http://(?:127\\.0\\.0\\.1|localhost):\\d+(?:/[A-Za-z0-9_\\-?=&.]*)?");

    /**
     * 界面监听者只用弱引用：Activity 重建后旧实例可以正常回收，
     * 进程输出仍由 Service 持续消费，不会因无人读取而堵塞。
     */
    public interface Listener {
        void onOutputLine(String line);
        void onStopRequested();
        void onTaskState(int state, long since);
        boolean isForeground();
    }

    private static WeakReference<Listener> listenerRef =
            new WeakReference<Listener>(null);

    public static synchronized void setListener(Listener listener) {
        listenerRef = new WeakReference<Listener>(listener);
    }

    public static synchronized void clearListener(Listener listener) {
        Listener current = listenerRef.get();
        if (current == listener) listenerRef.clear();
    }

    /** 接管并持续读取一个新启动的 Node 进程。 */
    public static synchronized void adoptProcess(final Process process) {
        if (PROCESS.current() == process) return;
        PROCESS.adopt(process);
        managedUrl = null;
        Thread output = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (PROCESS.current() != process) continue;
                        String clean = stripAnsi(line);
                        Log.i(TAG, "[dsh] " + SecretMasker.mask(clean));
                        if (managedUrl == null) {
                            Matcher match = URL_PATTERN.matcher(clean);
                            if (match.find()) publishManagedUrl(process, match.group());
                        }
                        Listener listener = currentListener();
                        if (listener != null) {
                            try { listener.onOutputLine(clean); }
                            catch (Throwable t) { Log.w(TAG, "输出监听失败", t); }
                        }
                    }
                } catch (IOException e) {
                    Log.i(TAG, "dsh 输出流结束", e);
                } finally {
                    // Closing stdout is not proof that the process exited.
                    if (!ProcessSupervisor.alive(process)) PROCESS.clear(process);
                }
            }
        }, "dsh-process-output");
        output.setDaemon(true);
        output.start();
    }

    public static Process managedProcess() {
        return PROCESS.current();
    }

    public static boolean isManagedProcessAlive() {
        return PROCESS.isAlive();
    }

    public static String managedUrl() {
        return PROCESS.isAlive() ? managedUrl : null;
    }

    private static synchronized void publishManagedUrl(Process source, String url) {
        if (PROCESS.current() == source) managedUrl = url;
    }

    /** 供设置页重启使用：只停进程，不关闭 Activity。 */
    public static boolean stopManagedProcess() {
        managedUrl = null;
        return PROCESS.stop();
    }

    public static boolean stopManagedProcessAndWait(long timeoutMs) {
        boolean stopped = PROCESS.stopAndWait(timeoutMs);
        if (stopped) synchronized (HarnessService.class) {
            if (PROCESS.current() == null) managedUrl = null;
        }
        return stopped;
    }

    private static synchronized Listener currentListener() {
        return listenerRef.get();
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try { nativeBridge = new NativeBridge(this); nativeBridgeReady = true; }
        catch (IOException unavailable) { Log.w(TAG, "原生能力桥启动失败: " + unavailable.getClass().getSimpleName()); }
        monitor.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { pollCoreTasks(); }
        }, 1, 3, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            closeMonitor();
            Log.i(TAG, "通知栏请求停止");
            stopManagedProcess();
            Listener listener = currentListener();
            if (listener != null) {
                try { listener.onStopRequested(); }
                catch (Throwable t) { Log.w(TAG, "停止回调失败", t); }
            }
            getSharedPreferences("dsh-native", MODE_PRIVATE).edit()
                    .remove("dshPort").remove("dshToken").apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForegroundNotification();
        if (ACTION_SETTINGS.equals(action)) {
            Log.i(TAG, "通知栏请求打开设置");
            return START_STICKY;
        }
        if (ACTION_STATUS.equals(action)) {
            // 状态更新走 Service 的 action 而不是静态回调：
            // 进程被系统重启后静态字段是 null，那样推送会静默失效。
            applyStatus(intent);
            return START_STICKY;
        }

        // 被系统回收后自动重建，agent 的存活性更高
        return START_STICKY;
    }

    private boolean foregroundStarted;
    private NativeBridge nativeBridge;

    Object nativeKeepaliveStatus() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return NativeBridgePolicy.object("foregroundService", foregroundStarted, "processAlive", isManagedProcessAlive(),
                "taskState", SessionStatus.label(diagnosticTaskState), "taskStateFresh", hasNativeTaskState(),
                "wakeLockHeld", taskWakeLock != null && taskWakeLock.isHeld(),
                "batteryOptimizationIgnored", Build.VERSION.SDK_INT < 23
                    || (power != null && power.isIgnoringBatteryOptimizations(getPackageName())));
    }

    private static volatile boolean nativeBridgeReady;
    static String nativeBridgeDiagnostic() { return nativeBridgeReady ? "loopback ready; authenticated; per-request capture consent" : "not running"; }

    private void ensureForegroundNotification() {
        if (foregroundStarted) return;
        // 常驻通知
        try {
            // 文案里点一下"展开"，因为部分 ROM 会折叠动作按钮（用户已实测遇到）。
            //
            // **不要写「正在运行」**：这是 Activity 推送第一条真实状态之前的占位文案，
            // 而此时 App 根本不知道有没有任务在跑。写「正在运行」会直接骗人 ——
            // 实测踩过：App 更新后服务被重建，这条占位通知一直挂着，
            // 而用户看到的正是「对话早就结束了，通知栏还显示运行中」。
            startForeground(NOTIFICATION_ID,
                    buildNotification("正在同步任务状态 · 展开可设置", null, false));
            foregroundStarted = true;
            lastNotificationSig = "";

        } catch (Throwable t) {
            Log.w(TAG, "startForeground 失败", t);
        }
    }

    /** 上一次的状态，用于判断是否需要提醒（只在**进入**等待批准时打扰）。 */
    private int lastState = SessionStatus.UNKNOWN;

    /** 上一次通知的内容签名（抑制无意义的重复发布）。 */
    private String lastNotificationSig = "";

    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TaskNotifier nativeNotifier = new TaskNotifier();
    private int observedCorePort;
    private String observedCoreToken = "";
    private volatile CoreRpcClient taskClient;
    private volatile boolean monitorClosed;
    private static volatile long nativeTaskAt;
    private int nativeTaskState = SessionStatus.UNKNOWN;
    private static volatile int diagnosticTaskState = SessionStatus.UNKNOWN;
    private boolean lastNetworkOk = true;
    private String lastNetworkLabel = "";
    private int lastConnection = ConnectionRecovery.UNKNOWN;
    private PowerManager.WakeLock taskWakeLock;
    private long approvalAt;

    static boolean hasNativeTaskState() {
        long age = System.currentTimeMillis() - nativeTaskAt;
        return nativeTaskAt > 0 && age >= 0 && age <= CoreTaskState.FRESH_MS;
    }

    static String taskDiagnostic() {
        return (hasNativeTaskState() ? "fresh: " : "stale: ") + SessionStatus.label(diagnosticTaskState);
    }

    private void pollCoreTasks() {
        if (monitorClosed) return;
        final android.content.SharedPreferences prefs = getSharedPreferences("dsh-native", MODE_PRIVATE);
        final int port = prefs.getInt("dshPort", 0);
        final String token = prefs.getString("dshToken", "");
        int state = SessionStatus.UNKNOWN;
        try {
            if (port <= 0 || token.length() == 0) throw new IOException("core not ready");
            CoreRpcClient client = taskClient;
            if (client == null || !client.matches(port, token)) {
                if (client != null) client.close();
                client = new CoreRpcClient(port, token);
                taskClient = client;
            }
            state = CoreTaskState.read(client);
            // Ignore results from an old instance after restart or teardown.
            if (monitorClosed || !client.matches(prefs.getInt("dshPort", 0), prefs.getString("dshToken", ""))) return;
        } catch (Exception unavailable) {
            // No response bodies or authentication data in logs. Unknown remains
            // unknown; it must not finish a task or trigger an automatic restart.
        }
        final int observed = state;
        main.post(new Runnable() {
            @Override public void run() {
                if (!monitorClosed && port == prefs.getInt("dshPort", 0)
                        && token.equals(prefs.getString("dshToken", ""))) try {
                    if (port != observedCorePort || !token.equals(observedCoreToken)) {
                        nativeNotifier = new TaskNotifier();
                        observedCorePort = port; observedCoreToken = token; approvalAt = 0L;
                    }
                    applyCoreTasks(observed);
                }
                catch (RuntimeException failure) { Log.w(TAG, "任务状态更新失败: " + failure.getClass().getSimpleName()); }
            }
        });
    }

    private void applyCoreTasks(int state) {
        nativeTaskState = state;
        diagnosticTaskState = state;
        nativeTaskAt = System.currentTimeMillis();
        Listener listener = currentListener();
        boolean foreground = listener != null && listener.isForeground();
        String completed = null;
        if (state == SessionStatus.RUNNING) {
            nativeNotifier.onEvent("start", "", nativeTaskAt, foreground);
            try {
                if (taskWakeLock == null) {
                    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
                    if (power != null) {
                        taskWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHNative:task");
                        taskWakeLock.setReferenceCounted(false);
                    }
                }
                if (taskWakeLock != null) taskWakeLock.acquire(60000L);
            } catch (RuntimeException failure) { Log.w(TAG, "任务保活不可用: " + failure.getClass().getSimpleName()); }
        } else if (state == SessionStatus.IDLE) {
            completed = nativeNotifier.onEvent("done", "", nativeTaskAt, foreground);
            releaseTaskWakeLock();
        }
        if (listener != null) listener.onTaskState(state, nativeNotifier.startedAt());
        Intent status = new Intent().putExtra(EXTRA_STATUS_STATE, state)
                .putExtra(EXTRA_STATUS_NETWORK, lastNetworkOk)
                .putExtra(EXTRA_STATUS_NETWORK_LABEL, lastNetworkLabel)
                .putExtra(EXTRA_CONNECTION_STATE, lastConnection)
                .putExtra(EXTRA_STATUS_SINCE, nativeNotifier.startedAt());
        applyStatus(status);
        if (completed != null) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, TASK_CHANNEL_ID) : new Notification.Builder(this);
                Notification notification = builder.setContentTitle("任务已完成").setContentText(completed)
                        .setSmallIcon(android.R.drawable.stat_notify_sync).setAutoCancel(true)
                        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
                manager.notify(0x4455, notification);
            }
        }
    }

    private void releaseTaskWakeLock() {
        try { if (taskWakeLock != null && taskWakeLock.isHeld()) taskWakeLock.release(); }
        catch (RuntimeException expired) { Log.w(TAG, "任务保活释放失败: " + expired.getClass().getSimpleName()); }
    }

    private void closeMonitor() {
        nativeBridgeReady = false;
        if (nativeBridge != null) { nativeBridge.close(); nativeBridge = null; }
        monitorClosed = true;
        nativeTaskAt = 0L;
        CoreRpcClient client = taskClient;
        if (client != null) client.close();
        monitor.shutdownNow();
        releaseTaskWakeLock();
    }

    @Override public void onDestroy() {
        closeMonitor();
        super.onDestroy();
    }

    /** 应用一次状态更新：常驻看板总是更新，提醒只在需要时发。 */
    private void applyStatus(Intent intent) {
        try {
            int state = intent.getIntExtra(EXTRA_STATUS_STATE, SessionStatus.UNKNOWN);
            if (ACTION_STATUS.equals(intent.getAction()) && state == SessionStatus.AWAITING_APPROVAL)
                approvalAt = System.currentTimeMillis();
            if (nativeTaskState == SessionStatus.RUNNING && System.currentTimeMillis() - approvalAt < 20000L)
                state = SessionStatus.AWAITING_APPROVAL;
            state = CoreTaskState.effective(nativeTaskState, nativeTaskAt, state, System.currentTimeMillis());
            boolean netOk = intent.getBooleanExtra(EXTRA_STATUS_NETWORK, true);
            String netLabel = intent.getStringExtra(EXTRA_STATUS_NETWORK_LABEL);
            long since = intent.getLongExtra(EXTRA_STATUS_SINCE, 0L);
            int connectionState = intent.getIntExtra(
                    EXTRA_CONNECTION_STATE, ConnectionRecovery.UNKNOWN);
            lastNetworkOk = netOk;
            lastNetworkLabel = netLabel == null ? "" : netLabel;
            lastConnection = connectionState;
            if (hasNativeTaskState()) since = nativeNotifier.startedAt();

            String title = SessionStatus.title(state,
                    System.currentTimeMillis() - since, connectionState);
            String text = SessionStatus.text(state, netOk, netLabel, connectionState);

            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 内容没变就不重新发布 —— 每次 notify() 都会让系统重画通知，
            // 在 MIUI 上表现为可见的闪烁。
            // 运行时长由系统计时器自己走、网络变化由系统回调驱动，
            // 所以「内容没变」是常态，不需要更新。
            String sig = SessionStatus.notificationSignature(
                    state, netOk, netLabel, since, connectionState);
            if (sig.equals(lastNotificationSig)) return;
            lastNotificationSig = sig;

            nm.notify(NOTIFICATION_ID,
                    buildNotification(text, title,
                            SessionStatus.useChronometer(state, since), since));

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
        DshUi.ensureChannel(this, TASK_CHANNEL_ID, "任务完成",
                "DSH 在后台完成任务时提醒",
                android.app.NotificationManager.IMPORTANCE_DEFAULT);
    }

    private Notification buildNotification(String text, String customTitle, boolean alert) {
        return buildNotification(text, customTitle, alert, 0L);
    }

    /**
     * @param chronometer 是否用系统计时器显示运行时长
     * @param since       任务开始时间（chronometer 为 true 时使用）
     */
    private Notification buildNotification(String text, String customTitle,
                                           boolean chronometer, long since) {
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
        b.setContentTitle(customTitle != null ? customTitle : "DeepSeek Harness")
         .setContentText(text)
         .setSmallIcon(icon)
         .setContentIntent(content)
         .setOngoing(true);
        // 系统计时器：由系统每秒自己走，与状态轮询周期无关。
        // 用它之前是手工把秒数拼进标题，而推送每 2 秒一次 ——
        // 通知里的秒数就两秒两秒地跳。
        if (chronometer && since > 0) {
            b.setUsesChronometer(true).setWhen(since);
        } else {
            b.setUsesChronometer(false).setWhen(System.currentTimeMillis());
        }
        b
         .addAction(android.R.drawable.ic_menu_preferences, "设置", settingsPi)
         .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi);
        // 展开后能看到完整状态（部分 ROM 会把正文截断）
        b.setStyle(new Notification.BigTextStyle().bigText(text));
        return b.build();
    }
}
