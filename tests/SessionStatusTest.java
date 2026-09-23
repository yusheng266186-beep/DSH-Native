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
        check("title mentions elapsed", t.contains("2:14"), t);
        check("idle title has no duration",
                !SessionStatus.title(SessionStatus.IDLE, 134_000L).contains("2:14"),
                SessionStatus.title(SessionStatus.IDLE, 134_000L));
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
        check("uses the real DSH labels", js.contains("停止生成") && js.contains("发送消息")
                && js.contains("等待审批"), "文案必须与 DSH 一致");
        check("english fallbacks present",
                js.contains("Stop generating") && js.contains("Waiting for approval"), "missing");
        check("idempotent guard", js.contains("__dshStatusWatch"), "missing");
        check("reports on change", js.contains("s!==last"), "missing");
        // 按钮显示的是图标，文案在属性里 —— 只查 textContent 会一个都命中不了
        check("checks aria-label", js.contains("aria-label"), "只查可见文字会永远判定未知");
        // 精确匹配：用子串匹配时，对话内容里出现这几个字就会误判
        check("exact match, not substring", js.contains("v===langs[k]") && js.contains("tx===langs[k]"),
                "子串匹配会被对话正文误触发");
        check("no indexOf on whole body",
                !js.contains("body.textContent.indexOf"), "整页子串搜索会误判");
        // 已处理的审批面板可能仍在 DOM 里，只是被隐藏
        check("requires visibility", js.contains("getBoundingClientRect") && js.contains("function visible"),
                "隐藏的旧面板会导致误报");
        check("uses approval button text", js.contains("允许一次"), "missing");
        // 跨会话判据：用户点进子代理视图时，只看当前输入框会把「主任务在跑」
        // 误判成「空闲」，通知里的时长就停住了
        check("uses cross-session running label", js.contains("进行中") && js.contains("Running"),
                "只看当前视图会被子代理视图误导");
        check("detects subagents running", js.contains("个子代理运行中"), "missing");
        check("subagent match is a regex (count varies)",
                js.contains("SUBAGENT=") && js.contains("regexHit"), "数字会变，精确匹配用不了");
        // 切换视图的瞬间可能读不到任何按钮，一次就下结论会让通知抖动
        check("idle needs two consecutive readings", js.contains("idleStreak>=2"),
                "切换视图瞬间会误报空闲");
        check("checks title", js.contains("'title'") || js.contains("\"title\""), "missing");
        check("checks placeholder", js.contains("placeholder"), "missing");
        // 心跳：状态不变时也要上报，否则通知里的时长与网络状态会僵住
        check("heartbeat keeps notification fresh", js.contains("[dsh-status-keep]"),
                "状态不变时通知会停在几分钟前的文案");
        check("uses textContent not innerText", js.contains("textContent") && !js.contains("innerText"),
                "innerText 每两秒触发布局计算");
        check("has interval", js.contains("setInterval"), "missing");

        System.out.println("=== 11. console parsing ===");
        check("parse running", SessionStatus.parseStatusConsole("[dsh-status] r") == SessionStatus.RUNNING, "wrong");
        check("parse approval", SessionStatus.parseStatusConsole("[dsh-status] a") == SessionStatus.AWAITING_APPROVAL, "wrong");
        check("parse idle", SessionStatus.parseStatusConsole("[dsh-status] i") == SessionStatus.IDLE, "wrong");
        check("parse unknown", SessionStatus.parseStatusConsole("[dsh-status] u") == SessionStatus.UNKNOWN, "wrong");
        check("unrelated line ignored", SessionStatus.parseStatusConsole("[web] hello") == -1, "wrong");
        check("null safe", SessionStatus.parseStatusConsole(null) == -1, "wrong");
        check("empty payload ignored", SessionStatus.parseStatusConsole("[dsh-status] ") == -1, "wrong");
        check("heartbeat running parses",
                SessionStatus.parseStatusConsole("[dsh-status-keep] r") == SessionStatus.RUNNING, "wrong");
        check("heartbeat approval parses",
                SessionStatus.parseStatusConsole("[dsh-status-keep] a") == SessionStatus.AWAITING_APPROVAL, "wrong");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
