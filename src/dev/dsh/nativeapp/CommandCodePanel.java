package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Command Code 订阅与用量面板。
 *
 * <p>数据来自三个端点（账户 API Key 认证，**不需要浏览器 Cookie**）：
 * {@code /alpha/billing/credits}、{@code /alpha/billing/subscriptions}、
 * {@code /alpha/usage/summary}。推导与格式化在纯逻辑类
 * {@link CommandCodeUsage} 里（有 38 项离线测试）。
 *
 * <h3>手机适配上的几个决定</h3>
 * <ul>
 *   <li><b>不用表格。</b> 表格在窄屏必然挤压 —— 改成一行为一项、
 *       标签在左数值在右，两端对齐，中间留空。</li>
 *   <li><b>每个端点独立降级。</b> 某个端点失败只显示一条说明，
 *       不会清空整块 —— 否则余额能看但请求数拿不到时会显得像全坏了。</li>
 *   <li><b>进度条自绘。</b> 用系统 SeekBar 会被主题染色且高度不可控，
 *       自绘才能保证与卡片圆角、配色一致。</li>
 *   <li><b>数值全部单行 + 末尾省略</b>，并把「$0.41 / $14」这种
 *       带分隔符的字符串交给等宽字体，避免数字宽度跳动。</li>
 * </ul>
 *
 * <h3>失败也要说清是什么失败</h3>
 * 401/403（密钥失效）、429（限流）、超时、DNS 失败的处置方式完全不同，
 * 因此 {@code get} 把 HTTP 状态码与错误类别一路带到界面上，
 * 而不是把状态码只写进日志、界面上统一显示「读取失败」。
 *
 * <h3>关闭面板之后</h3>
 * onDismiss 置 {@code closed} 标记并改用 {@code io.shutdown()} ——
 * {@code removeCallbacksAndMessages} 只能清掉当时已入队的消息，
 * 清不掉后台线程此后新 post 的那个。
 */
public final class CommandCodePanel {

    private static final String API = "https://api.commandcode.ai";
    private static final int TIMEOUT = 12000;

    private CommandCodePanel() { }

    /** 状态色：正常 / 注意 / 严重，与 DSH 的语义色一致。 */
    private static final int OK = 0xFF1A7F37;
    private static final int WARN = 0xFFB26A00;
    private static final int DANGER = 0xFFD93025;

