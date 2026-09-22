package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网络诊断。
 *
 * <p>设计原则：**测应用真正要用的那些路径**，而不是随便 ping 一个公共站点。
 * 应用对网络的全部依赖就三件事 —— 取版本清单、下 APK、下运行包分片，
 * 它们各自走固定的域名，所以这里逐个测这些域名才有意义：
 *
 * <table>
 *   <tr><td>版本清单</td><td>cdn.jsdelivr.net / fastly.jsdelivr.net /
 *       raw.githubusercontent.com / gh-proxy.com</td></tr>
 *   <tr><td>APK 与运行包</td><td>github.com（直连）/ gh-proxy.com / ghfast.top</td></tr>
 * </table>
 *
 * <p>每项分开报告的原因：这些域名**经常只有一部分可用**（例如直连 GitHub 不通
 * 但镜像通），只看一个「网络是否可用」的结论没有指导意义 ——
 * 需要知道的是「哪个源能用、更新会不会成功」。
 *
 * <p>检测用 {@link HttpURLConnection}，与更新功能**同一条代码路径**，
 * 因此结果能直接反映更新是否可用。
 */
public final class NetworkDiag {

    /** 单个请求的超时（毫秒）。太长会让诊断卡住，太短会误判慢速网络。 */
    private static final int TIMEOUT = 8000;

    /** 测速下载的字节数（512 KB）。足够反映速度，又不会浪费流量。 */
    private static final int SPEED_BYTES = 512 * 1024;

    private NetworkDiag() { }

    /** 一个待检测的目标。 */
    private static final class Target {
        final String label;
        final String url;
        final String host;
        Target(String label, String url, String host) {
            this.label = label; this.url = url; this.host = host;
        }
    }

    /** 版本清单的源（与 MainActivity 保持一致）。 */
    private static Target[] manifestTargets() {
        String repo = "yusheng266186-beep/DSH-Native";
        return new Target[]{
            new Target("jsDelivr", "https://cdn.jsdelivr.net/gh/" + repo + "@main/latest.json",
                    "cdn.jsdelivr.net"),
            new Target("jsDelivr(fastly)",
                    "https://fastly.jsdelivr.net/gh/" + repo + "@main/latest.json",
                    "fastly.jsdelivr.net"),
            new Target("GitHub raw",
                    "https://raw.githubusercontent.com/" + repo + "/main/latest.json",
                    "raw.githubusercontent.com"),
            new Target("gh-proxy", "https://gh-proxy.com/https://raw.githubusercontent.com/"
                    + repo + "/main/latest.json", "gh-proxy.com"),
        };
    }

    /**
     * 下载 APK 的源（与 MainActivity 的 SOURCES 顺序一致）。
     *
     * <p>URL 由调用方传入 —— 写死版本号会在下个版本静默失效
     * （诊断显示「失败」，但其实是地址过期了，不是网络问题）。
     */
    private static Target[] downloadTargets(String apkUrl) {
        String path = apkUrl;
        return new Target[]{
            new Target("直连", path, "github.com"),
            new Target("gh-proxy", "https://gh-proxy.com/" + path, "gh-proxy.com"),
            new Target("ghfast", "https://ghfast.top/" + path, "ghfast.top"),
        };
    }

