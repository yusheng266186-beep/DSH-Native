package dev.dsh.nativeapp;

/**
 * 后台任务完成通知的判定逻辑：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>要解决的问题：手机上把 App 切到后台后，**不知道 agent 什么时候做完**。
 * 长任务尤其如此 —— 回来时可能已经跑完很久，也可能还在跑。
 *
 * <p>数据来源：页面状态的变化（由 {@link SessionStatus} 采集）。
 * 早先的实现轮询 {@code /api/session/list} —— 实测该接口不存在，
 * DSH 的服务端 API 是自定义 RPC 而非 REST，所以那条链从未生效。
 *
 * <h3>为什么判定逻辑要单独抽出来</h3>
 * 「什么时候该通知」全是判断题，而且很容易做得烦人：任务跑 2 秒也弹一条、
 * 用户就在看着屏幕也弹一条、同一次任务弹好几条。这些边界必须能测。
 */
final class TaskNotifier {

    /** 短于此时长的任务不通知 —— 用户还没来得及切走，弹了只会打扰。 */
    static final long MIN_DURATION_MS = 10_000L;

    private static final String[] SESSION_MARKS = {
        "[dsh-task] start",
        "[dsh-task] done",
    };

    /** 当前是否有任务在跑。 */
    private boolean running;
    /** 本轮任务的开始时间。 */
    private long startedAt;

    /**
     * 收到一次状态变化。
     *
     * @param kind        start / done
     * @param sessionId   会话 id（用于通知文案，可为空）
     * @param now         当前时间
     * @param foreground  App 是否在前台
     * @return 需要展示的通知文案；不需要通知时返回 null
     */
    String onEvent(String kind, String sessionId, long now, boolean foreground) {
        if ("start".equals(kind)) {
            if (!running) {
                running = true;
                startedAt = now;
            }
            return null;
        }
        if (!"done".equals(kind)) return null;

        // 没见过 start 就收到 done（例如注入发生在任务中途）——
        // 无法知道时长，按「不通知」处理，好过弹一条时间不准的。
        if (!running || startedAt <= 0) {
            running = false;
            return null;
        }
        long elapsed = now - startedAt;
        running = false;
        startedAt = 0;

        // 用户就在看着屏幕：界面上本来就能看到结果，不需要再弹一条
        if (foreground) return null;
        // 太短的任务：多半是用户自己刚发的，弹了只会打扰
        if (elapsed < MIN_DURATION_MS) return null;

        // 这里**不需要**再做「两次通知间隔」的节流：
        // 上面的时长下限已经保证了两次通知至少相隔 MIN_DURATION_MS，
        // 找不到一个能触发间隔节流的场景 —— 写了也是死代码。
        return "任务已完成 · 用时 " + duration(elapsed);
    }

    /** 供测试与状态展示。 */
    boolean isRunning() { return running; }

    /** 把毫秒时长变成人话：{@code 1 分 24 秒} / {@code 2 小时 3 分}。 */
    static String duration(long ms) {
        if (ms < 0) return "0 秒";
        long sec = ms / 1000;
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        if (min < 60) {
            long s = sec % 60;
            return s == 0 ? min + " 分钟" : min + " 分 " + s + " 秒";
        }
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /**
     * 从控制台消息里解析任务事件。
     *
     * @return 长度为 2 的数组 {kind, sessionId}；不是任务事件时返回 null
     */
    static String[] parseConsole(String message) {
        if (message == null) return null;
        for (String mark : SESSION_MARKS) {
            int i = message.indexOf(mark);
            if (i < 0) continue;
            String kind = mark.endsWith("start") ? "start" : "done";
            // 约定格式：[dsh-task] done <sessionId>
            String rest = message.substring(i + mark.length()).trim();
            int sp = rest.indexOf(' ');
            String id = sp > 0 ? rest.substring(0, sp).trim() : rest.trim();
            if (id.startsWith("<") || id.length() > 64) id = "";
            return new String[]{ kind, id };
        }
        return null;
    }
}
