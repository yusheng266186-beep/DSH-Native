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
            "https://github.com/yusheng266186-beep/DSH-Native/releases/download/payload-v6/";
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
    /**
     * 顺序：直连优先，镜像兜底。
     *
     * <p>实测 gh-proxy 会缓存这个文件，多次返回旧版本（0.12.1），
     * 而直连 raw 始终是最新的。GitHub raw 自身有 5 分钟 CDN 缓存，
     * 不同边缘刷新时间还不一致 —— 所以后面还会「取所有源里版本最高的那个」。
     */
    private static final String[] VERSION_SOURCES = {
            // 顺序按**实测新鲜度**排，不是按印象：
            //   GitHub raw    —— 5 分钟 CDN 缓存，实测最准
            //   gh-proxy      —— 与 raw 同源，缓存同样几分钟
            //   jsDelivr x2   —— **@main 分支缓存严重过期**（实测停在几十个版本前），
            //                    保留它们只是因为「raw 被墙时还能拿到一个旧值」，
            //                    放到最后，避免每次都先白等一轮
            // 取「所有源的最高版本」而非第一个成功的 —— 正是这条逻辑
            // 让 jsDelivr 的陈旧数据不会影响更新。
            RAW,
            "https://gh-proxy.com/" + RAW,
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/latest.json",
            "https://fastly.jsdelivr.net/gh/" + REPO + "@main/latest.json",
    };

    /**
     * 镜像前缀。**只放前缀**，完整路径由 downloadPath 传入 ——
     * 之前把 ASSET_PATH 拼进这里，导致 App 自更新（走另一个 release）时
     * URL 变成 payload-v4/releases/download/... 这种错误组合。
     * 空串表示直连 GitHub 兜底。
     */
    private static final String[] SOURCES = {
            "",                               // 直连优先（实测这台设备上最快）
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
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
        // 文字缩放：只影响字号，不改变布局宽度
        ws.setTextZoom(currentZoom());
        // DSH 的「添加 → 文件」菜单会 click() 页面里的原生 <input type="file">。
        // WebView 必须由宿主实现 onShowFileChooser，否则点击毫无反应 ——
        // 这正是之前"无法上传文件"的真正原因。
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                // DSH 把真实错误藏在 API 响应里、界面只显示概括信息。
                // 捕获浏览器控制台，配合下面注入的 fetch 包装即可拿到完整错误。
                String m = cm == null ? "" : cm.message();
                if (m != null && m.length() > 0) {
                    // 任务事件单独分流：不写进日志（每 4 秒一次的轮询若都记，
                    // 日志会被刷爆），只用于通知判定
                    String[] ev = TaskNotifier.parseConsole(m);
                    if (ev != null) {
                        onTaskEvent(ev[0], ev[1]);
                        return true;
                    }
                    log("[web] " + (m.length() > 900 ? m.substring(0, 900) : m));
                }
                return true;
            }

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
                    log("错误: 启动文件选择器失败: " + t);
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
                installFetchDiagnostics();
                installTaskWatcher();
                final String act = pendingAction;
                pendingAction = "";
                if (pendingOpenSettings || act.length() > 0) {
                    pendingOpenSettings = false;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                        @Override public void run() {
                            if ("log".equals(act)) {
                                showLog();
                            } else {
                                showSettings();
                                if ("update".equals(act)) checkAppUpdate(true, null);
                            }
                        }
                    }, 600);
                }
                // 启动后静默检查一次更新：用户不必手动点，
                // 日志里也总能留下一条可核对的结果。
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                        autoCheckUpdate();
                    }
                }).start();
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

        // 允许绘制到刘海（挖孔）区域。
        //
        // Android 默认会让窗口**避开**刘海，在那一侧留一条黑边 ——
        // 竖屏时刘海在顶部（被状态栏遮住不明显），横屏时刘海转到侧面，
        // 于是左边出现一大块黑边。
        // SHORT_EDGES 表示「短边方向可延伸到刘海区」，配合下面的
        // 刘海安全区内边距，内容既填满屏幕又不会被摄像头挡住。
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
                log("已允许绘制到刘海区域（避免横屏黑边）");
            }
        } catch (Throwable t) {
            log("警告: 刘海模式设置失败: " + t);
        }

        // 让各 UI 组件（文件浏览、编辑器等）能把诊断信息写进统一日志
        DshUi.setLogSink(new DshUi.LogSink() {
            @Override public void log(String msg) { log(msg); }
        });

        setContentView(root);

        initSharedLog();
        requestStoragePermission();
        handleShareIntent(getIntent());
        resolveLaunchIntent(getIntent());

        cleanupStaleUpdateApk();
        installCrashHandler();
        showPreviousCrash();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    log("错误: 启动失败: " + t);
                    Log.e(TAG, "boot failed", t);
                    // 若本次启用了插件覆盖层，判定为插件所致并写入停用标记：
                    // 最坏情况只是少一个插件，绝不会让 App 打不开。
                    if (usedPluginPatch && appRoot != null) {
                        try {
                            writeText(new File(appRoot, ".plugins-disabled"),
                                    "上次带插件启动失败，已自动停用。\n"
                                  + "删除本文件可重新尝试启用插件。\n");
                            log("已自动停用插件覆盖层，下次启动将不带 --patch");
                        } catch (Throwable ignored) { }
                    }
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
        // 记录给插件管理用（安装时需要 node 与 npm 的路径）

        toolsDirRef = toolsDir;

        nodeRef = node;

        runPreflight(node, root, toolsDir);

        // 3. 准备 DSH 配置（若不存在）
        prepareConfig(root);

        // 4. 启动 dsh web
        File binJs = new File(dshDir, "lib/bin.js");
        // 必须挑一个空闲端口：设备上可能已有别的 DSH 实例占用 3080，
        // 直接沿用会 EADDRINUSE 导致启动失败、界面空白。
        chosenPort = findFreePort(PORT, PORT + 200);
        if (chosenPort == 0) {
            log("警告: 未找到空闲端口，交给系统分配（--port 0）");
        } else {
            log("使用端口 " + chosenPort + (chosenPort == PORT ? "" : "（" + PORT + " 已被占用）"));
        }
        setSplashStatus("正在启动服务…");
        log("启动 dsh web …");
        // 命令行改为列表构造，便于按需追加 --patch 覆盖层
        // （--patch 最后应用、优先级最高，用它启用插件无需改动 profile 文件）。
        java.util.List<String> dshCmd = new java.util.ArrayList<String>();
        dshCmd.add(node.getAbsolutePath());
        dshCmd.add("--expose-internals");      // 关键：替代无 android 构建的原生插件
        dshCmd.add("--no-warnings");
        dshCmd.add(binJs.getAbsolutePath());
        // --patch 是「启动器级」选项，必须排在 --profile 之前。
        // 实测放在 --profile 之后会报 unknown option 并导致 DSH 完全无法启动。
        enableOptionalPlugins(dshDir, root, dshCmd);
        dshCmd.add("--profile"); dshCmd.add("web");
        dshCmd.add("--no-open");
        dshCmd.add("--port"); dshCmd.add(String.valueOf(chosenPort));
        ProcessBuilder pb = new ProcessBuilder(dshCmd);
        pb.redirectErrorStream(true);
        pb.directory(workspace != null ? workspace : root);

        String libPath = new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath();
        String binPath = new File(toolsDir, "bin").getAbsolutePath() + ":/system/bin:/system/xbin";
        // 注意：这里**故意不设 NODE_PATH**。
        // DSH 从 <dshDir>/lib/bin.js 启动，Node 会沿目录向上自然找到
        // <dshDir>/node_modules，无需 NODE_PATH。
        // 而 NODE_PATH 是 Node 的遗留回退机制，会让同一个包经由不同路径
        // 被加载成**多个模块实例** —— DSH 的错误分类依赖 `instanceof`，
        // 实例不一致会导致「本是图片相关的错误」被兜底包装成
        // "prompt rejected (session/agent-busy)"，真实原因就此丢失。
        // （preflight 脚本位于 <root>/，那是另一个进程，仍需要 NODE_PATH。）

        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
        pb.environment().put("PATH", binPath);
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
                log("警告: 未捕获到带 token 的地址，尝试直接加载（可能显示未授权页）");
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
                log("错误: dsh web 进程已退出，且未打印服务地址");
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
        log("警告: 等待服务超时（" + MAX_SECONDS + " 秒）");
        showStatus("启动超时", "已等待 " + MAX_SECONDS + " 秒仍未拿到服务地址，请查看上方日志。");
        return null;
    }

    private volatile String lastUrl;
    private android.widget.FrameLayout rootView;
    /** App 私有根目录，供设置页读写配置。 */
    private volatile File appRoot;

    /** 后台任务完成通知的判定（纯逻辑在 TaskNotifier 里，有 33 项测试）。 */
    private final TaskNotifier taskNotifier = new TaskNotifier();
    /** App 是否在前台：在前台时界面本来就看得见结果，不必再弹通知。 */
    private volatile boolean inForeground = true;
    /** 任务完成通知的通知 id（与前台服务通知区分开）。 */
    private static final int TASK_DONE_NOTIFY_ID = 1001;
    /**
     * 可选的显示缩放档位（百分比）。
     *
     * <p>为什么需要：为了让 DSH 的桌面布局在手机上不被挤压，
     * 前端 viewport 被固定为 600px，代价是整体缩放后**文字偏小**。
     * WebView 的 textZoom 只放大文字、不影响布局，正好补上这个取舍。
     */
    private static final int[] ZOOM_STEPS = {100, 115, 130, 150};
    private static final String PREFS = "dsh-native";
    /** 前端 index.html 原始 viewport 写法（补丁从这里重新生成，保证可重复更新）。 */
    private static final String VP_ORIG =
            "content=\"width=device-width, initial-scale=1\"";
    /** 当前已应用的 viewport 宽度（0 = 尚未应用）。 */
    private volatile int appliedViewportWidth = 0;
    /** 工具链目录与 node 可执行文件（插件安装需要）。 */
    private volatile File toolsDirRef;
    private volatile File nodeRef;

    /** DSH 安装目录，供屏幕方向变化时重新适配 viewport。 */
    private volatile File dshDirRef;
    /** agent 的工作目录（优先共享存储）。 */
    private volatile File workspace;
    private android.view.View splashView;
    private android.widget.TextView splashStatus;
    private volatile boolean splashHidden;
    /** 通知栏「设置」动作带的标记。 */
    public static final String EXTRA_OPEN_SETTINGS = "dev.dsh.nativeapp.OPEN_SETTINGS";
    /** 待处理的设置请求（界面未就绪时先记下，加载完成后打开）。 */
    private volatile boolean pendingOpenSettings;
    /** 快捷方式请求的动作："" / "log" / "update"。 */
    private volatile String pendingAction = "";
    /**
     * 补丁执行状态，用于在设置页展示（DSH 更新后补丁可能失效）。
     *
     * <p>用 Map 而不是 List：同名只保留最新一条。早先用 List 追加，
     * 导致每次切换显示缩放都会在「维护状态」里多堆一行，重复累积。
     */
    private final java.util.Map<String, String> patchReport =
            new java.util.LinkedHashMap<String, String>();

    /**
     * 填充显示缩放档位按钮。
     *
     * <p>每次点击**整行重建**，而不是逐个按钮改文字和背景。
     * 早先的写法要同时维护「文字对勾 + 背景高亮」两份状态，
     * 与按钮自身的 focus/pressed 状态互相干扰，实测出现
     * 「多个档位同时高亮 / 对勾与当前值不一致」。
     * 现在渲染只依赖 {@link #currentZoom()} 这一个数据源，不可能不一致。
     */
    private void fillZoomRow(final android.widget.LinearLayout row) {
        row.removeAllViews();
        for (int i = 0; i < ZOOM_STEPS.length; i++) {
            final int pct = ZOOM_STEPS[i];
            boolean cur = pct == currentZoom();
            // 用 toggleButton：保证单行显示，不会把按钮撑高
            // 选中态由按钮样式（主/次）体现，不再叠加符号
            android.widget.Button b = DshUi.toggleButton(this, pct + "%", cur);
            b.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    applyZoom(pct);
                    fillZoomRow(row);          // 重建 → 状态必然一致
                    toast("显示缩放已设为 " + pct + "%");
                }
            });
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = DshUi.dp(this, 6);
            row.addView(b, lp);
        }
    }

    /** 读取显示缩放（默认 100%）。 */
    private int currentZoom() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("textZoom", 100);
        } catch (Throwable t) {
            return 100;
        }
    }

    /** 应用显示缩放；立即生效，无需重启。 */
    private void applyZoom(int pct) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("textZoom", pct).apply();
            if (webView != null) webView.getSettings().setTextZoom(pct);
            // 不写入「维护状态」——那是补丁清单；当前档位由按钮对勾体现
            log("显示缩放已设为 " + pct + "%");
        } catch (Throwable t) {
            log("警告: 设置显示缩放失败: " + t);
        }
    }

    /** 记录一条补丁状态。 */
    private void recordPatch(String name, boolean ok, String detail) {
        // 状态用颜色区分（设置页按 \u0000/\u0001 上色），文字里不含任何符号
        patchReport.put(name, (ok ? "\u0000" : "\u0001") + name
                + (detail == null || detail.length() == 0 ? "" : " — " + detail));
    }
    /** 本次启动是否使用了插件 --patch 覆盖层（用于失败时自动停用）。 */
    private volatile boolean usedPluginPatch;
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
                                log("  已捕获服务地址");
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
            // 顶层块存在 ≠ 内容正确：早期生成的 settings.yaml 可能缺少模型的
            // input 声明，而 DSH 对未声明者一律按「仅文字」处理 ——
            // 表现为发图片被拒。这里以预设为准整块同步（幂等）。
            syncProviderConfig(root, settings);
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

    /**
     * 用预设里的 {@code llm-pi-ai} 块整体覆盖 settings.yaml 中的同名块。
     *
     * <p><b>为什么不能用「逐模型补一行」的正则方案</b>：
     * 正则很难可靠地界定「一个模型条目到哪里结束」，一旦界定错就会
     * 把某个模型的 input 归给相邻模型（实测把 v4-flash 误判为支持图片）；
     * 而且匹配失败时无法与「本来就正确」区分，日志会误报「已齐全」。
     *
     * <p>这里改为按行解析顶层块并整体替换 —— 结构清晰、幂等、结果确定。
     * {@code llm-pi-ai} 只描述 provider 与模型清单，用户的模型选择存在
     * {@code agent-default-model} 块里，因此覆盖它不会动到用户的选择。
     *
     * <p><b>为什么必须同步</b>：DSH 的模型 schema 是
     * {@code input: entry.input ?? base?.input ?? [...request.defaultInput]}，
     * 而 {@code DEFAULT_INPUT = ["text"]} ——
     * **未声明 input 的模型一律被视为不支持图片**，发图片会在准入阶段被拒。
     */
    private void syncProviderConfig(File root, File settings) {
        try {
            File preset = new File(root, "settings-preset.yaml");
            if (!preset.exists() || !settings.exists()) return;

            String want = topLevelBlock(readText(preset), "llm-pi-ai");
            if (want == null || want.length() == 0) {
                log("  [警告] 预设里没有 llm-pi-ai 块，跳过同步");
                return;
            }
            String cur = readText(settings);
            String have = topLevelBlock(cur, "llm-pi-ai");
            if (want.equals(have)) {
                log("  模型配置与预设一致（含图片能力声明）");
                dumpForDiagnosis(want);          // 仍需导出供核对
                reportModelDiagnostics(settings);
                return;
            }

            String out;
            if (have == null) {
                out = cur.endsWith("\n") ? cur + want : cur + "\n" + want;
            } else {
                out = cur.replace(have, want);
            }
            writeText(settings, out);

            // 记录同步后的模型与图片能力，便于核对
            java.util.List<String> withImage = new java.util.ArrayList<String>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?m)^\\s*-\\s*id:\\s*[\"']?([^\"'\\n]+?)[\"']?\\s*$\n"
                  + "((?:(?!^\\s*-\\s*id:)[\\s\\S])*?)"
                  + "^\\s*input:\\s*\\[[^\\]]*image[^\\]]*\\]").matcher(want);
            while (m.find()) withImage.add(m.group(1).trim());
            log("  已同步模型配置；支持图片输入的模型: "
                    + (withImage.isEmpty() ? "（无）" : withImage.toString()));
        } catch (Throwable t) {
            log("  [警告] 同步模型配置失败: " + shorten(t));
        }
        try { dumpForDiagnosis(topLevelBlock(readText(settings), "llm-pi-ai")); }
        catch (Throwable ignored) { }
        reportModelDiagnostics(settings);
    }

    /**
     * 把 {@code llm-pi-ai} 配置导出到共享目录，便于在设备外核对。
     *
     * <p>只导出 provider 与模型定义：其中 {@code apiKeyEnv} 存的是
     * **环境变量名**而非密钥值，密钥始终只在 .credentials.yaml 里，
     * 因此这份导出不含敏感信息。
     */
    private void dumpForDiagnosis(String block) {
        try {
            if (block == null || block.length() == 0) return;
            File out = new File("/sdcard/DSHNative/model-config.yaml");
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            writeText(out, "# 由 App 导出的模型配置（不含密钥值，只有环境变量名）\n"
                    + "# 用途：核对模型是否声明了 input: [text, image]\n\n" + block);
            log("  已导出模型配置供核对: " + out.getAbsolutePath());
        } catch (Throwable ignored) { }
    }

    /**
     * 核对默认模型是否真的存在于 provider 的模型清单中。
     *
     * <p>DSH 在「发送图片」时会额外调用 {@code llm.resolveModelInfo(provider, model)}
     * 来检查模型是否支持图片输入 —— 这是**只有图片才走**的分支。
     * 若该模型在配置里查不到，这里会抛错并被 DSH 包装成
     * "prompt rejected (session/agent-busy)"。因此把核对结果写进日志。
     */
    private void reportModelDiagnostics(File settings) {
        try {
            String txt = readText(settings);
            String provider = readScalar(settings, "provider");
            String model = readScalar(settings, "model");
            log("默认模型: " + (provider.length() == 0 ? "?" : provider)
                    + " / " + (model.length() == 0 ? "?" : model));
            if (model.length() == 0) return;

            if (txt.indexOf("- id: \"" + model + "\"") < 0
                    && txt.indexOf("- id: " + model) < 0
                    && txt.indexOf("'" + model + "'") < 0) {
                log("  [警告] 该模型不在配置的模型清单中 —— "
                        + "发图片时 resolveModelInfo 会失败，请改用清单内的模型");
                return;
            }
            // 该模型是否声明了图片输入
            // 注意：分组必须用「至少一个字符」的惰性量词并带终止锚点，
            // 否则会匹配空串 —— 之前就因此永远报「未声明图片输入」（误报）。
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?m)^\\s*-\\s*id:\\s*[\"']?" + java.util.regex.Pattern.quote(model)
                  + "[\"']?\\s*$([\\s\\S]*?)(?=^\\s*-\\s*id:|\\Z)").matcher(txt);
            if (m.find()) {
                boolean img = m.group(1).contains("input:") && m.group(1).contains("image");
                log("  该模型" + (img ? "已声明支持图片输入" : "未声明图片输入（发图会被拒）"));
            }
        } catch (Throwable t) {
            log("  （模型核对失败: " + shorten(t) + "）");
        }
    }

    /**
     * 取 YAML 里某个顶层键的完整块（到下一个顶层键或文件尾）。
     * 用逐行解析而非正则，避免块边界判断出错。
     */
    private static String topLevelBlock(String yaml, String key) {
        String[] lines = yaml.split("\n", -1);
        int start = -1, end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.length() == 0 || l.charAt(0) == ' ' || l.charAt(0) == '\t'
                    || l.charAt(0) == '#') {
                continue;
            }
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim();
            if (start < 0) {
                if (k.equals(key)) start = i;
            } else {
                end = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    /**
     * 当前版本的 APK 下载地址（直连形式）。
     *
     * <p>网络诊断用它做测速 —— 必须与更新功能真正会下载的地址一致，
     * 否则测出来的速度没有参考意义。
     */
    private String apkDownloadUrl() {
        return "https://github.com/yusheng266186-beep/DSH-Native/releases/download/v"
                + appVersion() + "-bootstrap/DSHNative-bootstrap.apk";
    }

    // ---------------------------------------------------------------- 运行包修订号

    /** 本机已应用的运行包修订号（0 表示尚未记录）。 */
    private int appliedPayloadRevision() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt("payloadRevision", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 记录已应用的运行包修订号。 */
    private void rememberPayloadRevision(int rev) {
        if (rev <= appliedPayloadRevision()) return;
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("payloadRevision", rev).apply();
            log("运行包修订号已记录: " + rev);
        } catch (Throwable t) {
            log("记录运行包修订号失败: " + t);
        }
    }

    /** 递归删除目录（仅用于清空运行包，不动配置）。 */
    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        if (!dir.delete()) {
            // 删不掉不阻断：后续解压会覆盖同名文件，
            // 只是被移除的那些会残留 —— 记下来便于排查
            android.util.Log.w("dsh", "无法删除: " + dir.getAbsolutePath());
        }
    }

    /** 运行包摘要（供设置页显示）。 */
    private String payloadSummary() {
        try {
            File mf = new File(appRoot, "manifest.json");
            if (!mf.exists()) return "未知";
            org.json.JSONObject o = new org.json.JSONObject(readText(mf));
            return o.optInt("version", 0) + "（" + o.getJSONArray("parts").length() + " 分片）";
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 在应用内直接查看运行日志（DSH 风格卡片，非系统对话框）。 */
    private void showLog() {
        // 交给专门的查看器：支持「本次启动 / 全部」、级别过滤与搜索 ——
        // 旧实现只是把最后 400 行倒进一个 TextView，二十多次启动的日志
        // 混在一起，根本分不清哪条是当前的。
        LogViewer.show(this, sharedLog, new Runnable() {
            @Override public void run() {
                try {
                    if (sharedLog != null && sharedLog.exists()) {
                        writeText(sharedLog, "=== DSH Native 启动日志 ===\n");
                    }
                } catch (Throwable t) {
                    log("清空日志失败: " + t);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 任务完成通知
    /**
     * 注入会话状态轮询：观察 {@code running} 由有到无，判断任务完成。
     *
     * <p>为什么用注入而不是原生轮询：页面已经完成认证（会话 Cookie），
     * 同源 {@code fetch} 直接可用，不必把 token 拿出来在原生侧另开一条请求。
     * 回报走 {@code console.log} —— 不改动网页的安全面（不引入 JS 桥）。
     */
    private void installTaskWatcher() {
        try {
            webView.evaluateJavascript(TaskNotifier.pollScript(), null);
            log("已注入任务状态监听（完成后会在后台通知）");
        } catch (Throwable t) {
            log("任务状态监听注入失败: " + t);
        }
    }

    /** 处理来自注入脚本的任务事件。 */
    private void onTaskEvent(String kind, String sessionId) {
        try {
            String msg = taskNotifier.onEvent(kind, sessionId,
                    System.currentTimeMillis(), inForeground);
            if (msg == null) return;
            notifyTaskDone(msg);
        } catch (Throwable t) {
            log("任务事件处理失败: " + t);
        }
    }

    /** 发一条任务完成通知。 */
    private void notifyTaskDone(String text) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            int icon = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
            if (icon == 0) icon = android.R.drawable.stat_notify_sync;

            android.content.Intent open = new android.content.Intent(this, MainActivity.class);
            open.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getActivity(this, 0, open, flags);

            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b = new android.app.Notification.Builder(this, HarnessService.CHANNEL_ID);
            } else {
                b = new android.app.Notification.Builder(this);
            }
            b.setContentTitle("DeepSeek Harness")
             .setContentText(text)
             .setSmallIcon(icon)
             .setContentIntent(pi)
             .setAutoCancel(true);
            nm.notify(TASK_DONE_NOTIFY_ID, b.build());
            log("已发出任务完成通知: " + text);
        } catch (Throwable t) {
            log("任务完成通知发送失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 网络诊断
    /**
     * 注入一段 JS，把失败的 fetch 响应体打到浏览器控制台。
     *
     * <p>为什么需要：DSH 的接口在出错时返回结构化 JSON，
     * 但界面只显示一句概括（例如 "prompt rejected (session/agent-busy)"），
     * 真正的 reason 字段被丢掉了。包装 fetch 后可把它完整取出来，
     * 再由 onConsoleMessage 落到 App 日志里。
     */
    private void installFetchDiagnostics() {
        // 策略：记录**所有非静态资源**请求的状态与响应体（截断）。
        // 之前只记录非 2xx，但 DSH 的 RPC 很可能用 200 + 错误负载，
        // 于是什么都没抓到。全量记录才能保证失败请求必然显现 ——
        // 真正的错误详情（details.reason）只能从这里拿到。
        final String js =
            "(function(){"
          + "if(window.__dshDiag)return;window.__dshDiag=1;"
          + "var of=window.fetch;"
          + "function isAsset(u){return /\\.(js|css|png|jpe?g|gif|svg|woff2?|ttf|ico|map)(\\?|$)/i.test(u);}"
          + "window.fetch=function(){"
          + "  var a=arguments[0];"
          + "  var u='';"
          + "  try{u=(typeof a==='string')?a:(a&&a.url?a.url:String(a));}catch(x){}"
          + "  var p=of.apply(this,arguments);"
          + "  try{"
          + "    p.then(function(r){"
          + "      try{"
          + "        if(isAsset(u))return;"
          + "        var ct=(r.headers&&r.headers.get)?(r.headers.get('content-type')||''):'';"
          + "        var isJson=ct.indexOf('json')>=0;"
          + "        if(!isJson&&r.status<400){"
          + "          console.log('[dsh-api] '+r.status+' '+u+' ('+ct.split(';')[0]+')');return;"
          + "        }"
          + "        r.clone().text().then(function(t){"
          + "          console.log('[dsh-api] '+r.status+' '+u+' :: '+String(t).slice(0,700));"
          + "        }).catch(function(){});"
          + "      }catch(e){}"
          + "    }).catch(function(e){"
          + "      console.error('[dsh-api] NETFAIL '+u+' :: '+String(e));"
          + "    });"
          + "  }catch(e){}"
          + "  return p;"
          + "};"
          + "})();"
          // 客户端异常捕获：这是我此前一直缺的一块。
          // fetch 包装只能看到「已发出的请求」，而纯客户端抛错（例如
          // 文件 MIME 不在允许列表里而抛 UnsupportedImageMediaTypeError）
          // 根本不会产生请求 —— 必须靠 error / unhandledrejection 才能看到。
          + ";(function(){"
          + "if(window.__dshErr)return;window.__dshErr=1;"
          + "window.addEventListener('error',function(e){"
          + "  try{"
          + "    var st=(e.error&&e.error.stack)?e.error.stack:'';"
          + "    console.error('[dsh-js-error] '+(e.message||'')+' @'+(e.filename||'')+':'+(e.lineno||0)+' :: '+String(st).slice(0,700));"
          + "  }catch(x){}"
          + "});"
          + "window.addEventListener('unhandledrejection',function(e){"
          + "  try{"
          + "    var r=e.reason;"
          + "    var d=(r&&(r.stack||r.message))?(r.stack||r.message):String(r);"
          + "    var n=(r&&r.name)?(r.name+': '):'';"
          + "    console.error('[dsh-js-reject] '+n+String(d).slice(0,800));"
          + "  }catch(x){}"
          + "});"
          + "})();";
        try {
            webView.evaluateJavascript(js, null);
            log("已注入接口捕获 + 客户端异常捕获");
        } catch (Throwable t) {
            log("警告: 注入接口捕获失败: " + t);
        }
    }

    /**
     * Android 补丁：附件落盘时的「目录持久化」会向上遍历到文件系统根。
     *
     * <p><b>这是 DSH 自身的一个平台假设问题</b>：
     * {@code ensureDurableHome()} 以 {@code parse(home).root}（即 {@code "/"}）
     * 作为遍历边界，{@code ensureDurableDirectory()} 便逐级打开父目录做 fsync。
     *
     * <p>在桌面/容器里这没问题；但在 Android 上，App 只能访问
     * {@code /data/user/0/<包名>} 子树 —— 遍历到 {@code /data/user/0} 就 EACCES。
     * 由于该异常不是 AttachmentError，最终被上层兜底包装成
     * {@code prompt rejected (session/agent-busy)}，把真实原因完全掩盖。
     *
     * <p>（实测：我的环境以 root 运行、家目录在 {@code /root/.dsh}，向上遍历
     * 全部可访问，因此图片一直正常 —— 差异就在这里。）
     *
     * <p><b>修法</b>：把 {@code syncDirectory} 包一层，EACCES/EPERM 视为
     * 「该层无需持久化」直接跳过。这些目录本就不属于本应用，
     * 无法也不应去 fsync 它们。
     */
    private void patchAttachmentDurability(File dshDir) {
        try {
            File f = new File(dshDir,
                    "node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js");
            if (!f.exists()) {
                log("  （未找到附件模块，跳过持久化补丁）");
                return;
            }
            final String PATCH_TAG = "/* DSH-ANDROID-ATTACH-PATCH-v3 */";
            File orig = new File(f.getParentFile(), "index.js.dshorig");

            // 补丁会写回文件，而运行包不变时不会重新解压 ——
            // 因此不能靠「文件里是否已有补丁」判断幂等：那样补丁升级永远进不去
            // （实测：v0.16.2 的新补丁因 v0.16.0 已改过文件而被整体跳过）。
            // 方案：保留一份**剥离过补丁的原文件**，之后每次都从它重新生成。
            if (!orig.exists()) {
                String raw = readText(f);
                String base = stripAndroidPatch(raw);
                if (base.indexOf("await syncDirectory(") < 0) {
                    recordPatch("附件落盘", false, "DSH 代码已变化，补丁未应用（图片可能失效）");
                    log("  [警告] 附件模块中未找到预期调用，跳过补丁（可能 DSH 版本变化）");
                    return;
                }
                writeText(orig, base);
                log("  已保存附件模块原文件（供补丁重新生成）");
            }

            String cur = readText(f);
            if (cur.contains(PATCH_TAG)) {
                recordPatch("附件落盘", true, "已是最新");
                log("  附件补丁已是最新（v3）");
                return;
            }
            String out = buildPatchedAttachment(readText(orig), PATCH_TAG);
            if (out == null) {
                recordPatch("附件落盘", false, "自检未通过，已放弃");
                log("  [警告] 附件补丁自检未通过，放弃应用");
                return;
            }
            writeText(f, out);
            recordPatch("附件落盘", true, "越界 fsync 跳过 + 硬链接退化复制");
            log("  已应用附件补丁 v3（越界 fsync 跳过 + 硬链接退化复制 + 失败原因可见）");
        } catch (Throwable t) {
            log("  [警告] 附件持久化补丁失败: " + t);
        }
    }

    /**
     * 剥离此前版本打过的附件补丁，还原出干净的模块源码。
     *
     * <p>设备上可能残留旧版补丁（补丁是写回文件的），必须先还原，
     * 否则在新补丁上再包一层会造成重复包装甚至自我递归。
     */
    private static String stripAndroidPatch(String src) {
        // 按**位置**裁剪，不按注释内容匹配 ——
        // 旧版补丁的注释是两行、措辞还各不相同，按内容过滤会漏掉续行，
        // 残留半截注释直接造成语法错误（实测踩过）。
        // 补丁的包装函数总是前置在文件最前面，因此丢掉「最后一个包装函数
        // 定义所在行之前」的全部内容即可。
        String[] lines = src.split("\n", -1);
        int cut = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("async function __android")) cut = i + 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = cut; i < lines.length; i++) sb.append(lines[i]).append('\n');
        String out = sb.toString();
        out = out.replace("await __androidSyncDirectory(", "await syncDirectory(");
        out = out.replace("await __androidLink(", "await link(");
        return out;
    }

    /**
     * 生成打过补丁的附件模块；自检不通过时返回 null。
     *
     * <p>三处加固：
     * <ol>
     *   <li><b>越界 fsync 吞错</b> —— DSH 的 {@code ensureDurableHome()} 以
     *       {@code parse(home).root}（即 {@code "/"}）为遍历边界，会逐级打开父目录
     *       做 fsync。Android 上 App 只能访问 {@code /data/user/0/<包名>} 子树，
     *       走到 {@code /data/user/0} 即 EACCES。目录 fsync 属尽力而为，
     *       失败不应中断附件写入，故吞掉所有错误并记日志。</li>
     *   <li><b>硬链接退化为复制</b> —— 部分 Android 文件系统（FUSE/sdcardfs）不支持
     *       {@code link()}，返回 EPERM/EXDEV/ENOSYS 等；此时改用读写复制。</li>
     *   <li><b>失败原因可见</b> —— 原本 persist 失败只抛一句笼统消息，
     *       真实 error.code 藏在 cause 字段里、界面看不到。</li>
     * </ol>
     */
    private static String buildPatchedAttachment(String src, String tag) {
        if (src == null || src.indexOf("await syncDirectory(") < 0) return null;
        // 必须先替换调用点、再前置包装函数 —— 反过来会把包装函数自身的调用
        // 也替换掉，造成自我递归（实测 RangeError: Maximum call stack size exceeded）。
        String out = src.replace("await syncDirectory(", "await __androidSyncDirectory(");
        out = out.replace("await link(staged.path, target);", "await __androidLink(staged.path, target);");
        out = out.replace("await link(source, target);", "await __androidLink(source, target);");

        int replaced = 0;
        String marker = "throw new AttachmentError(\"Unable to persist attachment.\", "
                + "\"ATTACHMENT_WRITE_FAILED\", { cause: error });";
        if (out.contains(marker)) {
            out = out.replace(marker,
                "console.error('[dsh-attach] persist failed: ' + String(error && error.code)"
              + " + ' ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''));\n\t\t\t"
              + "throw new AttachmentError(\"Unable to persist attachment. [\""
              + " + String(error && error.code) + '] ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''), \"ATTACHMENT_WRITE_FAILED\", { cause: error });");
            replaced = 1;
        }

        String helper = tag + "\n"
              + "async function __androidSyncDirectory(p){try{return await syncDirectory(p);}"
              + "catch(e){console.error('[dsh-attach] syncDirectory skipped ' + p + ': '"
              + " + String(e && e.code));}}\n"
              + "async function __androidLink(from,to){try{return await link(from,to);}"
              + "catch(e){var c=e&&e.code;"
              + "if(c==='EPERM'||c==='EXDEV'||c==='ENOSYS'||c==='EACCES'||c==='EMLINK'"
              + "||c==='EOPNOTSUPP'){"
              + "console.error('[dsh-attach] link unsupported (' + c + '), copy instead');"
              + "const b=await readFile(from);await writeFile(to,b,{mode:384});return;}"
              + "throw e;}}\n";
        out = helper + out;

        // 自检：包装函数体内必须仍是原始调用，否则会自我递归
        if (out.indexOf("__androidSyncDirectory(p){try{return await syncDirectory(p);}") < 0
                || out.indexOf("__androidLink(from,to){try{return await link(from,to);}") < 0) {
            return null;
        }
        return out;
    }

    // ---------------------------------------------------------------- 可选插件
    /**
     * 通过 {@code --patch} 覆盖层启用 DSH 自带但默认未启用的插件。
     *
     * <p>为什么用 --patch 而不是改 profile：启动器的叠加顺序是
     * 「bundle 层 → profile 自身的 cordis.patch.yml → 启动器层（--patch）」，
     * --patch 在最后、优先级最高，因此不必碰用户 profile。
     *
     * <p>每个插件都先确认运行包里真的有它 —— 实测引用不存在的插件会让
     * DSH **整个启动失败**（plugin tree failed to load），必须防御。
     */
    /**
     * 生成插件覆盖层（{@code --patch}）。
     *
     * <p>启用哪些插件由**用户选择**决定（设置 → 插件），默认只启用 Schedule。
     * 覆盖层的内容交给纯逻辑类 {@link PluginSpecs#buildPatchYaml} 生成
     * （有 64 项测试，含命令注入防护与 YAML 结构校验）。
     *
     * <p>关键约束：**引用一个不存在的插件会让 DSH 整个启动失败**，
     * 所以每个名字在写进覆盖层之前都必须确认真的能解析到 ——
     * 内置插件在运行包里，用户装的在 profile 的 node_modules 里，两处都要看。
     */
    private void enableOptionalPlugins(File dshDir, File root,
                                       java.util.List<String> cmd) {
        if (new File(root, ".plugins-disabled").exists()) {
            log("插件已被自动停用（上次启动失败），跳过 --patch");
            return;
        }

        // 插件有两种类型，启用方式不同 —— 判错插件不会生效：
        //   普通插件  → 写进 --patch 的 insert 列表
        //   bundle 插件 → 必须追加到 profile 的 dsh.profile.bundles
        //（实测 dsh-about 属于后者：它的 cordis.patch.yml 是「bundle 声明的
        //  组合层」，只有把包名加进 bundles，那份 patch 才会被合并。）
        File profileModules = new File(new File(root, ".dsh"),
                "profiles/web/node_modules");
        java.util.List<String> plain = new java.util.ArrayList<String>();
        java.util.List<String> bundle = new java.util.ArrayList<String>();
        for (String name : enabledPluginNames(dshDir, root)) {
            File dir = resolvePlugin(dshDir, profileModules, name);
            if (PluginSpecs.pluginKind(dir) == PluginSpecs.KIND_BUNDLE) bundle.add(name);
            else plain.add(name);
        }

        // bundle 插件：改 profile 的 package.json
        if (!bundle.isEmpty()) {
            try {
                File pkg = new File(new File(root, ".dsh"), "profiles/web/package.json");
                if (pkg.isFile()) {
                    String before = readText(pkg);
                    String after = before;
                    for (String name : bundle) {
                        after = PluginSpecs.addToBundlesJson(after, name);
                    }
                    if (!after.equals(before)) {
                        writeText(pkg, after);
                        log("已把 bundle 插件写入 profile: " + bundle);
                    } else {
                        log("bundle 插件已在 profile 中: " + bundle);
                    }
                } else {
                    log("未找到 profile 的 package.json，跳过 bundle 插件: " + bundle);
                }
            } catch (Throwable t) {
                log("写入 bundle 插件失败: " + t);
            }
        }

        if (plain.isEmpty()) {
            if (!bundle.isEmpty()) recordPatch("可选插件", true, "bundle: " + bundle);
            if (bundle.isEmpty()) log("没有启用任何插件，跳过 --patch");
            return;
        }
        try {
            File patch = new File(root, "cordis.plugins.yml");
            writeText(patch, PluginSpecs.buildPatchYaml(plain));
            cmd.add("--patch");
            cmd.add(patch.getAbsolutePath());
            usedPluginPatch = true;
            log("已启用普通插件: " + plain
                    + (bundle.isEmpty() ? "" : "；bundle 插件: " + bundle));
            recordPatch("可选插件", true, plain.toString()
                    + (bundle.isEmpty() ? "" : " + bundle " + bundle));
        } catch (Throwable t) {
            log("插件覆盖层写入失败，跳过启用: " + t);
        }
    }

    /** 用户选择的插件（已过滤掉解析不到的）。 */
    private java.util.List<String> enabledPluginNames(File dshDir, File root) {
        java.util.Set<String> saved = null;
        try {
            saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("plugins", null);
        } catch (Throwable ignored) { }
        java.util.List<String> want = new java.util.ArrayList<String>();
        if (saved == null) {
            want.add("@deepseek-ai/dsh-schedule");      // 默认
        } else {
            want.addAll(saved);
        }

        File profileModules = new File(new File(root, ".dsh"),
                "profiles/web/node_modules");
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String name : want) {
            if (name == null || name.length() == 0) continue;
            if (resolvePlugin(dshDir, profileModules, name) == null) {
                log("  跳过插件 " + name + "：解析不到（可能未安装）");
                continue;
            }
            out.add(name);
        }
        return out;
    }

    /** 插件在磁盘上的位置；解析不到返回 null。两处都查：内置与用户安装。 */
    private File resolvePlugin(File dshDir, File profileModules, String name) {
        File a = new File(new File(dshDir, "node_modules"), name);
        if (a.isDirectory()) return a;
        File b = new File(profileModules, name);
        if (b.isDirectory()) return b;
        return null;
    }

    /** 持久化用户的插件选择；下次启动生效。 */
    private void setEnabledPlugins(java.util.Set<String> names) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putStringSet("plugins", new java.util.HashSet<String>(names))
                    .apply();
        } catch (Throwable t) {
            log("保存插件选择失败: " + t);
        }
    }

    /** 读取当前选择（未设置过时为默认值）。 */
    private java.util.Set<String> savedPluginSelection() {
        try {
            java.util.Set<String> s = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("plugins", null);
            if (s != null) return new java.util.HashSet<String>(s);
        } catch (Throwable ignored) { }
        java.util.Set<String> def = new java.util.HashSet<String>();
        def.add("@deepseek-ai/dsh-schedule");
        return def;
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

    /**
     * 版本比较统一走 {@link Version}（纯逻辑、有测试覆盖）。
     *
     * <p>这里原本自己实现了一份「严格三段式」解析
     * （{@code \d+\.\d+\.\d+}），而网络诊断里另有一份 ——
     * 两份实现必然会在某次修改后产生分歧，后果是
     * 「更新提示说没有新版本」与「诊断说源有问题」互相矛盾，极难排查。
     *
     * <p>统一后的规则只有一处：按段比数字、缺位补 0、容忍非数字后缀。
     */
    private static boolean isNewer(String remote, String local) {
        return Version.isNewer(remote, local);
    }

    /**
     * 读取版本清单，返回 [version, tag, apkName]；全部源都失败则返回 null。
     *
     * <p>两个稳健性设计：
     * <ul>
     *   <li>每个请求带时间戳参数，尽量绕过中间缓存</li>
     *   <li><b>取所有源中版本最高的</b> —— 缓存只会让某个源返回偏旧的版本，
     *       因此「取最大」是安全的，可避免镜像滞后导致误判「已是最新」</li>
     * </ul>
     */
    private String[] latestRelease() {
        String[] best = null;
        String bestVer = null;      // 用原始版本串比较，不再转成 int[3]
        int ok = 0;
        for (String base : VERSION_SOURCES) {
            try {
                String url = base + (base.indexOf('?') >= 0 ? "&" : "?")
                        + "t=" + System.currentTimeMillis();
                org.json.JSONObject o = new org.json.JSONObject(httpGet(url));
                String ver = o.optString("version", "");
                String tag = o.optString("tag", "");
                String apk = o.optString("apk", "DSHNative-bootstrap.apk");
                if (ver.length() == 0 || tag.length() == 0) continue;
                ok++;
                // 统一走 Version：按段比数字、缺位补 0、容忍后缀
                if (bestVer == null || Version.isNewer(ver, bestVer)) {
                    bestVer = ver;
                    best = new String[]{ ver, tag, apk };
                }
            } catch (Throwable t) {
                // 换下一个源
            }
        }
        if (best == null) {
            log("版本清单获取失败（" + VERSION_SOURCES.length + " 个源均不可用）");
        } else if (ok > 1) {
            log("版本清单: 从 " + ok + " 个源取得，采用最高版本 " + best[0]);
        }
        return best;
    }

    /** 版本号比较：a>b 返回正，a<b 返回负。 */
    /** 检查 App 更新；interactive=true 时把结果显示在给定文本上/弹提示。 */
    private void checkAppUpdate(final boolean interactive, final android.widget.TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                setStatus(status, "正在检查更新…");
                log("开始检查 App 更新（当前 " + appVersion() + "）…");
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

                    // 先看本地有没有已下载但尚未安装的安装包。
                    // 用户下载后取消安装、再点检查更新时，不该重复下载 34MB。
                    // 仅当本地包已经是「远端最新版」（或更新）时才直接安装；
                    // 若本地包比远端旧，说明期间又发了新版，应当重新下载，
                    // 否则会装上一个过时的版本。
                    String cachedVer = apkVersionOf(apk);
                    if (cachedVer != null && !isNewer(rel[0], cachedVer)) {
                        log("本地已有最新安装包 " + cachedVer
                                + "（此前下载后未安装），直接调起安装，跳过下载");
                        setStatus(status, "使用已下载的 " + cachedVer + " 安装包");
                        if (interactive) toast("使用已下载的 " + cachedVer + " 安装包");
                        installApk(apk);
                        return;
                    }
                    if (apk.exists()) {
                        log("本地安装包 " + cachedVer + " 旧于远端 " + rel[0]
                                + "，重新下载");
                        apk.delete();
                    }

                    downloadPath("https://github.com/" + REPO
                                    + "/releases/download/" + tag + "/",
                            apkName, apk);
                    log("更新包已下载: " + (apk.length() / 1048576) + " MB");
                    setStatus(status, "下载完成，请在弹出的安装界面确认覆盖安装");
                    installApk(apk);
                } catch (Throwable t) {
                    log("错误: 检查更新失败: " + t);
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
            log("错误: 调起安装器失败: " + t);
            toast("无法调起安装器: " + shorten(t));
        }
    }

    /**
     * 读取一个 APK 文件自身的 versionName（不安装即可读）。
     * 用于判断本地缓存的安装包是否还有效。
     */
    private String apkVersionOf(File apk) {
        try {
            if (apk == null || !apk.exists() || apk.length() < 100000) return null;
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (pi == null) return null;
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 清理本地更新包。
     *
     * <p>三种情况删除：已安装（版本不再更新）、损坏无法解析、空文件。
     * 保留「比当前新但还没装」的那一份 —— 用户取消安装后可以再次直接安装。
     */
    private void cleanupStaleUpdateApk() {
        try {
            File apk = UpdateProvider.apkFile(this);
            if (!apk.exists()) return;
            String v = apkVersionOf(apk);
            if (v != null && isNewer(v, appVersion())) {
                log("本地缓存有未安装的更新包: " + v + "（" + (apk.length() / 1048576) + " MB）");
                return;
            }
            apk.delete();
            log("已清理过期更新包（" + (v == null ? "无法解析" : v)
                    + "，当前 " + appVersion() + "）");
        } catch (Throwable ignored) { }
    }

    /** 启动后的静默检查：只记日志；有新版本时提示一次，不打断使用。 */
    private void autoCheckUpdate() {
        try {
            log("自动检查更新（当前 " + appVersion() + "）…");
            final String[] rel = latestRelease();
            if (rel == null) {
                log("自动检查更新：无法获取版本清单（不影响使用）");
                return;
            }
            if (!isNewer(rel[0], appVersion())) {
                log("自动检查更新：已是最新版本 " + appVersion());
                return;
            }
            log("自动检查更新：发现新版本 " + rel[0] + "（当前 " + appVersion() + "）");
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    toast("有新版本 " + rel[0] + "，可在通知栏「设置」中更新");
                }
            });
        } catch (Throwable t) {
            log("自动检查更新失败（不影响使用）: " + shorten(t));
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
                    log("错误: 更新运行包失败: " + t);
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
        int remoteRev = man.optInt("revision", 0);
        int appliedRev = appliedPayloadRevision();
        log("运行包清单: " + parts.length() + " 个分片（结构版本 "
                  + man.optInt("version", 0) + "，修订 " + remoteRev
                  + "，本机已应用 " + appliedRev + "）");

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
        // 决策交给纯逻辑类（有 16 项测试）。
        //
        // 关键：**哨兵全部匹配也可能需要更新** —— 若修订号变了，
        // 说明内容有实质变化（包括「只删文件」这种哨兵发现不了的改动），
        // 必须清空重来，否则被删掉的文件会永远留在设备上。
        int action = PayloadUpdate.decide(remoteRev, appliedRev, missing.size());
        log(PayloadUpdate.describe(action, parts.length(), missing.size()));
        if (action == PayloadUpdate.ACTION_UPTODATE) {
            // 已是最新：记录修订号，否则它会一直是 0，下次又判定为「需要整体重来」
            rememberPayloadRevision(remoteRev);
            return true;
        }

        if (PayloadUpdate.needsWipe(action)) {
            // 先清空：单靠覆盖解压无法移除文件，而这正是引入修订号要解决的问题。
            // 只删运行包目录 —— 用户配置在 <root>/.dsh，不受影响。
            setSplashStatus("正在更新运行包…");
            log("清空运行包目录后重新解压（配置目录不受影响）");
            deleteTree(dshDir);
            deleteTree(toolsDir);
            missing.clear();
            for (int i = 0; i < parts.length(); i++) {
                missing.add(parts.getJSONObject(i).getString("name"));
            }
        }
        setSplashStatus(PayloadUpdate.describe(action, parts.length(), missing.size()));

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
            log("  " + name + " 校验通过");

            setSplashStatus("正在解压运行包…");
            log("解压 " + name + " …");
            run(node, root, new String[]{
                    new File(root, "unpack.js").getAbsolutePath(),
                    archive.getAbsolutePath(),
                    dir.getAbsolutePath()}, null);
            archive.delete();
        }
        log("增量更新完成，本次下载 " + (bytes / 1048576) + " MB");
        // 修订号只在**全部成功后**才记录：中途失败（校验不过、解压出错）
        // 若已记下，下次启动会误判为已应用，被删的文件就永远补不回来了
        rememberPayloadRevision(remoteRev);
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

    /** 工作区说明的标题行（用于判断文件是否由本应用生成）。 */
    private static final String WORKSPACE_README_HEAD =
            "这是 DeepSeek Harness 的工作目录。";

    /**
     * 在工作区放一份说明：既告诉用户该把文件放哪，也告诉 agent 手上有哪些工具。
     *
     * <p>内容变化时会覆盖更新 —— 但仅当文件仍是本应用生成的那份
     * （以标题行为标志）；用户自己改写过的文件不动。
     */
    private void seedWorkspaceReadme(File dir) {
        File readme = new File(dir, "把文件放到这里.txt");
        String body =
                  WORKSPACE_README_HEAD + "\n"
                + "=====================================\n\n"
                + "• 把项目、文档放到这个文件夹，agent 就能直接读写它们\n"
                + "• agent 生成的产物也会出现在这里\n"
                + "• 该目录位于手机共享存储，任何文件管理器都能访问\n\n"
                + "命令行工具\n"
                + "-----------\n"
                + "node / npm / npx      Node.js\n"
                + "python3 / pip3        Python（可自行 pip install 装包）\n"
                + "git                   版本控制\n"
                + "rg / fd / jq / curl   搜索与网络\n\n"
                + "已预装的 Python 库\n"
                + "------------------\n"
                + "pypdf        读取 PDF\n"
                + "openpyxl     读写 Excel（.xlsx）\n"
                + "chardet      文本编码探测\n"
                + "Pillow       图片处理（PNG / JPEG / WebP / TIFF）\n\n"
                + "提示\n"
                + "----\n"
                + "• .docx / .xlsx / .pptx 本质是 zip + XML，用 Python 标准库\n"
                + "  （zipfile + xml.etree）即可直接读取，不一定要装库\n"
                + "• 中文文本乱码时，先用 chardet 探测编码\n"
                + "• 图片直接在对话里发即可\n\n"
                + "路径：" + dir.getAbsolutePath() + "\n";
        try {
            if (readme.exists()) {
                String cur = readText(readme);
                if (!cur.startsWith(WORKSPACE_README_HEAD)) return;   // 用户改过，不动
                if (cur.equals(body)) return;                          // 已是最新
            }
            writeText(readme, body);
            log("工作区说明已更新");
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
            log("警告: 前台服务启动失败（不影响运行）: " + t);
        }
    }

    private void showSettings() {
        log("打开设置页");
        try {
            final File dshHome = new File(appRoot, ".dsh");
            final File creds = new File(dshHome, ".credentials.yaml");
            final File settings = new File(dshHome, "settings.yaml");

            String cc = readRef(creds, "COMMANDCODE_API_KEY");
            String ds = readRef(creds, "DEEPSEEK_API_KEY");
            String model = readScalar(settings, "model");

            android.widget.LinearLayout body = DshUi.paddedBody(this);
            body.addView(DshUi.title(this, "设置"));
            body.addView(DshUi.hint(this, "密钥仅保存在 App 私有目录，不会外传。"),
                    DshUi.fullWidth(this, 4));

            final android.widget.EditText ccField =
                    addField(body, "Command Code API Key", cc, true);
            final android.widget.EditText dsField =
                    addField(body, "DeepSeek API Key", ds, true);
            final android.widget.EditText modelField =
                    addField(body, "默认模型", model, false);

            // ── 更新区 ──
            body.addView(DshUi.sectionLabel(this, "更新"), DshUi.fullWidth(this, 22));
            final android.widget.TextView upStatus =
                    DshUi.status(this, "当前 App 版本 " + appVersion());
            body.addView(upStatus, DshUi.fullWidth(this, 6));

            android.widget.Button btnPayload =
                    DshUi.button(this, "更新运行包（DSH / 工具链）", false);
            btnPayload.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    log("用户点击: 更新运行包");
                    updatePayloadNow(upStatus);
                }
            });
            body.addView(btnPayload, DshUi.fullWidth(this, 12));

            android.widget.Button btnApp =
                    DshUi.button(this, "检查 App 更新并安装", false);
            btnApp.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    log("用户点击: 检查 App 更新");
                    checkAppUpdate(true, upStatus);
                }
            });
            body.addView(btnApp, DshUi.fullWidth(this, 8));

            android.widget.Button btnLog = DshUi.button(this, "查看运行日志", false);
            btnLog.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { showLog(); }
            });
            body.addView(btnLog, DshUi.fullWidth(this, 8));

            // ── 插件 ──
            body.addView(DshUi.sectionLabel(this, "插件"), DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "启用内置插件，或从 npm 安装社区插件（重启后生效）"),
                    DshUi.fullWidth(this, 6));
            android.widget.Button btnPlugins = DshUi.button(this, "管理插件", false);
            {
                final File pluginDshDir = dshDirRef;
                btnPlugins.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        if (pluginDshDir == null) { toast("DSH 目录尚未就绪"); return; }
                        PluginPanel.show(MainActivity.this, new PluginPanel.Host() {
                            @Override public File dshDir() { return pluginDshDir; }
                            @Override public File root() { return appRoot; }
                            @Override public File toolsDir() { return toolsDirRef; }
                            @Override public File node() { return nodeRef; }
                            @Override public java.util.Set<String> selection() {
                                return savedPluginSelection();
                            }
                            @Override public void saveSelection(java.util.Set<String> names) {
                                setEnabledPlugins(names);
                            }
                        });
                    }
                });
            }
            body.addView(btnPlugins, DshUi.fullWidth(this, 8));

            // ── 配置备份 ──
            body.addView(DshUi.sectionLabel(this, "配置备份"), DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "把账户密钥与模型配置导出到共享存储；重装或换机后可恢复"),
                    DshUi.fullWidth(this, 6));
            android.widget.Button btnBackup = DshUi.button(this, "备份与恢复", false);
            final File backupHome = dshHome;
            btnBackup.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    ConfigBackupPanel.show(MainActivity.this, appRoot, backupHome);
                }
            });
            body.addView(btnBackup, DshUi.fullWidth(this, 8));

            // ── 订阅 ──
            body.addView(DshUi.sectionLabel(this, "订阅"), DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "查看 Command Code 账户余额、滚动窗口与本期用量"),
                    DshUi.fullWidth(this, 6));
            android.widget.Button btnCc = DshUi.button(this, "Command Code 用量", false);
            final String ccKey = readRef(creds, "COMMANDCODE_API_KEY");
            btnCc.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    CommandCodePanel.show(MainActivity.this, ccKey);
                }
            });
            body.addView(btnCc, DshUi.fullWidth(this, 8));

            // ── 网络 ──
            body.addView(DshUi.sectionLabel(this, "网络"), DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "检测更新功能依赖的各个源是否可用（直连与镜像分开报告）"),
                    DshUi.fullWidth(this, 6));
            android.widget.Button btnNet = DshUi.button(this, "网络诊断", false);
            btnNet.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    NetworkDiag.show(MainActivity.this, apkDownloadUrl());
                }
            });
            body.addView(btnNet, DshUi.fullWidth(this, 8));

            // ── 文件 ──
            body.addView(DshUi.sectionLabel(this, "文件"), DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "浏览应用私有目录、工作区与共享存储；文本文件可直接编辑"),
                    DshUi.fullWidth(this, 6));
            android.widget.Button btnFiles = DshUi.button(this, "浏览文件", false);
            btnFiles.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    FileBrowser.show(MainActivity.this, appRoot);
                }
            });
            body.addView(btnFiles, DshUi.fullWidth(this, 8));

            // ── 显示缩放 ──
            body.addView(DshUi.sectionLabel(this, "显示缩放"),
                    DshUi.fullWidth(this, 22));
            body.addView(DshUi.hint(this, "界面布局已固定为桌面宽度，文字偏小可在此放大（立即生效）"),
                    DshUi.fullWidth(this, 6));
            android.widget.LinearLayout zoomRow = new android.widget.LinearLayout(this);
            zoomRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            fillZoomRow(zoomRow);
            body.addView(zoomRow, DshUi.fullWidth(this, 8));

            // ── 维护状态：补丁是否仍然生效（DSH 更新后可能失效）──
            body.addView(DshUi.sectionLabel(this, "维护状态"), DshUi.fullWidth(this, 22));
            // 状态用颜色表达（不再用符号前缀）：
            // \u0000 = 正常，\u0001 = 需注意
            android.text.SpannableStringBuilder pr = new android.text.SpannableStringBuilder();
            if (patchReport.isEmpty()) {
                pr.append("（暂无补丁记录）");
            } else {
                for (String line : patchReport.values()) {
                    boolean warn = line.startsWith("\u0001");
                    String text = line.length() > 0 ? line.substring(1) : line;
                    int start = pr.length();
                    pr.append(text).append('\n');
                    pr.setSpan(new android.text.style.ForegroundColorSpan(
                                    warn ? 0xFFB26A00 : DshUi.TEXT_2),
                            start, pr.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            int tail = pr.length();
            pr.append("App ").append(appVersion())
              .append("　运行包 ").append(payloadSummary());
            pr.setSpan(new android.text.style.ForegroundColorSpan(DshUi.TEXT_3),
                    tail, pr.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // hint() 只接受 String，这里需要富文本（逐行着色）
            android.widget.TextView prView = DshUi.hint(this, "");
            prView.setText(pr);
            body.addView(prView, DshUi.fullWidth(this, 6));

            android.widget.Button cancel = DshUi.button(this, "取消", false);
            android.widget.Button save = DshUi.button(this, "保存并重启", true);
            final android.app.Dialog dlg = DshUi.dialog(this,
                    DshUi.scroll(this, body), DshUi.footer(this, cancel, save), 660);

            cancel.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { dlg.dismiss(); }
            });
            save.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    try {
                        writeRefs(creds, ccField.getText().toString().trim(),
                                dsField.getText().toString().trim());
                        String m = modelField.getText().toString().trim();
                        if (m.length() > 0) setScalar(settings, "model", m);
                        dlg.dismiss();
                        toast("已保存，正在重启服务…");
                        restartAgent();
                    } catch (Throwable t) {
                        toast("保存失败: " + t.getMessage());
                    }
                }
            });
            dlg.show();
        } catch (Throwable t) {
            log("错误: 打开设置页失败: " + t);
            toast("打开设置失败: " + shorten(t));
        }
    }

    /** 设置页里的「标签 + 输入框」组合。 */
    private android.widget.EditText addField(android.widget.LinearLayout body,
                                             String label, String value,
                                             boolean secret) {
        body.addView(DshUi.label(this, label), DshUi.fullWidth(this, 14));
        android.widget.EditText et = DshUi.input(this, value, secret);
        body.addView(et, DshUi.fullWidth(this, 6));
        return et;
    }

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
                catch (Throwable t) { log("错误: 重启失败: " + t); }
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
                private int lastLeft = -1;
                private int lastRight = -1;
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
                            // 左右内边距来自系统窗口 inset 与刘海安全区。
                            // 横屏时刘海在侧面：不预留会挡内容，预留了又怕留黑边 ——
                            // 两者必须同时处理，所以这里显式取最大值。
                            int left = insets.getSystemWindowInsetLeft();
                            int right = insets.getSystemWindowInsetRight();
                            if (android.os.Build.VERSION.SDK_INT >= 28) {
                                android.view.DisplayCutout cut = insets.getDisplayCutout();
                                if (cut != null) {
                                    left = Math.max(left, cut.getSafeInsetLeft());
                                    right = Math.max(right, cut.getSafeInsetRight());
                                }
                            }
                            if (left != lastLeft || right != lastRight) {
                                lastLeft = left;
                                lastRight = right;
                                log("左右内边距 " + left + " / " + right + "px（刘海/导航栏）");
                            }
                            v.setPadding(left, statusBarHeight(), right, pad);
                        }
                    } catch (Throwable t) {
                        log("inset 处理失败: " + t);
                    }
                    return insets;
                }
            });
            rootView.requestApplyInsets();
        } catch (Throwable t) {
            log("警告: 无法注册 inset 监听: " + t);
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
            log("警告: 无法注册布局监听: " + t);
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
            log("错误: 自检失败: node 文件不存在 → " + node.getAbsolutePath());
            return false;
        }
        // 记录权限位，便于判断 chmod 是否真的生效
        log("  node 权限: " + (node.canExecute() ? "可执行" : "警告: 无执行位")
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
            log("[错误] 自检失败：无法执行自带的 Node");
            log("    原因: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            log("");
            log("  这通常意味着 SELinux 拦截了对私有目录的 execve。");
            log("  本 App 已设 targetSdk=28 以规避该限制，若仍被拦截，");
            log("  说明此 ROM 的策略更严格，需要改用 nativeLibraryDir 方案。");
            log("");
            log("  请把以上内容完整反馈，这是判断架构是否成立的关键依据。");
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
                log("错误: 自检失败: node --version 退出码 " + code + "，输出: " + sb);
                return false;
            }
            log("  自检通过，node 版本: " + sb);

            return true;
        } catch (Exception e) {
            log("错误: 自检异常: " + e);
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
            log("警告: 检测到上次运行崩溃，堆栈如下（同时保存在 " + crashFile + "）：");
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
     * dlopen。但它并非无法解决 —— DSH 只用到很窄的一组 sharp API
     * （metadata / rotate / resize / jpeg / webp / toBuffer），
     * 因此这里替换为一个**可用实现**：JS 侧提供同样的 API，
     * 实际图像处理交给运行包自带的 Python + Pillow（sharp-android.js + pillow_shim.py）。
     *
     * <p>这样图片附件在手机上也能正常工作，且无需编译任何原生模块。
     */
    private void applyAndroidPatches(File root, File dshDir) {
        patchFrontendViewport(dshDir);
        patchAttachmentDurability(dshDir);
        try {
            File shim = new File(root, "sharp-android.js");
            File helper = new File(root, "pillow_shim.py");
            File sharpDir = new File(dshDir, "node_modules/sharp");
            if (!shim.exists() || !helper.exists() || !sharpDir.isDirectory()) {
                log("  [警告] 图片处理组件缺失，跳过（文字功能不受影响）");
                return;
            }
            // 两者必须放在同一目录：JS 侧用 __dirname 定位 pillow_shim.py
            copyFile(shim, new File(sharpDir, "index.js"));
            copyFile(helper, new File(sharpDir, "pillow_shim.py"));
            writeText(new File(sharpDir, "package.json"),
                    "{\"name\":\"sharp\",\"version\":\"0.0.0-android-pillow\","
                    + "\"main\":\"index.js\","
                    + "\"description\":\"Android implementation backed by Python/Pillow\"}");
            log("  已启用图片附件（sharp 由 Python/Pillow 实现）");
        } catch (Throwable t) {
            log("  [警告] Android 补丁应用失败: " + t);
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
    /**
     * 按当前屏幕宽度计算 viewport 宽度。
     *
     * <p>关键点：宽度必须**随屏幕宽度成比例**变化，否则渲染缩放会随方向变化。
     * 固定 600px 时，竖屏缩放 400/600=0.67，横屏却是 869/600=1.45 ——
     * 横屏内容被放大一倍多，几乎没法用。
     *
     * <p>取 1.5 倍：竖屏 400dp → 600px（与原行为一致），
     * 横屏 869dp → 1303px，两者缩放都是 0.67，文字物理大小一致，
     * 而横屏多出来的宽度全部交给 DSH 的桌面布局使用。
     */
    private int viewportWidthFor(android.content.res.Configuration cfg) {
        int cssW = cfg != null ? cfg.screenWidthDp : 400;
        int w = Math.round(cssW * 1.5f);
        return Math.max(600, Math.min(1600, w));
    }

    private void patchFrontendViewport(File dshDir) {
        dshDirRef = dshDir;
        File html = new File(dshDir,
                "node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html");
        if (!html.exists()) {
            log("  [警告] 未找到前端 index.html，跳过 viewport 适配");
            return;
        }
        try {
            int want = viewportWidthFor(getResources().getConfiguration());
            String src = readText(html);
            // 先把任何旧的 width=NNN 还原成原始写法，再统一替换 ——
            // 这样方向切换时可以反复更新（早先靠「是否已是 600」判断，
            // 换一个宽度就再也改不动了）。
            String base = src.replaceAll("content=\"width=[0-9]+\"", VP_ORIG);
            if (base.indexOf(VP_ORIG) < 0) {
                log("  [警告] viewport 标签格式不符，未做适配");
                recordPatch("前端 viewport", false, "标签格式不符，未适配");
                return;
            }
            if (want == appliedViewportWidth && src.equals(base.replace(VP_ORIG,
                    "content=\"width=" + want + "\""))) {
                log("  viewport 已适配（" + want + "px），无需改动");
                recordPatch("前端 viewport", true, want + "px");
                return;
            }
            writeText(html, base.replace(VP_ORIG, "content=\"width=" + want + "\""));
            appliedViewportWidth = want;
            log("  已适配屏幕宽度：" + want + "px（竖屏 600 / 横屏按比例放大，保持缩放一致）");
            recordPatch("前端 viewport", true, want + "px");
        } catch (Throwable t) {
            log("  [警告] viewport 适配失败: " + t);
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
        if (!script.exists()) { log("  [警告] 缺少 preflight.js，跳过"); return true; }

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
                    boolean info = "INFO".equals(tag);
                    String detail = (f.length > 3 && f[3].length() > 0) ? " → " + f[3] : "";
                    // INFO 用于「Android 上本就不需要」的项，避免用户误以为有问题
                    log("  " + (pass ? "[通过]" : info ? "[信息]" : warn ? "[警告]" : "[错误]")
                            + " " + (f.length > 2 ? f[2] : "?") + detail);
                } else if (line.startsWith("PREFLIGHT_END|")) {
                    try { failed = Integer.parseInt(line.substring(14).trim()); }
                    catch (NumberFormatException ignored) { }
                }
            }
            p.waitFor();
            if (failed == 0) { log("  自检全部通过"); return true; }
            log("  [警告] 有 " + (failed < 0 ? "若干" : String.valueOf(failed))
                    + " 项未通过，仍继续启动以便收集信息");
            return false;
        } catch (Exception e) {
            log("  [警告] 自检执行失败: " + e);
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
        // 小文件（如 manifest.json）不打进度，否则会出现 "100% (0/0 MB)" 这种误导性输出
        final boolean quiet = assetName.endsWith(".json");
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
                        if (quiet) { /* 静默 */ } else
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
        // 关键：此前是「文件已存在就跳过」，导致 APK 升级后
        // preflight.js / unpack.js / sharp-android.js 等内置脚本**永远不会更新** ——
        // 用户升级了 APK，跑的却还是旧脚本（preflight 的告警文案一直没变就是这个原因）。
        // 改为按 APK 版本号判断：版本变了就整体重新解压，版本没变则保持快速跳过。
        File verFile = new File(target, ".assets-version");
        final String curVer = appVersion();
        String haveVer = verFile.exists() ? readText(verFile).trim() : "";
        final boolean refresh = !curVer.equals(haveVer);
        if (refresh) {
            log("APK 内置脚本需更新（" + (haveVer.length() == 0 ? "首次" : haveVer)
                    + " → " + curVer + "），重新解压");
        }

        List<String> entries = new ArrayList<String>();
        collect("payload", entries);
        for (String path : entries) {
            String rel = path.substring("payload/".length());
            if (rel.length() == 0) continue;
            File out = new File(target, rel);
            if (!refresh && out.exists() && out.length() > 0) continue;   // 版本未变，跳过
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
        try { writeText(verFile, curVer); } catch (Throwable ignored) { }
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
            // 追加而非覆盖：否则每次启动都会抹掉上一个会话的记录，
            // 跨会话的问题（例如"应用内更新到底下载成功没有"）就无从追查。
            // 超过上限时轮转一次，保留上一份，避免无限增长。
            final long MAX_BYTES = 512 * 1024;
            if (sharedLog.exists() && sharedLog.length() > MAX_BYTES) {
                File prev = new File(sharedLog.getAbsolutePath() + ".1");
                if (prev.exists()) prev.delete();
                sharedLog.renameTo(prev);
            }
            File dir = sharedLog.getParentFile();
            java.io.FileWriter w = new java.io.FileWriter(sharedLog, true);
            w.write("\n\n=== DSH Native 启动日志 ===\n");
            w.write("时间: " + new java.util.Date() + "\n");
            w.write("设备: " + android.os.Build.MODEL + " / Android "
                    + android.os.Build.VERSION.RELEASE + " (SDK "
                    + android.os.Build.VERSION.SDK_INT + ")\n");
            w.write("APK 版本: 0.20.6\n");
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
    /**
     * 解析启动意图：通知栏「设置」标记，或桌面快捷方式的 action。
     *
     * <p>快捷方式只带 action 启动本 Activity（见 res/xml/shortcuts.xml），
     * 由这里分流成具体的界面动作。
     */
    private void resolveLaunchIntent(android.content.Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            pendingOpenSettings = true;
        }
        String a = intent.getAction();
        if (a == null) return;
        if (a.endsWith("ACTION_SETTINGS")) {
            pendingOpenSettings = true;
            log("快捷方式: 打开设置");
        } else if (a.endsWith("ACTION_LOG")) {
            pendingAction = "log";
            log("快捷方式: 查看日志");
        } else if (a.endsWith("ACTION_UPDATE")) {
            pendingAction = "update";
            log("快捷方式: 检查更新");
        }
    }

    /**
     * 屏幕方向变化：重新计算 viewport 宽度并重载页面。
     *
     * <p>为什么必须重载：viewport meta 只在页面加载时解析一次，
     * 改了内容不会重新布局。好在只在宽度确实变化时才重载
     * （轻微变化不触发），且 DSH 的会话在服务端，重载不丢内容。
     *
     * <p>清单已声明 configChanges 含 orientation/screenSize，
     * 因此本方法会被调用，而 Activity 不会被重建。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        try {
            int want = viewportWidthFor(cfg);
            File dir = dshDirRef;
            log("屏幕方向变化：宽 " + cfg.screenWidthDp + "dp → viewport "
                    + want + "px（当前 " + appliedViewportWidth + "）");
            if (dir == null || want == appliedViewportWidth) return;
            patchFrontendViewport(dir);
            if (webView != null) {
                log("  重新加载页面以应用新的 viewport");
                webView.reload();
            }
        } catch (Throwable t) {
            log("  [警告] 方向切换处理失败: " + t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
    }

    @Override
    protected void onPause() {
        inForeground = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
        resolveLaunchIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            // 已在运行：直接打开设置
            showSettings();
        }
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
            log("错误: 处理分享内容失败: " + t);
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
