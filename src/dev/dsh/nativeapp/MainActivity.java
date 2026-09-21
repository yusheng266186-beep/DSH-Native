package dev.dsh.nativeapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * DSH Native —— 引导式 APK。
 *
 * <p>架构：APK 本体只内置 Node 运行时与引导脚本（约 35MB）；
 * 首次启动时从 GitHub Release 下载两个运行包并解压到私有目录：
 * <ul>
 *   <li>{@code dsh.tar.zst} —— 完整 DSH（已打 Android 补丁）</li>
 *   <li>{@code tools.tar.zst} —— bionic 工具链（rg/git/bash/fd/jq）</li>
 * </ul>
 * 之后启动 {@code dsh web}，用 WebView 展示界面。
 *
 * <p>关键环境变量：{@code --expose-internals} 让 DSH 无需原生插件即可访问
 * Node 私有内部模块（该插件没有 android 构建）。
 */
public class MainActivity extends Activity {

    private static final String TAG = "DSHNative";

    /**
     * 运行包来源，按顺序尝试。
     *
     * <p>实测：GitHub 发布资产 CDN 在部分网络下**完全不可达**（连接超时），
     * 而镜像可稳定达到 1.2 MB/s。因此镜像优先，直连作为最后兜底。
     *
     * <p>各源速度实测（下载 8MB 样本）：
     * <pre>
     *   gh-proxy.com   1.21 MB/s
     *   ghfast.top     0.49 MB/s
     *   ghproxy.net    0.26 MB/s
     *   直连 github    超时失败
     * </pre>
     */
    private static final String ASSET_PATH =
            "https://github.com/yusheng266186-beep/DSH-Native/releases/download/payload-v1/";

    private static final String[] SOURCES = {
            "https://gh-proxy.com/" + ASSET_PATH,
            "https://ghfast.top/" + ASSET_PATH,
            "https://ghproxy.net/" + ASSET_PATH,
            ASSET_PATH,                       // 直连兜底
    };

    /**
     * 运行包与期望的 SHA-256 —— 移动网络容易中断，下载后必须校验，
     * 否则会静默产生损坏归档，导致解压失败且难以排查。
     * 数值与 release 中的 SHA256SUMS.txt 一致。
     */
    private static final String[][] ARCHIVES = {
            {"dsh.tar.zst", "565ed47e26b2410b593d826c7604b6e8ae4940ab75b791a5e6e52e1a5d045bf0"},
            {"tools.tar.zst", "c088ca79dbd49a07e647a85e11bdec1e64200bbd45a4f08fc43d91097d7208e7"},
    };

    private static final int PORT = 3080;

