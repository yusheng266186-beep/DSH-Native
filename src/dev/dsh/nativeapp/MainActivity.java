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
            "https://github.com/yusheng266186-beep/DSH-Native/releases/download/payload-v8/";
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

        // 主题必须**最先**解析：DshUi 的颜色是在创建视图时一次性取走的，
        // 晚一步就会有一批组件停在旧主题上（深浅混杂比全浅色更难看）。
        //
        // **跟谁走**：DSH 的主题是它自己的设置（ui-theme.preference =
        // light/dark/system），与 Android 系统深色**相互独立**。
        // 实测出现过"DSH 设深色、系统仍是浅色" —— 网页黑、原生白卡片。
        // 所以：观察过网页主题就一律以它为准（缓存在 prefs），
        // 还没观察过才退回系统深色作初值。
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.contains("webDark")) {
                DshUi.setDark(prefs.getBoolean("webDark", false));
            } else {
                DshUi.applyTheme(this);
            }
        } catch (Throwable t) {
            DshUi.applyTheme(this);
        }

        // 双保险：即使主题未被 ROM 正确解析，也确保没有标题栏、
        // 且窗口底色与页面底色一致（否则默认主题会露出黑色，形成顶部黑边）。
        try {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(DshUi.BG()));
        } catch (Throwable t) {
            log("窗口设置失败（不影响运行）: " + t);
        }

        // 状态栏透明 + 内容延伸上去 + 与主题相配的图标明暗。
        applySystemBars();

        rootView = new android.widget.FrameLayout(this);
        rootView.setBackgroundColor(DshUi.BG());
        // 内容延伸到状态栏之后，用等高内边距把内容推下来 ——
        // 状态栏区域露出的是页面底色，与下方的 DSH 界面连成一片。
        rootView.setPadding(0, statusBarHeight(), 0, 0);
        installImeInsetHandler();
        android.widget.FrameLayout root = rootView;

        // 日志面板不加入视图树：整个屏幕留给 DSH 界面。
        // 日志仍会写入 logcat 与 /sdcard/DSHNative/launch.log，便于后台排查。
        logView = new TextView(this);
        logView.setTextSize(10);

        webView = new WebView(this);
        // 页面首帧之前的底色。WebView 默认是白的 —— 深色下从开屏页
        // 到页面渲染之间会闪一下全白，比"什么都不做"更刺眼。
        webView.setBackgroundColor(DshUi.BG());
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
            /**
             * 顶部加载进度条。
             *
             * <p>WebView 没有原生 chrome，页面导航（旋转后重载、断连自动刷新、
             * DSH 内部路由切换）本来**全程没有任何反馈** —— 用户无法区分
             * "正在加载"和"已经卡死"。页面内的导航进度对"感知速度"的提升
             * 通常是最大的一笔。
             */
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                final android.widget.ProgressBar pb = topProgress;
                if (pb == null) return;
                pb.setAlpha(1f);
                pb.setProgress(newProgress);
                if (newProgress >= 100) {
                    pb.animate().alpha(0f).setDuration(260).start();
                }
            }

            /**
             * 网页里的 alert / confirm / prompt 必须自己弹出来。
             *
             * <p>不实现会怎样（用户实际遇到）：DSH 点「归档会话」时会走
             * confirm() 求二次确认，而 WebView 在没有 onJsConfirm 时
             * **直接返回 false，并且什么都不显示** ——
             * 表现为「提示要确认，但确认框从来不出现」，操作就此卡住。
             *
             * <p>所以这三个回调一个都不能省：alert 只提示，confirm 返回真假，
             * prompt 还要把输入回传。统一走 DshUi，保持界面风格一致。
             */
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final android.webkit.JsResult result) {
                showJsDialog("提示", message, false, result);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final android.webkit.JsResult result) {
                showJsDialog("确认", message, true, result);
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue,
                                      final android.webkit.JsPromptResult result) {
                showJsPrompt(message, defaultValue, result);
                return true;
            }


            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                // DSH 把真实错误藏在 API 响应里、界面只显示概括信息。
                // 捕获浏览器控制台，配合下面注入的 fetch 包装即可拿到完整错误。
                String m = cm == null ? "" : cm.message();
                if (m != null && m.length() > 0) {
                    // 手势入口：长按顶部区域打开设置（见注入脚本里的说明）
                    if (m.indexOf("[dsh-native] open-settings") >= 0) {
                        log("手势：长按顶部 → 打开设置");
                        showSettings();
                        return true;
                    }
                    // 网页主题上报：原生跟着 DSH 自己的主题走，
                    // 而不是跟 Android 系统深色（两者相互独立）
                    if (m.indexOf("[dsh-theme] dark=") >= 0) {
                        onWebTheme(m.indexOf("dark=1") >= 0);
                        return true;
                    }
                    // 任务事件单独分流：不写进日志（每 4 秒一次的轮询若都记，
                    // 日志会被刷爆），只用于通知判定
                    String[] ev = TaskNotifier.parseConsole(m);
                    if (ev != null) {
                        onTaskEvent(ev[0], ev[1]);
                        return true;
                    }
                    // 会话状态：直接读 **DSH 自己**的 /api/session/list 响应。
                    //
                    // 为什么不再自己轮询：DSH 的这个接口是 RPC 式 POST，
                    // 注入脚本用 GET 调它只会拿到 404（实测 109 次 404 / 28 次 200，
                    // 后者全是 DSH 自己发的）。而 App 本来就把它记在日志里 ——
                    // 那就直接从这份流量里读，既权威又不多发一个请求。
                    // 会话列表：页面侧已经把**完整 JSON**（截断之前）解析成计数上报。
                    //
                    // 这是「正在跑却显示空闲」的根因修复：旧实现直接在这条已经
                    // slice(0,700) 的文本里数 "running":true，而运行中的会话只要
                    // 不是列表第一项，它的 "running":true 就落在 700 字符之外被截掉，
                    // 计数为 0 → 判定空闲。
                    int sess = SessionStatus.parseSessionConsole(m);
                    if (sess >= 0) {
                        onStatusSignal(sess, SRC_SESS);
                        return true;
                    }
                    // 页面 DOM 的按钮状态：第二个独立证据源（停止生成 / 发送消息 / 等待审批）
                    int dom = SessionStatus.parseDomConsole(m);
                    if (dom >= 0) {
                        onStatusSignal(dom, SRC_DOM);
                        return true;
                    }
                    // 兜底：旧版嗅探。只在能证明「在跑」时才采纳 ——
                    // 截断会让证据消失，但不会让证据凭空出现。
                    if (m.indexOf("/session/list") >= 0 && m.indexOf("\"running\"") >= 0) {
                        if (onSessionListResponse(m)) return true;
                    }
                    // 连接丢失：DSH 的 Remote RPC（含归档等操作）走 WebSocket，
                    // 断掉之后这些操作会**静默失效** —— 界面上点了没反应。
                    // 用户实际遇到的就是「点归档没任何反应」。
                    if (m.indexOf("connection lost") >= 0
                            || m.indexOf("connection restored") >= 0
                            || m.indexOf("reconnect") >= 0) {
                        onConnectionEvent(m);
                    }
                    // 状态看板上报：单独分流，不写进日志（每 2 秒一次会刷爆）
                    int st = SessionStatus.parseStatusConsole(m);
                    if (st >= 0) {
                        onStatusSignal(st, SRC_DOM);
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

        // 关掉多窗口：target=_blank 的链接会落到同一个 WebView 上，
        // 从而经过 shouldOverrideUrlLoading 的拦截，送去系统浏览器 ——
        // 既不带走当前 WebView，也不需要另开一个我们控制不到的窗口。
        //
        // 曾试过改成「允许新窗口 + 在 onCreateWindow 里接管」，理由是担心
        // 关掉多窗口影响 DSH 的浏览器面板。但那个改动是基于猜测的：
        // 它把面板发出的 URL 直接送去了系统浏览器，反而更糟。已撤回。
        webView.getSettings().setSupportMultipleWindows(false);
        webView.setWebViewClient(new WebViewClient() {
            /**
             * 尽早装上触摸适配。
             *
             * <p>必须在 DSH 渲染出菜单**之前**执行，否则拦不到它的事件。
             * onPageStarted 是能拿到的最早时机（文档还没解析，
             * 但 document 已存在，监听有效）。
             */
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                try {
                    view.evaluateJavascript(SessionStatus.touchMenuFixScript(), null);
                } catch (Throwable ignored) { }
            }


            /**
             * 拦截链接跳转：**外部链接交给系统浏览器，WebView 永远停在 DSH 页面上**。
             *
             * <p>不拦截会怎样（用户实际遇到）：点一个外链，WebView 整页跳走，
             * 于是 DSH 界面没了；此时返回手势走到的是「退出应用」的确认框，
             * 点了取消什么也不会发生 —— 看起来就像返回键坏了，
             * 只能杀掉 App 重开。
             *
             * <p>现在外链不在 WebView 里打开，DSH 页面始终在，
             * 返回键的语义也始终是「退出」。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                                                    android.webkit.WebResourceRequest req) {
                return req != null && handleUrl(req.getUrl() == null ? null : req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 注意：loadDataWithBaseURL() 显示状态页时**同样会触发本回调**。
                // 之前没做区分，导致开屏被提前撤掉（露出状态页），
                // 且开屏淡到 alpha=0 后仍占满全屏、吃掉所有触摸事件 —— 表现为"整个应用点不动"。
                // 因此只认真正的 DSH 服务地址。
                if (url == null || url.indexOf("127.0.0.1") < 0) {
                    return;
                }
                // 注入必须**每次页面加载都做**，不能只做一次。
                //
                // 之前这里是 `if (dshPageLoaded) return;`。而 reload（断连自动刷新、
                // 屏幕旋转、手动刷新）会把页面里的注入脚本全部清空，`dshPageLoaded`
                // 却仍是 true —— 于是刷新之后**状态看板、任务完成通知、接口诊断
                // 全部静默失效**，通知栏永远停在刷新前的那一刻。
                // 这正是「任务在跑、状态栏却显示空闲」的第二个原因。
                //
                // 两个脚本内部都有幂等守卫（__dshDiag / __dshStatusWatch），
                // 重复注入没有副作用。
                installFetchDiagnostics();
                installStatusWatcher();
                // 注入完成后立刻推一次「正在获取状态」。
                //
                // 前台服务的占位通知并不知道有没有任务在跑，不该让它一直挂着 ——
                // 实测过一次：App 更新后服务被重建，占位文案「正在运行」就那么
                // 一直显示着，而对话早已结束。
                // 第一条真实状态最多 5 秒后到达（脚本会定时重放会话列表请求）。
                pushStatus(SessionStatus.UNKNOWN);
                if (dshPageLoaded) {
                    log("DSH 界面已重新加载，状态采集脚本已重新注入");
                    return;
                }
                dshPageLoaded = true;
                log("DSH 界面已加载，收起开屏");
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

        // 顶部加载进度条（2dp、品牌蓝）：WebView 唯一的加载反馈，见
        // WebChromeClient.onProgressChanged 的说明。
        topProgress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        try {
            topProgress.getProgressDrawable().setColorFilter(
                    DshUi.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) { }
        topProgress.setMax(100);
        topProgress.setAlpha(0f);               // 空闲时完全不可见
        android.widget.FrameLayout.LayoutParams progressLp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.max(2, DshUi.dp(this, 2)));
        progressLp.gravity = android.view.Gravity.TOP;
        progressLp.topMargin = statusBarHeight();   // 状态栏透明，内容延伸上去
        root.addView(topProgress, progressLp);


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
        // 分享**不能在这里立刻处理**。
        //
        // 处理分享需要知道"工作区在哪"，而 workspace 要等启动流程跑到中间
        // 才由 resolveWorkspace() 确定（见 boot()）。原来在这一行直接处理，
        // 于是冷启动分享 100% 失败：每次都弹「工作区不可用」并把文件丢掉
        //（只有 App 已在后台、workspace 已有值时才能成功 —— 所以表现为"时好时坏"）。
        // 现在先记下来，等 boot() 拿到工作区之后再处理。
        pendingShareIntent = getIntent();
        resolveLaunchIntent(getIntent());

        cleanupStaleUpdateApk();
        installCrashHandler();
        startUiWatchdog();
        showPreviousCrash();

        bootInBackground("启动");
    }

    /** 启动互斥：防止并发 boot（例如启动还没走完，用户又点了「重试启动」）。 */
    private final java.util.concurrent.atomic.AtomicBoolean booting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 后台跑一次启动流程。
     *
     * <p>三件事统一在这里做，避免每个调用点各写一遍（写两遍必然漏一处）：
     * <ol>
     *   <li><b>互斥</b>：同一时刻只允许一个 boot —— 并发 boot 会对同一批
     *       运行包文件、端口、进程重复操作；</li>
     *   <li><b>插件自愈</b>：带插件启动失败就写标记，下次不带插件；
     *       最坏只是少一个插件，绝不会让 App 打不开；</li>
     *   <li><b>失败出口</b>：抛异常、自检不通过、服务超时，用户看到的完全一样：
     *       一句话原因 + 点开看日志 + 可重试。</li>
     * </ol>
     */
    private void bootInBackground(final String what) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (!booting.compareAndSet(false, true)) {
                    log("已有启动正在进行，忽略本次「" + what + "」");
                    return;
                }
                String reason = null;
                try {
                    reason = boot();
                } catch (Throwable t) {
                    log("错误: " + what + "失败: " + t);
                    Log.e(TAG, "boot failed", t);
                    reason = t.getClass().getSimpleName()
                            + (t.getMessage() != null ? ": " + t.getMessage() : "");
                    if (usedPluginPatch && appRoot != null) {
                        try {
                            writeText(new File(appRoot, ".plugins-disabled"),
                                    "上次带插件启动失败，已自动停用。\n"
                                  + "删除本文件可重新尝试启用插件。\n");
                            log("已自动停用插件覆盖层，下次启动将不带 --patch");
                        } catch (Throwable ignored) { }
                    }
                } finally {
                    booting.set(false);
                }
                if (reason != null) showBootFailure(reason);
            }
        }).start();
    }

    /**
     * 启动失败的统一出口。
     *
     * <p>为什么必须"统一"：失败原来有两条路径，而只有"抛异常"那条挂了 UI。
     * 另一条（例如 Node 自检返回 false）是静默 return —— 开屏既不收起、
     * 也不可点（{@code box.setClickable(false)}），用户看到的是**永久白屏**，
     * 连"点一下看日志"都没有。而这个自检恰恰是整个架构成立的前提。
     */
    private void showBootFailure(final String reason) {
        log("启动未完成: " + reason);
        try {
            setSplashStatus("启动未完成：" + reason + "\n（点按此处查看日志）");
            if (splashView != null) {
                splashView.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) { hideSplash(); }
                });
            }
            // 开屏下方铺一张说明页：即便开屏因为任何原因没收起，
            // 用户点一下也能看到完整原因与出路
            showStatus("启动未完成", reason
                    + "<br><br>可以这样处理：<br>"
                    + "1. 点下面的「重试」再启动一次<br>"
                    + "2. 回到通知栏 →「设置」→ 更新运行包<br>"
                    + "3. 打开「运行日志」看具体原因<br><br>"
                    + "<a href=\"dsh-retry://boot\">重试启动</a>");
            // 8 秒后自动收起开屏，露出说明页 —— 不该让用户去猜"要点一下"
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                @Override public void run() { hideSplash(); }
            }, 8000);
        } catch (Throwable t) {
            log("显示启动失败信息时出错: " + t);
        }
    }

    /**
     * 启动流程。
     *
     * @return null 表示启动成功；非 null 是一句话的失败原因。
     *         <p><b>为什么用返回值而不是靠异常</b>：失败有两条路径 —— 抛异常，
     *         和提前 return。原来只有前者有 UI，后者（例如 Node 自检没通过）
     *         就是**永久白屏**：开屏既不收起也不可点，App 内没有任何入口
     *         能看到原因。统一成返回值之后，两条路径走同一个出口。
     */
    private String boot() throws Exception {
        final File root = new File(getFilesDir(), "dsh");
        appRoot = root;
        log("私有目录: " + root);

        // 把 agent 的工作目录放到共享存储，这样用文件管理器丢进去的项目
        // agent 能直接读写，产出也能直接看到。
        // （dsh-fs-local / dsh-bash-local 用 process.cwd() 解析相对路径）
        workspace = resolveWorkspace();
        log("工作区: " + (workspace != null ? workspace : root + "（回退到私有目录）"));

        // 冷启动时的分享内容，等工作区确定之后才处理（原因见 onCreate 里的说明）
        final android.content.Intent shareIntent = pendingShareIntent;
        if (shareIntent != null) {
            pendingShareIntent = null;
            runOnUiThread(new Runnable() {
                @Override public void run() { handleShareIntent(shareIntent); }
            });
        }


        startHarnessService();

        // 1. 解压 APK 内置的引导负载（node + 脚本）
        extractAssets(root);
        File node = new File(root, "node");
        chmod(node, "755");

        // 前置自检：先确认能否执行自带的 Node。
        // 这一步是整个架构成立的前提（Android 10+ 对 targetSdk>=29 的 App
        // 禁止 exec 私有目录文件）。放在下载之前，可以快速失败并给出明确原因。
        if (!probeNodeExec(node)) {
            // 这是**非异常**路径，必须显式返回原因 ——
            // 它曾经静默 return：用户看到的是永久白屏（开屏一直转圈、不可点），
            // 而"报错给用户看"的代码全都写在抛异常那条分支里，根本不会执行。
            return "内置 Node 无法执行（这是整个架构成立的前提检查失败）";
        }
        setSplashStatus("正在准备运行环境…");
        log("Node 就绪: " + runCapture(node, new String[]{"--version"}));

        // 2. 下载并解压运行包（首次启动）
        File dshDir = new File(root, "dsh");
        File toolsDir = new File(root, "tools");

        // 增量更新：清单 + 哨兵校验，只下载缺失或变化的分片。
        // 不再用"整体版本号"判断 —— 那会导致改一个小工具也要重下整个包。
        // 启动速度：本地运行包完整时**不做阻塞的网络检查**。
        //
        // 原来每次启动都要拉一次清单 —— 网络正常时 0.6~1.8 秒，
        // 直连超时的情况下最多 35 秒（15s 连接 + 20s 读取）才开始走镜像。
        // 现在改成：本地完整就直接启动，更新检查放到后台；
        // 若后台发现有更新，记一个标记，**下次启动时**再真正应用。
        boolean locallyComplete = payloadLocallyComplete(dshDir, toolsDir);
        boolean updatePending = payloadUpdatePending();
        if (locallyComplete && !updatePending) {
            log("本地运行包完整，直接启动（更新检查移至后台）");
            checkPayloadInBackground(node, root, dshDir, toolsDir);
            setSplashStatus("正在准备运行环境…");
        } else {
            if (updatePending) log("上次检查到运行包有更新，本次启动应用");
            boolean upToDate = ensurePayload(node, root, dshDir, toolsDir);
            clearPayloadUpdatePending();
            if (upToDate) {
                log("运行包已是最新，本次无需下载");
                setSplashStatus("正在准备运行环境…");
            }
        }


        // 2.4 应用 Android 专项补丁（sharp 优雅降级等）
        applyAndroidPatches(root, dshDir);

        // 2.5 运行环境自检 —— 一次性验证所有已知 Android 兼容性风险点
        // 记录给插件管理用（安装时需要 node 与 npm 的路径）

        registerNetworkWatcher();


        toolsDirRef = toolsDir;

        nodeRef = node;

        // 自检要跑十几次 node 调用（crypto / 子进程 / git / python / rg / bash …），
        // 每次启动都做一遍是「关掉再打开很慢」的一大来源。
        // 环境没变就没必要重测：用「运行包修订 + 资源版本」当指纹，
        // 两者都没变时直接跳过。真出问题时日志里仍能看到它是被跳过的。
        String envKey = payloadRevisionKey() + "/" + appVersion();
        if (envKey.equals(lastPreflightKey())) {
            log("运行环境自检已通过过（环境未变），跳过以加快启动");
        } else {
            runPreflight(node, root, toolsDir);
            rememberPreflightKey(envKey);
        }

        // 3. 准备 DSH 配置（若不存在）
        prepareConfig(root);

        // 4. 启动 dsh web
        File binJs = new File(dshDir, "lib/bin.js");

        // 先看**上次那个 DSH 还在不在**。
        //
        // 原来每次都无脑起一个新实例，而旧进程并没有随 App 退出而结束 ——
        // 于是每启动一次就多一个 DSH，端口一路从 3080 往上爬
        //（日志里已经出现过「3080 已被占用」）。而启动一个 DSH 要 20~60 秒，
        // 这就是「关掉再打开要等很久」的真正原因。
        //
        // 复用它：直接连上去，跳过整个启动过程。
        int live = liveDshPort();
        if (live > 0) {
            chosenPort = live;
            log("复用已在运行的 DSH（端口 " + live + "），跳过启动，立即进入界面");
            setSplashStatus("正在连接已有服务…");
            String url = "http://127.0.0.1:" + live + "/?token=" + savedDshToken();
            loadDshUi(url);
            return null;
        }

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
            loadDshUi(url);
            return null;
        }
        // 走到这里说明服务一直没就绪：**必须返回原因**而不是默默结束。
        // 原来这里是静默 return，用户只会看到开屏一直转圈。
        return "DSH 服务在等待时间内没有就绪（可能是端口占用、运行包损坏或网络问题）";
    }

    /** 加载 DSH 界面（复用实例与新建实例都走这里）。 */
    private void loadDshUi(String url) {
        log("界面就绪: " + url);
        rememberDsh(url);
        final String target = url;
        statusPageLoading = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                webView.loadUrl(target);
                // 复用已有实例时，开屏要在加载完成后收起；
                // 这里先排一个兜底，避免任何情况下被永久挡住
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                    @Override public void run() { hideSplash(); }
                }, 8000);
            }
        });
    }

    // ---------------------------------------------------------------- 自检缓存

    /** 运行包修订号的指纹（变了说明环境可能变）。 */
    private String payloadRevisionKey() {
        try {
            java.util.Set<String> set = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("payloadRevisions", null);
            if (set == null || set.isEmpty()) return "0";
            java.util.List<String> l = new java.util.ArrayList<String>(set);
            java.util.Collections.sort(l);
            return l.toString();
        } catch (Throwable t) {
            return "0";
        }
    }

    private String lastPreflightKey() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("preflightKey", "");
        } catch (Throwable t) {
            return "";
        }
    }

    private void rememberPreflightKey(String key) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("preflightKey", key).apply();
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 复用运行中的实例
    /**
     * 探测有没有**还在运行的** DSH 实例。
     *
     * <p>App 退出时不会带走 DSH 进程（它是独立子进程）—— 这是有意的：
     * 关掉 App 后 agent 还要继续跑。但这样一来，下次启动若直接再起一个，
     * 就会同时存在两个 DSH，而且新实例要 20~60 秒才能服务。
     *
     * <p>所以先找有没有活着的：端口能连上、并且能认出是 DSH。
     *
     * @return 可用端口；没有则返回 0
     */
    private int liveDshPort() {
        String token = savedDshToken();
        if (token == null || token.length() == 0) return 0;
        int saved = savedDshPort();
        // 先试上次记录的那个端口，再扫常见的这一段
        int[] candidates = new int[12];
        candidates[0] = saved;
        for (int i = 0; i < 11; i++) candidates[i + 1] = PORT + i;
        for (int port : candidates) {
            if (port <= 0) continue;
            if (!portOpen(port)) continue;
            // 能连上还不够：确认它真的是 DSH（而不是别的服务占了端口）。
            // 带上 token 请求首页，拿到内容就说明可用。
            try {
                String body = httpGetQuick("http://127.0.0.1:" + port + "/?token=" + token, 2500);
                if (body != null && body.length() > 200
                        && body.indexOf("authentication required") < 0) {
                    return port;
                }
            } catch (Throwable ignored) { }
        }
        return 0;
    }

    /** 端口是否有服务在监听（很快，只做连接）。 */
    private boolean portOpen(int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /** 极简 GET，带超时。失败返回 null。 */
    private String httpGetQuick(String url, int timeoutMs) {
        java.io.InputStream in = null;
        try {
            java.net.HttpURLConnection c =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) return null;
            in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 记下可用的实例地址（端口 + token），供下次启动复用。 */
    private void rememberDsh(String url) {
        try {
            int port = chosenPort;
            String token = "";
            int t = url.indexOf("token=");
            if (t >= 0) token = url.substring(t + 6);
            if (port <= 0) {
                int a = url.indexOf("127.0.0.1:");
                if (a >= 0) {
                    String rest = url.substring(a + 10);
                    int slash = rest.indexOf('/');
                    port = Integer.parseInt(slash > 0 ? rest.substring(0, slash) : rest);
                }
            }
            if (port <= 0 || token.length() == 0) return;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("dshPort", port)
                    .putString("dshToken", token)
                    .apply();
        } catch (Throwable ignored) { }
    }

    private int savedDshPort() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("dshPort", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String savedDshToken() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("dshToken", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 这个 URL 是否值得送去系统浏览器。
     *
     * <h3>曾经写错过一次</h3>
     * 早先我据一张截图判断某个地址「畸形」，加了一条「主机名必须有点」
     * 的规则。后来发现判断错了：截图里的错误是
     * {@code net::ERR_CONNECTION_CLOSED} ——
     * 那是**连接建立后被对端或代理关闭**，说明 **DNS 是解析成功的**。
     * 域名不存在会是 {@code ERR_NAME_NOT_RESOLVED}，两者完全不同。
     *
     * <p>单段主机名（例如 {@code https://xn--qvraaa/}）在内网、
     * 代理或 VPN 的 DNS 下**可以解析**，不该一律拒绝 ——
     * 那条规则会误伤正常地址。
     *
     * <h3>现在只拒绝真正不成立的输入</h3>
     * 空、有空格、控制字符、无法构造 URL 的 —— 只拒绝这些。
     * **误拒正常链接比放行一个坏链接更糟。**
     */
    private static boolean looksLikeRealUrl(String url) {
        try {
            if (url == null) return false;
            String u = url.trim();
            if (u.length() == 0) return false;
            // 控制字符与空格：这类地址一定构造不出可用 URL
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                if (c < 0x20 || c == 0x7F || c == ' ') return false;
            }
            String lower = u.toLowerCase(java.util.Locale.ROOT);
            // 非网页 scheme：交给系统（邮件、电话、应用跳转）
            if (lower.startsWith("mailto:") || lower.startsWith("tel:")
                    || lower.startsWith("sms:") || lower.startsWith("intent:")
                    || lower.startsWith("market:") || lower.startsWith("geo:")) {
                return true;
            }
            // 网页地址：能解析出主机名即可，不额外限制它长什么样
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                java.net.URL parsed = new java.net.URL(u);
                String host = parsed.getHost();
                return host != null && host.length() > 0;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** URL 太长时截断，只用于日志与提示。 */
    private static String briefUrl(String url) {
        if (url == null) return "";
        return url.length() <= 80 ? url : url.substring(0, 80) + "…";
    }

    /** 网页弹窗的通用实现：alert 与 confirm 共用。 */
    private void showJsDialog(String title, String message, final boolean cancellable,
                              final android.webkit.JsResult result) {
        try {
            android.widget.LinearLayout box = DshUi.paddedBody(this);
            box.addView(DshUi.title(this, title));

            // 网页给的文本可能很长，放进可滚动区域
            android.widget.TextView msg = new android.widget.TextView(this);
            msg.setText(message == null ? "" : message);
            msg.setTextSize(12.5f);
            msg.setTextColor(DshUi.TEXT());
            msg.setTextIsSelectable(true);
            android.widget.ScrollView sc = new android.widget.ScrollView(this);
            sc.addView(msg, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.LinearLayout.LayoutParams slp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            slp.topMargin = DshUi.dp(this, 10);
            box.addView(sc, slp);

            android.widget.Button ok = DshUi.button(this, "确定", true);
            final android.app.Dialog d;
            if (cancellable) {
                android.widget.Button cancel = DshUi.button(this, "取消", false);
                d = DshUi.dialogFill(this, box, DshUi.footer(this, cancel, ok), 460);
                cancel.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        d.dismiss();
                        result.cancel();
                    }
                });
            } else {
                d = DshUi.dialogFill(this, box, DshUi.footer(this, ok), 460);
            }
            ok.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    d.dismiss();
                    result.confirm();
                }
            });
            // 不许点外部关闭：网页的回调必须收到一个明确的答复，
            // 否则它会一直悬着，后续操作全部卡住
            d.setCancelable(false);
            d.show();
        } catch (Throwable t) {
            log("网页弹窗显示失败: " + t);
            // 弹不出来也必须给网页一个回应
            if (cancellable) result.cancel(); else result.confirm();
        }
    }

    /** 网页的 prompt：多一个输入框。 */
    private void showJsPrompt(String message, String defaultValue,
                              final android.webkit.JsPromptResult result) {
        try {
            android.widget.LinearLayout box = DshUi.paddedBody(this);
            box.addView(DshUi.title(this, "输入"));
            if (message != null && message.length() > 0) {
                box.addView(DshUi.hint(this, message), DshUi.fullWidth(this, 6));
            }
            final android.widget.EditText input =
                    DshUi.input(this, defaultValue == null ? "" : defaultValue, false);
            box.addView(input, DshUi.fullWidth(this, 10));
            android.widget.Button cancel = DshUi.button(this, "取消", false);
            android.widget.Button ok = DshUi.button(this, "确定", true);
            final android.app.Dialog d = DshUi.dialog(this, box,
                    DshUi.footer(this, cancel, ok), 400);
            cancel.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    d.dismiss();
                    result.cancel();
                }
            });
            ok.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    d.dismiss();
                    result.confirm(input.getText() == null ? "" : input.getText().toString());
                }
            });
            d.setCancelable(false);
            d.show();
        } catch (Throwable t) {
            log("网页输入框显示失败: " + t);
            result.cancel();
        }
    }

    /** 连接最近一次出问题的时间（0 表示当前正常）。 */
    private volatile long connectionLostAt;

    /** 是否已经安排过一次自动重载（避免反复刷新）。 */
    private volatile boolean reloadScheduled;

    /**
     * 处理网页端上报的连接事件。
     *
     * <p>为什么需要它：DSH 的 Remote RPC（归档会话、改设置、分叉会话……）
     * 走的是 **WebSocket**，而 App 的 API 日志只包了 `fetch` ——
     * 所以 WebSocket 断掉时，App 这边**什么都看不到**，
     * 用户那边则是「点归档没有任何反应」。
     *
     * <p>实测日志里出现过 {@code [connection] connection lost, retry #1}，
     * 而归档请求正是走这条通道 —— 请求根本没到服务端。
     *
     * <p>处理办法：发现连接丢失就起一个计时器；若迟迟没有恢复，
     * **自动刷新页面**重建连接。DSH 的会话状态在服务端，
     * 刷新不会丢东西（只会重建一次界面）。
     */
    /**
     * 自己触发页面刷新的时刻。
     *
     * <p>刷新会**主动拆掉**页面上正在重连的 WebSocket，DSH 随即打印
     * {@code connection lost}。若不区分「谁引起的断开」，看门狗就会
     * 把这次断开当成新的故障，15 秒后再刷新一次 ——
     * 形成自我维持的循环。真机日志里实测到 13 次连续自动刷新，
     * 用户看到的就是「窗口一遍遍白一下、闪一下，而任务其实一直在正常跑」。
     */
    private volatile long selfReloadAt;
    /** 连续自动刷新的次数。达到上限就停下来，改为提示用户手动处理。 */
    private int autoReloadCount;

    /** 自己刷新之后，这段时间内的连接事件不算「新故障」。 */
    private static final long RELOAD_SUPPRESS_MS = 30000;
    /** 连续自动刷新的上限。超过它说明刷新解决不了问题，再刷只会更糟。 */
    private static final int MAX_AUTO_RELOADS = 2;
    /** 断开多久后才考虑自动刷新。DSH 自己会重连，给它足够时间。 */
    private static final long RELOAD_AFTER_MS = 30000;

    private void onConnectionEvent(String message) {
        try {
            if (message.indexOf("connection lost") >= 0) {
                long now = System.currentTimeMillis();
                // 刚才是我们自己刷新的 → 这次断开是刷新的后果，不是新故障
                if (now - selfReloadAt < RELOAD_SUPPRESS_MS) {
                    log("[连接] WebSocket 断开（本次刷新引起，已忽略）：" + message);
                    return;
                }
                connectionLostAt = now;
                log("[连接] WebSocket 断开：" + message);
                scheduleReloadIfStuck();
            } else {
                // restored / reconnect 之类：认为恢复了
                if (connectionLostAt != 0) {
                    log("[连接] WebSocket 已恢复");
                    // 真的恢复过，说明刷新策略有效，计数归零重新开始
                    autoReloadCount = 0;
                }
                connectionLostAt = 0;
                reloadScheduled = false;
            }
        } catch (Throwable t) {
            log("处理连接事件失败: " + t);
        }
    }

    /** 若连接在若干秒内没有恢复，自动刷新页面。 */
    private void scheduleReloadIfStuck() {
        if (reloadScheduled) return;
        reloadScheduled = true;
        final long lostAt = connectionLostAt;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 期间恢复过就不动
                    if (connectionLostAt == 0 || connectionLostAt != lostAt) return;
                    long sec = (System.currentTimeMillis() - lostAt) / 1000;

                    // 任务正在跑时**绝不刷新**。
                    // 刷新会丢掉滚动位置、输入框内容和展开的面板，而 agent
                    // 在 node 进程里照常干活 —— 用户看到的就是「窗口自己重启/闪烁，
                    // 但任务其实正常进行」。DSH 自身的重连足以应付这种情况。
                    if (lastSessionStatus == SessionStatus.RUNNING
                            || lastSessionStatus == SessionStatus.AWAITING_APPROVAL) {
                        log("[连接] 断开 " + sec + " 秒，但任务正在运行 —— 不刷新页面"
                                + "（避免打断界面；DSH 会自行重连）");
                        connectionLostAt = 0;
                        reloadScheduled = false;
                        return;
                    }
                    // 刷新解决不了问题时就停止刷新，别再让界面一遍遍闪
                    if (autoReloadCount >= MAX_AUTO_RELOADS) {
                        log("[连接] 已自动刷新 " + autoReloadCount + " 次仍不稳定 —— "
                                + "停止自动刷新，请下拉通知栏或在设置里手动处理");
                        connectionLostAt = 0;
                        reloadScheduled = false;
                        return;
                    }
                    autoReloadCount++;
                    selfReloadAt = System.currentTimeMillis();
                    log("[连接] 断开 " + sec + " 秒仍未恢复，自动刷新页面重建连接"
                            + "（第 " + autoReloadCount + "/" + MAX_AUTO_RELOADS + " 次）");
                    statusPageLoading = false;
                    if (webView != null) webView.reload();
                    connectionLostAt = 0;
                    reloadScheduled = false;
                } catch (Throwable t) {
                    log("自动刷新失败: " + t);
                }
            }
        }, RELOAD_AFTER_MS);
    }

    /**
     * 决定一个 URL 是在 WebView 里加载，还是交给系统处理。
     *
     * @return true 表示「已接管，WebView 不要导航」
     */
    private boolean handleUrl(String url) {
        if (url == null || url.length() == 0) return false;
        // 状态页里的「重试启动」链接（启动失败时给出的出路之一）
        if (url.startsWith("dsh-retry:")) {
            log("用户点击「重试启动」");
            restartAgent();
            return true;
        }
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        // 本机地址留在 WebView 里（DSH 自己的页面、附件预览等）
        if (u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost")
                || u.startsWith("https://127.0.0.1") || u.startsWith("https://localhost")
                || u.startsWith("about:") || u.startsWith("data:")
                || u.startsWith("blob:") || u.startsWith("javascript:")) {
            return false;
        }
        // 先校验：畸形的 URL 不要往系统浏览器送。
        //
        // 用户实际遇到：DSH 传来一个 `https://xn--qvraaa/` ——
        // punycode 前缀后面接了个没有合法顶级域的短域名，根本不存在。
        // 原样转发的结果是系统浏览器弹出一页「网页无法打开」，
        // 看起来像是 App 或浏览器坏了，其实是这个地址本身不成立。
        //
        // 这类地址直接不打开，只记日志 —— 打开一个必然失败的页面
        // 比什么都不做更让人困惑。
        if (!looksLikeRealUrl(url)) {
            log("忽略无效链接（未送去浏览器）: " + briefUrl(url));
            return true;   // 仍然拦下，不让 WebView 去加载它
        }

        // 其余一律交给系统：外部网页、mailto:、tel:、intent: 等
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            log("外部链接已在系统浏览器打开: " + briefUrl(url));
        } catch (Throwable t) {
            // 没有能处理该 scheme 的应用 —— 提示一下，别让点击看起来没反应
            toast("无法打开该链接：" + briefUrl(url));
            log("打开外部链接失败: " + url + " → " + t);
        }
        return true;
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
    /** 顶部 WebView 加载进度条（2dp）。 */
    private android.widget.ProgressBar topProgress;
    private volatile boolean splashHidden;
    /** 通知栏「设置」动作带的标记。 */
    public static final String EXTRA_OPEN_SETTINGS = "dev.dsh.nativeapp.OPEN_SETTINGS";
    /** 待处理的设置请求（界面未就绪时先记下，加载完成后打开）。 */
    private volatile boolean pendingOpenSettings;
    /**
     * 冷启动时的分享意图，等到工作区确定之后再处理。
     *
     * <p>不能一进 onCreate 就处理：那时 workspace 还是 null，
     * 会直接弹「工作区不可用」并把文件丢掉（冷启动分享必失败）。
     */
    private volatile android.content.Intent pendingShareIntent;
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

    /** 本机已应用的分片修订号集合（形如 "dsh.tar.zst=1"）。 */
    private java.util.Set<String> appliedRevisions() {
        try {
            java.util.Set<String> s = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("payloadRevisions", null);
            if (s != null) return new java.util.HashSet<String>(s);
        } catch (Throwable t) { }
        return new java.util.HashSet<String>();
    }

    /** 从集合里找出某个分片的记录项（形如 "name=rev"）。 */
    private static String findRevision(java.util.Set<String> set, String name) {
        if (set == null || name == null) return null;
        String prefix = name + "=";
        for (String s : set) {
            if (s != null && s.startsWith(prefix)) return s;
        }
        return null;
    }

    /**
     * 记录各分片已应用的修订号。
     *
     * <p>只在**全部成功后**调用：中途失败若已记录，
     * 下次启动会误判为已应用，那些该删的文件就永远补不回来了。
     */
    private void rememberPayloadRevisions(org.json.JSONArray parts, File dshDir, File toolsDir) {
        try {
            java.util.Set<String> set = appliedRevisions();
            boolean changed = false;
            for (int i = 0; i < parts.length(); i++) {
                org.json.JSONObject part = parts.getJSONObject(i);
                String name = part.getString("name");
                int rev = part.optInt("revision", 0);
                String existing = findRevision(set, name);
                if (existing != null) set.remove(existing);
                set.add(PayloadUpdate.revisionKey(name, rev));
                changed = true;
            }
            if (changed) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putStringSet("payloadRevisions", set).apply();
                log("运行包修订号已记录（" + parts.length() + " 个分片）");
            }
        } catch (Throwable t) {
            log("记录运行包修订号失败: " + t);
        }
    }

    /**
     * 本地运行包是否看起来完整。
     *
     * <p>只看关键文件**是否存在**，不做摘要计算 —— 这一步在启动的关键路径上，
     * 要的是快。真正的完整性校验交给后台检查去慢慢做。
     */
    private boolean payloadLocallyComplete(File dshDir, File toolsDir) {
        try {
            // node 来自 **APK 内置资源**，解压到 <root>/node —— 不在 tools 载荷里。
            //
            // 这里曾经写成 tools/bin/node，那个路径**永远不存在**，
            // 于是这个判断永远返回 false，每次启动都走阻塞的网络检查。
            // 网络好时看不出来；国内网络下拉清单会挂住，
            // 表现为「一直卡在正在准备运行环境」。
            if (!new File(appRoot, "node").isFile()) return false;
            // DSH 本体在运行包里
            if (!new File(dshDir, "lib/bin.js").isFile()) return false;
            // 工具链：随便挑一个必然存在的（git 在 base 分片里）
            if (!new File(toolsDir, "bin/git").exists()
                    && !new File(toolsDir, "bin/bash").exists()) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 上次后台检查是否发现了待应用的更新。 */
    private boolean payloadUpdatePending() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("payloadUpdatePending", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void setPayloadUpdatePending(boolean pending) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("payloadUpdatePending", pending).apply();
        } catch (Throwable ignored) { }
    }

    private void clearPayloadUpdatePending() {
        setPayloadUpdatePending(false);
    }

    /**
     * 后台检查运行包更新。
     *
     * <p>**只读**：拉清单、比对哨兵，判断有没有需要处理的分片。
     * 不在这里下载 —— 那会在 DSH 正在运行时替换它的文件。
     * 发现有更新就记一个标记，下次启动时按正常流程应用。
     */
    private void checkPayloadInBackground(final File node, final File root,
                                          final File dshDir, final File toolsDir) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File mf = new File(root, "manifest.json");
                    download("manifest.json", mf);
                    org.json.JSONObject man = new org.json.JSONObject(readText(mf));
                    org.json.JSONArray parts = man.getJSONArray("parts");
                    java.util.Set<String> appliedRevs = appliedRevisions();
                    int need = 0;
                    for (int i = 0; i < parts.length(); i++) {
                        org.json.JSONObject part = parts.getJSONObject(i);
                        String name = part.getString("name");
                        File dir = "dsh".equals(part.getString("target")) ? dshDir : toolsDir;
                        org.json.JSONObject sent = part.getJSONObject("sentinel");
                        File sf = new File(dir, sent.getString("path"));
                        boolean matched = sf.exists()
                                && sf.length() == sent.getLong("size")
                                && sent.getString("sha256").equalsIgnoreCase(sha256(sf));
                        int rev = part.optInt("revision", 0);
                        int applied = PayloadUpdate.parseRevision(findRevision(appliedRevs, name));
                        java.util.List<String> rm = new java.util.ArrayList<String>();
                        org.json.JSONArray ra = part.optJSONArray("remove");
                        if (ra != null) {
                            for (int k = 0; k < ra.length(); k++) rm.add(ra.optString(k, ""));
                        }
                        if (PayloadUpdate.needsWork(
                                new PayloadUpdate.Part(name, rev, matched, rm), applied)) {
                            need++;
                        }
                    }
                    if (need > 0) {
                        log("后台检查：有 " + need + " 个分片需要更新，下次启动时应用");
                        setPayloadUpdatePending(true);
                    } else {
                        log("后台检查：运行包已是最新");
                        // 顺手把修订号记下，避免下次重复比对
                        rememberPayloadRevisions(parts, dshDir, toolsDir);
                    }
                } catch (Throwable t) {
                    // 后台检查失败不影响使用 —— 本地运行包是完整的
                    log("后台检查更新失败（不影响使用）: " + t.getMessage());
                }
            }
        }, "payload-check");
        t.setDaemon(true);
        t.start();
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
                // 清空日志是不可撤销的破坏性操作：这个文件跨多次启动累积，
                // 而排查"应用内更新到底成功没有"这类跨会话问题全靠它。
                // 一次误触就没了，所以先问一句。
                DshUi.confirm(MainActivity.this, "清空运行日志？",
                        "日志跨多次启动累积，清空后无法恢复。\n"
                      + "排查跨会话的问题时会用到它。",
                        "清空", new Runnable() {
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
        });
    }

    // ---------------------------------------------------------------- 任务完成通知
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
            // 先确保渠道存在：渠道原本只由前台服务创建，
            // 服务没起来时通知会因渠道不存在而静默丢失
            DshUi.ensureChannel(this, HarnessService.CHANNEL_ID,
                    "DeepSeek Harness", "运行状态与任务完成提醒",
                    android.app.NotificationManager.IMPORTANCE_LOW);
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

    // ---------------------------------------------------------------- 通知栏状态看板
    /**
     * 注入状态采集脚本。
     *
     * <p>读的是**页面自身的状态**，不是 HTTP 接口 ——
     * DSH 的服务端 API 走自定义 RPC（WebSocket），没有 REST 端点，
     * 早先轮询 {@code /api/session/list} 的版本实际上从未生效过。
     *
     * <p>三个判据取自 DSH 客户端插件的 locale 字典，是稳定的文案：
     * 「停止生成」「发送消息」「等待审批」。
     */
    private void installStatusWatcher() {
        try {
            webView.evaluateJavascript(SessionStatus.pollScript(), null);
            log("已注入状态看板（下拉通知栏可看运行状态）");
        } catch (Throwable t) {
            log("状态看板注入失败: " + t);
        }
    }

    /** 任务开始时间，用于在通知里显示运行时长。 */
    private volatile long taskStartedAt;

    /** 上一次已知状态。 */
    private volatile int lastSessionStatus = SessionStatus.UNKNOWN;

    /**
     * 从 DSH 自己的会话列表响应里读出运行状态。
     *
     * <p>{@code running} 是服务端给的权威字段，与界面怎么渲染无关 ——
     * 这一点比从界面上找按钮可靠得多（那段逻辑改过七次，每次都误报）。
     *
     * <p>只做字符串判断，不引入 JSON 解析：这里的输入是已经定型的日志行，
     * 且我们只需要知道「有没有任何会话在跑」。
     */
    /**
     * 兜底嗅探：从被截断的会话列表响应里找 {@code "running":true}。
     *
     * <p>只在页面侧的完整解析（{@code [dsh-sess]}）没送到时才用得上。
     * <b>只上报「在跑」，永远不上报「空闲」</b> —— 这条文本是被
     * {@code slice(0,700)} 截断过的，证据消失是常态，不能当反证。
     *
     * @return true 表示本次确实发现了「在跑」的证据
     */
    private boolean onSessionListResponse(String line) {
        try {
            int i = line.indexOf("\"running\":true");
            if (i >= 0) {
                onStatusSignal(SessionStatus.RUNNING, SRC_SESS);
                return true;
            }
        } catch (Throwable t) {
            // 解析失败不该影响使用
        }
        return false;
    }

    // ------------------------------------------------------------ 状态证据源
    //
    // 状态由两个**独立来源**共同决定，各自带时间戳：
    //   SRC_SESS：页面上报的会话列表计数（页面侧解析完整 JSON，最可信）
    //   SRC_DOM ：页面 DOM 上的按钮（停止生成 / 发送消息 / 等待审批）
    //
    // 为什么要两个：单一来源一旦失效（页面刷新、接口改版、文案变化），
    // 通知栏就会静默停在过期状态。两个来源互相兜底，且都对「新鲜度」敏感。

    /** 证据源：页面侧解析的会话列表计数。 */
    private static final int SRC_SESS = 0;
    /** 证据源：页面 DOM 按钮。 */
    private static final int SRC_DOM = 1;

    /** 证据有效期。超过它就不再采信该来源（避免用几分钟前的状态误导用户）。 */
    private static final long SIGNAL_TTL_MS = 20000;
    /** 运行状态证据过期多久后，不再沿用它、改报「未知」。 */
    private static final long RUNSTATE_STALE_MS = 90000;

    private volatile int sessSignalState = SessionStatus.UNKNOWN;
    private volatile long sessSignalAt;
    private volatile int domSignalState = SessionStatus.UNKNOWN;
    private volatile long domSignalAt;

    /** 诊断日志的限流字段。 */
    private long lastStatusLogAt;
    private int lastLoggedState = -2;

    /** 收到一个状态信号：记录来源与时间，再重新推导对外状态。 */
    private void onStatusSignal(int state, int source) {
        long now = System.currentTimeMillis();
        if (source == SRC_SESS) {
            sessSignalState = state;
            sessSignalAt = now;
        } else {
            domSignalState = state;
            domSignalAt = now;
        }
        int derived = deriveStatus(now);
        // 诊断日志：状态一旦算错，没有这行就只能靠猜（本次就吃过这个亏）。
        // 限流为「状态变化时」或「每分钟一次」，不会刷爆日志。
        if (derived != lastLoggedState || now - lastStatusLogAt > 60000) {
            lastStatusLogAt = now;
            lastLoggedState = derived;
            log("[状态] 会话列表=" + SessionStatus.label(sessSignalState)
                    + "（" + ageSec(now, sessSignalAt) + "）"
                    + "  页面=" + SessionStatus.label(domSignalState)
                    + "（" + ageSec(now, domSignalAt) + "）"
                    + " → 通知栏=" + (derived < 0 ? "（保持不变）" : SessionStatus.label(derived)));
        }
        if (derived >= 0) onSessionStatus(derived);
    }

    /** 信号距今多久（用于诊断日志）。单位一起返回，避免拼出「从未s 前」这种文案。 */
    private static String ageSec(long now, long at) {
        return at <= 0 ? "从未收到" : ((now - at) / 1000) + "s 前";
    }

    /**
     * 由两个证据源推导对外状态。
     *
     * <p><b>核心原则：读不到证据 ≠ 空闲。</b>
     * 旧实现是 {@code n > 0 ? RUNNING : IDLE}，而 n 来自被截断到 700 字符的
     * 响应体 —— 运行中的会话只要不是列表第一项，它的 {@code "running":true}
     * 就被截掉、计数为 0，于是**正在跑的任务被判成「空闲」**。
     *
     * <p>现在的规则：
     * <ol>
     *   <li>任何一方说「在跑」就是在跑；</li>
     *   <li>「空闲」必须有明确证据（全量会话列表，或 DOM 上确实只有发送按钮）；</li>
     *   <li>完全没有新鲜证据时返回 -1 —— 保持上一次状态，不猜、也不降级。</li>
     * </ol>
     *
     * @return 推导出的状态；无新鲜证据时返回 -1
     */
    private int deriveStatus(long now) {
        boolean domFresh = domSignalAt > 0 && now - domSignalAt <= SIGNAL_TTL_MS;
        boolean sessFresh = sessSignalAt > 0 && now - sessSignalAt <= SIGNAL_TTL_MS;

        // 1) 等待批准优先级最高 —— 这是唯一仍然读页面的状态
        if (domFresh && domSignalState == SessionStatus.AWAITING_APPROVAL) {
            return SessionStatus.AWAITING_APPROVAL;
        }
        // 2) 运行 / 空闲**只认会话列表**（页面侧解析的完整 JSON）。
        //
        //    不再用页面上的「停止生成 / 发送消息」按钮推断运行状态：
        //    那两个按钮的文案匹配一旦失效（换成图标、文案改字），就解析成
        //    「无依据」；更糟的是**误命中时会把状态钉死** —— 实测过一次：
        //    对话早已结束，通知栏却一直停在「运行中」。
        //    按钮从此只保留一个用途：识别「等待批准」（见上一步）。
        if (sessFresh) {
            return sessSignalState == SessionStatus.RUNNING
                    ? SessionStatus.RUNNING : SessionStatus.IDLE;
        }
        // 3) 刚过期不久：保持上一次状态，避免无谓抖动
        if (sessSignalAt > 0 && now - sessSignalAt <= RUNSTATE_STALE_MS) {
            return -1;
        }
        // 4) 长时间拿不到权威数据：宁可报「未知」，也不要把旧状态一直挂着。
        //    「通知里显示错误的状态比不显示更糟」是项目的既有约定。
        if (lastSessionStatus == SessionStatus.RUNNING
                || lastSessionStatus == SessionStatus.AWAITING_APPROVAL) {
            return SessionStatus.UNKNOWN;
        }
        return -1;
    }

    /** 收到一次页面状态上报，推给前台服务更新通知。 */
    private void onSessionStatus(int state) {
        try {
            if (state == SessionStatus.RUNNING && lastSessionStatus != SessionStatus.RUNNING
                    && lastSessionStatus != SessionStatus.AWAITING_APPROVAL) {
                taskStartedAt = System.currentTimeMillis();
            }
            // 顺带驱动「任务完成」通知。
            //
            // 原来它轮询 /api/session/list —— 那个接口**不存在**
            //（DSH 的服务端 API 是自定义 RPC，不是 REST），所以那条链
            // 实际上从未生效过。现在改用同一个页面状态源。
            if (state == SessionStatus.RUNNING || state == SessionStatus.AWAITING_APPROVAL) {
                taskNotifier.onEvent("start", "", System.currentTimeMillis(), inForeground);
            } else if (state == SessionStatus.IDLE) {
                String done = taskNotifier.onEvent("done", "",
                        System.currentTimeMillis(), inForeground);
                if (done != null) notifyTaskDone(done);
            }
            lastSessionStatus = state;
            pushStatus(state);
        } catch (Throwable t) {
            // 状态更新失败不该影响使用，也不该刷日志
        }
    }

    /**
     * 监听网络环境变化。
     *
     * <p>为什么要监听：切换网络（流量↔Wi-Fi、开关代理）后，DSH 到模型服务的
     * **长连接已经断了**，但它可能一直挂在那个死连接上 —— 既不报错也不超时。
     * 用户看到的就是「对话卡住、没反应」。
     *
     * <p>App 无法替 DSH 重发那个请求（那在它的进程里），但可以做两件事：
     * 现在只记日志。曾经在这里做过「提示 + 一键重连」，见 onNetworkChanged 的说明。
     */
    private void registerNetworkWatcher() {
        try {
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null || android.os.Build.VERSION.SDK_INT < 24) return;
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) { onNetworkChanged(); }
                @Override public void onLost(android.net.Network n) { onNetworkChanged(); }
            });
            log("已监听网络变化（仅记录，用于排查「对话没反应」）");
        } catch (Throwable t) {
            log("网络变化监听注册失败（不影响使用）: " + t);
        }
    }

    /**
     * 网络环境变了。
     *
     * <p>只记日志，**不动通知栏**。
     *
     * <p>曾经在这里做两件事：把「网络已切换，可点重连」写进通知、
     * 并加一个常驻的「重连」按钮。结果那个按钮每次开机都挂在通知栏上
     *（它是静态动作，与是否切换过网络无关），用户明确说没用、要求删掉。
     * 而且重启 DSH 会中断正在跑的任务 —— 用户自己强停 App 也能做到，
     * 不值得占一个常驻按钮。
     *
     * <p>网络切换本身仍值得记录：DSH 到模型服务的长连接可能因此失效，
     * 排查「对话没反应」时这是第一条要看的信息。
     */
    private void onNetworkChanged() {
        try {
            boolean[] net = networkState();
            log("网络环境已变化: " + SessionStatus.networkLabel(net[1], net[2], net[3], net[0]));
        } catch (Throwable t) {
            log("处理网络变化失败: " + t);
        }
    }


    /** 上一次推送给通知的状态（用于抑制重复推送）。 */
    private volatile int pushedState = -2;
    /** 是否已经推送过一次网络状态。 */
    private volatile boolean networkInited;

    /**
     * 把状态推给前台服务更新通知。
     *
     * <p>**只在真的变化时推送**。原来每 2 秒的心跳都会推一次，
     * 而每次推送都会让系统重新发布通知 —— 在 MIUI 上表现为通知栏
     * 每 2 秒闪一下。而现在运行时长走系统计时器、网络变化走系统回调，
     * 心跳本身已经不需要产生任何界面更新了。
     */
    private void pushStatus(int state) {
        try {
            // 状态没变 → 什么都不用做
            if (state == pushedState && networkInited) return;

            boolean[] net = networkState();
            android.content.Intent i = new android.content.Intent(this, HarnessService.class);
            i.setAction(HarnessService.ACTION_STATUS);
            i.putExtra(HarnessService.EXTRA_STATUS_STATE, state);
            i.putExtra(HarnessService.EXTRA_STATUS_NETWORK, net[0]);
            i.putExtra(HarnessService.EXTRA_STATUS_NETWORK_LABEL,
                    SessionStatus.networkLabel(net[1], net[2], net[3], net[0]));
            i.putExtra(HarnessService.EXTRA_STATUS_SINCE, taskStartedAt);
            startService(i);
            // **推送成功之后**才记下状态。
            //
            // 之前是先记后推：一旦 startService 抛异常（Android 8+ 的后台服务
            // 启动限制、服务处于 stopped 状态等），这里就认为「已经推过了」，
            // 通知栏会永久停在旧状态 —— 而用户正是据此判断 agent 还在不在干活。
            // 这个 catch 之前是空的，连日志都没有。
            pushedState = state;
            networkInited = true;
        } catch (Throwable t) {
            log("警告: 状态推送失败（通知栏将停在旧状态）: " + t);
        }
    }

    /**
     * 当前网络状态。
     *
     * @return {@code [是否可用, 是否Wi-Fi, 是否移动数据, 是否验证过外网]}
     */
    private boolean[] networkState() {
        boolean connected = false, wifi = false, cellular = false, validated = false;
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && android.os.Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n != null) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps != null) {
                        connected = caps.hasCapability(
                                android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        validated = caps.hasCapability(
                                android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                        wifi = caps.hasTransport(
                                android.net.NetworkCapabilities.TRANSPORT_WIFI);
                        cellular = caps.hasTransport(
                                android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
                    }
                }
            } else if (cm != null) {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    connected = true;
                    validated = true;
                    wifi = ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
                    cellular = ni.getType() == android.net.ConnectivityManager.TYPE_MOBILE;
                }
            }
        } catch (Throwable ignored) { }
        return new boolean[]{ connected, wifi, cellular, validated };
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
          + "var sessReq=null,sessLogged=0;"
          + "function isAsset(u){return /\\.(js|css|png|jpe?g|gif|svg|woff2?|ttf|ico|map)(\\?|$)/i.test(u);}"
          + "window.fetch=function(){"
          + "  var a=arguments[0];"
          + "  var u='';"
          + "  try{u=(typeof a==='string')?a:(a&&a.url?a.url:String(a));}catch(x){}"
          + "  var p=of.apply(this,arguments);"
          + "  try{"
          + "    var ini=arguments[1];"
          + "    if(u.indexOf('/api/session/list')>=0&&ini&&ini.body){"
          + "      sessReq={m:(ini.method||'POST'),h:ini.headers,b:String(ini.body)};"
          + "      if(!sessLogged){sessLogged=1;console.log('[dsh-sess-src] captured len='+sessReq.b.length);}"
          + "    }"
          + "  }catch(x){}"
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
          + "          try{"
          + "            if(u.indexOf('/api/session/list')>=0){"
          + "              var j=JSON.parse(t);"
          + "              var it=(j&&j.result&&j.result.value&&j.result.value.items)||[];"
          + "              var rn=0,q;"
          + "              for(q=0;q<it.length;q++){if(it[q]&&it[q].running===true)rn++;}"
          + "              console.log('[dsh-sess] r='+rn);"
          + "            }"
          + "          }catch(e2){}"
          + "          console.log('[dsh-api] '+r.status+' '+u+' :: '+String(t).slice(0,700));"
          + "        }).catch(function(){});"
          + "      }catch(e){}"
          + "    }).catch(function(e){"
          + "      console.error('[dsh-api] NETFAIL '+u+' :: '+String(e));"
          + "    });"
          + "  }catch(e){}"
          + "  return p;"
          + "};"
          // 定时重放会话列表请求。
          //
          // 为什么必须自己重放：`running` 是判断「有没有任务在跑」的**唯一权威依据**，
          // 而 DSH 自己请求 /api/session/list 的频率极低（实测一次启动只请求一两次）。
          // 结果就是证据过期 —— 任务早就结束了，App 却还拿着几分钟前的旧值。
          //
          // 这里记下 DSH 发过的那个请求（方法/头/体），每 5 秒用新的 rpcId 重放一次。
          // 它是只读的本地 RPC，不会改变任何状态；响应由我们自己的 promise 接收，
          // DSH 的客户端拿不到、也不受影响。
          + "setInterval(function(){"
          + "  if(!sessReq)return;"
          + "  try{"
          + "    var o=JSON.parse(sessReq.b);"
          + "    o.rpcId='probe-'+Date.now()+'-'+Math.floor(Math.random()*1e6);"
          + "    var hh={};"
          + "    try{"
          + "      if(sessReq.h&&typeof sessReq.h.forEach==='function'){sessReq.h.forEach(function(v,k){hh[k]=v;});}"
          + "      else if(sessReq.h){for(var k in sessReq.h){hh[k]=sessReq.h[k];}}"
          + "    }catch(x){}"
          + "    if(!hh['Content-Type']&&!hh['content-type'])hh['Content-Type']='application/json';"
          + "    of.call(window,'/api/session/list',{method:sessReq.m,headers:hh,"
          + "      body:JSON.stringify(o),credentials:'same-origin'})"
          + "      .then(function(r){return r.text();})"
          + "      .then(function(t){"
          + "        try{"
          + "          var j=JSON.parse(t);"
          + "          var it=(j&&j.result&&j.result.value&&j.result.value.items)||[];"
          + "          var n=0,q;"
          + "          for(q=0;q<it.length;q++){if(it[q]&&it[q].running===true)n++;}"
          + "          console.log('[dsh-sess] r='+n);"
          + "        }catch(x){}"
          + "      }).catch(function(){});"
          + "  }catch(x){}"
          + "},5000);"
          + "})();"
          // 应用内设置入口：长按顶部区域 1.2 秒。
          //
          // 为什么需要：全屏 WebView 上**没有任何原生按钮**，设置页只能靠
          // 通知栏动作或桌面图标长按快捷方式进入。而 MIUI 上"通知被关"很常见，
          // 那时设置、日志、更新、插件、备份**全部不可达**。
          // 用长按手势而不是放按钮：不会遮挡 DSH 自己的顶栏，也不会改变观感。
          + ";(function(){"
          + "if(window.__dshSettings)return;window.__dshSettings=1;"
          + "var sx=0,sy=0,st=0;"
          + "document.addEventListener('touchstart',function(e){"
          + "  try{"
          + "    var t=e.touches&&e.touches[0];if(!t)return;"
          + "    sx=t.clientX;sy=t.clientY;st=Date.now();"
          + "  }catch(x){}"
          + "},true);"
          + "document.addEventListener('touchend',function(e){"
          + "  try{"
          + "    if(!st)return;"
          + "    var dt=Date.now()-st;st=0;"
          + "    var t=(e.changedTouches&&e.changedTouches[0])||null;if(!t)return;"
          + "    var moved=Math.abs(t.clientX-sx)+Math.abs(t.clientY-sy);"
          // 顶部 56dp 内、按住超过 1.2 秒、手指基本没移动 —— 三个条件都要满足，
          // 否则会和页面自己的滚动/长按操作打架
          + "    if(sy<56&&dt>1200&&moved<20)console.log('[dsh-native] open-settings');"
          + "  }catch(x){}"
          + "},true);"
          + "})();"
          // 网页主题上报。
          //
          // DSH 的主题是它**自己的设置**（ui-theme.preference = light/dark/system），
          // 与 Android 系统深色相互独立。原生若只跟系统，就会出现
          // 「网页黑、原生白卡片」的同屏割裂（用户实测反馈过）。
          // 属性名取自 DSH 前端：body[data-ds-dark-theme]。
          + ";(function(){"
          + "if(window.__dshTheme)return;window.__dshTheme=1;"
          + "var last='';"
          + "function report(){"
          + "  try{"
          + "    var d=document.body&&document.body.hasAttribute('data-ds-dark-theme');"
          + "    var v=d?'1':'0';"
          + "    if(v!==last){last=v;console.log('[dsh-theme] dark='+v);}"
          + "  }catch(e){}"
          + "}"
          + "report();"
          + "try{new MutationObserver(report).observe(document.documentElement,"
          + "  {attributes:true,subtree:true,attributeFilter:['data-ds-dark-theme']});}"
          + "catch(e){}"
          + "setInterval(report,3000);"
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
            String cur = readText(f);
            if (cur.contains(PATCH_TAG)) {
                recordPatch("附件落盘", true, "已是最新");
                log("  附件补丁已是最新（v3）");
                return;
            }

            // payload 更新后，旧版本留下的 .dshorig 不能继续作为新版本的基线。
            // 只要当前文件没有本次补丁标记，就先从当前文件剥出干净源码并覆盖备份；
            // 否则新内核会被旧内核的备份重新生成，表现为升级后功能仍停在旧版。
            String base = stripAndroidPatch(cur);
            if (base.indexOf("await syncDirectory(") < 0) {
                recordPatch("附件落盘", false, "DSH 代码已变化，补丁未应用（图片可能失效）");
                log("  [警告] 附件模块中未找到预期调用，跳过补丁（可能 DSH 版本变化）");
                return;
            }
            writeText(orig, base);
            log("  已保存当前 DSH 版本的附件原文件（供补丁重新生成）");

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
        // DSH 的构建格式在版本间会切换是否保留分号；两种写法都要覆盖，
        // 否则内核升级后目录持久化补丁会生效，但附件硬链接回退会静默失效。
        out = out.replace("await link(staged.path, target);", "await __androidLink(staged.path, target);");
        out = out.replace("await link(staged.path, target)", "await __androidLink(staged.path, target)");
        out = out.replace("await link(source, target);", "await __androidLink(source, target);");
        out = out.replace("await link(source, target)", "await __androidLink(source, target)");

        int replaced = 0;
        String marker = "throw new AttachmentError(\"Unable to persist attachment.\", "
                + "\"ATTACHMENT_WRITE_FAILED\", { cause: error });";
        String singleMarker = "throw new AttachmentError('Unable to persist attachment.', "
                + "'ATTACHMENT_WRITE_FAILED', { cause: error })";
        String detail = "console.error('[dsh-attach] persist failed: ' + String(error && error.code)"
              + " + ' ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''));\n\t\t\t"
              + "throw new AttachmentError('Unable to persist attachment. ['"
              + " + String(error && error.code) + '] ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''), 'ATTACHMENT_WRITE_FAILED', { cause: error })";
        if (out.contains(marker)) {
            out = out.replace(marker, detail + ";");
            replaced = 1;
        } else if (out.contains(singleMarker)) {
            out = out.replace(singleMarker, detail);
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
              + "const b=await readFile(from);const fs=await import('node:fs/promises');"
              + "await fs.writeFile(to,b,{mode:384});return;}"
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
                    // 除了版本号，还必须校验**签名与已安装版本一致**。
                    //
                    // 只看版本号会踩这个坑：同一个版本号重发（例如换了签名后重发），
                    // 缓存里那个装不上的包会被反复复用 —— 用户每次点更新都失败，
                    // 而日志只写「使用已下载的 X 安装包」，完全看不出问题在哪。
                    // （实测踩过：0.23.4 重发时是我手工删掉缓存才通的。）
                    if (cachedVer != null && !isNewer(rel[0], cachedVer)
                            && cachedApkInstallable(apk)) {
                        log("本地已有最新安装包 " + cachedVer
                                + "（此前下载后未安装），直接调起安装，跳过下载");
                        setStatus(status, "使用已下载的 " + cachedVer + " 安装包");
                        if (interactive) toast("使用已下载的 " + cachedVer + " 安装包");
                        installApk(apk);
                        return;
                    }
                    if (apk.exists()) {
                        log("本地安装包 " + cachedVer + " 不能直接安装（版本旧于远端 "
                                + rel[0] + "，或签名与已安装版本不一致），重新下载");
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
     * 缓存里的安装包能否覆盖安装到当前应用上。
     *
     * <p>判据是**签名必须与已安装版本一致** —— Android 只允许同签名的包覆盖安装，
     * 签名不同会直接「安装失败(-7)：与已安装应用签名不同」。
     *
     * <p>缓存复用如果只看版本号，遇到「同版本号重发」（例如换签名后重发）
     * 就会反复复用一个装不上的包：用户每次点更新都失败，而日志里
     * 只写着「使用已下载的 X 安装包」，看不出问题在哪。
     *
     * @return true 表示可以放心直接调起安装
     */
    private boolean cachedApkInstallable(File apk) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo installed = pm.getPackageInfo(
                    getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            android.content.pm.PackageInfo archive = pm.getPackageArchiveInfo(
                    apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (installed == null || archive == null
                    || installed.signatures == null || archive.signatures == null
                    || installed.signatures.length == 0
                    || archive.signatures.length != installed.signatures.length) {
                return false;
            }
            for (int i = 0; i < installed.signatures.length; i++) {
                if (!installed.signatures[i].equals(archive.signatures[i])) return false;
            }
            return true;
        } catch (Throwable t) {
            // 取不到就当"不能复用"：宁可重新下一次，也不要把装不上的包推给用户
            log("无法校验缓存安装包的签名（将重新下载）: " + t);
            return false;
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
        // 清单下载放在后台线程里，设一个**总时限**。
        // 国内网络下拉 GitHub 可能长时间无响应，而这一步在启动路径上 ——
        // 不设限就会一直卡在「正在准备运行环境」。
        // 超时后走「用上次缓存的清单」这条既有分支。
        final java.util.concurrent.atomic.AtomicReference<Throwable> dlErr =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
        Thread dl = new Thread(new Runnable() {
            @Override public void run() {
                try { download("manifest.json", mf); }
                catch (Throwable t) { dlErr.set(t); }
            }
        }, "manifest-fetch");
        dl.setDaemon(true);
        dl.start();
        try { dl.join(20000); } catch (InterruptedException ie) { }
        if (dl.isAlive()) {
            dl.interrupt();
            log("清单下载超时（20 秒），改用本地缓存");
            throw new IOException("清单下载超时");
        }
        if (dlErr.get() != null) throw dlErr.get();
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

        // 第一遍：逐分片判定 —— 哨兵是否匹配 + 修订号是否变高。
        // 判定逻辑在纯逻辑类 PayloadUpdate 里（37 项测试）。
        java.util.Set<String> appliedRevs = appliedRevisions();
        java.util.List<String> missing = new java.util.ArrayList<String>();
        java.util.LinkedHashMap<String, java.util.List<String>> removals =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject part = parts.getJSONObject(i);
            String name = part.getString("name");
            File dir = "dsh".equals(part.getString("target")) ? dshDir : toolsDir;
            org.json.JSONObject sent = part.getJSONObject("sentinel");
            File sf = new File(dir, sent.getString("path"));
            boolean matched = sf.exists()
                    && sf.length() == sent.getLong("size")
                    && sent.getString("sha256").equalsIgnoreCase(sha256(sf));

            int rev = part.optInt("revision", 0);
            int applied = PayloadUpdate.parseRevision(findRevision(appliedRevs, name));
            java.util.List<String> rm = new java.util.ArrayList<String>();
            org.json.JSONArray ra = part.optJSONArray("remove");
            if (ra != null) {
                for (int k = 0; k < ra.length(); k++) rm.add(ra.optString(k, ""));
            }
            PayloadUpdate.Part info = new PayloadUpdate.Part(name, rev, matched, rm);
            if (PayloadUpdate.needsWork(info, applied)) {
                missing.add(name);
                java.util.List<String> del = PayloadUpdate.removalsFor(info, applied);
                if (!del.isEmpty()) removals.put(name, del);
            }
        }
        log(PayloadUpdate.describe(parts.length(), missing.size()));
        if (missing.isEmpty()) {
            // 已是最新：把修订号记下来，否则每次都会重新判定
            rememberPayloadRevisions(parts, dshDir, toolsDir);
            return true;
        }
        setSplashStatus(PayloadUpdate.describe(parts.length(), missing.size()));

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

            // 处理前先执行该分片声明的删除。
            // 哨兵发现不了「文件被删了」，这份清单就是为它准备的。
            java.util.List<String> del = removals.get(name);
            if (del != null && !del.isEmpty()) {
                for (String rel : del) {
                    File victim = new File(dir, rel);
                    if (victim.exists()) {
                        deleteTree(victim);
                        log("  已移除 " + rel);
                    }
                }
            }

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
        rememberPayloadRevisions(parts, dshDir, toolsDir);
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
                        // toolsDirRef / nodeRef 在启动流程中才赋值。
                        // 不检查的话，启动未完成时安装插件会在后台线程 NPE，
                        // 而被 catch 吞掉、只显示「安装失败」，看不到原因。
                        if (pluginDshDir == null || toolsDirRef == null || nodeRef == null) {
                            toast("运行环境尚未就绪，请稍后再试");
                            return;
                        }
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
                                    warn ? 0xFFB26A00 : DshUi.TEXT_2()),
                            start, pr.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            int tail = pr.length();
            pr.append("App ").append(appVersion())
              .append("　运行包 ").append(payloadSummary());
            pr.setSpan(new android.text.style.ForegroundColorSpan(DshUi.TEXT_3()),
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
                        if (lastSessionStatus == SessionStatus.RUNNING
                                || lastSessionStatus == SessionStatus.AWAITING_APPROVAL) {
                            // 正在跑任务时重启会**直接中断它** —— 必须问一句。
                            // 原来这个按钮一点就走（nodeProcess.destroy()），
                            // 正在生成的回答、正在跑的 shell 调用全丢。
                            // 而 App 本来是知道运行状态的（lastSessionStatus）。
                            DshUi.confirm(MainActivity.this,
                                    "有任务正在运行",
                                    "重启会中断当前正在执行的任务，确定要重启吗？",
                                    "重启", new Runnable() {
                                @Override public void run() {
                                    toast("正在重启服务…");
                                    restartAgent();
                                }
                            });
                        } else {
                            toast("已保存，正在重启服务…");
                            restartAgent();
                        }
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
        bootInBackground("重启");
    }

    /**
     * 网页主题变化 → 原生跟随。
     *
     * <p>DSH 的主题是它自己的设置（`ui-theme.preference` = light / dark / system），
     * 与 Android 系统深色**相互独立** —— 只跟系统就会出现「网页黑、原生白」
     * 的同屏割裂（用户实测反馈：「你的深色模式设置了寂寞」）。
     *
     * <p>缓存到 prefs：下次启动时先用上次观察到的主题，避免开屏与界面
     * 在页面加载完成前闪一下另一种配色。
     */
    private void onWebTheme(final boolean dark) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("webDark", dark).apply();
        } catch (Throwable ignored) { }

        // 去抖：页面重载时会**先报一次旧主题、再报新主题**。
        // 每收到一次就重建，就会形成：
        //   重载 → 报浅色 → 重建 → 重载 → 报深色 → 重建 → …（无限）
        // 用户遇到的「深浅反复横跳、不停重启」就是这么来的。
        //
        // 注意：主题观察者**只在变化时上报**，所以不能用「连续两次相同」
        // 来判断稳定 —— 那样永远不会触发。正确做法是**延时复核**：
        // 记下这次的值与时刻，等一会儿再看它有没有被新的上报取代。
        pendingWebDark = dark;
        final long stamp = ++webDarkStamp;
        pendingWebDarkAt = System.currentTimeMillis();
        if (DshUi.isDark() == dark) return;   // 已经一致，不必安排

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    // 期间又变了 → 说明页面还在晃动，这次不处理
                    if (stamp != webDarkStamp) return;
                    if (pendingWebDark != dark) return;
                    if (DshUi.isDark() == dark) return;
                    if (isFinishing()) return;
                    log("网页主题稳定为：" + (dark ? "深色" : "浅色") + " → 重建界面");
                    DshUi.setDark(dark);
                    if (shouldAllowRecreate()) recreate();
                } catch (Throwable t) {
                    log("主题切换重建失败: " + t);
                }
            }
        }, 1200);
    }

    /** 最近上报的网页主题（延时复核用）。 */
    /** 上报序号：新上报会让旧的延时任务失效。 */
    private long webDarkStamp;
    /** 最近一次上报的时刻。 */
    private long pendingWebDarkAt;


    /** 最近上报的网页主题（去抖用）。 */
    private boolean pendingWebDark;
    /** 同一主题连续上报了几次。 */
    private int webDarkStableCount;


    /**
     * 重建节流 + 死循环自救。
     *
     * <p>主题切换需要重建界面，而重建本身又会带来配置变化 ——
     * 只要两者对「应该是什么主题」的判断不一致，就会无限循环。
     * 用户实际遇到过：界面在深浅之间反复横跳、应用不停重启，
     * 而且**重装无效**（触发它的设置存在应用数据里，不随安装包改变）。
     *
     * <p>这里做两件事：
     * <ol>
     *   <li>短时间内重建太多次 → <b>拒绝重建</b>，先让界面停下来；</li>
     *   <li>启动本身也太频繁（说明重启循环已经形成）→
     *       <b>清掉主题偏好</b>，回到跟随系统 —— 给用户一条自救路径。</li>
     * </ol>
     */
    private boolean shouldAllowRecreate() {
        long now = System.currentTimeMillis();
        try {
            android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            // 用「上一个重建时刻 + 这一窗口内的次数」记在 prefs 里 ——
            // 实例字段每次重建都会被重置，起不到限流作用（踩过）。
            long windowStart = p.getLong("recreateWindowStart", 0L);
            int count = p.getInt("recreateCount", 0);
            if (now - windowStart > 30000L) {
                windowStart = now;
                count = 0;
            }
            count++;
            p.edit().putLong("recreateWindowStart", windowStart)
                    .putInt("recreateCount", count).apply();

            if (count <= 4) return true;
            log("30 秒内已重建 " + count + " 次，停止重建以避免死循环");
            // 不再清除 webDark —— 之前那样做反而让循环继续：
            // 清掉后 onCreate 改用系统主题，页面仍会报它自己的主题，
            // 两者不一致 → 继续重建。
            return false;
        } catch (Throwable t) {
            return true;
        }
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
    /**
     * 状态栏透明 + 内容延伸上去 + **与当前主题相配**的系统栏图标（消除顶部黑边）。
     *
     * <p>图标明暗必须跟着主题走：浅色下用深色图标（LIGHT_* 标志），
     * 深色下用浅色图标 —— 否则 #151517 的深色页面上会压一排黑色图标，
     * 等于把状态栏和导航栏一起「吃掉」。
     */
    private void applySystemBars() {
        try {
            android.view.Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(DshUi.BG());
            int vis = android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!DshUi.isDark()) {
                vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                // 导航栏图标的明暗开关是 API 26 才有的；主题里
                // windowLightNavigationBar=true，深色下必须靠「不再置位」把它去掉。
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vis |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            w.getDecorView().setSystemUiVisibility(vis);
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
        // 开屏页铺满整屏，用页面底色而不是写死的白 —— 深色下白屏一闪很刺眼。
        box.setBackgroundColor(DshUi.BG());

        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        // 鲸鱼标志
        android.widget.ImageView logo = new android.widget.ImageView(this);
        int id = getResources().getIdentifier(
                "ic_launcher_foreground", "mipmap", getPackageName());
        if (id > 0) logo.setImageResource(id);
        // 鲸鱼是纯装饰：让读屏跳过它，而不是念出一个无意义的图标名
        logo.setImportantForAccessibility(
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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
                    DshUi.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) { }
        android.widget.LinearLayout.LayoutParams slp =
                new android.widget.LinearLayout.LayoutParams(
                        (int) (30 * d), (int) (30 * d));
        slp.topMargin = (int) (26 * d);
        col.addView(spin, slp);

        // 文案
        splashStatus = new android.widget.TextView(this);
        splashStatus.setText("正在启动 DeepSeek Harness");
        splashStatus.setTextColor(DshUi.TEXT_2());
        splashStatus.setTextSize(13.5f);
        // 启动进度对读屏用户必须能听到（"正在下载运行包 40%"这类变化），
        // 否则整个启动过程对他们是一片沉默。
        splashStatus.setAccessibilityLiveRegion(
                android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
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
        int skipped = 0;
        for (String path : entries) {
            String rel = path.substring("payload/".length());
            if (rel.length() == 0) continue;
            File out = new File(target, rel);
            if (!refresh && out.exists() && out.length() > 0) continue;   // 版本未变，跳过
            // 版本变了，但**内容未必变**。
            //
            // 原来只要版本号不同就把 93MB 全部重写一遍 —— 其中包括 47MB 的 node
            // 和 31MB 的 ICU 数据，而这两个几乎从不变化。用户每次升级 APK 后
            // 的首次启动都要为此多花几十秒的写入。
            //
            // 现在分两类：
            //   小文件（脚本、证书，KB 级）—— 直接重写，省得比对；
            //   大文件（二进制、库）—— 先比大小再比摘要，一致就跳过。
            boolean isSmall = isSmallAsset(rel);
            if (refresh && !isSmall && out.exists() && out.length() > 0
                    && sameAsset(path, out)) {
                skipped++;
                continue;
            }
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
        if (refresh && skipped > 0) {
            log("  已跳过 " + skipped + " 个内容未变的文件（省下重复写入）");
        }
        try { writeText(verFile, curVer); } catch (Throwable ignored) { }
    }

    /** 小文件（脚本、证书）直接重写，不做摘要比对 —— 比对本身也要读一遍。 */
    private static boolean isSmallAsset(String rel) {
        return rel.endsWith(".js") || rel.endsWith(".py") || rel.endsWith(".crt")
                || rel.endsWith(".json") || rel.endsWith(".txt") || rel.endsWith(".sh");
    }

    /**
     * APK 内的资源与磁盘上的文件是否一致。
     *
     * <p>先比大小（便宜），再比 sha256（稍贵但准确）。
     * 只比大小是不够的：同大小的不同构建会被漏掉，而那正是升级后必须更新的情况。
     */
    private boolean sameAsset(String assetPath, File disk) {
        try {
            // 一次遍历同时算大小与摘要 —— 不必为此把资源读两遍
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            long size = 0;
            java.io.InputStream in = getAssets().open(assetPath);
            try {
                byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                    size += n;
                }
            } finally {
                in.close();
            }
            if (size != disk.length()) return false;

            java.security.MessageDigest md2 = java.security.MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream fis = new java.io.FileInputStream(disk);
            try {
                byte[] buf = new byte[262144];
                int n;
                while ((n = fis.read(buf)) > 0) md2.update(buf, 0, n);
            } finally {
                fis.close();
            }
            return java.util.Arrays.equals(md.digest(), md2.digest());
        } catch (Throwable t) {
            // 比对失败就当作不同 —— 宁可多写一次，也不要留下过期的二进制
            return false;
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
        // 不再"只允许画一次"。
        //
        // 原来第一行是 `if (statusPageLoading) return;`，而这个标志永不复位 ——
        // 启动流程最早调用它时写的是「正在启动 DSH …」，顺手把门闩锁死，
        // 之后所有更新（「DSH 启动失败」「已等待 N 秒」「启动超时」）
        // 全被这一个 return 丢掉，页面永远停在最开始那句「正在启动…」上，
        // 而开屏又盖着它 —— 用户就是"一直转圈、永远没变化"。
        // 状态页本来就是"最新情况优先"，允许覆盖更新。
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

    /**
     * 日志队列：写盘由一条专门的消费者线程做，调用方只入队。
     *
     * <p><b>为什么必须这样</b>：原来 {@code log()} 在**调用线程**里直接
     * open/write/close 文件。而 {@code onConsoleMessage} 是 **UI 线程回调**，
     * 每一条 {@code [dsh-api]} 响应都要写一次 —— 实测日志里 72% 的字节
     * 来自这类行。也就是说：网页每发一个请求，主线程就要去 /sdcard 上
     * 做一次同步文件写。会话一忙，主线程就被这些慢速 I/O 切碎，
     * 表现出来正是"窗口卡住、点什么都没反应"。
     *
     * <p>有界队列：满了就丢日志。日志丢几条无所谓，卡住主线程不行。
     */
    private final java.util.concurrent.BlockingQueue<String> logQueue =
            new java.util.concurrent.LinkedBlockingQueue<String>(4000);
    private volatile boolean logWorkerStarted;
    private final java.util.concurrent.atomic.AtomicInteger droppedLogLines =
            new java.util.concurrent.atomic.AtomicInteger();

    private void log(final String msg) {
        Log.i(TAG, msg);
        if (msg == null) return;
        if (!logWorkerStarted) startLogWorker();
        // offer 而非 put：队列满时立即返回，绝不阻塞调用方（尤其是 UI 线程）
        if (!logQueue.offer(msg)) {
            droppedLogLines.incrementAndGet();
        }
    }

    /**
     * 主线程卡顿看门狗。
     *
     * <p><b>为什么需要它</b>：用户报「窗口完全卡死，点什么都没反应」，
     * 而现有日志里既没有崩溃、也没有 ANR 记录、更没有时间戳 ——
     * 完全无法判断卡了多久、卡在哪一步，只能靠猜。这次就是如此。
     *
     * <p>做法：每秒往主线程 post 一个 ping，若 5 秒收不回来，
     * 就记一条带时长的日志；恢复后等一分钟再继续，避免刷屏。
     * 下次再卡，日志里会有「[卡顿] 主线程已阻塞 N 秒」这条硬证据。
     */
    private void startUiWatchdog() {
        final android.os.Handler ui =
                new android.os.Handler(android.os.Looper.getMainLooper());
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    final long sent = System.currentTimeMillis();
                    final java.util.concurrent.CountDownLatch done =
                            new java.util.concurrent.CountDownLatch(1);
                    if (!ui.post(new Runnable() {
                        @Override public void run() { done.countDown(); }
                    })) {
                        return;   // 主线程已退出
                    }
                    try {
                        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            long ms = System.currentTimeMillis() - sent;
                            log("[卡顿] 主线程已阻塞 " + (ms / 1000) + " 秒（看门狗）");
                            // 等它恢复，避免卡顿时刷屏；最多等一分钟
                            done.await(60, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "dsh-ui-watchdog");
        t.setDaemon(true);
        t.start();
        log("已启动主线程卡顿看门狗（阻塞超过 5 秒会记日志）");
    }

    private synchronized void startLogWorker() {
        if (logWorkerStarted) return;
        logWorkerStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    String m;
                    try {
                        m = logQueue.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        int dropped = droppedLogLines.getAndSet(0);
                        if (dropped > 0) {
                            appendSharedLog("（日志队列满，丢弃了 " + dropped + " 条）");
                        }
                        appendSharedLog(m);
                    } catch (Throwable ignored) {
                        // 日志写失败不能反过来影响功能
                    }
                }
            }
        }, "dsh-log-writer");
        t.setDaemon(true);
        t.start();
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
            w.write("APK 版本: 0.25.5\n");
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
        // 系统切深浅色本就会重建 Activity（清单的 configChanges 不含 uiMode）。
        // 但个别 ROM 会把 uiMode 塞进一次「已声明由应用处理」的配置变化里一起下发，
        // 那时这里不纠正就会停在旧主题 —— DshUi 的颜色是在建视图时取走的，
        // 改标志位对已有视图无效，只能重建。
        // 主题来源必须和 onCreate 保持一致 —— 否则会死循环。
        //
        // 踩过的坑（用户实际遇到：界面在深浅之间反复横跳、不停重启）：
        //   onCreate            → 有 webDark 就用【网页主题】
        //   onConfigurationChanged → 无条件用【系统主题】覆盖
        // 而 recreate() 本身会带来一次配置变化，于是：
        //   重建 → 配置变化 → 系统主题覆盖网页主题 → 不一致 → 再重建 → …
        // 无限循环。
        //
        // 现在两边同一套规则：网页上报过主题就只认它。
        boolean wasDark = DshUi.isDark();
        android.content.SharedPreferences tp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (tp.contains("webDark")) {
            DshUi.setDark(tp.getBoolean("webDark", false));
        } else {
            DshUi.applyTheme(this);
        }
        if (DshUi.isDark() != wasDark) {
            log("深色模式变化：重建界面以应用新主题");
            if (shouldAllowRecreate()) {
                recreate();
            } else {
                log("重建过于频繁，已跳过（防止深浅色死循环）");
            }
            return;
        }
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
            File ws = workspace;
            if (ws == null) {
                // 兜底：工作区不可用（共享存储没挂上、权限被拒）时，
                // 落到应用私有目录，而不是把用户分享过来的文件直接丢掉。
                // 用户至少还能在「设置 → 文件」里找到它，agent 也能读到。
                ws = new File(getFilesDir(), "workspace");
                //noinspection ResultOfMethodCallIgnored
                ws.mkdirs();
                log("工作区不可用，分享内容改落到私有目录: " + ws);
                toast("工作区不可用，已存到应用私有目录");
            }
            if (!ws.isDirectory()) {
                toast("无法创建接收目录，分享内容未保存");
                log("错误: 接收目录不可用: " + ws);
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
                // 拷贝放到后台线程：分享过来的可能是几百 MB 的视频，
                // 在 UI 线程里拷会直接 ANR（这也是原来就存在的隐患）。
                final File wsFinal = ws;
                final File out = new File(wsFinal, name);
                final android.net.Uri src = stream;
                final String finalName = name;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            java.io.InputStream in = getContentResolver().openInputStream(src);
                            if (in == null) throw new IOException("无法读取分享的文件");
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                            byte[] buf = new byte[65536];
                            int k;
                            while ((k = in.read(buf)) > 0) fo.write(buf, 0, k);
                            fo.close();
                            in.close();
                            log("已接收分享文件: " + out.getAbsolutePath());
                            toast("已放入工作区：" + finalName);
                        } catch (Throwable t) {
                            log("错误: 保存分享文件失败: " + t);
                            toast("接收分享文件失败");
                        }
                    }
                }).start();
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

    /**
     * 返回键：确认后退出应用。
     *
     * <p>原来的实现是「网页能后退就后退」—— 那对多页网站是对的，
     * 但 DSH 是**单页应用**：它的网页历史里是各种界面状态，
     * 而不是用户理解的「上一页」。按返回键会退到一个过期甚至空白的页面，
     * 看起来就像 App 卡住或重置了。
     *
     * <p>对单页应用，返回键的合理语义只有一个：**退出**。
     * 退出前确认一下，避免误触。
     */
    @Override
    public void onBackPressed() {
        // 已经退到后台（用户连按两次）就直接退，不再重复弹框
        long now = System.currentTimeMillis();
        if (now - lastBackPressed < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressed = now;

        android.widget.LinearLayout box = DshUi.paddedBody(this);
        box.addView(DshUi.title(this, "退出 DeepSeek Harness？"));
        box.addView(DshUi.hint(this,
                "退出后 agent 会在后台继续运行（通知栏可以看到状态），"
                + "下次打开会立即回到当前界面。"), DshUi.fullWidth(this, 8));
        android.widget.Button cancel = DshUi.button(this, "取消", false);
        android.widget.Button exit = DshUi.button(this, "退出", true);
        final android.app.Dialog d = DshUi.dialog(this, box,
                DshUi.footer(this, cancel, exit), 360);
        cancel.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { d.dismiss(); }
        });
        exit.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                d.dismiss();
                // 退到后台而不是销毁：进程留着，agent 继续跑，
                // 再打开时能立刻回到原界面
                moveTaskToBack(true);
            }
        });
        d.show();
    }

    /** 上一次按返回键的时间（用于「再按一次退出」与防止重复弹框）。 */
    private long lastBackPressed;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nodeProcess != null) nodeProcess.destroy();
    }
}
