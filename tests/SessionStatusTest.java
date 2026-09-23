package dev.dsh.nativeapp;

/**
 * SessionStatus 的离线测试。
 *
 * 重点：
 *   ① 优先级 —— 等待批准必须压过运行中（任务卡在用户这一步，紧迫性最高）；
 *   ② 判不出来时**不许猜** —— 报 UNKNOWN，因为通知里显示错误的状态
 *      比不显示更糟（用户会以为 agent 卡住了）；
 *   ③ 网络异常必须体现在正文里（否则「运行中」却一直没动静时无从判断）。
 */
public class SessionStatusTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. state priority ===");
        check("approval beats running",
                SessionStatus.fromDom(true, false, true, true) == SessionStatus.AWAITING_APPROVAL,
                String.valueOf(SessionStatus.fromDom(true, false, true, true)));
        check("stop button -> running",
                SessionStatus.fromDom(true, false, false, true) == SessionStatus.RUNNING, "wrong");
        check("send button -> idle",
                SessionStatus.fromDom(false, true, false, true) == SessionStatus.IDLE, "wrong");
        check("both buttons -> running wins (stop is the stronger signal)",
                SessionStatus.fromDom(true, true, false, true) == SessionStatus.RUNNING,
                String.valueOf(SessionStatus.fromDom(true, true, false, true)));

        System.out.println("=== 2. never guess ===");
        check("page not ready -> unknown",
                SessionStatus.fromDom(false, false, false, false) == SessionStatus.UNKNOWN, "wrong");
        check("page ready but no buttons -> unknown",
                SessionStatus.fromDom(false, false, false, true) == SessionStatus.UNKNOWN,
                "显示错误的状态比不显示更糟");
        check("page not ready beats approval",
                SessionStatus.fromDom(false, false, true, false) == SessionStatus.UNKNOWN,
                "页面没加载完时不该报批准");

        System.out.println("=== 3. labels ===");
        check("running label", "运行中".equals(SessionStatus.label(SessionStatus.RUNNING)), "wrong");
        check("approval label", "等待批准".equals(SessionStatus.label(SessionStatus.AWAITING_APPROVAL)), "wrong");
        check("idle label", "空闲".equals(SessionStatus.label(SessionStatus.IDLE)), "wrong");
        check("unknown label", SessionStatus.label(SessionStatus.UNKNOWN).contains("未知"), "wrong");

        System.out.println("=== 4. title carries elapsed time ===");
        String t = SessionStatus.title(SessionStatus.RUNNING, 134_000L);
        check("title mentions running", t.contains("运行中"), t);
        // 时长不再拼进标题 —— 改由系统计时器显示（setUsesChronometer + setWhen）。
        // 原来每 2 秒推送一次、每次带一个算好的秒数，通知里的秒数就两秒两秒地跳。
        check("title does NOT contain elapsed", !t.contains("2:14"), t);
        check("chronometer used while running",
                SessionStatus.useChronometer(SessionStatus.RUNNING), "wrong");
        check("chronometer used while awaiting approval",
                SessionStatus.useChronometer(SessionStatus.AWAITING_APPROVAL), "wrong");
        check("no chronometer when idle",
                !SessionStatus.useChronometer(SessionStatus.IDLE), "wrong");
        check("approval title shows state",
                SessionStatus.title(SessionStatus.AWAITING_APPROVAL, 60_000L).contains("等待批准"),
                SessionStatus.title(SessionStatus.AWAITING_APPROVAL, 60_000L));

        System.out.println("=== 5. network problem must be visible ===");
        String offline = SessionStatus.text(SessionStatus.RUNNING, false, "未连接");
        check("offline text says network", offline.contains("网络不可用"), offline);
        check("offline text warns task may be stuck", offline.contains("中断"), offline);
        check("online running text", SessionStatus.text(SessionStatus.RUNNING, true, "")
                .contains("正在执行"), SessionStatus.text(SessionStatus.RUNNING, true, ""));
        check("approval text asks for action",
                SessionStatus.text(SessionStatus.AWAITING_APPROVAL, true, "").contains("批准"),
                SessionStatus.text(SessionStatus.AWAITING_APPROVAL, true, ""));
        check("network detail included",
                SessionStatus.text(SessionStatus.IDLE, false, "已连接但无法访问外网")
                        .contains("无法访问外网"),
                SessionStatus.text(SessionStatus.IDLE, false, "已连接但无法访问外网"));

        System.out.println("=== 6. importance: only approval should interrupt ===");
        check("approval is high", SessionStatus.importance(SessionStatus.AWAITING_APPROVAL, true) == 3, "wrong");
        check("offline is default", SessionStatus.importance(SessionStatus.RUNNING, false) == 2, "wrong");
        check("normal is low", SessionStatus.importance(SessionStatus.RUNNING, true) == 1, "wrong");
        check("idle is low", SessionStatus.importance(SessionStatus.IDLE, true) == 1, "wrong");

        System.out.println("=== 7. alert only on entering approval ===");
        check("entering approval alerts",
                SessionStatus.shouldAlert(SessionStatus.RUNNING, SessionStatus.AWAITING_APPROVAL), "wrong");
        check("already in approval does not re-alert",
                !SessionStatus.shouldAlert(SessionStatus.AWAITING_APPROVAL, SessionStatus.AWAITING_APPROVAL),
                "会反复打扰");
        check("leaving approval does not alert",
                !SessionStatus.shouldAlert(SessionStatus.AWAITING_APPROVAL, SessionStatus.RUNNING), "wrong");
        check("running does not alert",
                !SessionStatus.shouldAlert(SessionStatus.IDLE, SessionStatus.RUNNING), "wrong");

        System.out.println("=== 8. duration formatting ===");
        check("0 -> empty", SessionStatus.duration(0).length() == 0, SessionStatus.duration(0));
        check("negative -> empty", SessionStatus.duration(-5).length() == 0, "wrong");
        check("500ms -> empty", SessionStatus.duration(500).length() == 0, "wrong");
        check("45s", "45 秒".equals(SessionStatus.duration(45_000)), SessionStatus.duration(45_000));
        check("60s -> 1:00", "1:00".equals(SessionStatus.duration(60_000)), SessionStatus.duration(60_000));
        check("134s -> 2:14", "2:14".equals(SessionStatus.duration(134_000)), SessionStatus.duration(134_000));
        check("pads seconds", "1:05".equals(SessionStatus.duration(65_000)), SessionStatus.duration(65_000));
        check("3700s -> 1:01", "1:01".equals(SessionStatus.duration(3_700_000)), SessionStatus.duration(3_700_000));

        System.out.println("=== 9. network label ===");
        check("offline", "未连接".equals(SessionStatus.networkLabel(false, false, false, false)), "wrong");
        check("wifi", "Wi-Fi".equals(SessionStatus.networkLabel(true, true, false, true)), "wrong");
        check("cellular", "移动数据".equals(SessionStatus.networkLabel(true, false, true, true)), "wrong");
        check("connected but no internet",
                SessionStatus.networkLabel(true, true, false, false).contains("无法访问外网"),
                SessionStatus.networkLabel(true, true, false, false));

        System.out.println("=== 10. injected script ===");
        String js = SessionStatus.pollScript();
        // 注入脚本只做一件事：检测待批准。
        // 运行/空闲由 App 从 DSH 自己的 /api/session/list 响应里读 ——
        // 那个接口是 RPC 式 POST，注入脚本用 GET 调只会 404
        //（实测 109 次 404 全是这么来的，28 次 200 都是 DSH 自己发的）。
        check("does NOT self-poll the API",
                !js.contains("fetch("), "自己轮询会 404，还白耗电");
        check("keeps approval detection",
                js.contains("允许一次") && js.contains("等待审批"), "missing");
        check("approval uses exact match",
                js.contains("v===langs[k]") && js.contains("tx===langs[k]"),
                "子串匹配会被对话正文误触发");
        check("no whole-body substring search",
                !js.contains("body.textContent.indexOf"), "整页子串搜索会误判");
        check("requires visibility",
                js.contains("getBoundingClientRect") && js.contains("function visible"),
                "隐藏的旧面板会导致误报");
        check("reports only on change", js.contains("a!==last"), "会刷日志");
        check("idempotent guard", js.contains("__dshStatusWatch"), "missing");
        check("has interval", js.contains("setInterval"), "missing");

        System.out.println("=== 10.1 touch menu fix ===");
        String tf = SessionStatus.touchMenuFixScript();
        check("blocks pointerleave after touch", tf.contains("pointerleave")
                && tf.contains("stopPropagation"), "悬停菜单会被手指抬起关掉");
        check("capture phase", tf.contains(",true)"), "必须用捕获阶段才能拦住 React 委托的事件");
        check("limited window", tf.contains("<800"), "不能永久屏蔽，鼠标行为要保留");
        check("idempotent guard", tf.contains("__dshTouchFix"), "missing");

        System.out.println("=== 11. console parsing ===");
        check("parse approval", SessionStatus.parseStatusConsole("[dsh-appr] a") == SessionStatus.AWAITING_APPROVAL, "wrong");
        check("no approval -> ignored", SessionStatus.parseStatusConsole("[dsh-appr] -") == -1, "wrong");
        check("unrelated line ignored", SessionStatus.parseStatusConsole("[web] hello") == -1, "wrong");
        check("null safe", SessionStatus.parseStatusConsole(null) == -1, "wrong");
        check("empty payload ignored", SessionStatus.parseStatusConsole("[dsh-appr] ") == -1, "wrong");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