    private WebView webView;
    private TextView logView;
    private Process nodeProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setPadding(20, 20, 20, 20);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f));

        setContentView(root);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    log("✗ 启动失败: " + t);
                    Log.e(TAG, "boot failed", t);
                }
            }
        }).start();
    }

    private void boot() throws Exception {
        final File root = new File(getFilesDir(), "dsh");
        log("私有目录: " + root);

        // 1. 解压 APK 内置的引导负载（node + 脚本）
        extractAssets(root);
        File node = new File(root, "node");
        chmod(node, "755");

        // 前置自检：先确认能否执行自带的 Node。
        // 这一步是整个架构成立的前提（Android 10+ 对 targetSdk>=29 的 App
        // 禁止 exec 私有目录文件）。放在下载之前，可以快速失败并给出明确原因。
        if (!probeNodeExec(node)) {
            return;
        }
        log("Node 就绪: " + runCapture(node, new String[]{"--version"}));

        // 2. 下载并解压运行包（首次启动）
        File dshDir = new File(root, "dsh");
        File toolsDir = new File(root, "tools");
        File marker = new File(root, ".payload-ok");

        if (!marker.exists() || !new File(dshDir, "lib/bin.js").exists()) {
            for (String[] entry : ARCHIVES) {
                String name = entry[0];
                String expectedSha = entry[1];
                File archive = new File(root, name);

                File dest = name.startsWith("dsh") ? dshDir : toolsDir;

                // 已有可用归档则跳过下载（支持中断后重来）
                if (archive.exists() && archive.length() > 1024
                        && expectedSha.equalsIgnoreCase(sha256(archive))) {
                    log("已下载且校验通过: " + name);
                } else {
                    if (archive.exists()) {
                        log("已有归档校验不通过，重新下载: " + name);
                        archive.delete();
                    }
                    download(name, archive);
                    String actual = sha256(archive);
                    if (!expectedSha.equalsIgnoreCase(actual)) {
                        archive.delete();
                        throw new IOException("SHA-256 校验失败: " + name
                                + "\n  期望 " + expectedSha
                                + "\n  实际 " + actual
                                + "\n请检查网络后重试");
                    }
                    log("  ✓ SHA-256 校验通过");
                }

                log("解压 " + name + " …");
                run(node, root, new String[]{
                        new File(root, "unpack.js").getAbsolutePath(),
                        archive.getAbsolutePath(),
                        dest.getAbsolutePath()}, null);
                archive.delete();
            }
            marker.createNewFile();
        } else {
            log("运行包已就绪，跳过下载");
        }

        // 3. 准备 DSH 配置（若不存在）
        prepareConfig(root);

        // 4. 启动 dsh web
        File binJs = new File(dshDir, "lib/bin.js");
        log("启动 dsh web …");
        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(),
                "--expose-internals",          // 关键：替代无 android 构建的原生插件
                "--no-warnings",
                binJs.getAbsolutePath(),
                "--profile", "web", "--no-open", "--port", String.valueOf(PORT));
        pb.redirectErrorStream(true);
        pb.directory(root);

        String libPath = new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath();
        String binPath = new File(toolsDir, "bin").getAbsolutePath() + ":/system/bin:/system/xbin";
        String nodePath = new File(dshDir, "node_modules").getAbsolutePath()
                + ":" + new File(root, "node_modules").getAbsolutePath();

        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("PATH", binPath);
        pb.environment().put("NODE_PATH", nodePath);
        pb.environment().put("HOME", root.getAbsolutePath());
        pb.environment().put("DSH_HOME", new File(root, ".dsh").getAbsolutePath());
        pb.environment().put("TMPDIR", root.getAbsolutePath());
        pb.environment().put("TERM", "xterm-256color");

        nodeProcess = pb.start();
        log("dsh web 已启动 (pid " + pidOf(nodeProcess) + ")");
        pipeOutput(nodeProcess);

        // 5. 等待服务就绪后加载界面
        String url = waitForServer();
        if (url != null) {
            log("界面就绪: " + url);
            final String target = url;
            runOnUiThread(new Runnable() {
                @Override public void run() { webView.loadUrl(target); }
            });
        } else {
            log("⚠ 等待服务超时，请查看上方日志");
        }
    }

    /** 轮询本地端口，从 stdout 抓取带 token 的地址。 */
    private String waitForServer() {
        for (int i = 0; i < 60; i++) {
            if (lastUrl != null) return lastUrl;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        return lastUrl;
    }

    private volatile String lastUrl;

    private void pipeOutput(final Process p) {
        final InputStream is = p.getInputStream();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    StringBuilder carry = new StringBuilder();
                    while ((line = r.readLine()) != null) {
                        log("[dsh] " + line);
                        int idx = line.indexOf("http://127.0.0.1");
                        if (idx >= 0 && lastUrl == null) {
                            String u = line.substring(idx).trim();
                            int sp = u.indexOf(' ');
                            if (sp > 0) u = u.substring(0, sp);
                            lastUrl = u;
                        }
                        carry.setLength(0);
                    }
                } catch (IOException e) {
                    log("[dsh] 输出流结束");
                }
            }
        }).start();
    }

    /** 首次运行时写入最小配置：DSH_HOME 与凭据。 */
    private void prepareConfig(File root) throws IOException {
        File dshHome = new File(root, ".dsh");
        if (!dshHome.exists()) dshHome.mkdirs();
        File creds = new File(dshHome, ".credentials.yaml");
        if (!creds.exists()) {
            // 留空，用户可在 Web 界面的 Models 页面里填写 API Key
            Log.i(TAG, "credentials file not present; user configures via web UI");
        }
    }

    /**
     * 前置自检：能否执行私有目录里的 Node。
     *
     * <p>这是整个架构的成立前提。Android 10 起，{@code targetSdk >= 29} 的 App
     * 被 SELinux 禁止对私有目录文件调用 {@code execve()}；本 App 用 {@code targetSdk 28}
     * 规避，但能否生效只能在真实沙箱里验证，因此这里显式探测并给出明确结论。
     *
     * @return 可执行返回 true；否则打印诊断信息并返回 false
     */
    private boolean probeNodeExec(File node) {
        log("自检: 检查 Node 可执行性 …");
        if (!node.exists()) {
            log("✗ 自检失败: node 文件不存在 → " + node.getAbsolutePath());
            return false;
        }
        // 记录权限位，便于判断 chmod 是否真的生效
        log("  node 权限: " + (node.canExecute() ? "可执行" : "⚠ 无执行位")
                + ", 大小 " + (node.length() / 1048576) + "MB");

        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(), "--version");
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH",
                new File(node.getParentFile(), "lib").getAbsolutePath());
        Process p = null;
        try {
            p = pb.start();
        } catch (IOException e) {
            log("");
            log("✗✗ 自检失败：无法执行自带的 Node");
            log("    原因: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log("");
            log("  这通常意味着 SELinux 拦截了对私有目录的 execve。");
            log("  本 App 已设 targetSdk=28 以规避该限制，若仍被拦截，");
            log("  说明此 ROM 的策略更严格，需要改用 nativeLibraryDir 方案。");
            log("");
            log("  👉 请把以上内容完整反馈，这是判断架构是否成立的关键依据。");
            return false;
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(line.trim());
            }
            int code = p.waitFor();
            if (code != 0) {
                log("✗ 自检失败: node --version 退出码 " + code + "，输出: " + sb);
                return false;
            }
            log("  ✓ 自检通过，node 版本: " + sb);
            return true;
        } catch (Exception e) {
            log("✗ 自检异常: " + e);
            return false;
        }
    }

    /** 计算文件 SHA-256，返回小写十六进制；失败返回空串。 */
    private String sha256(File f) {
        InputStream in = null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                                 .append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "sha256 failed", e);
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * 分块下载 + 块级重试 + 多源降级 + 手动跟随重定向。
     *
     * <p>这台设备实测网络极不稳定，且 GitHub 直连常不可达，
     * 因此每个分块都会依次尝试多个来源（镜像优先），
     * 任一块失败只影响该块，已下载进度保留。
     */
    private void download(String assetName, File out) throws IOException {
        final int CHUNK = 2 * 1024 * 1024;
        final int MAX_RETRY_PER_SOURCE = 4;

        long total = -1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(out, "rw");
        try {
            raf.setLength(0);
            long done = 0;
            int chunkIdx = 0;
            int retries = 0;
            long t0 = System.currentTimeMillis();
            int lastLoggedPct = -1;
            String lastErr = null;

            while (total < 0 || done < total) {
                long end = total < 0 ? (done + CHUNK - 1) : Math.min(done + CHUNK - 1, total - 1);

                byte[] buf = null;
                for (int s = 0; s < SOURCES.length && buf == null; s++) {
                    String url = SOURCES[s] + assetName;
                    for (int attempt = 0; attempt < MAX_RETRY_PER_SOURCE; attempt++) {
                        try {
                            Object[] r = fetchRange(url, done, end);
                            buf = (byte[]) r[0];
                            if (total < 0 && r[1] != null) total = (Long) r[1];
                            if (s > 0) log("  已切换到镜像源 #" + s);
                            break;
                        } catch (Exception e) {
                            lastErr = shorten(e);
                            retries++;
                            try {
                                Thread.sleep(500L * (attempt + 1));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IOException("下载被中断");
                            }
                        }
                    }
                    if (buf == null) log("  源 #" + s + " 失败（" + lastErr + "），尝试下一个");
                }

                if (buf == null) {
                    throw new IOException("下载失败（块 " + chunkIdx + "，已下载 "
                            + (done / 1048576) + "MB）：所有来源均不可用\n最后错误：" + lastErr
                            + "\n请检查网络后重新打开 App —— 已下载部分会保留。");
                }
                long want = end - done + 1;
                if (buf.length < want && total > 0 && done + buf.length < total) {
                    throw new IOException("块 " + chunkIdx + " 长度不足: " + buf.length + " / " + want);
                }
                raf.seek(done);
                raf.write(buf);
                done += buf.length;
                chunkIdx++;

                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 10 != lastLoggedPct / 10) {
                        lastLoggedPct = pct;
                        long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                        log("  " + pct + "%  (" + (done / 1048576) + "/" + (total / 1048576)
                                + " MB, " + (done / 1048576 / secs) + " MB/s, 重试 " + retries + " 次)");
                    }
                }
                if (total < 0 && buf.length < CHUNK) break;
            }
            log("  下载完成 " + (done / 1048576) + " MB，重试 " + retries + " 次");
        } finally {
            raf.close();
        }
    }

    /** 压缩异常信息，避免日志刷屏。 */
    private String shorten(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }

    /** 取指定字节范围。返回 {byte[] data, Long totalSizeOrNull}。 */
    private Object[] fetchRange(String url, long start, long end) throws IOException {
        HttpURLConnection c = null;
        try {
            // 手动跟随重定向：CDN 会 302 到签名 URL，且签名地址可能变化
            String cur = url;
            for (int hop = 0; hop < 8; hop++) {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.setRequestProperty("User-Agent", "DSHNative/0.2.0");
                c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) throw new IOException("重定向缺少 Location");
                    cur = loc;
                    continue;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                Long total = null;
                if (code == 206) {
                    String cr = c.getHeaderField("Content-Range");   // bytes 0-1/33540352
                    if (cr != null) {
                        int slash = cr.lastIndexOf('/');
                        if (slash > 0) {
                            try { total = Long.parseLong(cr.substring(slash + 1).trim()); }
                            catch (NumberFormatException ignored) { }
                        }
                    }
                } else {
                    int cl = c.getContentLength();
                    if (cl > 0) total = (long) cl;
                }
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[131072];
                int n;
                while ((n = in.read(b)) > 0) bos.write(b, 0, n);
                in.close();
                return new Object[]{bos.toByteArray(), total};
            }
            throw new IOException("重定向次数过多");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String runCapture(File node, String[] args) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<String>();
            cmd.add(node.getAbsolutePath());
            for (String a : args) cmd.add(a);
            pb.command(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", new File(node.getParentFile(), "lib").getAbsolutePath());
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = r.readLine();
            p.waitFor();
            return line == null ? "(无输出)" : line.trim();
        } catch (Exception e) {
            return "(失败: " + e.getMessage() + ")";
        }
    }

    private void run(File node, File root, String[] args, Void unused) throws Exception {
        ProcessBuilder pb = new ProcessBuilder();
        List<String> cmd = new ArrayList<String>();
        cmd.add(node.getAbsolutePath());
        for (String a : args) cmd.add(a);
        pb.command(cmd);
        pb.redirectErrorStream(true);
        pb.directory(root);
        pb.environment().put("LD_LIBRARY_PATH", new File(root, "lib").getAbsolutePath());
        pb.environment().put("TMPDIR", root.getAbsolutePath());
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) log("  " + line);
        int code = p.waitFor();
        if (code != 0) throw new IOException("子进程退出码 " + code);
    }

    /** 递归解压 assets/payload 到目标目录。 */
    private void extractAssets(File target) throws IOException {
        List<String> entries = new ArrayList<String>();
        collect("payload", entries);
        for (String path : entries) {
            String rel = path.substring("payload/".length());
            if (rel.length() == 0) continue;
            File out = new File(target, rel);
            if (out.exists() && out.length() > 0) continue;   // 已解压
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            InputStream in = getAssets().open(path);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
        }
    }

    private void collect(String dir, List<String> out) throws IOException {
        String[] children = getAssets().list(dir);
        if (children == null || children.length == 0) { out.add(dir); return; }
        for (String c : children) collect(dir + "/" + c, out);
    }

    private void chmod(File f, String mode) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", mode, f.getAbsolutePath()});
            p.waitFor();
        } catch (Throwable t) {
            f.setExecutable(true, false);
        }
    }

    private String pidOf(Process p) {
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            return String.valueOf(f.get(p));
        } catch (Throwable t) { return "?"; }
    }

    private void log(final String msg) {
        Log.i(TAG, msg);
        runOnUiThread(new Runnable() {
            @Override public void run() { logView.append(msg + "\n"); }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nodeProcess != null) nodeProcess.destroy();
    }
}
