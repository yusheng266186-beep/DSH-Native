package dev.dsh.nativeapp;

/**
 * CommandCodeUsage 的离线测试。
 *
 * 测试数据取自**真实 API 响应**（2026-09-22 实测），不是编造的 ——
 * 这样能验证「月度分母 = 余额 + 已用」这类推导在真实数据上是否成立。
 */
public class CommandCodeUsageTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    /** 按实测响应构造。 */
    static CommandCodeUsage real() {
        CommandCodeUsage u = new CommandCodeUsage();
        u.monthlyBalance = 28.8080996992;
        u.purchasedCredits = 0;
        u.freeCredits = 0;
        u.fiveUsed = 0.408983337;  u.fiveCap = 14;
        u.weekUsed = 13.6267695465; u.weekCap = 35;
        u.planId = "individual-goat";
        u.status = "active";
        u.totalCost = 41.18638054880002;
        u.requestCount = 14629;
        u.failedCount = 0;
        u.successRate = 100;
        u.tokensIn = 4658306441L;
        u.tokensOut = 12958580L;
        u.tokensTotal = 4671265021L;
        u.hasCredits = u.hasSubscription = u.hasUsage = true;
        return u;
    }

    public static void main(String[] args) {
        System.out.println("=== 1. monthly pool math (real data) ===");
        CommandCodeUsage u = real();
        double total = u.monthlyTotal();
        check("monthly total ~= $70 (GOAT allowance)",
                Math.abs(total - 70.0) < 0.05, String.format("%.4f", total));
        check("used percent ~= 58.8%",
                Math.abs(u.monthlyUsedPercent() - 58.84) < 0.5,
                String.format("%.2f", u.monthlyUsedPercent()));
        check("remain percent = 100 - used",
                Math.abs(u.monthlyUsedPercent() + u.monthlyRemainPercent() - 100) < 0.001, "wrong");

        System.out.println("=== 2. rolling windows ===");
        check("5h percent ~= 2.92%",
                Math.abs(u.fivePercent() - 2.921) < 0.01, String.format("%.3f", u.fivePercent()));
        check("weekly percent ~= 38.9%",
                Math.abs(u.weekPercent() - 38.93) < 0.1, String.format("%.3f", u.weekPercent()));
        // 2.92% 落在「<10 用一位小数」档 —— 只有 <1% 才需要两位
        // （0.02% 抹成 0% 会让人以为没消耗；2.9% 多给一位是噪音）
        check("percentText(2.921) = 2.9%", "2.9%".equals(CommandCodeUsage.percentText(u.fivePercent())),
                CommandCodeUsage.percentText(u.fivePercent()));
        check("percentText(38.9) = 39%", "39%".equals(CommandCodeUsage.percentText(u.weekPercent())),
                CommandCodeUsage.percentText(u.weekPercent()));

        System.out.println("=== 3. percent precision tiers ===");
        check("0 -> 0%", "0%".equals(CommandCodeUsage.percentText(0)), CommandCodeUsage.percentText(0));
        check("0.002 not rounded to 0%", !"0%".equals(CommandCodeUsage.percentText(0.002)),
                CommandCodeUsage.percentText(0.002));
        check("0.5 -> 0.50%", "0.50%".equals(CommandCodeUsage.percentText(0.5)),
                CommandCodeUsage.percentText(0.5));
        check("5.25 -> 5.3%", "5.3%".equals(CommandCodeUsage.percentText(5.25)),
                CommandCodeUsage.percentText(5.25));
        check("58.8 -> 59%", "59%".equals(CommandCodeUsage.percentText(58.84)),
                CommandCodeUsage.percentText(58.84));

        System.out.println("=== 4. money formatting ===");
        check("28.808 -> $28.81", "$28.81".equals(CommandCodeUsage.money(28.8080996992)),
                CommandCodeUsage.money(28.8080996992));
        check("41.186 -> $41.19", "$41.19".equals(CommandCodeUsage.money(41.1863805488)),
                CommandCodeUsage.money(41.1863805488));
        check("0 -> $0.00", "$0.00".equals(CommandCodeUsage.money(0)), CommandCodeUsage.money(0));
        check("0.004 not shown as $0.00 (looks exhausted)",
                !"$0.00".equals(CommandCodeUsage.money(0.004)), CommandCodeUsage.money(0.004));
        check("NaN safe", "$0.00".equals(CommandCodeUsage.money(Double.NaN)),
                CommandCodeUsage.money(Double.NaN));

        System.out.println("=== 5. plan and status labels ===");
        check("individual-goat -> GOAT",
                "GOAT".equals(CommandCodeUsage.planLabel("individual-goat")),
                CommandCodeUsage.planLabel("individual-goat"));
        check("active -> 生效中",
                "生效中".equals(CommandCodeUsage.statusLabel("active")),
                CommandCodeUsage.statusLabel("active"));
        check("unknown plan returns raw id, not a guess",
                "weird-plan-v9".equals(CommandCodeUsage.planLabel("weird-plan-v9")),
                CommandCodeUsage.planLabel("weird-plan-v9"));
        check("empty plan safe", CommandCodeUsage.planLabel("") != null, "null");
        check("null plan safe", CommandCodeUsage.planLabel(null) != null, "null");

        System.out.println("=== 6. countdown ===");
        long now = 1000000000000L;
        check("3h12m", "3 小时 12 分".equals(CommandCodeUsage.until(now + 3*3600000L + 12*60000L, now)),
                CommandCodeUsage.until(now + 3*3600000L + 12*60000L, now));
        check("2d3h", "2 天 3 小时".equals(CommandCodeUsage.until(now + 2*86400000L + 3*3600000L, now)),
                CommandCodeUsage.until(now + 2*86400000L + 3*3600000L, now));
        check("past returns empty (caller hides row)",
                "".equals(CommandCodeUsage.until(now - 1000, now)),
                CommandCodeUsage.until(now - 1000, now));
        check("zero target returns empty", "".equals(CommandCodeUsage.until(0, now)), "wrong");

        System.out.println("=== 7. token abbreviation ===");
        check("4671265021 -> 4.67 B", "4.67 B".equals(CommandCodeUsage.tokens(4671265021L)),
                CommandCodeUsage.tokens(4671265021L));
        check("12958580 -> 13.0 M", "13.0 M".equals(CommandCodeUsage.tokens(12958580L)),
                CommandCodeUsage.tokens(12958580L));
        check("1500 -> 1.5 K", "1.5 K".equals(CommandCodeUsage.tokens(1500)), CommandCodeUsage.tokens(1500));
        check("999 -> 999", "999".equals(CommandCodeUsage.tokens(999)), CommandCodeUsage.tokens(999));

        System.out.println("=== 8. success rate ===");
        check("100 with 0 failed", "100.0%".equals(CommandCodeUsage.successText(100, 14629, 0)),
                CommandCodeUsage.successText(100, 14629, 0));
        check("no requests -> explanatory text",
                "暂无请求".equals(CommandCodeUsage.successText(0, 0, 0)),
                CommandCodeUsage.successText(0, 0, 0));
        check("derives from counts when rate is NaN",
                "50.0%".equals(CommandCodeUsage.successText(Double.NaN, 10, 5)),
                CommandCodeUsage.successText(Double.NaN, 10, 5));

        System.out.println("=== 9. alert level ===");
        check("typical usage -> normal (0)", real().alertLevel() == 0,
                String.valueOf(real().alertLevel()));
        CommandCodeUsage near = real();
        near.weekUsed = 34.5;   // 98.6%
        check("98.6% -> serious (2)", near.alertLevel() == 2, String.valueOf(near.alertLevel()));
        CommandCodeUsage mid = real();
        mid.monthlyBalance = 8; mid.totalCost = 42;   // 84%
        check("84% -> warning (1)", mid.alertLevel() == 1, String.valueOf(mid.alertLevel()));
        CommandCodeUsage zero = new CommandCodeUsage();
        check("all zero -> normal, no NaN", zero.alertLevel() == 0, String.valueOf(zero.alertLevel()));
        check("zero caps do not divide by zero",
                zero.fivePercent() == 0 && zero.weekPercent() == 0 && zero.monthlyUsedPercent() == 0, "NaN");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
