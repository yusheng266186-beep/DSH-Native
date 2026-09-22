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

    /** 首选端口。实际使用 chosenPort —— 3080 常被设备上其他 DSH 实例占用。 */
    private static final int PORT = 3080;
    private int chosenPort = PORT;

    private WebView webView;
    private TextView logView;
    private Process nodeProcess;
    private File crashFile;
    /** 共享日志：写到 /sdcard/DSHNative/launch.log，便于在设备内直接查看排查。 */
    private File sharedLog;
    private final Object logLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);

        // 日志面板不加入视图树：整个屏幕留给 DSH 界面。
        // 日志仍会写入 logcat 与 /sdcard/DSHNative/launch.log，便于后台排查。
        logView = new TextView(this);
        logView.setTextSize(10);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        // 关键：Android WebView 默认忽略 viewport meta 里的固定宽度，
        // 必须开启 useWideViewPort 才会遵循，否则我们写入的 width=600 无效。
        // loadWithOverviewMode 让页面整体缩放以适配屏幕宽度。
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        // 允许双指缩放：适配后的布局文字偏小，用户可自行放大
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        // DSH 的「添加 → 文件」菜单会 click() 页面里的原生 <input type="file">。
        // WebView 必须由宿主实现 onShowFileChooser，否则点击毫无反应 ——
        // 这正是之前"无法上传文件"的真正原因。
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<android.net.Uri[]> callback,
                    android.webkit.WebChromeClient.FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    android.content.Intent intent = new android.content.Intent(
                            android.content.Intent.ACTION_GET_CONTENT);
                    intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    boolean multiple = params.getMode()
                            == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;

                    String[] accept = params.getAcceptTypes();
                    java.util.List<String> kinds = new java.util.ArrayList<String>();
                    if (accept != null) {
                        for (String a : accept) {
                            if (a == null) continue;
                            for (String piece : a.split(",")) {
                                String t = piece.trim();
                                if (t.length() > 0) kinds.add(t);
                            }
                        }
                    }
                    String type = kinds.isEmpty() ? "*/*" : kinds.get(0);
                    if (kinds.size() > 1) {
                        intent.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                                kinds.toArray(new String[0]));
                    }
                    intent.setType(type);
                    intent.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, multiple);
                    log("DSH 请求选择文件 (type=" + type + ", multiple=" + multiple + ")");
                    startActivityForResult(
                            android.content.Intent.createChooser(intent, "选择文件"),
                            REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    log("✗ 启动文件选择器失败: " + t);
                    pendingFileCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                log("WebView 加载失败: " + errorCode + " " + description + " @ " + failingUrl);
                statusPageLoading = false;
                showStatus("界面加载失败",
                        "错误 " + errorCode + "：" + description + "<br>地址 " + failingUrl);
            }
        });
        root.addView(webView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        initSharedLog();
        requestStoragePermission();
        installCrashHandler();
        showPreviousCrash();

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

        // 2.4 应用 Android 专项补丁（sharp 优雅降级等）
        applyAndroidPatches(root, dshDir);

        // 2.5 运行环境自检 —— 一次性验证所有已知 Android 兼容性风险点
        runPreflight(node, root, toolsDir);

        // 3. 准备 DSH 配置（若不存在）
        prepareConfig(root);

        // 4. 启动 dsh web
        File binJs = new File(dshDir, "lib/bin.js");
        // 必须挑一个空闲端口：设备上可能已有别的 DSH 实例占用 3080，
        // 直接沿用会 EADDRINUSE 导致启动失败、界面空白。
        chosenPort = findFreePort(PORT, PORT + 200);
        if (chosenPort == 0) {
            log("⚠ 未找到空闲端口，交给系统分配（--port 0）");
        } else {
            log("使用端口 " + chosenPort + (chosenPort == PORT ? "" : "（" + PORT + " 已被占用）"));
        }
        log("启动 dsh web …");
        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(),
                "--expose-internals",          // 关键：替代无 android 构建的原生插件
                "--no-warnings",
                binJs.getAbsolutePath(),
                "--profile", "web", "--no-open", "--port", String.valueOf(chosenPort));
        pb.redirectErrorStream(true);
        pb.directory(root);

        String libPath = new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath();
        String binPath = new File(toolsDir, "bin").getAbsolutePath() + ":/system/bin:/system/xbin";
        String nodePath = new File(dshDir, "node_modules").getAbsolutePath()
                + ":" + new File(root, "node_modules").getAbsolutePath();

        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
        pb.environment().put("PATH", binPath);
        pb.environment().put("NODE_PATH", nodePath);
        pb.environment().put("HOME", root.getAbsolutePath());
        pb.environment().put("DSH_HOME", new File(root, ".dsh").getAbsolutePath());
        pb.environment().put("TMPDIR", root.getAbsolutePath());
        // 用 dumb 而非 xterm-256color：避免 dsh 输出 ANSI 颜色转义。
        // 之前正是这些转义混进了 URL（\u001b[36m...\u001b[0m），
        // 导致 WebView 加载了非法地址 → 下半屏一直空白。
        pb.environment().put("TERM", "dumb");
        // git 把系统配置硬编码为 /data/data/com.termux/files/usr/etc/gitconfig，
        // 我们读不到该路径，禁用系统级配置以免产生警告；同时指定自带的 helper 目录。
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        File gitCore = new File(toolsDir, "libexec/git-core");
        if (gitCore.isDirectory()) {
            pb.environment().put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        }

        nodeProcess = pb.start();
        log("dsh web 已启动 (pid " + pidOf(nodeProcess) + ")");
        pipeOutput(nodeProcess);

        // 5. 等待服务就绪后加载界面
        String url = waitForServer();
        if (url == null) {
            // 没抓到带 token 的地址，但端口若已响应仍尝试加载（会看到 401 页而非空白）
            if (probeHttp(PORT) > 0) {
                url = "http://127.0.0.1:" + chosenPort + "/";
                log("⚠ 未捕获到带 token 的地址，尝试直接加载（可能显示未授权页）");
            }
        }
        if (url != null) {
            log("界面就绪: " + url);
            final String target = url;
            statusPageLoading = false;
            runOnUiThread(new Runnable() {
                @Override public void run() { webView.loadUrl(target); }
            });
        }
    }

    /** 轮询本地端口，从 stdout 抓取带 token 的地址。 */
    private String waitForServer() {
        final int MAX_SECONDS = 240;
        showStatus("正在启动 DSH …", "首次启动需加载插件，通常 20–60 秒。");
        for (int i = 0; i < MAX_SECONDS; i++) {
            if (lastUrl != null) return lastUrl;

            if (!isProcessAlive(nodeProcess)) {
                log("✗ dsh web 进程已退出，且未打印服务地址");
                showStatus("DSH 启动失败",
                        "dsh 进程已退出。请查看上方日志面板中标有 <code>[dsh]</code> 的输出行。");
                return null;
            }

            int code = probeHttp(chosenPort);
            if (code > 0 && i % 5 == 0) {
                log("  端口已响应 (HTTP " + code + ")，等待打印地址 …");
            }
            if (i > 0 && i % 20 == 0) {
                showStatus("正在启动 DSH …", "已等待 " + i + " 秒。若超过 2 分钟仍无响应，"
                        + "请查看上方日志面板。");
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        log("⚠ 等待服务超时（" + MAX_SECONDS + " 秒）");
        showStatus("启动超时", "已等待 " + MAX_SECONDS + " 秒仍未拿到服务地址，请查看上方日志。");
        return null;
    }

    private volatile String lastUrl;
    /** DSH 触发的文件选择回调（必须保留引用，否则会被回收导致无响应）。 */
    private android.webkit.ValueCallback<android.net.Uri[]> pendingFileCallback;
    private static final int REQ_FILE_CHOOSER = 0x2001;

    private void pipeOutput(final Process p) {
        final InputStream is = p.getInputStream();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    StringBuilder carry = new StringBuilder();
                    while ((line = r.readLine()) != null) {
                        String clean = stripAnsi(line);
                        log("[dsh] " + clean);
                        if (lastUrl == null) {
                            java.util.regex.Matcher m = URL_PATTERN.matcher(clean);
                            if (m.find()) {
                                lastUrl = m.group();
                                log("  ✓ 已捕获服务地址");
                            }
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

        // 1) 模型配置 —— 必须「合并」而不是「不存在才写」：
        //    DSH 启动时会自行创建 settings.yaml（写入 onboarding 状态等），
        //    所以文件通常已存在，简单跳过会导致预置模型永远注入不进去。
        File preset = new File(root, "settings-preset.yaml");
        File settings = new File(dshHome, "settings.yaml");
        if (preset.exists()) {
            java.util.List<String> added = mergeTopLevelBlocks(readText(preset), settings);
            if (!added.isEmpty()) {
                log("  已注入模型配置: " + added);
            } else {
                log("  模型配置已存在，无需注入");
            }
        }

        // 2) 凭据 —— 同理，DSH 会自建 .credentials.yaml 存放浏览器会话授权，
        //    因此要把 refs 段「合并」进去，而不是文件存在就跳过。
        File creds = new File(dshHome, ".credentials.yaml");
        String[] candidates = {
                "/sdcard/DSHNative/credentials.yaml",
                "/sdcard/DSHNative/.credentials.yaml",
                "/sdcard/Download/DSHNative/credentials.yaml",
                "/storage/emulated/0/DSHNative/credentials.yaml",
        };
        for (String path : candidates) {
            File src = new File(path);
            if (src.exists() && src.length() > 16) {
                java.util.List<String> added = mergeCredentials(src, creds);
                if (!added.isEmpty()) {
                    // 只记录键名 —— 绝不把密钥值写进日志
                    log("  已从共享文件导入凭据: " + added);
                } else {
                    log("  凭据已就绪，无需导入");
                }
                return;
            }
        }
        log("  未找到共享凭据文件，请在 Models 页面填写 API Key");
    }

    /** 把 DSH 请求的文件选择结果回传给它。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    android.content.Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (pendingFileCallback == null) return;
        android.net.Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                result = new android.net.Uri[n];
                for (int i = 0; i < n; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new android.net.Uri[]{ data.getData() };
            }
        }
        log("文件选择完成: " + (result == null ? "已取消" : result.length + " 个文件"));
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    /** 读取文本文件。 */
    private String readText(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        in.close();
        return new String(b, 0, off, "UTF-8");
    }

    /** YAML 里是否已存在某顶层键。 */
    private boolean hasTopLevelKey(String yaml, String key) {
        return java.util.regex.Pattern
                .compile("(?m)^" + java.util.regex.Pattern.quote(key) + "\\s*:")
                .matcher(yaml).find();
    }

    /**
     * 把预置 YAML 中「目标文件尚不存在的顶层块」追加进去。
     *
     * @return 实际追加的顶层键名
     */
    private java.util.List<String> mergeTopLevelBlocks(String preset, File dst) throws IOException {
        String target = dst.exists() ? readText(dst) : "";
        java.util.List<String> added = new java.util.ArrayList<String>();
        java.util.List<String[]> blocks = new java.util.ArrayList<String[]>();

        String key = null;
        StringBuilder block = new StringBuilder();
        java.util.regex.Pattern topKey =
                java.util.regex.Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*):");
        for (String ln : preset.split("\n", -1)) {
            java.util.regex.Matcher m = topKey.matcher(ln);
            if (m.find()) {
                if (key != null) blocks.add(new String[]{key, block.toString()});
                key = m.group(1);
                block = new StringBuilder();
            }
            if (key != null) block.append(ln).append('\n');
        }
        if (key != null) blocks.add(new String[]{key, block.toString()});

        StringBuilder add = new StringBuilder();
        for (String[] b : blocks) {
            if (hasTopLevelKey(target, b[0])) continue;
            add.append(b[1]);
            added.add(b[0]);
        }
        if (added.isEmpty()) return added;

        StringBuilder out = new StringBuilder(target);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        out.append('\n').append(add);
        writeText(dst, out.toString());
        return added;
    }

    /**
     * 把源凭据文件 refs: 段下的条目合并进目标文件（跳过已存在的键）。
     *
     * @return 实际导入的键值对
     */
    private java.util.List<String> mergeCredentials(File src, File dst) throws IOException {
        String mine = readText(src);
        String target = dst.exists() ? readText(dst) : "";
        java.util.List<String> refs = new java.util.ArrayList<String>();
        java.util.List<String> added = new java.util.ArrayList<String>();

        boolean inRefs = false;
        for (String ln : mine.split("\n", -1)) {
            if (ln.matches("^refs\\s*:.*")) { inRefs = true; continue; }
            if (inRefs) {
                if (ln.length() > 0 && !Character.isWhitespace(ln.charAt(0))) break;
                String t = ln.trim();
                if (t.length() > 0 && t.indexOf(':') > 0) refs.add(t);
            }
        }
        java.util.List<String> addedLines = new java.util.ArrayList<String>();
        for (String r : refs) {
            String k = r.substring(0, r.indexOf(':')).trim();
            if (target.indexOf(k + ":") < 0) { addedLines.add(r); added.add(k); }
        }
        if (added.isEmpty()) return added;

        StringBuilder out = new StringBuilder(target);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^refs\\s*:.*$").matcher(out);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            sb.append(out, 0, m.end());
            for (String r : addedLines) sb.append("\n  ").append(r);
            sb.append(out.substring(m.end()));
            out = sb;
        } else {
            out.append("refs:\n");
            for (String r : addedLines) out.append("  ").append(r).append('\n');
        }
        writeText(dst, out.toString());
        return added;
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
        // Termux 的 libcrypto 把 OPENSSLDIR 硬编码为 /data/data/com.termux/files/usr/etc/tls，
        // 我们的 App 读不到，必须用自带配置覆盖，否则 OpenSSL 初始化失败、node 退出码 13。
        pb.environment().put("OPENSSL_CONF",
                new File(node.getParentFile(), "openssl.cnf").getAbsolutePath());
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

    /**
     * 把未捕获异常写到私有目录的 crash.log。
     *
     * <p>这台设备上取 logcat 不方便，闪退时用户只看到"已停止运行"。
     * 落盘后下次启动会直接把堆栈显示在面板上，便于定位。
     */
    private void installCrashHandler() {
        try {
            crashFile = new File(getFilesDir(), "crash.log");
            final Thread.UncaughtExceptionHandler def =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        java.io.PrintWriter pw = new java.io.PrintWriter(
                                new java.io.FileWriter(crashFile, true));
                        pw.println("=== " + new java.util.Date() + " / thread " + t.getName() + " ===");
                        e.printStackTrace(pw);
                        pw.flush();
                        pw.close();
                    } catch (Throwable ignored) { }
                    if (def != null) def.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 若上次运行崩溃过，把堆栈显示在面板顶部。 */
    private void showPreviousCrash() {
        try {
            if (crashFile == null || !crashFile.exists()) return;
            byte[] raw = new byte[(int) Math.min(crashFile.length(), 8192)];
            java.io.FileInputStream in = new java.io.FileInputStream(crashFile);
            int n = in.read(raw);
            in.close();
            if (n <= 0) return;
            log("⚠️ 检测到上次运行崩溃，堆栈如下（同时保存在 " + crashFile + "）：");
            String txt = new String(raw, 0, n, "UTF-8");
            for (String line : txt.split("\n")) {
                if (line.trim().length() > 0) log("  " + line);
            }
            log("――― 崩溃日志结束 ―――");
            crashFile.delete();   // 只显示一次，避免刷屏
        } catch (Throwable ignored) { }
    }

    /**
     * 应用 Android 专项补丁。
     *
     * <p>目前只处理 sharp：它的预编译产物是 glibc 链接的，Android(bionic) 无法
     * dlopen。它由 {@code dsh-attachment-local} 惰性加载（仅处理图片时用到），
     * 这里替换为优雅降级的桩，避免用户上传图片时看到晦涩的加载器报错。
     */
    private void applyAndroidPatches(File root, File dshDir) {
        patchFrontendViewport(dshDir);
        try {
            File stubSrc = new File(root, "sharpstub.js");
            File sharpDir = new File(dshDir, "node_modules/sharp");
            if (!stubSrc.exists() || !sharpDir.isDirectory()) {
                return;
            }
            copyFile(stubSrc, new File(sharpDir, "index.js"));
            writeText(new File(sharpDir, "package.json"),
                    "{\"name\":\"sharp\",\"version\":\"0.0.0-android-stub\","
                    + "\"main\":\"index.js\",\"description\":\"Android graceful-degradation stub\"}");
            log("  已为 sharp 应用优雅降级桩（图片附件不可用，其余功能不受影响）");
        } catch (Throwable t) {
            log("  ⚠️ Android 补丁应用失败: " + t);
        }
    }

    /**
     * 手机端适配：把前端 viewport 由「设备宽度」改为固定宽度。
     *
     * <p>DSH 的 Web 界面是桌面优先设计：设置弹窗需要约 600 CSS px，
     * 而手机纵向只有约 400 px，导致内容横向溢出、被切割挤压。
     *
     * <p>把 viewport 固定为 600 px 后，浏览器会整体缩放以适配屏幕宽度 ——
     * 相当于「桌面版网站」模式：布局得到足够空间，代价是文字略小，
     * 因此同时开启了双指缩放供用户自行调整。
     */
    private void patchFrontendViewport(File dshDir) {
        File html = new File(dshDir,
                "node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html");
        if (!html.exists()) {
            log("  ⚠️ 未找到前端 index.html，跳过 viewport 适配");
            return;
        }
        try {
            String src = readText(html);
            String from = "content=\"width=device-width, initial-scale=1\"";
            String to = "content=\"width=600\"";
            if (src.indexOf("content=\"width=600\"") >= 0) {
                log("  viewport 已适配（600px），跳过");
                return;
            }
            if (src.indexOf(from) < 0) {
                log("  ⚠️ viewport 标签格式不符，未做适配");
                return;
            }
            writeText(html, src.replace(from, to));
            log("  已适配手机布局：viewport → 600px（可双指缩放）");
        } catch (Throwable t) {
            log("  ⚠️ viewport 适配失败: " + t);
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        InputStream in = new java.io.FileInputStream(src);
        OutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        os.close();
        in.close();
    }

    private void writeText(File f, String text) throws IOException {
        OutputStream os = new FileOutputStream(f);
        os.write(text.getBytes("UTF-8"));
        os.close();
    }

    /**
     * 运行环境自检：在启动 dsh web 之前一次性验证所有已知风险点
     * （OpenSSL 配置、zstd、子进程、worker_threads、node-pty、DNS）。
     *
     * <p>失败不阻断启动，只告警 —— 这样即使有问题也能拿到最多的现场信息。
     */
    private boolean runPreflight(File node, File root, File toolsDir) {
        log("运行环境自检 …");
        File script = new File(root, "preflight.js");
        if (!script.exists()) { log("  ⚠️ 缺少 preflight.js，跳过"); return true; }

        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(),
                script.getAbsolutePath(), root.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.directory(root);
        pb.environment().put("LD_LIBRARY_PATH", new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath());
        pb.environment().put("PATH", new File(toolsDir, "bin").getAbsolutePath()
                + ":/system/bin:/system/xbin");
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
        pb.environment().put("NODE_PATH", new File(root, "dsh/node_modules").getAbsolutePath());
        pb.environment().put("TMPDIR", root.getAbsolutePath());
        pb.environment().put("HOME", root.getAbsolutePath());

        try {
            Process p = pb.start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            int failed = -1;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("PREFLIGHT|")) {
                    String[] f = line.split("\\|", 4);
                    String tag = f.length > 1 ? f[1] : "";
                    boolean pass = "PASS".equals(tag);
                    boolean warn = "WARN".equals(tag);
                    String detail = (f.length > 3 && f[3].length() > 0) ? " → " + f[3] : "";
                    log("  " + (pass ? "✅" : warn ? "⚠️" : "❌")
                            + " " + (f.length > 2 ? f[2] : "?") + detail);
                } else if (line.startsWith("PREFLIGHT_END|")) {
                    try { failed = Integer.parseInt(line.substring(14).trim()); }
                    catch (NumberFormatException ignored) { }
                }
            }
            p.waitFor();
            if (failed == 0) { log("  ✅ 自检全部通过"); return true; }
            log("  ⚠️ 有 " + (failed < 0 ? "若干" : String.valueOf(failed))
                    + " 项未通过，仍继续启动以便收集信息");
            return false;
        } catch (Exception e) {
            log("  ⚠️ 自检执行失败: " + e);
            return true;
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
            pb.environment().put("OPENSSL_CONF",
                    new File(node.getParentFile(), "openssl.cnf").getAbsolutePath());
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
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
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
        // Android 上 Process 用 pid() 方法而非字段
        try {
            Object r = Process.class.getMethod("pid").invoke(p);
            if (r != null) return String.valueOf(r);
        } catch (Throwable ignored) { }
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            return String.valueOf(f.get(p));
        } catch (Throwable t) { return "?"; }
    }

    /** 匹配 dsh 启动时打印的本地服务地址（含 token）。 */
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile(
                    "http://(?:127\\.0\\.0\\.1|localhost):\\d+(?:/[A-Za-z0-9_\\-?=&.]*)?");

    /** 去掉 ANSI 转义序列，避免颜色码混进 URL。 */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
    }

    private volatile boolean statusPageLoading;

    /** 在 WebView 中显示状态，避免出现无从判断的空白区域。 */
    private void showStatus(final String title, final String detail) {
        if (statusPageLoading) return;
        statusPageLoading = true;
        final String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;padding:22px;background:#0b0e14;color:#e6e6e6;"
                + "font:15px/1.7 -apple-system,system-ui,sans-serif}"
                + "h1{font-size:18px;margin:0 0 10px}p{color:#8b949e;font-size:13px;margin:0 0 6px}"
                + "code{background:#161b22;padding:2px 6px;border-radius:4px;font-size:12px}</style>"
                + "</head><body><h1>" + title + "</h1><p>" + detail + "</p></body></html>";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            }
        });
    }

    /**
     * 从 start 起寻找可绑定的空闲端口；找不到返回 0（交由系统分配）。
     *
     * <p>绑定后立即释放，存在极小的竞争窗口，但足以避免与设备上
     * 已运行的同类服务（如另一个 DSH 实例）冲突。
     */
    private int findFreePort(int start, int end) {
        for (int p = start; p <= end; p++) {
            java.net.ServerSocket s = null;
            try {
                s = new java.net.ServerSocket();
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress("127.0.0.1", p));
                return p;
            } catch (Throwable ignored) {
                // 端口被占用，试下一个
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    /** 探测本地端口：返回 HTTP 状态码；未就绪返回 -1。 */
    private int probeHttp(int port) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(
                    "http://127.0.0.1:" + port + "/").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestMethod("GET");
            return c.getResponseCode();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Process.isAlive() 是 API 26+，用反射以便在旧 jar 上编译。 */
    private boolean isProcessAlive(Process p) {
        if (p == null) return false;
        try {
            Object r = Process.class.getMethod("isAlive").invoke(p);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return true;
        }
    }

    private void log(final String msg) {
        Log.i(TAG, msg);
        appendSharedLog(msg);
        // 界面已改为全屏 WebView，日志不再上屏；如需在屏幕上查看，
        // 取消下面注释即可（会占用屏幕空间）。
        // runOnUiThread(new Runnable() {
        //     @Override public void run() { logView.append(msg + "\n"); }
        // });
    }

    /**
     * 初始化共享日志文件。
     *
     * <p>为什么要写这里：本项目多次因无法获取真实运行日志而反复来回。
     * \`/sdcard\` 是设备内各 App 与调试环境都能访问的位置，
     * 把启动日志写在这里，就无需 adb、无需截图即可排查。
     */
    private void initSharedLog() {
        // 依次尝试多个候选位置：不同 ROM 的共享存储策略不同，
        // 且目录若由其他 UID 创建则本 App 可能无写权限。
        java.util.List<File> candidates = new java.util.ArrayList<File>();
        candidates.add(new File("/sdcard/DSHNative/launch.log"));
        candidates.add(new File("/sdcard/Download/DSHNative/launch.log"));
        candidates.add(new File("/storage/emulated/0/DSHNative/launch.log"));
        candidates.add(new File("/sdcard/launch.log"));
        for (File cand : candidates) {
            try {
                File dir = cand.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                java.io.FileWriter probe = new java.io.FileWriter(cand, true);
                probe.write("");
                probe.close();
                sharedLog = cand;
                break;
            } catch (Throwable ignored) { /* 试下一个 */ }
        }
        try {
            if (sharedLog == null) throw new IOException("所有候选路径均不可写");
            File dir = sharedLog.getParentFile();
            java.io.FileWriter w = new java.io.FileWriter(sharedLog, false);
            w.write("=== DSH Native 启动日志 ===\n");
            w.write("时间: " + new java.util.Date() + "\n");
            w.write("设备: " + android.os.Build.MODEL + " / Android "
                    + android.os.Build.VERSION.RELEASE + " (SDK "
                    + android.os.Build.VERSION.SDK_INT + ")\n");
            w.write("APK 版本: 0.6.0\n");
            w.write("路径: " + sharedLog.getAbsolutePath() + "\n");
            w.write("说明: 本文件由 App 写入，便于在设备内直接查看，可随时删除。\n\n");
            w.close();
            Log.i(TAG, "shared log: " + sharedLog.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "initSharedLog failed", t);
            sharedLog = null;
        }
    }

    /**
     * 日志脱敏。
     *
     * <p>曾经只处理了 {@code token=}，结果导入凭据时把**完整 API Key 明文写进了日志**。
     * 现在覆盖常见密钥形态，并兜底屏蔽超长无空格串。
     */
    private static String maskSecrets(String s) {
        s = s.replaceAll("token=[A-Za-z0-9_\\-]+", "token=***");
        s = s.replaceAll("sk-[A-Za-z0-9_\\-]{6,}", "sk-***");
        s = s.replaceAll("user_[A-Za-z0-9_\\-]{12,}", "user_***");
        s = s.replaceAll("[A-Za-z0-9_\\-]{40,}", "***");
        return s;
    }

    /** 追加一行到共享日志；token 等敏感串做脱敏。 */
    private void appendSharedLog(String msg) {
        if (sharedLog == null) return;
        synchronized (logLock) {
            java.io.FileWriter w = null;
            try {
                String line = maskSecrets(msg);
                w = new java.io.FileWriter(sharedLog, true);
                w.write(line);
                w.write('\n');
            } catch (Throwable ignored) {
                // 权限未授予或存储不可用时静默跳过
            } finally {
                if (w != null) try { w.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 请求存储权限。
     *
     * <p>编译所用的 android.jar 是 API 16，没有 requestPermissions 方法，
     * 因此用反射调用；运行期 API 23+ 均可用。
     */
    private void requestStoragePermission() {
        try {
            java.lang.reflect.Method m = android.app.Activity.class.getMethod(
                    "requestPermissions", String[].class, int.class);
            m.invoke(this, new String[]{
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                    "android.permission.READ_EXTERNAL_STORAGE"}, 1001);
            log("已请求存储权限（用于把日志写到 /sdcard/DSHNative/）");
        } catch (Throwable t) {
            log("存储权限请求失败（不影响运行）: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 权限回调。
     *
     * <p>刻意不写 @Override：编译用的 android.jar(API 16) 未声明该方法，
     * 但运行期 API 23+ 会正常回调。
     */
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == 0;   // PackageManager.PERMISSION_GRANTED
        log("存储权限结果: " + (granted ? "已授予，日志将写入 /sdcard/DSHNative/launch.log"
                : "被拒绝 —— 无法写共享日志，不影响 App 运行"));
    }

    /**
     * 返回键：先在 WebView 内后退，退无可退才退出 App。
     *
     * <p>全屏 WebView 里若直接退出，用户想返回上个界面时会误关应用。
     */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nodeProcess != null) nodeProcess.destroy();
    }
}