    /**
     * 打开面板。
     *
     * @param apiKey Command Code 账户密钥（不会写入日志）
     */
    public static void show(final Activity act, final String apiKey) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "Command Code"));

        if (apiKey == null || apiKey.length() == 0) {
            body.addView(DshUi.hint(act, "未配置 Command Code 密钥。\n"
                    + "请先在上方填入 COMMANDCODE_API_KEY 并保存。"), DshUi.fullWidth(act, 8));
            Button close = DshUi.button(act, "关闭", true);
            final Dialog d = DshUi.dialog(act, body, DshUi.footer(act, close), 300);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            d.show();
            return;
        }

        final LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 8);
        body.addView(scroll, slp);

        final Button refresh = DshUi.button(act, "刷新", false);
        Button close = DshUi.button(act, "关闭", true);

        final Dialog dlg = DshUi.dialogFill(act, body, DshUi.footer(act, refresh, close), 820);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        final Handler ui = new Handler(Looper.getMainLooper());
        final ExecutorService io = Executors.newSingleThreadExecutor();

        // 关闭标记。onDismiss 里的 removeCallbacksAndMessages 只能清掉**当时已入队**
        // 的消息，清不掉后台线程此后新 post 的那个 —— 那会继续往已经消失的
        // 容器里 addView。所以每个 post 回调都要自己看这个标记。
        final boolean[] closed = {false};

        // 读取代际。刷新按钮在读取期间被禁用，正常点不出第二轮；
        // 但只要有第二轮开始，它的 removeAllViews() 就可能夹在上一轮
        // 尚未执行的渲染回调之前 —— 旧数据会重新出现在清空后的容器里。
        final int[] gen = {0};

        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                closed[0] = true;
                // shutdown 而不是 shutdownNow：shutdownNow 会**排空尚未开始**的任务 ——
                // 那正是「点了刷新却什么都没发生」的来源。shutdown 只停止收新任务，
                // 已入队的照常跑完（结果因 closed 不再上屏）。
                io.shutdown();
                ui.removeCallbacksAndMessages(null);
            }
        });

        final Runnable[] load = new Runnable[1];
        load[0] = new Runnable() {
            @Override public void run() {
                final int my = ++gen[0];
                content.removeAllViews();
                // 读取期间禁用并换文案：三个端点串行请求，最坏情况要等几十秒，
                // 期间连点会把同一份读取排好几次队。
                DshUi.setBusy(refresh, "刷新", "刷新中…", true);
                final TextView loading = DshUi.hint(act, "正在读取…");
                content.addView(loading, DshUi.fullWidth(act, 0));

                io.execute(new Runnable() {
                    @Override public void run() {
                        final CommandCodeUsage u = new CommandCodeUsage();
                        String creditsErr = null, subErr = null, usageErr = null;

                        // 三个端点各自独立：任何一个失败都不影响其它两块
                        final Fetch credits = get("/alpha/billing/credits", apiKey);
                        final Fetch sub = get("/alpha/billing/subscriptions", apiKey);
                        final Fetch usage = get("/alpha/usage/summary", apiKey);

                        // 失败原因（HTTP 状态 / 超时 / DNS）一路带到界面上：
                        // 401 是密钥失效、429 是限流、超时是网络 —— 处置方式完全不同，
                        // 全压成一句「读取失败」等于把用户唯一能自助排查的线索丢掉。
                        if (!credits.ok()) creditsErr = credits.error;
                        else {
                            try { parseCredits(credits.body, u); u.hasCredits = true; }
                            catch (Throwable t) { creditsErr = "响应无法解析（API 可能已变更）"; }
                        }
                        if (!sub.ok()) subErr = sub.error;
                        else {
                            try { parseSubscription(sub.body, u); u.hasSubscription = true; }
                            catch (Throwable t) { subErr = "响应无法解析（API 可能已变更）"; }
                        }
                        if (!usage.ok()) usageErr = usage.error;
                        else {
                            try { parseUsage(usage.body, u); u.hasUsage = true; }
                            catch (Throwable t) { usageErr = "响应无法解析（API 可能已变更）"; }
                        }

                        final String e1 = creditsErr, e2 = subErr, e3 = usageErr;
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (closed[0]) return;      // 面板已关：视图已不存在
                                if (my != gen[0]) return;   // 已被新一轮取代：旧数据不上屏
                                DshUi.setBusy(refresh, "刷新", "刷新中…", false);
                                render(act, content, u, e1, e2, e3);
                            }
                        });
                    }
                });
            }
        };
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load[0].run(); }
        });

        dlg.show();
        load[0].run();
    }

    // ================================================================ 渲染

    private static void render(Activity act, LinearLayout box, CommandCodeUsage u,
                               String creditsErr, String subErr, String usageErr) {
        box.removeAllViews();

        boolean allFailed = !u.hasCredits && !u.hasSubscription && !u.hasUsage;

        // ── 头部：套餐与周期 ──
        if (u.hasSubscription) {
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView plan = new TextView(act);
            plan.setText(CommandCodeUsage.planLabel(u.planId));
            plan.setTextSize(17f);
            plan.setTextColor(DshUi.TEXT);
            plan.setSingleLine(true);
            head.addView(plan, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView st = new TextView(act);
            st.setText(CommandCodeUsage.statusLabel(u.status));
            st.setTextSize(12f);
            st.setTextColor(u.alertLevel() == 0 ? OK : WARN);
            st.setSingleLine(true);
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stlp.leftMargin = DshUi.dp(act, 8);
            head.addView(st, stlp);

            box.addView(head, DshUi.fullWidth(act, 0));

            String period = CommandCodeUsage.day(u.periodStart) + " → "
                    + CommandCodeUsage.day(u.periodEnd);
            if (u.cancelAtPeriodEnd) period += "（到期后不续订）";
            if (period.trim().length() > 0) {
                box.addView(DshUi.hint(act, "本期 " + period), DshUi.fullWidth(act, 4));
            }
        } else {
            box.addView(DshUi.hint(act, "订阅信息不可用"
                    + (subErr == null ? "" : "（" + subErr + "）")), DshUi.fullWidth(act, 0));
        }

        // ── 额度 ──
        box.addView(DshUi.sectionLabel(act, "额度"), DshUi.fullWidth(act, 18));
        if (u.hasCredits) {
            LinearLayout card = card(act);

            // 月度余额：服务端只给余额，分母由「余额 + 已用」推出
            row(act, card, "月度余额",
                    CommandCodeUsage.money(u.monthlyBalance), DshUi.TEXT, 14f);
            if (u.hasUsage) {
                bar(act, card, u.monthlyRemainPercent(),
                        "已用 " + CommandCodeUsage.percentText(u.monthlyUsedPercent())
                      + "　总额 " + CommandCodeUsage.money(u.monthlyTotal()),
                        u.alertLevel());
            }
            if (u.purchasedCredits > 0 || u.freeCredits > 0) {
                row(act, card, "额外额度",
                        CommandCodeUsage.money(u.purchasedCredits + u.freeCredits),
                        DshUi.TEXT_2, 11f);
            }

            // 两个滚动窗口
            LinearLayout win = card(act);
            windowBlock(act, win, "5 小时窗口", u.fiveUsed, u.fiveCap,
                    u.fivePercent(), u.fiveResetAt, u.fiveExceeded);
            windowBlock(act, win, "每周窗口", u.weekUsed, u.weekCap,
                    u.weekPercent(), u.weekResetAt, u.weekExceeded);
            box.addView(win, DshUi.fullWidth(act, 8));
        } else {
            box.addView(DshUi.hint(act, "额度不可用"
                    + (creditsErr == null ? "" : "（" + creditsErr + "）")), DshUi.fullWidth(act, 0));
        }

        // ── 本期用量 ──
        box.addView(DshUi.sectionLabel(act, "本期用量"), DshUi.fullWidth(act, 18));
        if (u.hasUsage) {
            LinearLayout card = card(act);
            row(act, card, "请求数", String.format(Locale.ROOT, "%,d", u.requestCount),
                    DshUi.TEXT, 13f);
            row(act, card, "成功率",
                    CommandCodeUsage.successText(u.successRate, u.requestCount, u.failedCount),
                    u.failedCount > 0 ? WARN : DshUi.TEXT, 13f);
            if (u.failedCount > 0) {
                row(act, card, "失败", String.valueOf(u.failedCount), WARN, 13f);
            }
            row(act, card, "成本", CommandCodeUsage.money(u.totalCost), DshUi.TEXT, 13f);
            row(act, card, "Token 总量", CommandCodeUsage.tokens(u.tokensTotal), DshUi.TEXT, 13f);
            row(act, card, "　输入 / 输出",
                    CommandCodeUsage.tokens(u.tokensIn) + " / " + CommandCodeUsage.tokens(u.tokensOut),
                    DshUi.TEXT_3, 11f);
            box.addView(card, DshUi.fullWidth(act, 8));
        } else {
            box.addView(DshUi.hint(act, "用量不可用"
                    + (usageErr == null ? "" : "（" + usageErr + "）")), DshUi.fullWidth(act, 0));
        }

        if (allFailed) {
            body_failHint(act, box, mergeReason(creditsErr, subErr, usageErr));
        }

        DshUi.log("Command Code 用量: 套餐=" + u.planId + " 状态=" + u.status
                + " 余额=" + CommandCodeUsage.money(u.monthlyBalance)
                + " 5h=" + CommandCodeUsage.percentText(u.fivePercent())
                + " 周=" + CommandCodeUsage.percentText(u.weekPercent())
                + " 请求=" + u.requestCount);
    }

    /**
     * 三个端点全失败时，给出统一的原因判断。
     *
     * <p>不再是三行一样的「读取失败」，而是**实际拿到的原因**加上按状态码
     * 分类的排查方向 —— 用户能据此判断该换密钥、该等一会儿、还是该去查网络。
     */
    private static void body_failHint(Activity act, LinearLayout box, String reason) {
        StringBuilder sb = new StringBuilder("三个端点都不可用。");
        if (reason != null && reason.length() > 0) {
            sb.append("实际原因：").append(reason).append("。");
        }
        sb.append("\n　· HTTP 401 / 403：密钥无效或已失效")
          .append("\n　· HTTP 429：请求过于频繁，稍后再试")
          .append("\n　· HTTP 5xx：服务端临时故障")
          .append("\n　· 超时 / 域名解析失败：网络不通（可先用「网络诊断」确认）");
        box.addView(DshUi.hint(act, sb.toString()), DshUi.fullWidth(act, 14));
    }

    /**
     * 把三个端点的失败原因合成一句话。
     *
     * <p>同一原因只报一次：密钥失效时三个端点会返回一模一样的 401，
     * 逐条列出来只会让这一行变成三遍重复。
     */
    private static String mergeReason(String e1, String e2, String e3) {
        String[] all = new String[]{ e1, e2, e3 };
        String[] label = new String[]{ "套餐", "额度", "用量" };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.length; i++) {
            if (all[i] == null || all[i].length() == 0) continue;
            if (sb.indexOf(all[i]) >= 0) continue;
            if (sb.length() > 0) sb.append("；");
            sb.append(label[i]).append(" ").append(all[i]);
        }
        return sb.toString();
    }

    private static LinearLayout card(Activity act) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(DshUi.cardBg(act));
        int p = DshUi.dp(act, 14);
        c.setPadding(p, DshUi.dp(act, 10), p, DshUi.dp(act, 12));
        return c;
    }

    /**
     * 一行：左侧标签、右侧数值，两端对齐。
     *
     * <p>用权重分配宽度而不是表格 —— 表格在窄屏会挤压，
     * 而权重两端对齐在任何宽度下都成立。
     */
    private static void row(Activity act, LinearLayout parent, String label,
                            String value, int valueColor, float valueSize) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, DshUi.dp(act, 5), 0, DshUi.dp(act, 5));

        TextView l = new TextView(act);
        l.setText(label);
        l.setTextSize(12.5f);
        l.setTextColor(DshUi.TEXT_2);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(valueSize);
        v.setTextColor(valueColor);
        // 等宽字体让「$28.81」这类数字宽度稳定，多行时右侧不会参差
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = DshUi.dp(act, 10);
        r.addView(v, vlp);

        parent.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 一个滚动窗口：用量 / 上限 + 进度条 + 重置倒计时。 */
    private static void windowBlock(Activity act, LinearLayout parent, String title,
                                    double used, double cap, double pct,
                                    long resetAt, boolean exceeded) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, DshUi.dp(act, 7), 0, 0);

        TextView l = new TextView(act);
        l.setText(title);
        l.setTextSize(12.5f);
        l.setTextColor(DshUi.TEXT_2);
        l.setSingleLine(true);
        r.addView(l, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(act);
        v.setText(CommandCodeUsage.money(used) + " / " + CommandCodeUsage.money(cap)
                + "　" + CommandCodeUsage.percentText(pct));
        v.setTextSize(11.5f);
        v.setTextColor(exceeded ? DANGER : (pct >= 80 ? WARN : DshUi.TEXT));
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f);
        vlp.leftMargin = DshUi.dp(act, 10);
        r.addView(v, vlp);
        parent.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 进度条
        Bar bar = new Bar(act, pct, exceeded ? DANGER : (pct >= 80 ? WARN : DshUi.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, DshUi.dp(act, 5));
        blp.topMargin = DshUi.dp(act, 6);
        parent.addView(bar, blp);

        // 倒计时：为空表示已过期，此时整行不显示（不写「0 分钟」这种废话）
        String left = CommandCodeUsage.until(resetAt, System.currentTimeMillis());
        if (left.length() > 0) {
            TextView t = DshUi.hint(act, left + "后重置");
            t.setTextSize(10.5f);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = DshUi.dp(act, 3);
            parent.addView(t, tlp);
        }
    }

    /** 进度条 + 下方说明（月度余额那块用）。 */
    private static void bar(Activity act, LinearLayout parent, double pct,
                            String caption, int level) {
        Bar b = new Bar(act, pct, level == 2 ? DANGER : (level == 1 ? WARN : DshUi.ACCENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, DshUi.dp(act, 5));
        blp.topMargin = DshUi.dp(act, 2);
        parent.addView(b, blp);

        TextView t = DshUi.hint(act, caption);
        t.setTextSize(10.5f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = DshUi.dp(act, 4);
        parent.addView(t, tlp);
    }

    /** 一条用量进度条：自绘，保证圆角与配色和卡片一致。 */
    private static final class Bar extends View {
        private final float pct;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Bar(Context c, double pct, int color) {
            super(c);
            this.pct = (float) Math.max(0, Math.min(100, pct));
            this.color = color;
        }

        @Override protected void onDraw(Canvas cv) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(0xFFEDEFF3);
            cv.drawRoundRect(rect, r, r, paint);
            if (pct <= 0) return;
            // 极小比例也留一小段可见宽度，否则看起来像没画出来
            float w = Math.max(getHeight(), getWidth() * pct / 100f);
            rect.set(0, 0, Math.min(w, getWidth()), getHeight());
            paint.setColor(color);
            cv.drawRoundRect(rect, r, r, paint);
        }
    }

    // ================================================================ 网络与解析

    /** 一次请求的结果：成功带正文，失败带**能直接展示给用户的原因**。 */
    private static final class Fetch {
        final String body;    // 成功时的响应正文；失败为 null
        final String error;   // 失败原因（如「HTTP 401 · 密钥无效」）；成功为 null
        Fetch(String body, String error) { this.body = body; this.error = error; }
        boolean ok() { return body != null; }
    }

    /**
     * 取一个端点。
     *
     * <p>失败不再只返回 null：调用方需要把**原因**带上界面。此前状态码只写进
     * 日志，界面上只有一句没有信息量的「读取失败」—— 而密钥失效（401）、
     * 限流（429）、网络超时对用户来说是完全不同的三件事，处置方式也不同。
     */
    private static Fetch get(String path, String key) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API + path).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "DSH-Native");
            int code = c.getResponseCode();
            if (code != 200) {
                String why = httpReason(code);
                DshUi.log("Command Code " + path + " → HTTP " + code);
                return new Fetch(null, why);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0 && bos.size() < 262144) bos.write(buf, 0, r);
            in.close();
            return new Fetch(bos.toString("UTF-8"), null);
        } catch (Throwable t) {
            String why = netReason(t);
            DshUi.log("Command Code " + path + " 失败: " + why + " / " + t);
            return new Fetch(null, why);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** HTTP 状态码 → 用户能理解的原因（把可自助排查的信息留在界面上）。 */
    private static String httpReason(int code) {
        if (code == 401) return "HTTP 401 · 密钥无效";
        if (code == 403) return "HTTP 403 · 无权访问该端点";
        if (code == 404) return "HTTP 404 · 端点不存在（API 可能已变更）";
        if (code == 429) return "HTTP 429 · 请求过于频繁，稍后再试";
        if (code >= 500) return "HTTP " + code + " · 服务端故障";
        return "HTTP " + code;
    }

    /**
     * 网络异常 → 原因。
     *
     * <p>只取第一行并截断：异常文案可能很长（甚至带堆栈），
     * 而它要显示在只有一行宽的说明区里。
     */
    private static String netReason(Throwable t) {
        if (t instanceof java.net.SocketTimeoutException) {
            return "超时（" + (TIMEOUT / 1000) + " 秒无响应）";
        }
        if (t instanceof java.net.UnknownHostException) return "域名解析失败";
        if (t instanceof javax.net.ssl.SSLException) return "TLS 握手失败（可能被代理拦截）";
        if (t instanceof java.net.ConnectException) return "连接被拒绝";
        String m = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg != null && msg.length() > 0) {
            int nl = msg.indexOf('\n');
            m += " · " + (nl > 0 ? msg.substring(0, nl) : msg);
        }
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    static void parseCredits(String json, CommandCodeUsage u) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(json);
        org.json.JSONObject cr = o.optJSONObject("credits");
        if (cr != null) {
            u.monthlyBalance = cr.optDouble("monthlyCredits", 0);
            u.purchasedCredits = cr.optDouble("purchasedCredits", 0);
            u.freeCredits = cr.optDouble("freeCredits", 0);
        }
        org.json.JSONObject wl = o.optJSONObject("windowLimits");
        if (wl != null) {
            org.json.JSONObject f = wl.optJSONObject("fiveHour");
            if (f != null) {
                u.fiveUsed = f.optDouble("used", 0);
                u.fiveCap = f.optDouble("cap", 0);
                u.fiveResetAt = f.optLong("resetAt", 0);
                u.fiveExceeded = f.optBoolean("exceeded", false);
            }
            org.json.JSONObject w = wl.optJSONObject("weekly");
            if (w != null) {
                u.weekUsed = w.optDouble("used", 0);
                u.weekCap = w.optDouble("cap", 0);
                u.weekResetAt = w.optLong("resetAt", 0);
                u.weekExceeded = w.optBoolean("exceeded", false);
            }
        }
    }

    static void parseSubscription(String json, CommandCodeUsage u) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(json);
        org.json.JSONObject d = o.optJSONObject("data");
        if (d == null) return;
        u.planId = d.optString("planId", "");
        u.status = d.optString("status", "");
        u.cancelAtPeriodEnd = d.optBoolean("cancelAtPeriodEnd", false);
        u.periodStart = parseIso(d.optString("currentPeriodStart", ""));
        u.periodEnd = parseIso(d.optString("currentPeriodEnd", ""));
    }

    static void parseUsage(String json, CommandCodeUsage u) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(json);
        u.requestCount = o.optInt("totalCount", o.optInt("completedCount", 0));
        u.failedCount = o.optInt("failedCount", 0);
        u.successRate = o.optDouble("successRate", Double.NaN);
        u.totalCost = o.optDouble("totalCost", 0);
        u.tokensIn = o.optLong("totalTokensIn", 0);
        u.tokensOut = o.optLong("totalTokensOut", 0);
        u.tokensTotal = o.optLong("totalTokens", u.tokensIn + u.tokensOut);
    }

    /**
     * 解析 ISO-8601 时间戳（服务端给的是 {@code 2026-09-10T09:59:29.000Z}）。
     *
     * <p><b>必须显式按 UTC 解析。</b>字符串结尾的 {@code Z} 表示 UTC，
     * 而 {@code SimpleDateFormat} 默认用**本地时区** —— 在 UTC+8 上
     * {@code 2026-09-10T09:59:29Z} 会被读成当地时间，比真实时刻早 8 小时，
     * 日期可能因此差一天（例如本期结束日显示成前一天）。
     */
    static long parseIso(String s) {
        if (s == null || s.length() < 10) return 0;
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Throwable t) {
            return 0;
        }
    }
}