    /**
     * 打开网络诊断。检测在后台线程执行，结果逐条追加到界面 ——
     * 一次性等待几秒什么都不显示，用户会以为界面卡死。
     */
    public static void show(final Activity act, final String apkUrl) {
        LinearLayout body = DshUi.paddedBody(act);
        body.addView(DshUi.title(act, "网络诊断"));

        final TextView summary = DshUi.hint(act, "正在检测…");
        body.addView(summary, DshUi.fullWidth(act, 6));

        final LinearLayout results = new LinearLayout(act);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setBackground(DshUi.cardBg(act));
        results.setPadding(DshUi.dp(act, 14), DshUi.dp(act, 10),
                DshUi.dp(act, 14), DshUi.dp(act, 10));
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.addView(results, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = DshUi.dp(act, 10);
        body.addView(scroll, slp);

        Button rerun = DshUi.button(act, "重新检测", false);
        Button close = DshUi.button(act, "关闭", true);
        final Dialog dlg = DshUi.dialogFill(act, body, DshUi.footer(act, rerun, close), 820);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); }
        });

        final Handler ui = new Handler(Looper.getMainLooper());
        final ExecutorService io = Executors.newSingleThreadExecutor();
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                io.shutdownNow();
                ui.removeCallbacksAndMessages(null);
            }
        });

        final Runnable[] run = new Runnable[1];
        run[0] = new Runnable() {
            @Override public void run() {
                results.removeAllViews();
                summary.setText("正在检测…");
                DshUi.log("网络诊断开始");
                io.execute(new Runnable() {
                    @Override public void run() {
                        final boolean[] state = new boolean[]{ false, false };  // 直连 / 镜像
                        final List<String> lines = new ArrayList<String>();

                        // ── DNS ──
                        post(ui, results, sectionTitle(act, "DNS 解析"));
                        for (String h : new String[]{ "cdn.jsdelivr.net", "fastly.jsdelivr.net",
                                "raw.githubusercontent.com", "gh-proxy.com", "ghfast.top",
                                "github.com" }) {
                            final String line = checkDns(h);
                            lines.add("DNS " + h + ": " + line);
                            post(ui, results, row(act, h, line));
                        }

                        // ── 版本清单源 ──
                        //
                        // 这里**不只看能不能连上，还要看返回的版本新不新** ——
                        // 实测 jsDelivr 的 @main 分支缓存会停在几十个版本之前：
                        // 连通性完全正常（200），但数据是旧的。
                        // 只报「可用」会让人以为更新检测没问题，实际可能永远发现不了新版本。
                        post(ui, results, sectionTitle(act, "版本清单源（含数据新鲜度）"));
                        int manifestOk = 0;
                        String bestVersion = null;
                        for (Target t : manifestTargets()) {
                            String fetched = fetchVersion(t.url);
                            final String line;
                            if (fetched == null) {
                                line = "失败（取不到或不是合法 JSON）";
                            } else {
                                manifestOk++;
                                bestVersion = Version.max(bestVersion, fetched);
                                line = "返回 " + fetched;
                            }
                            lines.add("清单 " + t.label + ": " + line);
                            post(ui, results, row(act, t.label, line));
                        }

                        // ── 下载源（含测速）──
                        post(ui, results, sectionTitle(act, "下载源（测速 512 KB）"));
                        int dlOk = 0;
                        final Target[] dls = downloadTargets(apkUrl);
                        for (int i = 0; i < dls.length; i++) {
                            final Target t = dls[i];
                            final boolean[] speed = new boolean[]{ true };
                            final String line = checkHttp(t, true, speed);
                            if (line.indexOf("可用") >= 0) {
                                dlOk++;
                                if (i == 0) state[0] = true;       // 直连
                                else state[1] = true;              // 镜像
                            }
                            lines.add("下载 " + t.label + ": " + line);
                            post(ui, results, row(act, t.label, line));
                        }

                        // ── 环境变量 ──
                        post(ui, results, sectionTitle(act, "代理环境变量"));
                        final String proxy = describeProxy();
                        post(ui, results, row(act, "http_proxy / https_proxy", proxy));
                        lines.add("代理: " + proxy);

                        // ── 结论 ──
                        final String verdict;
                        final String best = bestVersion;
                        if (dlOk > 0 && manifestOk > 0) {
                            verdict = "更新功能正常"
                                    + (state[0] ? "（直连可用）" : "（直连不通，走镜像）");
                            // 提示各源版本不一致：更新逻辑取最高值，因此仍能正常工作
                            if (best != null) {
                                final String tip = "提醒：各源返回的最高版本为 " + best
                                        + "。更新逻辑取所有源中的最高值，因此不受单个源缓存陈旧影响。";
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        results.addView(DshUi.hint(act, tip));
                                    }
                                });
                            }
                        } else if (dlOk > 0) {
                            verdict = "可下载，但版本清单的源都不通 —— 可能检测不到新版本";
                        } else if (manifestOk > 0) {
                            verdict = "能取到版本清单，但下载源都不通 —— 点更新会失败";
                        } else {
                            verdict = "网络不可用：请检查 WiFi / 移动数据 / VPN";
                        }
                        ui.post(new Runnable() {
                            @Override public void run() {
                                summary.setText(verdict);
                                TextView v = DshUi.hint(act, "结论：" + verdict);
                                v.setTextColor(DshUi.TEXT);
                                v.setPadding(0, DshUi.dp(act, 14), 0, 0);
                                results.addView(v);
                            }
                        });
                        for (String l : lines) DshUi.log("  " + l);
                        DshUi.log("网络诊断结论: " + verdict);
                    }
                });
            }
        };
        rerun.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run[0].run(); }
        });

        dlg.show();
        run[0].run();
    }

    // ---------------------------------------------------------------- 单项检测

    /** DNS 解析：返回「耗时 + 解析到的地址」。 */
    private static String checkDns(String host) {
        long t0 = System.currentTimeMillis();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            long ms = System.currentTimeMillis() - t0;
            if (addrs == null || addrs.length == 0) return "无结果 " + ms + " ms";
            String ip = addrs[0].getHostAddress();
            return ms + " ms   " + ip + (addrs.length > 1 ? " 等 " + addrs.length + " 个" : "");
        } catch (Throwable e) {
            return "失败（" + shortErr(e) + "）";
        }
    }

    /**
     * HTTPS 连通性检测。
     *
     * @param measureSpeed 是否顺带测速（只读 512 KB）
     * @param speedFlag    出参，测速失败时置 false
     */
    private static String checkHttp(Target t, boolean measureSpeed, boolean[] speedFlag) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(t.url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DSH-Native-NetDiag");
            if (measureSpeed) {
                c.setRequestProperty("Range", "bytes=0-" + (SPEED_BYTES - 1));
            }
            int code = c.getResponseCode();
            long head = System.currentTimeMillis() - t0;

            if (!measureSpeed) {
                // 只做连通性判断：读到 200 即认为该源可用
                return code + "  " + head + " ms" + (code == 200 ? "  可用" : "");
            }

            // 测速：读满 512 KB 或直到流结束
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            long read = 0;
            if (in != null) {
                byte[] buf = new byte[16384];
                int r;
                while (read < SPEED_BYTES && (r = in.read(buf)) > 0) read += r;
                in.close();
            }
            long total = System.currentTimeMillis() - t0;
            if (read <= 0) {
                if (speedFlag != null) speedFlag[0] = false;
                return code + "  " + head + " ms  未读到数据";
            }
            double mbps = read / 1048576.0 / Math.max(0.001, total / 1000.0);
            return code + "  " + head + " ms  "
                    + String.format(Locale.ROOT, "%.2f MB/s", mbps) + "  可用";
        } catch (Throwable e) {
            if (speedFlag != null) speedFlag[0] = false;
            return "失败（" + shortErr(e) + "）";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 从清单 URL 取版本号；取不到或格式不对返回 null。 */
    private static String fetchVersion(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "DSH-Native-NetDiag");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0 && bos.size() < 65536) bos.write(buf, 0, r);
            in.close();
            org.json.JSONObject o = new org.json.JSONObject(bos.toString("UTF-8"));
            String v = o.optString("version", null);
            return v == null || v.length() == 0 ? null : v;
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 代理环境变量：有代理时下载很可能失败，值得单独说明。 */
    private static String describeProxy() {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{ "http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY" }) {
            String v = System.getenv(k);
            if (v != null && v.length() > 0) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(k).append("=").append(v);
            }
        }
        return sb.length() == 0 ? "未设置（直连）" : sb.toString();
    }

    private static String shortErr(Throwable e) {
        String m = e.getClass().getSimpleName();
        String msg = e.getMessage();
        if (msg != null && msg.length() > 0) {
            // 只保留第一行，避免把整段堆栈塞进界面
            int nl = msg.indexOf('\n');
            m += ": " + (nl > 0 ? msg.substring(0, nl) : msg);
        }
        return m.length() > 70 ? m.substring(0, 70) + "…" : m;
    }

    // ---------------------------------------------------------------- 界面小件

    private static void post(Handler ui, final LinearLayout box, final View v) {
        ui.post(new Runnable() {
            @Override public void run() { box.addView(v); }
        });
    }

    private static View sectionTitle(Activity act, String text) {
        TextView tv = DshUi.sectionLabel(act, text);
        tv.setPadding(0, DshUi.dp(act, 14), 0, DshUi.dp(act, 4));
        return tv;
    }

    /** 一行结果：左侧名称（定宽），右侧结论。 */
    private static View row(Activity act, String name, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);

        TextView n = new TextView(act);
        n.setText(name);
        n.setTextSize(11.5f);
        n.setTextColor(DshUi.TEXT_2);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(n, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));

        TextView val = new TextView(act);
        val.setText(value);
        val.setTextSize(11.5f);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        // 失败用橙色标出：这是整页里唯一需要一眼找到的信息
        val.setTextColor(value.contains("失败") ? 0xFFB26A00 : DshUi.TEXT);
        val.setSingleLine(true);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f);
        vlp.leftMargin = DshUi.dp(act, 10);
        r.addView(val, vlp);
        return r;
    }
}
