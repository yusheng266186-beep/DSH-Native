package dev.dsh.nativeapp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Command Code 订阅用量：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>数据来自三个端点（都用账户 API Key 认证，无需浏览器 Cookie）：
 * <ul>
 *   <li>{@code /alpha/billing/credits} —— 月度余额与两个滚动窗口</li>
 *   <li>{@code /alpha/billing/subscriptions} —— 套餐与计费周期</li>
 *   <li>{@code /alpha/usage/summary} —— 本期请求数、成本、Token</li>
 * </ul>
 *
 * <h3>两个容易算错的地方</h3>
 * <ol>
 *   <li><b>月度池的分母服务端不给。</b> credits 只返回「余额」，
 *       已用量在 usage/summary 的 totalCost 里 —— 两者相加才是总额。
 *       （实测 GOAT：28.81 + 41.19 = 70.00，与官方月额度 $70 吻合。）</li>
 *   <li><b>小百分比不能被抹成 0。</b> 已用 0.41 / 上限 14 = 2.9%，
 *       但如果某次只用了 0.001，四舍五入到整数会显示 0%，看起来像没消耗。
 *       因此按量级选精度：小于 1% 给两位小数。</li>
 * </ol>
 *
 * <p>金额单位是**美元**（官方字段名叫 credits，但数值与请求成本一致）。
 */
final class CommandCodeUsage {

    // ── 额度 ──
    double monthlyBalance;
    double purchasedCredits;
    double freeCredits;

    // ── 滚动窗口（每 5 小时 / 每周）──
    double fiveUsed, fiveCap;
    long fiveResetAt;
    boolean fiveExceeded;
    double weekUsed, weekCap;
    long weekResetAt;
    boolean weekExceeded;

    // ── 订阅 ──
    String planId = "";
    String status = "";
    long periodStart, periodEnd;
    boolean cancelAtPeriodEnd;

    // ── 本期用量 ──
    int requestCount, failedCount;
    double successRate;
    double totalCost;
    long tokensIn, tokensOut, tokensTotal;

    /** 是否拿到任何有效数据（四个端点全失败时用来决定提示文案）。 */
    boolean hasCredits, hasSubscription, hasUsage;

    // ================================================================ 派生量

    /** 月度额度总额 = 剩余 + 已用。服务端不直接给分母，只能这样推。 */
    double monthlyTotal() {
        return monthlyBalance + purchasedCredits + freeCredits + totalCost;
    }

    /** 月度已用百分比（0–100）。分母为 0 时返回 0。 */
    double monthlyUsedPercent() {
        double total = monthlyTotal();
        return total <= 0 ? 0 : clamp(totalCost / total * 100.0);
    }

    /** 月度剩余百分比（0–100）。 */
    double monthlyRemainPercent() {
        return clamp(100.0 - monthlyUsedPercent());
    }

    double fivePercent() {
        return percent(fiveUsed, fiveCap);
    }

    double weekPercent() {
        return percent(weekUsed, weekCap);
    }

    private static double percent(double used, double cap) {
        return cap <= 0 ? 0 : clamp(used / cap * 100.0);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(100, v));
    }

    // ================================================================ 格式化

    /**
     * 金额：统一美元两位小数。
     *
     * <p>余额可能小于 1 分（例如 0.004），这时两位小数会显示成 $0.00 ——
     * 看起来像已用完。因此小于 0.01 时改用四位小数。
     */
    static String money(double v) {
        if (Double.isNaN(v)) return "$0.00";
        double a = Math.abs(v);
        if (a > 0 && a < 0.01) return String.format(Locale.ROOT, "$%.4f", v);
        return String.format(Locale.ROOT, "$%.2f", v);
    }

    /**
     * 百分比：按量级选精度。
     *
     * <p>0.002% 抹成 0% 会让人以为没有消耗；20.4% 显示两位小数又太啰嗦。
     */
    static String percentText(double p) {
        if (Double.isNaN(p) || p <= 0) return "0%";
        if (p < 1) return String.format(Locale.ROOT, "%.2f%%", p);
        if (p < 10) return String.format(Locale.ROOT, "%.1f%%", p);
        return String.format(Locale.ROOT, "%.0f%%", p);
    }

    /**
     * 套餐名：把 {@code individual-goat} 之类的 id 变成可读标签。
     *
     * <p>无法识别时**原样返回 id** 而不是编一个名字 —— 猜错档位比显示原始 id 更糟。
     */
    static String planLabel(String planId) {
        if (planId == null || planId.length() == 0) return "未知套餐";
        String id = planId.toLowerCase(Locale.ROOT);
        if (id.contains("goat")) return "GOAT";
        if (id.contains("max")) return "Max";
        if (id.contains("pro")) return "Pro";
        if (id.contains("team")) return "Team";
        if (id.contains("go")) return "Go";
        return planId;
    }

    /** 订阅状态的中文标签；无法识别时原样返回。 */
    static String statusLabel(String status) {
        if (status == null || status.length() == 0) return "";
        String s = status.toLowerCase(Locale.ROOT);
        if (s.equals("active")) return "生效中";
        if (s.equals("trialing")) return "试用中";
        if (s.equals("past_due")) return "逾期";
        if (s.equals("canceled") || s.equals("cancelled")) return "已取消";
        if (s.equals("incomplete")) return "未完成";
        if (s.equals("unpaid")) return "未付款";
        return status;
    }

    /** 把 epoch 毫秒格式化为「MM-dd」；无效值返回空串。 */
    static String day(long ms) {
        if (ms <= 0) return "";
        try {
            return new SimpleDateFormat("MM-dd", Locale.ROOT).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 距离某时刻还有多久，形如 {@code 3 小时 12 分}。
     *
     * <p>返回空串表示已过或时间无效 —— 调用方据此隐藏该行，
     * 而不是显示「0 分钟」这种没有信息量的内容。
     */
    static String until(long targetMs, long nowMs) {
        if (targetMs <= 0 || nowMs <= 0) return "";
        long d = targetMs - nowMs;
        if (d <= 0) return "";
        long min = d / 60000;
        long h = min / 60;
        long day = h / 24;
        if (day > 0) return day + " 天 " + (h % 24) + " 小时";
        if (h > 0) return h + " 小时 " + (min % 60) + " 分";
        return Math.max(1, min) + " 分钟";
    }

    /** Token 数量：用 B / M / K 缩写，避免显示十位数。 */
    static String tokens(long n) {
        if (n < 0) return "0";
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(Locale.ROOT, "%.1f K", n / 1000.0);
        if (n < 1000000000L) return String.format(Locale.ROOT, "%.1f M", n / 1000000.0);
        return String.format(Locale.ROOT, "%.2f B", n / 1000000000.0);
    }

    /** 成功率：服务端给的是 0–100 的数；非法值返回空串。 */
    static String successText(double rate, int total, int failed) {
        if (total <= 0) return "暂无请求";
        double r = Double.isNaN(rate) ? (100.0 * (total - failed) / total) : rate;
        return String.format(Locale.ROOT, "%.1f%%", r);
    }

    /**
     * 额度告警级别：0 正常、1 注意、2 严重。
     *
     * <p>用途是决定界面上用哪种颜色 —— 让「快用完了」这件事一眼可见，
     * 而不是让用户自己去读数字。
     */
    int alertLevel() {
        double worst = Math.max(Math.max(monthlyUsedPercent(), fivePercent()), weekPercent());
        if (fiveExceeded || weekExceeded || worst >= 95) return 2;
        if (worst >= 80) return 1;
        return 0;
    }
}
