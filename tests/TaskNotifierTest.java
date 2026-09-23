package dev.dsh.nativeapp;

/**
 * TaskNotifier 的离线测试。
 *
 * 「什么时候该弹通知」全是判断题，而且很容易做得烦人 ——
 * 任务跑 2 秒也弹、用户正看着屏幕也弹、连续任务弹好几条。
 * 这些边界必须逐条钉死。
 */
public class TaskNotifierTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. basic notify ===");
        long t0 = 1_000_000_000L;
        TaskNotifier n = new TaskNotifier();
        check("start produces no notification",
                n.onEvent("start", "s1", t0, false) == null, "should be null");
        check("running flag set", n.isRunning(), "should be true");
        String msg = n.onEvent("done", "s1", t0 + 90_000L, false);
        check("long run in background notifies", msg != null, "should notify");
        check("message mentions duration", msg != null && msg.contains("1 分 30 秒"), String.valueOf(msg));

        System.out.println("=== 2. suppressed cases ===");
        TaskNotifier fg = new TaskNotifier();
        fg.onEvent("start", "s", t0, true);
        check("foreground -> no notification",
                fg.onEvent("done", "s", t0 + 90_000L, true) == null, "should be null");

        TaskNotifier shortRun = new TaskNotifier();
        shortRun.onEvent("start", "s", t0, false);
        check("short run -> no notification",
                shortRun.onEvent("done", "s", t0 + 3_000L, false) == null, "should be null");
        TaskNotifier edge = new TaskNotifier();
        edge.onEvent("start", "s", t0, false);
        check("just below threshold still suppressed",
                edge.onEvent("done", "s", t0 + TaskNotifier.MIN_DURATION_MS - 1, false) == null, "wrong");
        TaskNotifier edge2 = new TaskNotifier();
        edge2.onEvent("start", "s", t0, false);
        check("exactly at threshold notifies",
                edge2.onEvent("done", "s", t0 + TaskNotifier.MIN_DURATION_MS, false) != null, "wrong");

        TaskNotifier orphan = new TaskNotifier();
        check("done without start -> no notification",
                orphan.onEvent("done", "s", t0, false) == null, "should be null");

        TaskNotifier junk = new TaskNotifier();
        check("unknown kind ignored", junk.onEvent("whatever", "s", t0, false) == null, "wrong");

        System.out.println("=== 3. consecutive tasks ===");
        // 连续两个长任务都应通知：时长下限已保证两者间隔 >= 10 秒，
        // 不存在需要额外节流的场景（早先写过一版间隔节流，是死代码，已删）
        TaskNotifier g = new TaskNotifier();
        g.onEvent("start", "a", t0, false);
        check("first long task notifies", g.onEvent("done", "a", t0 + 60_000L, false) != null, "wrong");
        g.onEvent("start", "b", t0 + 61_000L, false);
        check("consecutive long task also notifies",
                g.onEvent("done", "b", t0 + 121_000L, false) != null, "wrong");
        g.onEvent("start", "c", t0 + 200_000L, false);
        check("third also notifies",
                g.onEvent("done", "c", t0 + 300_000L, false) != null, "wrong");

        System.out.println("=== 4. state after done ===");
        TaskNotifier s = new TaskNotifier();
        s.onEvent("start", "x", t0, false);
        s.onEvent("done", "x", t0 + 60_000L, false);
        check("running cleared after done", !s.isRunning(), "should be false");
        s.onEvent("start", "y", t0 + 200_000L, false);
        check("can start again", s.isRunning(), "should be true");

        System.out.println("=== 5. duration formatting ===");
        check("45s", "45 秒".equals(TaskNotifier.duration(45_000)), TaskNotifier.duration(45_000));
        check("60s -> 1 分钟", "1 分钟".equals(TaskNotifier.duration(60_000)), TaskNotifier.duration(60_000));
        check("84s -> 1 分 24 秒", "1 分 24 秒".equals(TaskNotifier.duration(84_000)), TaskNotifier.duration(84_000));
        check("3600s -> 1 小时", "1 小时".equals(TaskNotifier.duration(3_600_000)), TaskNotifier.duration(3_600_000));
        check("7380s -> 2 小时 3 分", "2 小时 3 分".equals(TaskNotifier.duration(7_380_000)), TaskNotifier.duration(7_380_000));
        check("zero -> 0 秒", "0 秒".equals(TaskNotifier.duration(0)), TaskNotifier.duration(0));
        check("negative safe", TaskNotifier.duration(-5) != null, "null");

        System.out.println("=== 6. console parsing ===");
        String[] a = TaskNotifier.parseConsole("[dsh-task] start abc123");
        check("parse start", a != null && "start".equals(a[0]) && "abc123".equals(a[1]),
                a == null ? "null" : a[0] + "/" + a[1]);
        String[] b = TaskNotifier.parseConsole("[dsh-task] done abc123 84000");
        check("parse done", b != null && "done".equals(b[0]) && "abc123".equals(b[1]),
                b == null ? "null" : b[0] + "/" + b[1]);
        check("unrelated console line ignored",
                TaskNotifier.parseConsole("[dsh-api] 200 /api/x :: {}") == null, "should be null");
        check("null safe", TaskNotifier.parseConsole(null) == null, "should be null");
        String[] c = TaskNotifier.parseConsole("[dsh-task] done");
        check("done without id still parses", c != null && "done".equals(c[0]), "wrong");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
