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
            "https://github.com/yusheng266186-beep/DSH-Native/releases/download/payload-v4/";
    /** 用于检查 App 自身更新的仓库。 */
    private static final String REPO = "yusheng266186-beep/DSH-Native";

    /**
     * 版本清单来源。
     *
     * <p>**刻意不用 GitHub API** —— 未认证请求限 60 次/小时且按 IP 计，
     * 手机流量多为运营商 NAT 共享 IP，实测已直接返回 403。
     * 改为读取仓库里的静态 latest.json（走 CDN，无此限制）。
     */
    private static final String RAW = "https://raw.githubusercontent.com/"
            + REPO + "/main/latest.json";
    private static final String[] VERSION_SOURCES = {
            "https://gh-proxy.com/" + RAW,
            "https://ghproxy.net/" + RAW,
            RAW,
    };

    /**
     * 镜像前缀。**只放前缀**，完整路径由 downloadPath 传入 ——
     * 之前把 ASSET_PATH 拼进这里，导致 App 自更新（走另一个 release）时
     * URL 变成 payload-v4/releases/download/... 这种错误组合。
     * 空串表示直连 GitHub 兜底。
     */
    private static final String[] SOURCES = {
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "",                               // 直连兜底
    };

    /**
     * 运行包与期望的 SHA-256 —— 移动网络容易中断，下载后必须校验，
     * 否则会静默产生损坏归档，导致解压失败且难以排查。
     * 数值与 release 中的 SHA256SUMS.txt 一致。
     */

    /** 首选端口。实际使用 chosenPort —— 3080 常被设备上其他 DSH 实例占用。 */
    private static final int PORT = 3080;

    /**
     * 运行包版本。改动工具链或 DSH 内容时递增 ——
     * 标记文件里记的是版本号而非"存在与否"，
     * 否则旧版运行包会被永远跳过（此前 payload-v2 加入 Python 时就踩过这个坑）。
     */
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

        // 双保险：即使主题未被 ROM 正确解析，也确保没有标题栏、
        // 且窗口底色为白（否则默认主题会露出黑色，形成顶部黑边）。
        try {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
        } catch (Throwable t) {
            log("窗口设置失败（不影响运行）: " + t);
        }

        // 状态栏透明 + 内容延伸上去 + 深色图标。
        applySystemBars();

        rootView = new android.widget.FrameLayout(this);
        rootView.setBackgroundColor(0xFFFFFFFF);
        // 内容延伸到状态栏之后，用等高内边距把内容推下来 ——
        // 状态栏区域露出的是白色背景，配深色图标，视觉上连成一片。
        rootView.setPadding(0, statusBarHeight(), 0, 0);
        installImeInsetHandler();
        android.widget.FrameLayout root = rootView;

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
            public void onPageFinished(WebView view, String url) {
                // 注意：loadDataWithBaseURL() 显示状态页时**同样会触发本回调**。
                // 之前没做区分，导致开屏被提前撤掉（露出状态页），
                // 且开屏淡到 alpha=0 后仍占满全屏、吃掉所有触摸事件 —— 表现为"整个应用点不动"。
                // 因此只认真正的 DSH 服务地址。
                if (url == null || url.indexOf("127.0.0.1") < 0) {
                    return;
                }
                if (dshPageLoaded) return;
                dshPageLoaded = true;
                log("DSH 界面已加载，收起开屏");
                // 稍等片刻再淡出：单页应用 onload 后还需一点时间渲染
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                    @Override public void run() { hideSplash(); }
                }, 250);
            }

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

        // 开屏页盖在最上层：启动期间用户看到的是鲸鱼动画与友好文案，
        // 而不是滚动的日志行。加载完成后淡出。
        splashView = buildSplash();
        root.addView(splashView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        initSharedLog();
        requestStoragePermission();
        handleShareIntent(getIntent());

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
                    setSplashStatus("启动未完成 —— 点按此处可查看详细日志");
                    if (splashView != null) {
                        // 失败时给出退路：点一下收起开屏，露出下方详细错误页
                        splashView.setOnClickListener(new android.view.View.OnClickListener() {
                            @Override public void onClick(android.view.View v) { hideSplash(); }
                        });
                    }
                }
            }
        }).start();
    }

    private void boot() throws Exception {
        final File root = new File(getFilesDir(), "dsh");
        appRoot = root;
        log("私有目录: " + root);

        // 把 agent 的工作目录放到共享存储，这样用文件管理器丢进去的项目
        // agent 能直接读写，产出也能直接看到。
        // （dsh-fs-local / dsh-bash-local 用 process.cwd() 解析相对路径）
        workspace = resolveWorkspace();
        log("工作区: " + (workspace != null ? workspace : root + "（回退到私有目录）"));

        startHarnessService();

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
        setSplashStatus("正在准备运行环境…");
        log("Node 就绪: " + runCapture(node, new String[]{"--version"}));

        // 2. 下载并解压运行包（首次启动）
        File dshDir = new File(root, "dsh");
        File toolsDir = new File(root, "tools");

        // 增量更新：清单 + 哨兵校验，只下载缺失或变化的分片。
        // 不再用"整体版本号"判断 —— 那会导致改一个小工具也要重下整个包。
        boolean upToDate = ensurePayload(node, root, dshDir, toolsDir);
        if (upToDate) {
            log("运行包已是最新，本次无需下载");
            setSplashStatus("正在准备运行环境…");
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
        setSplashStatus("正在启动服务…");
        log("启动 dsh web …");
        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(),
                "--expose-internals",          // 关键：替代无 android 构建的原生插件
                "--no-warnings",
                binJs.getAbsolutePath(),
                "--profile", "web", "--no-open", "--port", String.valueOf(chosenPort));
        pb.redirectErrorStream(true);
        pb.directory(workspace != null ? workspace : root);

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
        // git 的辅助程序（git-remote-https 等）在 libexec/git-core，
        // 二进制里硬编码的是 Termux 路径，必须显式指定，否则 clone/push 失败。
        File gitCore = new File(toolsDir, "libexec/git-core");
        if (gitCore.isDirectory()) {
            pb.environment().put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        }
        File gitTpl = new File(toolsDir, "share/git-core/templates");
        if (gitTpl.isDirectory()) {
            pb.environment().put("GIT_TEMPLATE_DIR", gitTpl.getAbsolutePath());
        }
        // Python 的安装前缀同样被硬编码为 Termux 路径；
        // 指到我们自己的目录，sys.prefix 才正确、pip 才会装到这里。
        // 证书包路径同样被硬编码成 Termux 路径（如 curl 的 cert.pem），
        // App 里读不到会导致 HTTPS 全部失败。改为指向 APK 内置的根证书包。
        // git 也走同一份（此前 curl/git clone 会报 error adding trust anchors）。
        File caBundle = new File(root, "ca-certificates.crt");
        if (caBundle.exists()) {
            String ca = caBundle.getAbsolutePath();
            pb.environment().put("CURL_CA_BUNDLE", ca);
            pb.environment().put("SSL_CERT_FILE", ca);
            pb.environment().put("GIT_SSL_CAINFO", ca);
            pb.environment().put("REQUESTS_CA_BUNDLE", ca);   // 供 python-requests 类工具
        }
        // $SHELL 若不设置会继承到不存在的 Termux 路径（agent 已实测踩到），
        // 显式指向自带 bash，依赖 $SHELL 的工具才不会误判。
        File bash = new File(toolsDir, "bin/bash");
        if (bash.exists()) {
            pb.environment().put("SHELL", bash.getAbsolutePath());
        }
        // npm 的前缀同样被硬编码成 Termux 路径；指到自带目录，
        // 这样 npm install -g 才会装进我们自己的可写目录。
        pb.environment().put("PREFIX", toolsDir.getAbsolutePath());
        pb.environment().put("npm_config_prefix", toolsDir.getAbsolutePath());
        pb.environment().put("npm_config_cache", new File(root, ".npm-cache").getAbsolutePath());
        pb.environment().put("npm_config_update_notifier", "false");
        pb.environment().put("PYTHONHOME", toolsDir.getAbsolutePath());
        pb.environment().put("PYTHONNOUSERSITE", "1");

        nodeProcess = pb.start();
        log("dsh web 已启动 (pid " + pidOf(nodeProcess) + ")");
        pipeOutput(nodeProcess);

        // 5. 等待服务就绪后加载界面
        String url = waitForServer();
        // 兜底：45 秒后无论如何都收起开屏，避免任何情况下界面被永久挡住
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
            @Override public void run() { hideSplash(); }
        }, 45000);
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
    private android.widget.FrameLayout rootView;
    /** App 私有根目录，供设置页读写配置。 */
    private volatile File appRoot;
    /** agent 的工作目录（优先共享存储）。 */
    private volatile File workspace;
    private android.view.View splashView;
    private android.widget.TextView splashStatus;
    private volatile boolean splashHidden;
    /** 真正的 DSH 页面是否已加载（用于区分状态页触发的 onPageFinished）。 */
    private volatile boolean dshPageLoaded;

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

    // ---------------------------------------------------------------- 应用内更新
    /** 当前 APK 版本名。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 极简 HTTP GET（GitHub API 用；走系统 CA，与运行包的证书问题无关）。 */
    private String httpGet(String url) throws IOException {
        java.net.HttpURLConnection c =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "DSH-Native-Android");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            if (in != null) {
                byte[] b = new byte[8192];
                int k;
                while ((k = in.read(b)) > 0) bo.write(b, 0, k);
                in.close();
            }
            if (code >= 400) throw new IOException("HTTP " + code);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static int[] parseVer(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(v == null ? "" : v);
        if (m.find()) {
            return new int[]{ Integer.parseInt(m.group(1)),
                              Integer.parseInt(m.group(2)),
                              Integer.parseInt(m.group(3)) };
        }
        return new int[]{0, 0, 0};
    }

    private static boolean isNewer(String remote, String local) {
        int[] r = parseVer(remote), l = parseVer(local);
        for (int i = 0; i < 3; i++) {
            if (r[i] != l[i]) return r[i] > l[i];
        }
        return false;
    }

    /**
     * 读取版本清单，返回 [version, tag, apkName]；失败返回 null。
     * 多个来源依次尝试（镜像优先）。
     */
    private String[] latestRelease() {
        Throwable last = null;
        for (String u : VERSION_SOURCES) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(httpGet(u));
                String ver = o.optString("version", "");
                String tag = o.optString("tag", "");
                String apk = o.optString("apk", "DSHNative-bootstrap.apk");
                if (ver.length() > 0 && tag.length() > 0) {
                    return new String[]{ ver, tag, apk };
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) log("版本清单获取失败: " + shorten(last));
        return null;
    }

    /** 检查 App 更新；interactive=true 时把结果显示在给定文本上/弹提示。 */
    private void checkAppUpdate(final boolean interactive, final android.widget.TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                setStatus(status, "正在检查更新…");
                try {
                    final String[] rel = latestRelease();
                    if (rel == null) {
                        setStatus(status, "未找到可用的发布版本");
                        return;
                    }
                    final String tag = rel[1];
                    final String apkName = rel[2];
                    final String local = appVersion();
                    if (!isNewer(rel[0], local)) {
                        log("已是最新版本: " + local + "（远端 " + rel[0] + "）");
                        setStatus(status, "已是最新版本 " + local);
                        if (interactive) toast("已是最新版本");
                        return;
                    }
                    log("发现新版本: " + tag + "（当前 " + local + "）");
                    setStatus(status, "发现新版本 " + rel[0] + "，正在下载…");
                    if (interactive) toast("发现新版本 " + rel[0] + "，开始下载");
                    File apk = UpdateProvider.apkFile(MainActivity.this);
                    if (apk.exists()) apk.delete();
                    downloadPath("https://github.com/" + REPO
                                    + "/releases/download/" + tag + "/",
                            apkName, apk);
                    log("更新包已下载: " + (apk.length() / 1048576) + " MB");
                    setStatus(status, "下载完成，请在弹出的安装界面确认覆盖安装");
                    installApk(apk);
                } catch (Throwable t) {
                    log("✗ 检查更新失败: " + t);
                    setStatus(status, "检查失败：" + shorten(t));
                    if (interactive) toast("检查更新失败");
                }
            }
        }).start();
    }

    private void setStatus(final android.widget.TextView tv, final String text) {
        if (tv == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() { tv.setText(text); }
        });
    }

    /** 请求「安装未知应用」权限（API 26+ 必须由用户手动开启）。 */
    private boolean ensureInstallPermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    log("需要「安装未知应用」权限，正在跳转设置 …");
                    android.content.Intent i = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    toast("请先允许「安装未知应用」，然后重新点击检查更新");
                    return false;
                }
            }
        } catch (Throwable t) {
            log("安装权限检查失败（继续尝试）: " + t);
        }
        return true;
    }

    /** 调起系统安装器覆盖安装。 */
    private void installApk(File apk) {
        if (!ensureInstallPermission()) return;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            i.setDataAndType(UpdateProvider.contentUri(),
                    "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("请在安装界面确认覆盖安装");
        } catch (Throwable t) {
            log("✗ 调起安装器失败: " + t);
            toast("无法调起安装器: " + shorten(t));
        }
    }

    /** 手动更新运行包（重新走清单校验，然后重启 agent）。 */
    private void updatePayloadNow(final android.widget.TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                setStatus(status, "正在检查运行包…");
                try {
                    File root = appRoot;
                    File node = new File(root, "node");
                    boolean upToDate = ensurePayload(node, root,
                            new File(root, "dsh"), new File(root, "tools"));
                    setStatus(status, upToDate ? "运行包已是最新" : "运行包已更新，正在重启…");
                    if (upToDate) {
                        toast("运行包已是最新");
                    } else {
                        restartAgent();
                    }
                } catch (Throwable t) {
                    log("✗ 更新运行包失败: " + t);
                    setStatus(status, "更新失败：" + shorten(t));
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- 运行包
    /**
     * 确保运行包就绪，**只下载缺失或变化的分片**。
     *
     * <p>流程：取 manifest.json → 用每个分片的"哨兵文件"（路径+大小+sha256）
     * 判断本地内容是否已一致 → 只对不一致的分片执行下载、校验、解压。
     *
     * <p>哨兵方案的两个好处：
     * <ul>
     *   <li>免状态文件：每次启动都按内容校验，文件被破坏会自动修复</li>
     *   <li>免重复下载：从旧结构迁移过来时，内容没变就直接判定为已就绪</li>
     * </ul>
     *
     * @return true 表示全部已就绪（本次没有任何下载）
     */
    private boolean ensurePayload(File node, File root, File dshDir, File toolsDir)
            throws Exception {
        File mf = new File(root, "manifest.json");
        try {
            download("manifest.json", mf);
        } catch (Throwable t) {
            // 离线也要能启动：用上次缓存的清单
            if (!mf.exists()) {
                throw new IOException("无法获取运行包清单，且本地无缓存：" + t.getMessage());
            }
            log("清单更新失败，改用本地缓存（离线启动）: " + t.getMessage());
        }

        org.json.JSONObject man = new org.json.JSONObject(readText(mf));
        org.json.JSONArray parts = man.getJSONArray("parts");
        log("运行包清单: " + parts.length() + " 个分片（结构版本 "
                + man.optInt("version", 0) + "）");

        // 第一遍：哨兵校验，找出缺失/变化的分片
        java.util.List<String> missing = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject part = parts.getJSONObject(i);
            File dir = "dsh".equals(part.getString("target")) ? dshDir : toolsDir;
            org.json.JSONObject sent = part.getJSONObject("sentinel");
            File sf = new File(dir, sent.getString("path"));
            boolean ok = sf.exists()
                    && sf.length() == sent.getLong("size")
                    && sent.getString("sha256").equalsIgnoreCase(sha256(sf));
            if (!ok) missing.add(part.getString("name"));
        }
        if (missing.isEmpty()) return true;

        log("需更新的分片（" + missing.size() + "/" + parts.length() + "）: " + missing);
        setSplashStatus(missing.size() == parts.length()
                ? "正在下载运行包…" : "正在增量更新（" + missing.size() + " 项）…");

        // 第二遍：只下载这些分片
        long bytes = 0;
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject part = parts.getJSONObject(i);
            String name = part.getString("name");
            if (!missing.contains(name)) continue;

            String expected = part.getString("sha256");
            File dir = "dsh".equals(part.getString("target")) ? dshDir : toolsDir;
            File archive = new File(root, name);

            if (archive.exists()) archive.delete();
            download(name, archive);
            String actual = sha256(archive);
            if (!expected.equalsIgnoreCase(actual)) {
                archive.delete();
                throw new IOException("SHA-256 校验失败: " + name
                        + "\n  期望 " + expected + "\n  实际 " + actual);
            }
            bytes += archive.length();
            log("  ✓ " + name + " 校验通过");

            setSplashStatus("正在解压运行包…");
            log("解压 " + name + " …");
            run(node, root, new String[]{
                    new File(root, "unpack.js").getAbsolutePath(),
                    archive.getAbsolutePath(),
                    dir.getAbsolutePath()}, null);
            archive.delete();
        }
        log("增量更新完成，本次下载 " + (bytes / 1048576) + " MB");
        return false;
    }

    // ---------------------------------------------------------------- 工作区
    /**
     * 选择 agent 的工作目录。
     *
     * <p>优先共享存储 —— 否则用户无法把文件放进 App 私有目录，agent 也就无从下手。
     * 逐个候选路径试写，全失败则返回 null（调用方回退到私有目录）。
     */
    private File resolveWorkspace() {
        String[] candidates = {
                "/sdcard/DSHNative/workspace",
                "/sdcard/Download/DSHNative/workspace",
                "/storage/emulated/0/DSHNative/workspace",
        };
        for (String path : candidates) {
            File d = new File(path);
            try {
                if (!d.exists() && !d.mkdirs()) continue;
                File probe = new File(d, ".write-probe");
                java.io.FileWriter w = new java.io.FileWriter(probe, true);
                w.write("");
                w.close();
                probe.delete();
                seedWorkspaceReadme(d);
                return d;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /** 在工作区放一份说明，用户知道该把文件放哪。 */
    private void seedWorkspaceReadme(File dir) {
        File readme = new File(dir, "把文件放到这里.txt");
        if (readme.exists()) return;
        try {
            writeText(readme,
                    "这是 DeepSeek Harness 的工作目录。\n"
                  + "=====================================\n\n"
                  + "• 把项目、文档放到这个文件夹，agent 就能直接读写它们\n"
                  + "• agent 生成的产物也会出现在这里\n"
                  + "• 该目录位于手机共享存储，任何文件管理器都能访问\n\n"
                  + "路径：" + dir.getAbsolutePath() + "\n");
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 前台服务
    /** 启动前台服务，避免切后台/锁屏时 agent 被系统冻结。 */
    private void startHarnessService() {
        try {
            HarnessService.onStopRequested = new Runnable() {
                @Override public void run() {
                    log("收到停止请求，正在结束 …");
                    if (nodeProcess != null) nodeProcess.destroy();
                    runOnUiThread(new Runnable() {
                        @Override public void run() { finish(); }
                    });
                }
            };
            HarnessService.onSettingsRequested = new Runnable() {
                @Override public void run() {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showSettings(); }
                    });
                }
            };
            android.content.Intent svc =
                    new android.content.Intent(this, HarnessService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            log("前台服务已启动（后台保活）");
            // 把"通知是否可见"变成可观测的状态，避免只能靠猜
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                boolean on = nm != null && nm.areNotificationsEnabled();
                log(on ? "通知权限: 已开启（常驻通知可见）"
                       : "通知权限: 已关闭（常驻通知不显示；保活仍然有效）");
                if (!on) {
                    log("  提示: 可在 设置 → 应用 → DeepSeek Harness → 通知 中开启");
                }
            } catch (Throwable t) {
                log("通知权限: 无法查询 (" + t.getClass().getSimpleName() + ")");
            }
        } catch (Throwable t) {
            log("⚠️ 前台服务启动失败（不影响运行）: " + t);
        }
    }

    // ---------------------------------------------------------------- 设置页
    private android.widget.EditText labeledField(
            android.widget.LinearLayout box, String label, String value, boolean secret) {
        float d = getResources().getDisplayMetrics().density;
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTextColor(0xFF6B7280);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (12 * d);
        box.addView(tv, tlp);

        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(value == null ? "" : value);
        et.setTextSize(13.5f);
        et.setSingleLine(true);
        if (secret) {
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        box.addView(et, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 原生设置页：编辑 API Key 与默认模型（避开手机上很难用的 Web 设置页）。 */
    private void showSettings() {
        try {
            final File dshHome = new File(appRoot, ".dsh");
            final File creds = new File(dshHome, ".credentials.yaml");
            final File settings = new File(dshHome, "settings.yaml");

            String cc = readRef(creds, "COMMANDCODE_API_KEY");
            String ds = readRef(creds, "DEEPSEEK_API_KEY");
            String model = readScalar(settings, "model");

            float d = getResources().getDisplayMetrics().density;
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int pad = (int) (20 * d);
            box.setPadding(pad, (int) (4 * d), pad, 0);

            final android.widget.EditText ccField =
                    labeledField(box, "Command Code API Key", cc, true);
            final android.widget.EditText dsField =
                    labeledField(box, "DeepSeek API Key", ds, true);
            final android.widget.EditText modelField =
                    labeledField(box, "默认模型", model, false);

            // ---- 更新区 ----
            android.widget.TextView upTitle = new android.widget.TextView(this);
            upTitle.setText("更新");
            upTitle.setTextSize(13f);
            upTitle.setTextColor(0xFF111827);
            android.widget.LinearLayout.LayoutParams utlp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            utlp.topMargin = (int) (22 * d);
            box.addView(upTitle, utlp);

            final android.widget.TextView upStatus = new android.widget.TextView(this);
            upStatus.setText("当前 App 版本 " + appVersion()
                    + "\n运行包：点下面按钮检查是否有新内容");
            upStatus.setTextSize(11.5f);
            upStatus.setTextColor(0xFF6B7280);
            android.widget.LinearLayout.LayoutParams uslp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            uslp.topMargin = (int) (6 * d);
            box.addView(upStatus, uslp);

            android.widget.Button btnPayload = new android.widget.Button(this);
            btnPayload.setText("更新运行包（DSH / 工具链）");
            btnPayload.setTextSize(12.5f);
            btnPayload.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    updatePayloadNow(upStatus);
                }
            });
            android.widget.LinearLayout.LayoutParams blp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = (int) (10 * d);
            box.addView(btnPayload, blp);

            android.widget.Button btnApp = new android.widget.Button(this);
            btnApp.setText("检查 App 更新并安装");
            btnApp.setTextSize(12.5f);
            btnApp.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    checkAppUpdate(true, upStatus);
                }
            });
            android.widget.LinearLayout.LayoutParams blp2 =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            blp2.topMargin = (int) (6 * d);
            box.addView(btnApp, blp2);

            android.widget.TextView hint = new android.widget.TextView(this);
            hint.setText("保存后会重启 agent 服务。密钥仅保存在 App 私有目录，不会外传。");
            hint.setTextSize(11.5f);
            hint.setTextColor(0xFF9CA3AF);
            android.widget.LinearLayout.LayoutParams hlp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = (int) (14 * d);
            box.addView(hint, hlp);

            android.widget.ScrollView sc = new android.widget.ScrollView(this);
            sc.addView(box);

            new android.app.AlertDialog.Builder(this)
                .setTitle("DeepSeek Harness 设置")
                .setView(sc)
                .setPositiveButton("保存并重启", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dlg, int which) {
                        try {
                            writeRefs(creds,
                                    ccField.getText().toString().trim(),
                                    dsField.getText().toString().trim());
                            String m = modelField.getText().toString().trim();
                            if (m.length() > 0) setScalar(settings, "model", m);
                            toast("已保存，正在重启服务…");
                            restartAgent();
                        } catch (Throwable t) {
                            toast("保存失败: " + t.getMessage());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Throwable t) {
            toast("打开设置失败: " + t.getMessage());
        }
    }

    /** 重启 agent（销毁旧进程后重新走一遍 boot）。 */
    private void restartAgent() {
        try {
            if (nodeProcess != null) nodeProcess.destroy();
        } catch (Throwable ignored) { }
        dshPageLoaded = false;
        splashHidden = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (splashView != null) splashView.setVisibility(android.view.View.VISIBLE);
                if (splashStatus != null) splashStatus.setText("正在重启服务…");
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                try { boot(); }
                catch (Throwable t) { log("✗ 重启失败: " + t); }
            }
        }).start();
    }

    /** 轻提示。 */
    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                android.widget.Toast.makeText(MainActivity.this, msg,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 从凭据文件 refs: 段读取某个键。 */
    private String readRef(File f, String key) {
        try {
            if (!f.exists()) return "";
            boolean inRefs = false;
            for (String ln : readText(f).split("\n", -1)) {
                if (ln.matches("^refs\\s*:.*")) { inRefs = true; continue; }
                if (inRefs) {
                    if (ln.length() > 0 && !Character.isWhitespace(ln.charAt(0))) break;
                    String t = ln.trim();
                    if (t.startsWith(key + ":")) return t.substring(key.length() + 1).trim();
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** 写回两个 API Key（保留文件里其它内容）。 */
    private void writeRefs(File f, String cc, String ds) throws IOException {
        String txt = f.exists() ? readText(f) : "version: 1\n";
        txt = replaceRefLine(txt, "COMMANDCODE_API_KEY", cc);
        txt = replaceRefLine(txt, "DEEPSEEK_API_KEY", ds);
        if (txt.indexOf("refs:") < 0) txt += "refs:\n";
        writeText(f, txt);
    }

    private String replaceRefLine(String txt, String key, String val) {
        if (val == null || val.length() == 0) {
            // 清空该行（保留键名，值为空）
            return txt.replaceAll("(?m)^(\\s*" + java.util.regex.Pattern.quote(key) + "\\s*:).*$", "$1 ");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^(\\s*" + java.util.regex.Pattern.quote(key) + "\\s*:).*$").matcher(txt);
        if (m.find()) {
            return m.replaceFirst("$1 " + java.util.regex.Matcher.quoteReplacement(val));
        }
        // 追加到 refs: 段
        java.util.regex.Matcher r = java.util.regex.Pattern.compile("(?m)^refs\\s*:.*$").matcher(txt);
        if (r.find()) {
            return txt.substring(0, r.end()) + "\n  " + key + ": " + val + txt.substring(r.end());
        }
        return txt + "refs:\n  " + key + ": " + val + "\n";
    }

    /** 读取 YAML 里某个标量（取第一个匹配的 key: value）。 */
    private String readScalar(File f, String key) {
        try {
            if (!f.exists()) return "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*" + java.util.regex.Pattern.quote(key) + "\\s*:\\s*(.+?)\\s*$")
                    .matcher(readText(f));
            if (m.find()) return m.group(1).replaceAll("^[\"']|[\"']$", "");
        } catch (Throwable ignored) { }
        return "";
    }

    /** 替换 YAML 里某个标量的值。 */
    private void setScalar(File f, String key, String val) throws IOException {
        String txt = f.exists() ? readText(f) : "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^(\\s*" + java.util.regex.Pattern.quote(key) + "\\s*:)\\s*.*$").matcher(txt);
        if (m.find()) {
            txt = m.replaceFirst("$1 \"" + java.util.regex.Matcher.quoteReplacement(val) + "\"");
        }
        writeText(f, txt);
    }

    // ---------------------------------------------------------------- 系统栏
    /** 状态栏透明 + 内容延伸上去 + 深色系统图标（消除顶部黑边）。 */
    private void applySystemBars() {
        try {
            android.view.Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0xFFFFFFFF);
            w.getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable t) {
            log("系统栏设置失败（不影响运行）: " + t);
        }
    }

    /**
     * 让 WebView 在键盘弹出时真正“变矮”。
     *
     * <p>仅靠清单里的 {@code adjustResize} 不够 —— 本应用用了
     * {@code SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN}（边到边），窗口不会随键盘收缩。
     * 因此直接监听 IME 的 window inset，把它转成根视图的底部内边距。
     *
     * <p>两种机制是**互补**而非叠加：
     * 若 adjustResize 已生效，系统会把 IME 这块 inset 消耗掉，这里读到的
     * 只有导航栏高度 → 内边距为 0；反之则由这里兜住。
     */
    private void installImeInsetHandler() {
        try {
            rootView.setOnApplyWindowInsetsListener(
                    new android.view.View.OnApplyWindowInsetsListener() {
                private int lastPad = -1;
                @Override
                public android.view.WindowInsets onApplyWindowInsets(
                        android.view.View v, android.view.WindowInsets insets) {
                    try {
                        int bottom = insets.getSystemWindowInsetBottom();
                        int nav = navigationBarHeight();
                        int pad = Math.max(0, bottom - nav);
                        if (pad != lastPad) {
                            lastPad = pad;
                            log("键盘内边距 " + pad + "px（底部 inset=" + bottom
                                    + ", 导航栏=" + nav + "）");
                            v.setPadding(0, statusBarHeight(), 0, pad);
                        }
                    } catch (Throwable t) {
                        log("inset 处理失败: " + t);
                    }
                    return insets;
                }
            });
            rootView.requestApplyInsets();
        } catch (Throwable t) {
            log("⚠️ 无法注册 inset 监听: " + t);
        }

        // 第二条路（更经典可靠）：比较窗口可见区域与根视图高度来推断键盘高度。
        // 即使 inset 未派发（edge-to-edge 下可能发生），这条也能拿到数值。
        try {
            final android.view.View decor = getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                private int lastKb = -1;
                @Override public void onGlobalLayout() {
                    try {
                        android.graphics.Rect visible = new android.graphics.Rect();
                        decor.getWindowVisibleDisplayFrame(visible);
                        int screenH = decor.getRootView().getHeight();
                        int hidden = screenH - visible.bottom;      // 被遮挡的高度
                        int nav = navigationBarHeight();
                        int kb = hidden - nav;
                        if (kb < 0) kb = 0;
                        // 小于 15% 视为噪声（状态栏/导航栏抖动）
                        if (kb < screenH * 0.15) kb = 0;
                        if (kb != lastKb) {
                            lastKb = kb;
                            log("键盘检测: 高 " + kb + "px（窗口 " + screenH
                                    + ", 可见底 " + visible.bottom + ", 导航栏 " + nav + "）");
                            rootView.setPadding(0, statusBarHeight(), 0, kb);
                        }
                    } catch (Throwable ignored) { }
                }
            });
            log("已启用键盘布局监听");
        } catch (Throwable t) {
            log("⚠️ 无法注册布局监听: " + t);
        }
    }

    private int navigationBarHeight() {
        try {
            int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return 0;
    }

    private int statusBarHeight() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 开屏
    /**
     * 构建开屏页：鲸鱼标志（呼吸动画）+ 转圈 + 一行友好文案。
     *
     * <p>之前启动期间用户看到的是滚动的日志行，既不好看也不友好。
     * 现在改为纯粹的视觉反馈；详细日志仍在后台写文件。
     * 若启动失败，点一下开屏页即可收起，露出下方的详细错误页。
     */
    private android.view.View buildSplash() {
        float d = getResources().getDisplayMetrics().density;

        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundColor(0xFFFFFFFF);

        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // 鲸鱼标志
        android.widget.ImageView logo = new android.widget.ImageView(this);
        int id = getResources().getIdentifier(
                "ic_launcher_foreground", "mipmap", getPackageName());
        if (id > 0) logo.setImageResource(id);
        int boxSize = (int) (196 * d);          // 前景里鲸鱼约占 58%，故视图取得大些
        col.addView(logo, new android.widget.LinearLayout.LayoutParams(boxSize, boxSize));

        // 呼吸动画：透明度 + 轻微缩放
        try {
            android.animation.PropertyValuesHolder a =
                    android.animation.PropertyValuesHolder.ofFloat("alpha", 0.45f, 1f);
            android.animation.PropertyValuesHolder sx =
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.93f, 1f);
            android.animation.PropertyValuesHolder sy =
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.93f, 1f);
            android.animation.ObjectAnimator anim =
                    android.animation.ObjectAnimator.ofPropertyValuesHolder(logo, a, sx, sy);
            anim.setDuration(1150);
            anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            anim.start();
        } catch (Throwable ignored) { }

        // 转圈
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        try {
            spin.getIndeterminateDrawable().setColorFilter(
                    0xFF4D6BFE, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) { }
        android.widget.LinearLayout.LayoutParams slp =
                new android.widget.LinearLayout.LayoutParams(
                        (int) (30 * d), (int) (30 * d));
        slp.topMargin = (int) (26 * d);
        col.addView(spin, slp);

        // 文案
        splashStatus = new android.widget.TextView(this);
        splashStatus.setText("正在启动 DeepSeek Harness");
        splashStatus.setTextColor(0xFF6B7280);
        splashStatus.setTextSize(13.5f);
        android.widget.LinearLayout.LayoutParams tlp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (18 * d);
        col.addView(splashStatus, tlp);

        android.widget.FrameLayout.LayoutParams clp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.CENTER;
        box.addView(col, clp);

        // 默认不接收点击：正常启动时应由 onPageFinished 自动收起。
        // 只有启动失败时才挂上"点击查看日志"（见 boot 的 catch）。
        box.setClickable(false);
        return box;
    }

    /** 更新开屏文案（友好措辞，不暴露日志）。 */
    private void setSplashStatus(final String text) {
        if (splashStatus == null || splashHidden) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (splashStatus != null && !splashHidden) splashStatus.setText(text);
            }
        });
    }

    /** 淡出并移除开屏。 */
    private void hideSplash() {
        if (splashView == null || splashHidden) return;
        splashHidden = true;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                final android.view.View sv = splashView;
                if (sv == null) return;
                // 立即停止接收触摸：即使动画因故未结束，也不会再挡住界面
                sv.setClickable(false);
                sv.setFocusable(false);
                try {
                    android.view.animation.AlphaAnimation fade =
                            new android.view.animation.AlphaAnimation(1f, 0f);
                    fade.setDuration(400);
                    fade.setFillAfter(true);
                    sv.startAnimation(fade);
                } catch (Throwable ignored) { }
                // 兜底：无论动画是否回调，都在 500ms 后强制移除。
                // （此前只依赖 onAnimationEnd，一旦未触发就留下一个透明但吃触摸的全屏视图）
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                    @Override public void run() {
                        try { sv.clearAnimation(); sv.setVisibility(android.view.View.GONE); }
                        catch (Throwable ignored) { }
                    }
                }, 500);
            }
        });
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
        setSplashStatus("正在检查运行环境…");
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
        File caB = new File(root, "ca-certificates.crt");
        if (caB.exists()) {
            pb.environment().put("CURL_CA_BUNDLE", caB.getAbsolutePath());
            pb.environment().put("SSL_CERT_FILE", caB.getAbsolutePath());
            pb.environment().put("GIT_SSL_CAINFO", caB.getAbsolutePath());
        }
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
        downloadPath(ASSET_PATH, assetName, out);
    }

    /** 从指定 release 路径下载（供 App 自更新使用，它不在运行包那个 release 下）。 */
    private void downloadPath(String basePath, String assetName, File out) throws IOException {
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
                    String url = SOURCES[s] + basePath + assetName;
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
                        // 开屏只显示友好的进度，不显示速率/重试等技术细节
                        setSplashStatus("正在下载运行包 " + pct + "%");
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
    private String shorten(Throwable e) {
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
        File ca = new File(root, "ca-certificates.crt");
        if (ca.exists()) {
            pb.environment().put("CURL_CA_BUNDLE", ca.getAbsolutePath());
            pb.environment().put("SSL_CERT_FILE", ca.getAbsolutePath());
            pb.environment().put("GIT_SSL_CAINFO", ca.getAbsolutePath());
        }
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
            w.write("APK 版本: 0.12.0\n");
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
                    "android.permission.READ_EXTERNAL_STORAGE",
                    // Android 13+ 常驻通知需要它；拒绝也不影响服务运行，只是通知不显示
                    "android.permission.POST_NOTIFICATIONS"}, 1001);
            log("已请求存储与通知权限");
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
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /**
     * 处理从其他 App 分享过来的内容。
     *
     * <p>文件直接落到工作区；文本存成带时间戳的说明文件。
     * 这样用户在任何 App 里「分享到 DeepSeek Harness」，
     * 内容就出现在 agent 能直接读写的地方。
     */
    private void handleShareIntent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!android.content.Intent.ACTION_SEND.equals(action)
                && !android.content.Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        try {
            final File ws = workspace;
            if (ws == null) {
                toast("工作区不可用，无法接收分享内容");
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                    java.util.Locale.US).format(new java.util.Date());

            android.net.Uri stream = intent.getParcelableExtra(
                    android.content.Intent.EXTRA_STREAM);
            String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            String subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT);

            if (stream != null) {
                String name = queryDisplayName(stream);
                if (name == null || name.length() == 0) name = "分享文件-" + stamp;
                File out = new File(ws, name);
                java.io.InputStream in = getContentResolver().openInputStream(stream);
                if (in == null) throw new IOException("无法读取分享的文件");
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                byte[] buf = new byte[65536];
                int k;
                while ((k = in.read(buf)) > 0) fo.write(buf, 0, k);
                fo.close();
                in.close();
                log("已接收分享文件: " + out.getAbsolutePath());
                toast("已放入工作区：" + name);
                return;
            }

            if (text != null && text.length() > 0) {
                String base = (subject != null && subject.trim().length() > 0)
                        ? subject.trim().replaceAll("[\\/:*?\"<>|]", "_") : "分享内容";
                File out = new File(ws, base + "-" + stamp + ".txt");
                writeText(out, text);
                log("已接收分享文本: " + out.getAbsolutePath());
                toast("已放入工作区：" + out.getName());
            }
        } catch (Throwable t) {
            log("✗ 处理分享内容失败: " + t);
            toast("接收分享内容失败");
        }
    }

    private String queryDisplayName(android.net.Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && i >= 0) {
                    String v = c.getString(i);
                    c.close();
                    return v;
                }
                c.close();
            }
        } catch (Throwable ignored) { }
        return null;
    }

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
