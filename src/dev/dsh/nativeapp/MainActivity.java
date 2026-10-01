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
            "https://github.com/yusheng266186-beep/DSH-Native/releases/download/payload-v12/";
    /**
     * payload-v12 的 manifest.json 固定摘要。
     *
     * <p>摘要内置在 APK，而不是从同一个镜像下载，代理即使同时替换清单和归档
     * 也无法通过验证。更换 payload tag 或清单内容时必须同步更新这个值。
     */
    private static final String PAYLOAD_MANIFEST_SHA256 =
            "d0806178368ed5f4ed864c8924bb6a56a5fb526d413f8e355884751ade7fee78";
    /** 用于检查 App 自身更新的仓库。 */
    private static final String REPO = "yusheng266186-beep/DSH-Native";

    /**
     * 版本清单来源。
     *
     * <p>**刻意不用 GitHub API** —— 未认证请求限 60 次/小时且按 IP 计，
     * 手机流量多为运营商 NAT 共享 IP，实测已直接返回 403。
     * 改为读取仓库里的静态 latest.json（走 CDN，无此限制）。
     */
    private static final String RAW_ROOT = "https://raw.githubusercontent.com/"
            + REPO + "/main/";

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
    private File crashFile;
    /** 私有运行日志；只有用户主动导出诊断时才复制到共享存储。 */
    private File sharedLog;
    private final Object logLock = new Object();
    /** Activity 销毁时统一停止会永久等待的后台线程，避免主题重建后泄漏旧界面。 */
    private final WorkerRegistry activityWorkers = new WorkerRegistry();
    /** 静态 DshUi 日志入口必须能按实例解除，不能永久持有旧 Activity。 */
    private final DshUi.LogSink dshUiLogSink = new DshUi.LogSink() {
        @Override public void log(String msg) { MainActivity.this.log(msg); }
    };

    /**
     * Service 只弱引用这个监听器；Activity 重建时旧界面不会被进程输出线程持有。
     */
    private final HarnessService.Listener harnessListener =
            new HarnessService.Listener() {
        @Override public void onOutputLine(String line) {
            log("[dsh] " + line);
        }

        @Override public void onStopRequested() {
            log("收到停止请求，运行进程已结束");
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 原生外壳语言必须在创建任何 DshUi 组件前确定。
        // DSH 网页有自己的语言设置，两者互不覆盖。
        configureUiLanguage();

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

        // 任务计时与历史不再绑定 Activity 实例。若上次进程在任务中被系统回收，
        // 先恢复同一轮起点，随后由页面的权威会话状态确认「继续」或「结束」。
        restoreTaskState();

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
        // 日志写入 logcat 与应用私有目录；只有用户主动导出时才进入共享存储。
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
                // 上一次完成时的淡出可能还在跑。若不取消，新页面刚开始加载，
                // 旧动画会继续把进度条淡掉，用户看不到这次导航的反馈。
                pb.animate().cancel();
                pb.setAlpha(1f);
                pb.setProgress(newProgress);
                if (newProgress >= 100) {
                    if (DshUi.animationsEnabled(MainActivity.this)) {
                        pb.animate().alpha(0f).setDuration(260).start();
                    } else {
                        pb.setAlpha(0f);
                    }
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
                    if (SessionRecovery.isFailure(m)) {
                        onSessionRecoveryFailure(m);
                        return true;
                    }
                    if (m.indexOf("[dsh-native] new-session-not-found") >= 0) {
                        log("会话恢复：未找到新建会话按钮");
                        toast("未找到新建会话入口，请从侧栏手动新建");
                        return true;
                    }
                    if (m.indexOf("[dsh-native] new-session-clicked") >= 0) {
                        log("会话恢复：已点击新建会话");
                        return true;
                    }
                    if (WebToolsEntry.isReady(m)) {
                        onWebToolsEntryReady();
                        return true;
                    }
                    if (WebToolsEntry.isMissing(m)) {
                        onWebToolsEntryMissing();
                        return true;
                    }
                    if (SessionOrganizer.isSearchReady(m)) {
                        log("会话管理：已聚焦搜索框");
                        return true;
                    }
                    if (SessionOrganizer.isArchiveReady(m)) {
                        log("会话管理：已打开归档筛选");
                        return true;
                    }
                    if (SessionOrganizer.isUnsupported(m)) {
                        log("会话管理：当前 DSH 页面未找到对应入口");
                        toast(UiText.t("未找到会话入口，请先展开左侧栏",
                                "Session control not found. Expand the sidebar first."));
                        return true;
                    }
                    // 手势入口：长按顶部区域打开设置（见注入脚本里的说明）
                    if (m.indexOf("[dsh-native] open-settings") >= 0) {
                        log("WebUI / 手势入口：打开工具与设置");
                        showSettings();
                        return true;
                    }
                    if (m.indexOf(WebToolsEntry.REFRESH_MODELS_MARKER) >= 0) {
                        log("WebUI / 更新模型列表：直接同步已配置服务商的上游目录");
                        ModelCenterPanel.refreshConfigured(MainActivity.this, modelCenterHost(false));
                        return true;
                    }
                    if (m.indexOf("[dsh-native] share-task-sent") >= 0) {
                        log("分享任务已提交到 DSH");
                        finishShareTaskSubmission(
                                UiText.t("分享任务已提交", "Shared task submitted"),
                                DshUi.RESULT_SUCCESS);
                        return true;
                    }
                    if (m.indexOf("[dsh-native] share-task-prefilled") >= 0) {
                        log("分享任务已填入编辑器，发送按钮暂不可用");
                        finishShareTaskSubmission(
                                UiText.t("任务已填入，请确认后发送",
                                        "Task filled in. Review and send it."),
                                DshUi.RESULT_WARNING);
                        return true;
                    }
                    if (m.indexOf("[dsh-native] share-task-editor-not-found") >= 0
                            || m.indexOf("[dsh-native] share-task-error") >= 0) {
                        log("分享任务无法自动提交: " + m);
                        finishShareTaskSubmission(
                                UiText.t("文件已保存，但未找到任务输入框",
                                        "Files saved, but the task editor was not found."),
                                DshUi.RESULT_ERROR);
                        return true;
                    }
                    // 网页主题上报：原生跟着 DSH 自己的主题走，
                    // 而不是跟 Android 系统深色（两者相互独立）
                    if (m.indexOf("[dsh-theme] dark=") >= 0) {
                        onWebTheme(m.indexOf("dark=1") >= 0);
                        return true;
                    }
                    // WebSocket 生命周期由最早期只读包装器上报。它不包含 URL、
                    // 消息或令牌，只用于让通知栏和任务中心区分「系统有网」与
                    // 「DSH 长连接真的可用」。
                    int connection = ConnectionRecovery.parseConsole(m);
                    if (connection >= 0) {
                        onConnectionState(connection, "页面探针");
                        return true;
                    }
                    if (DraftRecovery.isRestored(m)) {
                        log("[草稿] 已恢复未发送内容");
                        if (!draftRestoreToastShown) {
                            draftRestoreToastShown = true;
                            toast(UiText.t("已恢复上次未发送的草稿",
                                    "Restored your unsent draft"));
                        }
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
                    // 会话列表的首次请求通常发生在 onPageFinished 之前。
                    // 在这里先接管 fetch，才能可靠获得任务开始时间与结束状态。
                    if (url != null && url.indexOf("127.0.0.1") >= 0) {
                        view.setInitialScale(0);
                        connectionState = ConnectionRecovery.CONNECTING;
                        installSessionProbe(view);
                        installConnectionWatcher(view);
                        installDraftRecovery(view);
                    }
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
                installSessionProbe(view);
                installConnectionWatcher(view);
                installDraftRecovery(view);
                installSessionRecoveryWatcher();
                installStatusWatcher();
                installWebToolsEntry();
                // 首次探针还没有给出真实状态时，推一次「正在获取状态」。
                //
                // 前台服务的占位通知并不知道有没有任务在跑，不该让它一直挂着 ——
                // 实测过一次：App 更新后服务被重建，占位文案「正在运行」就那么
                // 一直显示着，而对话早已结束。
                // 探针现在嵌入 index.html，真实状态可能已经在 onPageFinished 前到达；
                // 此时绝不能再用 UNKNOWN 把它覆盖掉。
                if (lastSessionStatus == SessionStatus.UNKNOWN) {
                    pushStatus(SessionStatus.UNKNOWN);
                }
                final String sharedPrompt = pendingSharedTaskPrompt;
                if (sharedPrompt != null) {
                    pendingSharedTaskPrompt = null;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                        @Override public void run() { dispatchShareTaskPrompt(sharedPrompt); }
                    }, 800);
                }
                if (dshPageLoaded) {
                    log("DSH 界面已重新加载，状态采集脚本已重新注入");
                    return;
                }
                dshPageLoaded = true;
                log("DSH 界面已加载，收起开屏");
                final String act = pendingAction;
                pendingAction = "";
                final boolean resumeOnboarding = shouldResumeModelOnboarding();
                if (pendingOpenSettings || act.length() > 0 || resumeOnboarding) {
                    pendingOpenSettings = false;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(new Runnable() {
                        @Override public void run() {
                            if ("log".equals(act)) {
                                showLog();
                            } else if ("onboarding".equals(act) || resumeOnboarding) {
                                showModelOnboarding();
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
        DshUi.setLogSink(dshUiLogSink);

        setContentView(root);

        initSharedLog();
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

        // 新安装先解释下载量、权限和配置入口，再开始百兆级运行包下载。
        // 升级用户若已有 .dsh 数据则直接进入，绝不会被补弹“首次使用”。
        if (shouldShowFirstRunGuide()) {
            showFirstRunGuide();
        } else {
            requestStoragePermission();
            bootInBackground("启动");
        }
    }

    /** Resolve the native-shell language before any views are created. */
    private void configureUiLanguage() {
        String preference = UiText.AUTO;
        try {
            preference = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("uiLanguage", UiText.AUTO);
        } catch (Throwable ignored) { }
        UiText.configure(preference, java.util.Locale.getDefault().getLanguage());
    }

    private String uiLanguagePreference() {
        try {
            return UiText.normalize(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("uiLanguage", UiText.AUTO));
        } catch (Throwable ignored) {
            return UiText.AUTO;
        }
    }

    private void applyUiLanguage(String preference) {
        String normalized = UiText.normalize(preference);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("uiLanguage", normalized).apply();
        UiText.configure(normalized, java.util.Locale.getDefault().getLanguage());
    }

    /** Do not show a new onboarding screen to people upgrading an existing install. */
    private boolean shouldShowFirstRunGuide() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean completed = p.getBoolean("firstRunGuideCompleted", false);
        File existingHome = new File(new File(getFilesDir(), "dsh"), ".dsh");
        boolean existing = existingHome.isDirectory();
        boolean show = UiText.shouldShowFirstRun(completed, existing);
        if (!show && !completed && existing) {
            p.edit().putBoolean("firstRunGuideCompleted", true).apply();
        }
        return show;
    }

    /** 新安装下载中断后仍应恢复真正的账号配置；升级用户从不写入此标记。 */
    private boolean shouldResumeModelOnboarding() {
        try {
            android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            return UiText.shouldResumeModelSetup(
                    p.getBoolean("modelOnboardingPending", false),
                    p.getBoolean("modelOnboardingCompleted", false));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * First-run guide shown before the large runtime download begins.
     * Language changes rebuild only this dialog; the agent process is not started or restarted.
     */
    private void showFirstRunGuide() {
        final android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("欢迎使用 DeepSeek Harness", "Welcome to DeepSeek Harness")));
        body.addView(DshUi.hint(this,
                UiText.t("首次使用只需完成三步", "Get started in three steps")),
                DshUi.fullWidth(this, 5));

        addFirstRunStep(body, "1",
                UiText.t("准备运行环境", "Prepare the runtime"),
                UiText.t("首次启动会下载约 117 MiB 的 DSH 与工具链，建议预留至少 550 MiB 空间并连接 Wi-Fi。",
                        "The first launch downloads about 117 MiB of DSH and tools. Keep at least 550 MiB free and use Wi-Fi when possible."));
        addFirstRunStep(body, "2",
                UiText.t("选择权限", "Choose permissions"),
                UiText.t("共享存储用于工作区、导入和诊断导出；通知用于显示后台任务状态。拒绝后仍可使用私有工作区。",
                        "Shared storage enables the workspace, imports, and diagnostic exports. Notifications show background task status. Private storage still works if you decline."));
        addFirstRunStep(body, "3",
                UiText.t("填写账号", "Add your account"),
                UiText.t("运行环境就绪后会自动打开“模型中心”，完成连接检测即可开始使用。",
                        "When the runtime is ready, Model center opens so you can check the provider and start."));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("选择界面语言", "Choose interface language")),
                DshUi.fullWidth(this, 22));
        android.widget.LinearLayout languageRow = new android.widget.LinearLayout(this);
        languageRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final String currentPreference = uiLanguagePreference();
        final android.widget.Button auto = DshUi.toggleButton(this,
                UiText.t("跟随系统", "System"), UiText.AUTO.equals(currentPreference));
        final android.widget.Button zh = DshUi.toggleButton(this,
                "中文", UiText.ZH.equals(currentPreference));
        final android.widget.Button en = DshUi.toggleButton(this,
                "English", UiText.EN.equals(currentPreference));
        addEqualButton(languageRow, auto, 0);
        addEqualButton(languageRow, zh, 6);
        addEqualButton(languageRow, en, 6);
        body.addView(languageRow, DshUi.fullWidth(this, 8));

        final android.widget.Button start = DshUi.button(this,
                UiText.t("开始配置", "Start setup"), true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, start), 680);
        dialog.setCancelable(false);
        auto.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.choiceActivated(v);
                dialog.dismiss(); applyUiLanguage(UiText.AUTO); showFirstRunGuide();
            }
        });
        zh.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.choiceActivated(v);
                dialog.dismiss(); applyUiLanguage(UiText.ZH); showFirstRunGuide();
            }
        });
        en.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.choiceActivated(v);
                dialog.dismiss(); applyUiLanguage(UiText.EN); showFirstRunGuide();
            }
        });
        start.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("firstRunGuideCompleted", true)
                        .putBoolean("modelOnboardingPending", true)
                        .apply();
                pendingAction = "onboarding";
                dialog.dismiss();
                requestStoragePermission();
                bootInBackground("首次配置");
            }
        });
        dialog.show();
    }

    private void addFirstRunStep(android.widget.LinearLayout body, String number,
                                 String title, String detail) {
        body.addView(DshUi.sectionLabel(this, number + ". " + title),
                DshUi.fullWidth(this, 18));
        body.addView(DshUi.hint(this, detail), DshUi.fullWidth(this, 5));
    }

    private void addEqualButton(android.widget.LinearLayout row,
                                android.widget.Button button, int leftMarginDp) {
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = DshUi.dp(this, leftMarginDp);
        row.addView(button, lp);
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
            final boolean payloadFailure = isPayloadFailure(reason);
            String shown = reason == null ? "未知原因" : reason;
            if (shown.length() > 220) shown = shown.substring(0, 220) + "…";
            setSplashStatus((payloadFailure ? "运行包准备失败：" : "启动未完成：")
                    + shown + "\n（点按此处查看日志）");
            if (splashView != null) {
                splashView.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) { hideSplash(); }
                });
            }
            // 开屏下方铺一张说明页：即便开屏因为任何原因没收起，
            // 用户点一下也能看到完整原因与出路
            String safeReason = htmlEscape(reason == null ? "未知原因" : reason);
            String actions;
            if (payloadFailure) {
                actions = "运行包下载或校验没有完成。可以重新尝试下载，已存在的配置不会被删除。<br><br>"
                        + "<a href=\"dsh-retry://payload\">重新下载运行包</a>　"
                        + "<a href=\"dsh-retry://log\">打开运行日志</a><br><br>"
                        + "<a href=\"dsh-retry://boot\">重试启动</a>";
            } else {
                actions = "可以这样处理：<br>"
                        + "1. 点下面的「重试」再启动一次<br>"
                        + "2. 回到通知栏 →「设置」→ 更新运行包<br>"
                        + "3. 打开「运行日志」看具体原因<br><br>"
                        + "<a href=\"dsh-retry://boot\">重试启动</a>　"
                        + "<a href=\"dsh-retry://log\">打开运行日志</a>";
            }
            showStatus(payloadFailure ? "运行包准备失败" : "启动未完成",
                    "<code>" + safeReason + "</code><br><br>" + actions);
            // 8 秒后自动收起开屏，露出说明页 —— 不该让用户去猜"要点一下"
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(new Runnable() {
                @Override public void run() { hideSplash(); }
            }, 8000);
        } catch (Throwable t) {
            log("显示启动失败信息时出错: " + t);
        }
    }

    /** 启动失败是否发生在运行包清单、下载、校验或解压阶段。 */
    private boolean isPayloadFailure(String reason) {
        if (reason == null) return false;
        String s = reason.toLowerCase(java.util.Locale.ROOT);
        return s.indexOf("运行包") >= 0 || s.indexOf("清单") >= 0
                || s.indexOf("下载失败") >= 0 || s.indexOf("manifest") >= 0
                || s.indexOf("sha-256") >= 0 || s.indexOf("解压") >= 0
                || s.indexOf("payload") >= 0;
    }

    /** 把启动失败原因放进状态页 HTML 前先编码，避免异常文本破坏页面。 */
    private String htmlEscape(String text) {
        return WebUrl.escapeHtml(text);
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

        if (!supportsArm64Runtime()) {
            return "此版本只包含 arm64-v8a 运行环境；当前设备架构为 "
                    + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS);
        }

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
        boolean updatePending = payloadUpdatePending()
                || (locallyComplete && validatePayloadManifest(new File(root, "manifest.json")) != null);
        boolean rollbackHold = payloadRollbackHold();
        if (locallyComplete && rollbackHold) {
            log("运行包已恢复到上一版本，自动更新暂缓；可在更新与维护中手动重试");
            setSplashStatus("正在使用已恢复的运行环境…");
        } else if (locallyComplete && !updatePending) {
            log("本地运行包完整，直接启动（更新检查移至后台）");
            checkPayloadInBackground(node, root, dshDir, toolsDir);
            setSplashStatus("正在准备运行环境…");
        } else {
            if (updatePending) log("上次检查到运行包有更新，本次启动应用");
            try {
                boolean upToDate = ensurePayload(node, root, dshDir, toolsDir);
                clearPayloadUpdatePending();
                if (upToDate) {
                    log("运行包已是最新，本次无需下载");
                    setSplashStatus("正在准备运行环境…");
                }
            } catch (Throwable updateError) {
                // 已有一套完整环境时，更新失败不能把 App 一起锁死。
                // 保留旧运行包继续启动，后台稍后会重新检查；首次安装则仍要报错。
                // 五 D 的自动恢复完成后再复核关键文件；若恢复本身也失败，绝不能
                // 继续拿“更新前曾经完整”这个旧结论启动一套可能已损坏的环境。
                if (!locallyComplete || !payloadLocallyComplete(dshDir, toolsDir)) {
                    if (updateError instanceof Exception) throw (Exception) updateError;
                    throw new Exception(updateError);
                }
                clearPayloadUpdatePending();
                payloadLastError = shorten(updateError);
                log("运行包更新失败，保留当前可用版本继续启动: " + updateError);
                setSplashStatus("更新失败，正在使用现有运行环境…");
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

        Process started = pb.start();
        HarnessService.adoptProcess(started);
        log("dsh web 已启动并交由前台服务监管 (pid " + pidOf(started) + ")");

        // 5. 等待服务就绪后加载界面
        String url = waitForServer();
        // 兜底：45 秒后无论如何都收起开屏，避免任何情况下界面被永久挡住
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
            @Override public void run() { hideSplash(); }
        }, 45000);
        if (url == null) {
            // 没抓到带 token 的地址，但端口若已响应仍尝试加载（会看到 401 页而非空白）
            if (probeHttp(chosenPort) > 0) {
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

    /** 内置 Node 与工具链目前只提供 64 位 ARM 构建。 */
    private static boolean supportsArm64Runtime() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis == null) return false;
        for (String abi : abis) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private static String formatMib(long bytes) {
        if (bytes < 0L) bytes = 0L;
        return String.format(java.util.Locale.ROOT, "%.0f MiB", bytes / 1048576.0d);
    }

    /** 加载 DSH 界面（复用实例与新建实例都走这里）。 */
    private void loadDshUi(String url) {
        log("界面就绪: " + url);
        rememberDsh(url);
        final String target = url;
        statusPageLoading = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                WebView view = webView;
                if (view == null || isFinishing() || isDestroyed()) return;
                // 清除上一页双指缩放留下的页面比例。index.html 不再锁死
                // initial-scale，0 会让 overview 模式按当前屏宽做 fit-to-width。
                view.setInitialScale(0);
                view.loadUrl(target);
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
        return LocalServerProbe.readAuthenticatedIndex(url, timeoutMs);
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
        return WebUrl.looksLikeRealUrl(url);
    }

    /** URL 太长时截断，只用于日志与提示。 */
    private static String briefUrl(String url) {
        return WebUrl.brief(url);
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
    private static final int MAX_AUTO_RELOADS = ConnectionRecovery.MAX_AUTO_RELOADS;
    /** 断开多久后才考虑自动刷新。DSH 自己会重连，给它足够时间。 */
    private static final long RELOAD_AFTER_MS = ConnectionRecovery.RELOAD_AFTER_MS;

    private void onConnectionEvent(String message) {
        try {
            if (message.indexOf("connection lost") >= 0) {
                onConnectionState(ConnectionRecovery.RETRYING, "运行日志");
            } else if (message.indexOf("connection restored") >= 0) {
                onConnectionState(ConnectionRecovery.CONNECTED, "运行日志");
            } else if (message.indexOf("reconnect") >= 0) {
                onConnectionState(ConnectionRecovery.RETRYING, "运行日志");
            }
        } catch (Throwable t) {
            log("处理连接事件失败: " + t);
        }
    }

    /** 合并页面探针与旧日志嗅探的连接状态，并同步任务中心与通知栏。 */
    private void onConnectionState(int state, String source) {
        try {
            long now = System.currentTimeMillis();
            int previous = connectionState;
            if (ConnectionRecovery.isProblem(state)) {
                // 页面由 App 自己刷新时，旧连接的 close 是预期结果。显示为
                // 「正在连接」即可，不能再启动一轮看门狗形成刷新循环。
                if (now - selfReloadAt < RELOAD_SUPPRESS_MS) {
                    connectionState = ConnectionRecovery.CONNECTING;
                    if (previous != connectionState) {
                        log("[连接] 页面刷新后正在重建连接（" + source + "）");
                    }
                    pushStatus(lastSessionStatus);
                    return;
                }
                connectionState = state;
                if (connectionLostAt == 0L) connectionLostAt = now;
                if (taskTimeline.onConnectionState(state)) persistTaskTimeline();
                if (previous != state) {
                    log("[连接] " + ConnectionRecovery.label(state, false)
                            + "（" + source + "）");
                }
                scheduleReloadIfStuck();
            } else if (state == ConnectionRecovery.CONNECTED) {
                connectionState = state;
                if (previous != state || connectionLostAt != 0L) {
                    log("[连接] WebSocket 已连接（" + source + "）");
                }
                connectionLostAt = 0L;
                reloadScheduled = false;
                autoReloadCount = 0;
            } else {
                connectionState = state;
                if (previous != state) {
                    log("[连接] " + ConnectionRecovery.label(state, false)
                            + "（" + source + "）");
                }
            }
            pushStatus(lastSessionStatus);
        } catch (Throwable t) {
            log("处理连接状态失败: " + t);
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

                    boolean taskActive = lastSessionStatus == SessionStatus.RUNNING
                            || lastSessionStatus == SessionStatus.AWAITING_APPROVAL
                            || taskTimeline.active() != null;
                    // 任务正在跑时**绝不刷新**。
                    // 刷新会丢掉滚动位置、输入框内容和展开的面板，而 agent
                    // 在 node 进程里照常干活 —— 用户看到的就是「窗口自己重启/闪烁，
                    // 但任务其实正常进行」。DSH 自身的重连足以应付这种情况。
                    if (taskActive) {
                        log("[连接] 断开 " + sec + " 秒，但任务正在运行 —— 不刷新页面"
                                + "（避免打断界面；DSH 会自行重连）");
                        reloadScheduled = false;
                        return;
                    }
                    // 刷新解决不了问题时就停止刷新，别再让界面一遍遍闪
                    if (autoReloadCount >= MAX_AUTO_RELOADS) {
                        log("[连接] 已自动刷新 " + autoReloadCount + " 次仍不稳定 —— "
                                + "停止自动刷新，请下拉通知栏或在设置里手动处理");
                        reloadScheduled = false;
                        pushStatus(lastSessionStatus);
                        return;
                    }
                    if (!ConnectionRecovery.shouldReload(false,
                            System.currentTimeMillis() - lostAt, autoReloadCount)) {
                        reloadScheduled = false;
                        return;
                    }
                    autoReloadCount++;
                    selfReloadAt = System.currentTimeMillis();
                    connectionState = ConnectionRecovery.CONNECTING;
                    log("[连接] 断开 " + sec + " 秒仍未恢复，自动刷新页面重建连接"
                            + "（第 " + autoReloadCount + "/" + MAX_AUTO_RELOADS + " 次）");
                    statusPageLoading = false;
                    if (webView != null) webView.reload();
                    connectionLostAt = 0;
                    reloadScheduled = false;
                    pushStatus(lastSessionStatus);
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
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("dsh-recovery://retry")) {
            log("用户点击「重试恢复」");
            dismissRecoveryBanner();
            lastRecoveryAt = 0;
            statusPageLoading = false;
            if (webView != null) webView.reload();
            toast("正在重试会话恢复");
            return true;
        }
        if (lower.startsWith("dsh-recovery://new")) {
            log("用户点击「新建会话」");
            dismissRecoveryBanner();
            if (webView != null) {
                try {
                    webView.evaluateJavascript(SessionRecovery.newSessionScript(), null);
                } catch (Throwable t) {
                    log("会话恢复：调用新建会话入口失败: " + t);
                    toast("无法打开新建会话入口，请从侧栏手动新建");
                }
            }
            return true;
        }
        if (lower.startsWith("dsh-recovery://logs")) {
            log("用户点击「导出诊断」");
            dismissRecoveryBanner();
            exportDiagnostics();
            return true;
        }
        // 状态页里的「重试启动」链接（启动失败时给出的出路之一）
        if (lower.startsWith("dsh-retry:")) {
            if (lower.startsWith("dsh-retry://payload")) {
                log("用户点击「重新下载运行包」");
            } else if (lower.startsWith("dsh-retry://log")) {
                log("用户点击「打开运行日志」");
                hideSplash();
                showLog();
                return true;
            } else {
                log("用户点击「重试启动」");
            }
            restartAgent();
            return true;
        }
        String u = lower;
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
            String serviceUrl = HarnessService.managedUrl();
            if (serviceUrl != null) {
                log("  已捕获服务地址");
                return serviceUrl;
            }

            if (!HarnessService.isManagedProcessAlive()) {
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

    private android.widget.FrameLayout rootView;
    /** 维护任务互斥：App 更新与运行包更新不可并行。 */
    private final OperationGate maintenanceGate = new OperationGate();
    /** WindowInsets 与可见窗口两条路径分别维护，应用时合并且保留左右安全区。 */
    private int safeInsetLeft;
    private int safeInsetRight;
    private int imeInsetPadding;
    private int imeFramePadding;
    /** App 私有根目录，供设置页读写配置。 */
    private volatile File appRoot;

    /** 后台任务完成通知的判定（纯逻辑在 TaskNotifier 里并由离线测试覆盖）。 */
    private final TaskNotifier taskNotifier = new TaskNotifier();
    /** 最近任务的持久化时间线；在 onCreate 里从偏好设置恢复。 */
    private TaskTimeline taskTimeline = new TaskTimeline();
    private static final String PREF_TASK_TIMELINE = "taskTimelineV1";
    /** WebSocket 生命周期状态，与 Android 网络是否联网是两套独立证据。 */
    private volatile int connectionState = ConnectionRecovery.UNKNOWN;
    /** 一次 Activity 生命周期只提示一次草稿恢复。 */
    private boolean draftRestoreToastShown;
    /** App 是否在前台：在前台时界面本来就看得见结果，不必再弹通知。 */
    private volatile boolean inForeground = true;
    /** 任务完成通知的通知 id（与前台服务通知区分开）。 */
    private static final int TASK_DONE_NOTIFY_ID = 1001;
    /**
     * 可选的显示缩放档位（百分比）。
     *
     * <p>为什么需要：DSH 的页面现在会按屏幕宽度响应式适配，但不同设备的
     * 字体密度与用户视力仍有差异。WebView 的 textZoom 只放大文字、
     * 不改变项目内容与会话状态。
     */
    private static final int[] ZOOM_STEPS = {100, 115, 130, 150};
    private static final String PREFS = "dsh-native";
    /** 当前已应用的 viewport 宽度（0 = 尚未应用）。 */
    private volatile int appliedViewportWidth = 0;
    /** 工具链目录与 node 可执行文件（插件安装需要）。 */
    private volatile File toolsDirRef;
    private volatile File nodeRef;

    /** 最近一次会话恢复失败的原始文本，供诊断导出使用。 */
    private volatile String lastRecoveryRaw = "";
    /** 会话恢复提示的去抖时间，避免同一个网页异常重复插入提示。 */
    private volatile long lastRecoveryAt;
    /** 页面顶部的原生恢复提示卡片。 */
    private android.view.View recoveryBannerView;
    /** 最近一次运行包更新失败，供设置页与诊断包说明现场。 */
    private volatile String payloadLastError = "";

    /** DSH 安装目录，供屏幕方向变化时重新适配 viewport。 */
    private volatile File dshDirRef;
    /** agent 的工作目录（优先共享存储）。 */
    private volatile File workspace;
    /** 所有命名项目的共同根目录；默认工作区就是该目录本身。 */
    private volatile File workspaceRoot;
    private android.view.View splashView;
    private android.widget.ImageView splashLogo;
    private android.animation.ObjectAnimator splashAnimator;
    private android.widget.TextView splashStatus;
    /** 顶部 WebView 加载进度条（2dp）。 */
    private android.widget.ProgressBar topProgress;
    /** 当前页面未找到 WebUI 入口锚点时，只提示一次备用手势。 */
    private volatile boolean toolsEntryFallbackWarned;
    private volatile boolean splashHidden;
    /** 防止快速连按返回键叠加多个确认框。 */
    private android.app.Dialog exitDialog;
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
    /** 分享导入后、网页尚未就绪时等待提交的任务提示词。 */
    private volatile String pendingSharedTaskPrompt;
    /** 分享任务的持续提交反馈，仅在主线程读写。 */
    private DshUi.TaskProgress shareSubmitProgress;
    /** 仍在运行的分享导入面板，Activity 销毁时统一收回。 */
    private final java.util.List<DshUi.TaskProgress> shareImportProgresses =
            new java.util.ArrayList<DshUi.TaskProgress>();
    /** 提交反馈代次：超时回调不得关闭更新的提交。 */
    private int shareSubmitGeneration;
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
                    DshUi.choiceActivated(v);
                    applyZoom(pct);
                    fillZoomRow(row);          // 重建 → 状态必然一致
                    DshUi.animateChoiceChange(row);
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

    /** 首次运行时写入最小配置：DSH_HOME 与凭据。 */
    private void prepareConfig(File root) throws IOException {
        File dshHome = new File(root, ".dsh");
        if (!dshHome.exists()) dshHome.mkdirs();

        // 1) 模型配置 —— 必须「合并」而不是「不存在才写」：
        //    DSH 启动时会自行创建 settings.yaml（写入 onboarding 状态等），
        //    所以文件通常已存在，简单跳过会导致预置模型永远注入不进去。
        File preset = new File(root, "settings-preset.yaml");
        File settings = new File(dshHome, "settings.yaml");

        // DSH v0.2.0 会把 settings.yaml 一次性改名成 .imported 并导入 profile。
        // 之后文件不存在，若此时用预置重建，用户刚刷新的模型目录会被覆盖 ——
        // 表现为「每次打开应用都要重新更新模型列表」。已导入过就不再重建。
        File importedSettings = new File(dshHome, "settings.yaml.imported");
        if (ModelCatalogPersistence.shouldSkipPresetInjection(
                settings.isFile(), readText(importedSettings))) {
            log("  模型目录已导入过（settings.yaml.imported），跳过预置覆盖");
        } else if (preset.exists()) {
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
        applyProjectModelConfig(activeProjectName(), settings);

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

            // Keep the bundled transport settings, but preserve a live model
            // catalog explicitly saved by ModelCenterPanel.
            String mergedWant = ModelCatalogSync.mergePresetProviderBlock(want, have);
            String out;
            if (have == null) {
                out = cur.endsWith("\n") ? cur + mergedWant : cur + "\n" + mergedWant;
            } else {
                out = cur.replace(have, mergedWant);
            }
            writeText(settings, out);

            // 记录同步后的模型与图片能力，便于核对
            java.util.List<String> withImage = ModelImageSupport.imageCapableModels(want);
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
            String content = "# 由 App 导出的模型配置（不含密钥值，只有环境变量名）\n"
                    + "# 用途：核对模型是否声明了 input: [text, image]\n\n" + block;
            // 内容没变就不写：/sdcard 是 FUSE，每次启动都写一遍纯属浪费启动时间。
            if (!ModelCatalogPersistence.shouldWriteDiagnostics(
                    out.isFile() ? readText(out) : null, content)) {
                return;
            }
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            writeText(out, content);
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

            // DeepSeek 官方路由使用 DSH 内置目录，不在 llm-pi-ai 的自定义模型块里。
            // 把它硬拿去搜 commandcode 清单会制造“模型不存在”的假警报。
            if (ModelConfig.DEEPSEEK.equals(provider)) {
                log("  使用 DSH 内置 DeepSeek 官方模型目录");
                return;
            }

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
        return YamlBlocks.topLevelBlock(yaml, key);
    }

    /**
     * 当前版本的 APK 下载地址（直连形式）。
     *
     * <p>网络诊断用它做测速 —— 必须与更新功能真正会下载的地址一致，
     * 否则测出来的速度没有参考意义。
     */
    private String apkDownloadUrl() {
        return "https://github.com/" + REPO + "/releases/download/"
                + ReleaseChannel.tag(appVersion(), updateChannelPreference())
                + "/DSHNative-bootstrap.apk";
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

    /** 回滚后暂停后台自动重试，避免每次启动重复进入同一个失败循环。 */
    private boolean payloadRollbackHold() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("payloadRollbackHold", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setPayloadRollbackHold(boolean hold) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("payloadRollbackHold", hold).apply();
        } catch (Throwable ignored) { }
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
        if (payloadRollbackHold()) {
            log("后台检查：运行环境处于回滚暂缓状态，等待用户手动重试");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File mf = refreshPayloadManifest(root);
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

    /** 统计运行目录大小，不跟随符号链接；溢出时饱和到 Long.MAX_VALUE。 */
    private static long directorySizeNoFollow(File file) {
        if (file == null || !file.exists()) return 0L;
        try {
            if (FileOps.isSymbolicLink(file)) return 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
        if (file.isFile()) return Math.max(0L, file.length());
        long total = 0L;
        File[] children = file.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            long size = directorySizeNoFollow(child);
            if (total >= Long.MAX_VALUE - size) return Long.MAX_VALUE;
            total += size;
        }
        return total;
    }

    /** 删除清单中的单项；复用 FileOps 的规范路径与符号链接边界。 */
    private static void deletePayloadEntry(File target, File runtimeRoot) throws IOException {
        if (target == null || !target.exists()) return;
        java.util.List<File> roots = java.util.Collections.singletonList(runtimeRoot);
        String error = FileOps.delete(target, roots);
        if (error != null || target.exists()) {
            throw new IOException("无法安全移除运行包文件: " + target.getName()
                    + (error == null ? "" : "（" + error + "）"));
        }
    }

    /** 只删除 appRoot 的明确维护子目录，绝不接受任意路径或 .dsh 用户数据。 */
    private static void deleteRuntimeChild(File target, File root) throws IOException {
        RuntimeDir.delete(target, root);
    }

    /**
     * 为将要变化的 dsh/tools 目录创建压缩快照，并在全部校验后原子替换旧快照。
     */
    private void createPayloadRollback(File node, File root,
                                       java.util.Set<String> changedTargets,
                                       java.util.Set<String> previousRevisions)
            throws Exception {
        File stage = new File(root, "payload-rollback.next");
        File active = new File(root, "payload-rollback");
        File old = new File(root, "payload-rollback.old");
        deleteRuntimeChild(stage, root);
        if (!stage.mkdirs()) throw new IOException("无法创建运行环境快照目录");

        java.util.List<PayloadRollback.Target> saved =
                new java.util.ArrayList<PayloadRollback.Target>();
        try {
            for (String target : changedTargets) {
                if (!PayloadRollback.isSafeTarget(target)) {
                    throw new IOException("快照目标不安全: " + target);
                }
                File source = new File(root, target);
                if (!source.isDirectory()) {
                    throw new IOException("上一运行环境缺少目录: " + target);
                }
                String archiveName = PayloadRollback.snapshotName(target);
                File archive = new File(stage, archiveName);
                log("保存上一运行环境: " + target + " …");
                run(node, root, new String[]{
                        new File(root, "snapshot.js").getAbsolutePath(),
                        source.getAbsolutePath(), archive.getAbsolutePath()}, null);
                if (!archive.isFile() || archive.length() <= 0L) {
                    throw new IOException("运行环境快照为空: " + target);
                }
                saved.add(new PayloadRollback.Target(target, archiveName,
                        archive.length(), sha256(archive)));
            }
            String journalText = PayloadRollback.serialize(new PayloadRollback.Journal(
                    System.currentTimeMillis(), saved, previousRevisions));
            if (journalText == null) throw new IOException("无法生成回滚记录");
            File journal = new File(stage, PayloadRollback.JOURNAL_FILE);
            writeText(journal, journalText);
            if (PayloadRollback.parse(readText(journal)) == null) {
                throw new IOException("回滚记录写入后校验失败");
            }

            deleteRuntimeChild(old, root);
            if (active.exists() && !active.renameTo(old)) {
                throw new IOException("无法轮换上一份运行环境快照");
            }
            if (!stage.renameTo(active)) {
                if (old.exists() && !old.renameTo(active)) {
                    throw new IOException("无法启用新的运行环境快照，且无法恢复原快照");
                }
                throw new IOException("无法启用新的运行环境快照");
            }
            deleteRuntimeChild(old, root);
            log("上一运行环境已保存（" + saved.size() + " 个目录）");
        } catch (Throwable error) {
            try { deleteRuntimeChild(stage, root); } catch (Throwable ignored) { }
            if (error instanceof Exception) throw (Exception) error;
            throw new IOException("创建运行环境快照失败", error);
        }
    }

    /** 读取回滚记录；真正恢复前必须校验摘要，设置页只做快速的结构与大小检查。 */
    private PayloadRollback.Journal readPayloadRollback(File root, boolean verifyDigest) {
        try {
            File dir = new File(root, "payload-rollback");
            File journalFile = new File(dir, PayloadRollback.JOURNAL_FILE);
            if (!journalFile.isFile()) return null;
            PayloadRollback.Journal journal = PayloadRollback.parse(readText(journalFile));
            if (journal == null) return null;
            for (PayloadRollback.Target target : journal.targets) {
                File archive = new File(dir, target.archive);
                if (!archive.isFile() || archive.length() != target.size
                        || (verifyDigest
                        && !target.sha256.equalsIgnoreCase(sha256(archive)))) return null;
            }
            return journal;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PayloadRollback.Journal loadPayloadRollback(File root) {
        return readPayloadRollback(root, true);
    }

    private String payloadRollbackSummary() {
        File root = appRoot;
        PayloadRollback.Journal journal = root == null
                ? null : readPayloadRollback(root, false);
        if (journal == null) return "没有可恢复的上一运行环境";
        String when = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(journal.createdAt));
        return "可恢复 " + when + " 保存的运行环境（"
                + journal.targets.size() + " 个目录）";
    }

    /** 把修订号精确恢复为快照前的集合，并使下次启动重新执行环境自检。 */
    private void restorePayloadRevisionState(java.util.Set<String> revisions)
            throws IOException {
        java.util.Set<String> safe = new java.util.HashSet<String>();
        if (revisions != null) {
            for (String revision : revisions) {
                if (PayloadRollback.isSafeRevision(revision)) safe.add(revision);
            }
        }
        boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet("payloadRevisions", safe)
                .remove("preflightKey")
                .putBoolean("payloadUpdatePending", false)
                .putBoolean("payloadRollbackHold", true)
                .commit();
        if (!saved) throw new IOException("无法保存恢复后的运行环境状态");
    }

    private static boolean restoredTargetLooksUsable(File root, String target) {
        if ("dsh".equals(target)) return new File(root, "dsh/lib/bin.js").isFile();
        if ("tools".equals(target)) {
            return new File(root, "tools/bin/git").exists()
                    || new File(root, "tools/bin/bash").exists();
        }
        return false;
    }

    /**
     * 恢复已验证的上一运行环境。每个目录先保留当前副本；解压或哨兵失败时，
     * 立即把当前副本原位放回，避免“恢复失败”再次破坏可用环境。
     */
    private void restorePayloadRollback(File node, File root, boolean automatic)
            throws IOException {
        PayloadRollback.Journal journal = loadPayloadRollback(root);
        if (journal == null) throw new IOException("没有完整且可信的运行环境快照");
        File rollback = new File(root, "payload-rollback");
        HarnessService.stopManagedProcess();

        for (PayloadRollback.Target target : journal.targets) {
            File current = new File(root, target.name);
            File previousCurrent = new File(root, ".rollback-current-" + target.name);
            deleteRuntimeChild(previousCurrent, root);
            boolean moved = current.exists();
            if (moved && !current.renameTo(previousCurrent)) {
                throw new IOException("无法暂存当前运行目录: " + target.name);
            }
            try {
                File archive = new File(rollback, target.archive);
                run(node, root, new String[]{
                        new File(root, "unpack.js").getAbsolutePath(),
                        archive.getAbsolutePath(), current.getAbsolutePath()}, null);
                if (!restoredTargetLooksUsable(root, target.name)) {
                    throw new IOException("恢复后的关键文件缺失: " + target.name);
                }
            } catch (Throwable restoreError) {
                try { deleteRuntimeChild(current, root); } catch (Throwable ignored) { }
                if (moved && !previousCurrent.renameTo(current)) {
                    throw new IOException("恢复失败且无法放回当前运行目录: "
                            + target.name, restoreError);
                }
                throw new IOException("恢复运行目录失败: " + target.name, restoreError);
            }
            deleteRuntimeChild(previousCurrent, root);
        }

        restorePayloadRevisionState(journal.revisions);
        try { deleteRuntimeChild(rollback, root); }
        catch (Throwable cleanupError) {
            log("回滚完成，但无法清理已使用的快照: " + shorten(cleanupError));
        }
        log((automatic ? "自动" : "手动") + "恢复上一运行环境完成");
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

    /** 记录会话恢复失败，并在网页上方放一个不遮住输入区的原生提示卡片。 */
    private void onSessionRecoveryFailure(String raw) {
        String s = raw == null ? "" : raw;
        if (s.length() > 4000) s = s.substring(0, 4000) + "…";
        lastRecoveryRaw = s;
        long now = System.currentTimeMillis();
        log("[会话恢复] " + s);
        if (now - lastRecoveryAt < 5000 && recoveryBannerView != null) return;
        lastRecoveryAt = now;
        showSessionRecoveryBanner();
    }

    /** 创建可重复使用的原生恢复卡片；卡片以外的 WebView 仍可继续操作。 */
    private void showSessionRecoveryBanner() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (rootView == null) return;
                    dismissRecoveryBannerNow();
                    android.widget.LinearLayout card = DshUi.paddedBody(MainActivity.this);
                    card.setBackground(DshUi.cardBg(MainActivity.this));
                    try { card.setElevation(DshUi.dp(MainActivity.this, 6)); }
                    catch (Throwable ignored) { }
                    card.addView(DshUi.title(MainActivity.this, SessionRecovery.title()));
                    card.addView(DshUi.hint(MainActivity.this, SessionRecovery.detail()),
                            DshUi.fullWidth(MainActivity.this, 8));

                    android.widget.Button retry =
                            DshUi.button(MainActivity.this, "重试恢复", true);
                    android.widget.Button fresh =
                            DshUi.button(MainActivity.this, "新建会话", false);
                    android.widget.Button logs =
                            DshUi.button(MainActivity.this, "导出诊断", false);
                    android.widget.Button close =
                            DshUi.button(MainActivity.this, "关闭提示", false);
                    retry.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            dismissRecoveryBanner();
                            lastRecoveryAt = 0;
                            statusPageLoading = false;
                            if (webView != null) webView.reload();
                            toast("正在重试会话恢复");
                        }
                    });
                    fresh.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            dismissRecoveryBanner();
                            if (webView == null) return;
                            try {
                                webView.evaluateJavascript(
                                        SessionRecovery.newSessionScript(), null);
                            } catch (Throwable t) {
                                log("会话恢复：调用新建会话入口失败: " + t);
                                toast("无法打开新建会话入口，请从侧栏手动新建");
                            }
                        }
                    });
                    logs.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            dismissRecoveryBanner();
                            exportDiagnostics();
                        }
                    });
                    close.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override public void onClick(android.view.View v) {
                            dismissRecoveryBanner();
                        }
                    });
                    card.addView(DshUi.footer(MainActivity.this, retry, fresh),
                            DshUi.fullWidth(MainActivity.this, 2));
                    card.addView(DshUi.footer(MainActivity.this, logs, close),
                            DshUi.fullWidth(MainActivity.this, 2));

                    android.widget.FrameLayout.LayoutParams lp =
                            new android.widget.FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    int margin = DshUi.dp(MainActivity.this, 10);
                    lp.leftMargin = margin;
                    lp.rightMargin = margin;
                    lp.topMargin = DshUi.dp(MainActivity.this, 10);
                    lp.gravity = Gravity.TOP;
                    recoveryBannerView = card;
                    rootView.addView(card, lp);
                } catch (Throwable t) {
                    log("显示会话恢复提示失败: " + t);
                }
            }
        });
    }

    /** 从根布局移除恢复提示，调用方不需要判断当前是否已显示。 */
    private void dismissRecoveryBanner() {
        runOnUiThread(new Runnable() {
            @Override public void run() { dismissRecoveryBannerNow(); }
        });
    }

    private void dismissRecoveryBannerNow() {
        if (recoveryBannerView == null) return;
        try {
            android.view.ViewParent p = recoveryBannerView.getParent();
            if (p instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) p).removeView(recoveryBannerView);
            }
        } catch (Throwable t) {
            log("移除会话恢复提示失败: " + t);
        }
        recoveryBannerView = null;
    }

    /**
     * 导出可直接分享的 ZIP 诊断包。只包含系统/运行环境摘要与脱敏日志，
     * 不包含 settings.yaml、凭据、会话正文、附件或项目文件。
     */
    private void exportDiagnostics() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.util.List<File> dirs = new java.util.ArrayList<File>();
                    dirs.add(new File("/sdcard/DSHNative"));
                    // 无共享存储权限时仍能导出到应用缓存，并通过 UpdateProvider 分享。
                    dirs.add(new File(getCacheDir(), "diagnostics"));

                    File out = null;
                    String stamp = new java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                            .format(new java.util.Date());
                    for (File dir : dirs) {
                        if (dir == null) continue;
                        try {
                            if (!dir.exists() && !dir.mkdirs()) continue;
                            File candidate = new File(dir,
                                    "dsh-native-diagnostics-" + stamp + ".zip");
                            writeText(candidate, "");
                            out = candidate;
                            break;
                        } catch (Throwable ignored) { }
                    }
                    if (out == null) throw new IOException("没有可写的共享存储目录");

                    String tail = "";
                    if (sharedLog != null && sharedLog.exists()) {
                        tail = readText(sharedLog);
                        int max = 512 * 1024;
                        if (tail.length() > max) tail = tail.substring(tail.length() - max);
                    }

                    android.content.res.Configuration config =
                            getResources().getConfiguration();
                    android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
                    boolean[] network = networkState();
                    String webViewVersion = "unknown";
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        android.content.pm.PackageInfo web =
                                android.webkit.WebView.getCurrentWebViewPackage();
                        if (web != null) webViewVersion = web.packageName + " " + web.versionName;
                    }
                    boolean notifications = false;
                    try {
                        android.app.NotificationManager nm =
                                (android.app.NotificationManager) getSystemService(
                                        NOTIFICATION_SERVICE);
                        notifications = nm != null && (android.os.Build.VERSION.SDK_INT < 24
                                || nm.areNotificationsEnabled());
                    } catch (Throwable ignored) { }

                    long now = System.currentTimeMillis();
                    DiagnosticReport.Builder report = new DiagnosticReport.Builder()
                            .title("DSH Native diagnostics")
                            .section("App")
                            .add("Package", getPackageName())
                            .add("Version", appVersion())
                            .add("Update channel", updateChannelPreference())
                            .add("UI language", UiText.isEnglish() ? "en" : "zh")
                            .add("Text zoom", currentZoom() + "%")
                            .add("Web page loaded", dshPageLoaded)
                            .section("Device")
                            .add("Manufacturer", android.os.Build.MANUFACTURER)
                            .add("Model", android.os.Build.MODEL)
                            .add("Android SDK", android.os.Build.VERSION.SDK_INT)
                            .add("ABIs", java.util.Arrays.toString(
                                    android.os.Build.SUPPORTED_ABIS))
                            .add("Display px", metrics.widthPixels + "x" + metrics.heightPixels)
                            .add("Display dp", config.screenWidthDp + "x" + config.screenHeightDp)
                            .add("Density", metrics.density)
                            .add("Layout profile", DeviceLayout.profile(config.screenWidthDp,
                                    config.screenHeightDp, config.fontScale))
                            .add("WebView", webViewVersion)
                            .section("Runtime")
                            .add("Payload", payloadSummary())
                            .add("Payload revisions", payloadRevisionKey())
                            .add("Update pending", payloadUpdatePending())
                            .add("Rollback hold", payloadRollbackHold())
                            .add("Rollback", payloadRollbackSummary())
                            .add("DSH directory size", appRoot == null ? "unknown"
                                    : FileListing.humanSize(directorySizeNoFollow(
                                            new File(appRoot, "dsh"))))
                            .add("Tools directory size", appRoot == null ? "unknown"
                                    : FileListing.humanSize(directorySizeNoFollow(
                                            new File(appRoot, "tools"))))
                            .section("Status")
                            .add("Task", SessionStatus.title(lastSessionStatus,
                                    taskStartedAt > 0 ? now - taskStartedAt : 0L,
                                    connectionState))
                            .add("Connection", ConnectionRecovery.label(
                                    connectionState, true))
                            .add("Network", SessionStatus.networkLabel(
                                    network[1], network[2], network[3], network[0], true))
                            .add("Notifications enabled", notifications)
                            .add("Maintenance operation", maintenanceGate.active() == null
                                    ? "none" : maintenanceGate.active())
                            .section("Recent errors")
                            .add("Payload update", payloadLastError.length() == 0
                                    ? "none" : payloadLastError)
                            .add("Session recovery", lastRecoveryRaw.length() == 0
                                    ? "none" : "present; content omitted")
                            .add("Patch checks", patchReport.size());

                    File staged = new File(out.getAbsolutePath() + ".tmp");
                    java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                            new java.io.BufferedOutputStream(new FileOutputStream(staged)));
                    try {
                        writeDiagnosticEntry(zip, "summary.txt", report.build());
                        writeDiagnosticEntry(zip, "launch-log-tail.txt", maskSecrets(tail));
                        writeDiagnosticEntry(zip, "privacy.txt",
                                "This bundle omits credentials, settings files, session content, "
                                        + "attachments and project files. Log text is masked.\n");
                    } finally {
                        zip.close();
                    }
                    TransferState.atomicReplace(staged, out);
                    final File exported = out;
                    log("诊断包已导出: " + exported.getAbsolutePath());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            shareDiagnosticBundle(exported);
                        }
                    });
                } catch (Throwable t) {
                    log("导出诊断失败: " + t);
                    toast("导出诊断失败，请打开运行日志后复制");
                }
            }
        }, "dsh-diagnostics").start();
    }

    private static void writeDiagnosticEntry(java.util.zip.ZipOutputStream zip,
                                             String name, String text) throws IOException {
        java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        byte[] data = (text == null ? "" : text).getBytes("UTF-8");
        zip.write(data);
        zip.closeEntry();
    }

    /** 导出完成后直接打开系统分享面板；失败时仍保留共享存储中的文件。 */
    private void shareDiagnosticBundle(File file) {
        if (file == null || !file.isFile()) {
            toast("诊断包未生成");
            return;
        }
        try {
            android.net.Uri uri = android.net.Uri.parse("content://"
                    + UpdateProvider.AUTHORITY + "/"
                    + ShareTargets.PREFIX + ShareTargets.encode(file));
            android.content.Intent send = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            send.putExtra(android.content.Intent.EXTRA_TITLE, file.getName());
            send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.Intent chooser = android.content.Intent.createChooser(
                    send, UiText.t("分享诊断包", "Share diagnostics"));
            chooser.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
            toast("诊断包已生成，可选择应用分享");
        } catch (Throwable shareError) {
            log("打开诊断分享面板失败: " + shareError);
            toast("诊断包已保存到 " + file.getAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- 任务完成通知
    /** 从偏好设置恢复任务计时与最近历史。损坏数据由纯逻辑层安全忽略。 */
    private void restoreTaskState() {
        try {
            long now = System.currentTimeMillis();
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(PREF_TASK_TIMELINE, "");
            taskTimeline = TaskTimeline.restore(raw, now);
            TaskTimeline.Entry active = taskTimeline.active();
            if (active != null
                    && taskNotifier.restoreRunning(
                            active.sessionId(), active.startedAt(), now)) {
                taskStartedAt = taskNotifier.startedAt();
                log("[任务] 已恢复上次任务计时，等待页面确认状态");
            }
        } catch (Throwable t) {
            taskTimeline = new TaskTimeline();
            log("[任务] 历史恢复失败，已使用空时间线: " + t);
        }
    }

    /** 任务状态变化后立即持久化，避免进程在下一帧被回收时再次丢失。 */
    private void persistTaskTimeline() {
        try {
            boolean saved = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_TASK_TIMELINE, taskTimeline.serialize()).commit();
            if (!saved) log("[任务] 历史保存未落盘");
        } catch (Throwable t) {
            log("[任务] 历史保存失败: " + t);
        }
    }

    /** 处理来自注入脚本的任务事件。 */
    private void onTaskEvent(String kind, String sessionId) {
        try {
            long now = System.currentTimeMillis();
            if (taskTimeline.onTaskEvent(kind, sessionId, now)) {
                persistTaskTimeline();
            }
            String msg = taskNotifier.onEvent(kind, sessionId, now, inForeground);
            taskStartedAt = taskNotifier.startedAt();
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
            DshUi.ensureChannel(this, HarnessService.TASK_CHANNEL_ID,
                    "任务完成", "DSH 在后台完成任务时提醒",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT);
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
                b = new android.app.Notification.Builder(
                        this, HarnessService.TASK_CHANNEL_ID);
            } else {
                b = new android.app.Notification.Builder(this);
            }
            b.setContentTitle("任务已完成")
             .setContentText(text)
             .setSmallIcon(icon)
             .setContentIntent(pi)
             .setAutoCancel(true);
            if (android.os.Build.VERSION.SDK_INT < 26) {
                b.setDefaults(android.app.Notification.DEFAULT_ALL)
                 .setPriority(android.app.Notification.PRIORITY_DEFAULT);
            }
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
     * <p>会话列表由 {@link SessionProbe} 捕获并重放；页面可见状态同时作为独立兜底，
     * 避免 API 地址或请求格式变化时通知栏失去运行/空闲状态。
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

    /**
     * 把原生工具入口放进 DSH 自己的侧边栏布局。
     *
     * <p>入口以 DSH 的「设置」按钮为语义锚点，克隆同一套行样式并参与正常布局，
     * 不再使用覆盖 WebView 的悬浮 View。脚本仍只经控制台标记回传动作，避免向
     * 页面暴露具有原生权限的 {@code JavascriptInterface}。</p>
     */
    private void installWebToolsEntry() {
        try {
            webView.evaluateJavascript(WebToolsEntry.script(), null);
            log("已注入 WebUI 工具入口");
        } catch (Throwable t) {
            log("WebUI 工具入口注入失败: " + t);
        }
    }

    /** 第一次确认入口成功时说明迁移位置；后续启动不再打扰。 */
    private void onWebToolsEntryReady() {
        log("WebUI 工具入口已挂载到 DSH 侧边栏");
        try {
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean("toolsEntryMigrationShown", false)) {
                prefs.edit().putBoolean("toolsEntryMigrationShown", true).apply();
                toast(UiText.t(
                        "工具入口已移至 DSH 侧边栏底部",
                        "App tools moved to the bottom of the DSH sidebar"));
            }
        } catch (Throwable t) {
            log("记录工具入口迁移提示失败: " + t);
        }
    }

    /** 锚点长期缺失时给出可操作的退路，但绝不恢复遮挡网页的悬浮按钮。 */
    private void onWebToolsEntryMissing() {
        log("警告: DSH 设置锚点未出现，WebUI 工具入口暂未挂载");
        if (toolsEntryFallbackWarned) return;
        toolsEntryFallbackWarned = true;
        toast(UiText.t(
                "工具入口暂未加载；可长按页面顶部 1.2 秒打开",
                "App tools did not load. Long-press the page top for 1.2 seconds."));
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
     *   <li>等待批准优先，因为它需要用户动作；</li>
     *   <li>新鲜会话列表是运行/空闲的权威证据；</li>
     *   <li>列表暂不可用时，才用可见的停止/发送按钮兜底；</li>
     *   <li>完全没有新鲜证据时保持现状，过久的运行态改为「同步中」。</li>
     * </ol>
     *
     * @return 推导出的状态；无新鲜证据时返回 -1
     */
    private int deriveStatus(long now) {
        boolean domFresh = domSignalAt > 0 && now - domSignalAt <= SIGNAL_TTL_MS;
        boolean sessFresh = sessSignalAt > 0 && now - sessSignalAt <= SIGNAL_TTL_MS;
        boolean sessRecent = sessSignalAt > 0 && now - sessSignalAt <= RUNSTATE_STALE_MS;
        return SessionStatus.resolveSignals(lastSessionStatus,
                sessSignalState, sessFresh, sessRecent,
                domSignalState, domFresh);
    }

    /** 收到一次页面状态上报，推给前台服务更新通知。 */
    private void onSessionStatus(int state) {
        try {
            long now = System.currentTimeMillis();
            if (taskTimeline.onSessionState(state, taskNotifier.sessionId(), now)) {
                persistTaskTimeline();
            }
            // 顺带驱动「任务完成」通知。
            //
            // 原来它把 /api/session/list 当普通 REST GET，实际会 404。
            // 现在改用 SessionProbe 捕获的真实 RPC 状态源。
            if (state == SessionStatus.RUNNING || state == SessionStatus.AWAITING_APPROVAL) {
                // 重复的运行心跳不得重置起点；即使状态曾短暂降为 UNKNOWN，
                // TaskNotifier 仍保留同一轮任务的开始时间。
                taskNotifier.onEvent("start", "", now, inForeground);
                taskStartedAt = taskNotifier.startedAt();
            } else if (state == SessionStatus.IDLE) {
                String done = taskNotifier.onEvent("done", "", now, inForeground);
                taskStartedAt = 0L;
                if (done != null) notifyTaskDone(done);
            }
            lastSessionStatus = state;
            pushStatus(state);
            if (state == SessionStatus.IDLE) applyPendingModelCatalog();
            // 断线时为保护运行任务而跳过了自动刷新；一旦权威状态确认任务已
            // 结束，就可以在仍未恢复连接的情况下重新启动安全恢复计时器。
            if (state == SessionStatus.IDLE && connectionLostAt > 0L
                    && ConnectionRecovery.isProblem(connectionState)) {
                scheduleReloadIfStuck();
            }
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
            // 不能只写日志。通知正文包含网络状态，同一任务运行期间网络变化时
            // 也必须重新发布，否则会一直显示旧网络信息。
            pushStatus(lastSessionStatus);
        } catch (Throwable t) {
            log("处理网络变化失败: " + t);
        }
    }


    /** 上一次推送给通知的状态（用于抑制重复推送）。 */
    private volatile int pushedState = -2;
    /** 是否已经推送过一次网络状态。 */
    private volatile boolean networkInited;
    /** 上次推送使用的任务起点与网络内容，用于精确去重。 */
    private volatile long pushedSince = -1L;
    private volatile boolean pushedNetworkOk;
    private volatile String pushedNetworkLabel = "";
    private volatile int pushedConnectionState = -2;

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
            boolean[] net = networkState();
            String netLabel = SessionStatus.networkLabel(net[1], net[2], net[3], net[0]);
            // 状态证据短暂过期进入 UNKNOWN 时，TaskNotifier 仍持有同一轮的
            // 可靠起点；继续把它交给系统计时器，避免通知里的运行时间突然消失。
            long since = taskNotifier.isRunning() ? taskStartedAt : 0L;
            // 状态、计时起点和网络内容都没变才跳过。只看 state 会漏掉
            // 网络切换，也会让新任务复用上一轮的系统计时器。
            if (state == pushedState && networkInited && since == pushedSince
                    && net[0] == pushedNetworkOk && netLabel.equals(pushedNetworkLabel)
                    && connectionState == pushedConnectionState) return;

            android.content.Intent i = new android.content.Intent(this, HarnessService.class);
            i.setAction(HarnessService.ACTION_STATUS);
            i.putExtra(HarnessService.EXTRA_STATUS_STATE, state);
            i.putExtra(HarnessService.EXTRA_STATUS_NETWORK, net[0]);
            i.putExtra(HarnessService.EXTRA_STATUS_NETWORK_LABEL, netLabel);
            i.putExtra(HarnessService.EXTRA_STATUS_SINCE, since);
            i.putExtra(HarnessService.EXTRA_CONNECTION_STATE, connectionState);
            startService(i);
            // **推送成功之后**才记下状态。
            //
            // 之前是先记后推：一旦 startService 抛异常（Android 8+ 的后台服务
            // 启动限制、服务处于 stopped 状态等），这里就认为「已经推过了」，
            // 通知栏会永久停在旧状态 —— 而用户正是据此判断 agent 还在不在干活。
            // 这个 catch 之前是空的，连日志都没有。
            pushedState = state;
            networkInited = true;
            pushedSince = since;
            pushedNetworkOk = net[0];
            pushedNetworkLabel = netLabel;
            pushedConnectionState = connectionState;
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
     * 在页面最早期安装只读会话状态探针。
     *
     * <p>必须同时在 {@code onPageStarted} 和 {@code onPageFinished} 调用：前者保证
     * 不错过首次会话列表请求，后者是在极少数 WebView 尚未提供 fetch 时的幂等兜底。</p>
     */
    private void installSessionProbe(WebView view) {
        try {
            if (view != null) view.evaluateJavascript(SessionProbe.script(), null);
        } catch (Throwable t) {
            log("警告: 会话状态探针注入失败: " + t);
        }
    }

    /** 安装不读取消息内容的 WebSocket 生命周期探针。 */
    private void installConnectionWatcher(WebView view) {
        try {
            if (view != null) view.evaluateJavascript(ConnectionRecovery.script(), null);
        } catch (Throwable t) {
            log("警告: 连接状态探针注入失败: " + t);
        }
    }

    /** 安装同源 localStorage 草稿恢复；脚本不具备任何原生权限。 */
    private void installDraftRecovery(WebView view) {
        try {
            if (view != null) view.evaluateJavascript(DraftRecovery.script(), null);
        } catch (Throwable t) {
            log("警告: 草稿恢复注入失败: " + t);
        }
    }

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

    /** 监听页面可见的恢复错误，处理没有走 fetch 或控制台的页面失败。 */
    private void installSessionRecoveryWatcher() {
        try {
            webView.evaluateJavascript(SessionRecovery.domWatcherScript(), null);
        } catch (Throwable t) {
            log("警告: 注入会话恢复监听失败: " + t);
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
        } else if (out.contains(singleMarker)) {
            out = out.replace(singleMarker, detail);
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
     * <p>启用哪些插件由**用户选择并明确授权**决定（设置 → 插件），默认不启用插件。
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
        if (saved != null) want.addAll(saved);

        File profileModules = new File(new File(root, ".dsh"),
                "profiles/web/node_modules");
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.util.List<String> pendingGrant = new java.util.ArrayList<String>();
        StringBuilder grantSignature = new StringBuilder();
        for (String name : want) {
            if (name == null || name.length() == 0) continue;
            File resolved = resolvePlugin(dshDir, profileModules, name);
            if (resolved == null) {
                log("  跳过插件 " + name + "：解析不到（可能未安装）");
                continue;
            }
            String grant = PluginPermissions.grantKey(name,
                    PluginSpecs.readPackageJson(resolved));
            if (!pluginPermissionGrants().contains(grant)) {
                log("  跳过插件 " + name + "：当前版本尚未获得用户授权");
                pendingGrant.add(name);
                grantSignature.append(grant).append('\n');
                continue;
            }
            out.add(name);
        }
        if (!pendingGrant.isEmpty()) {
            String noticeKey = "pluginPermissionNotice."
                    + Integer.toHexString(grantSignature.toString().hashCode());
            android.content.SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(noticeKey, false)) {
                prefs.edit().putBoolean(noticeKey, true).apply();
                toast("有 " + pendingGrant.size()
                        + " 个已选插件需要重新授权，请到数据与扩展 → 插件");
            }
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

    /** 读取当前选择；第三阶段起不再默认启用任何插件，必须显式授权。 */
    private java.util.Set<String> savedPluginSelection() {
        try {
            java.util.Set<String> s = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("plugins", null);
            if (s != null) return new java.util.HashSet<String>(s);
        } catch (Throwable ignored) { }
        return new java.util.HashSet<String>();
    }

    private java.util.Set<String> pluginPermissionGrants() {
        try {
            java.util.Set<String> grants = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getStringSet("pluginPermissionGrants", null);
            if (grants != null) return new java.util.HashSet<String>(grants);
        } catch (Throwable ignored) { }
        return new java.util.HashSet<String>();
    }

    private void rememberPluginPermissionGrant(String key) {
        if (key == null || key.length() == 0) return;
        java.util.Set<String> grants = pluginPermissionGrants();
        grants.add(key);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet("pluginPermissionGrants", grants).apply();
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

    private String updateChannelPreference() {
        try {
            return ReleaseChannel.normalize(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("updateChannel", ReleaseChannel.STABLE));
        } catch (Throwable ignored) {
            return ReleaseChannel.STABLE;
        }
    }

    private void setUpdateChannelPreference(String channel) {
        String normalized = ReleaseChannel.normalize(channel);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("updateChannel", normalized).apply();
        log("更新通道已切换为" + ReleaseChannel.label(normalized));
    }

    /**
     * 测试通道同时读取测试与稳定清单并取版本最高者，避免测试用户错过后来发布的
     * 更高稳定版；稳定通道只读取 latest.json，绝不会被预发布版本打扰。
     */
    private String[] versionSources() {
        java.util.List<String> files = new java.util.ArrayList<String>();
        if (ReleaseChannel.TEST.equals(updateChannelPreference())) {
            files.add(ReleaseChannel.manifest(ReleaseChannel.TEST));
        }
        files.add(ReleaseChannel.manifest(ReleaseChannel.STABLE));
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String file : files) {
            String raw = RAW_ROOT + file;
            out.add(raw);
            out.add("https://gh-proxy.com/" + raw);
            out.add("https://cdn.jsdelivr.net/gh/" + REPO + "@main/" + file);
            out.add("https://fastly.jsdelivr.net/gh/" + REPO + "@main/" + file);
        }
        return out.toArray(new String[0]);
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
        String[] sources = versionSources();
        for (String base : sources) {
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
            log("版本清单获取失败（" + sources.length + " 个源均不可用）");
        } else if (ok > 1) {
            log("更新通道: " + ReleaseChannel.label(updateChannelPreference())
                    + "（仅决定检查更新源，当前安装包 " + appVersion() + "）"
                    + "；从 " + ok + " 个源取得，采用最高版本 " + best[0]);
        }
        return best;
    }

    /** 版本号比较：a>b 返回正，a<b 返回负。 */
    /** 检查 App 更新；interactive=true 时把结果显示在给定文本上/弹提示。 */
    private void checkAppUpdate(final boolean interactive, final android.widget.TextView status) {
        checkAppUpdate(interactive, status, null);
    }

    private void checkAppUpdate(final boolean interactive,
                                final android.widget.TextView status,
                                final android.widget.Button button) {
        if (!maintenanceGate.tryStart(OperationGate.APP_UPDATE)) {
            String active = maintenanceGate.active();
            String message = "已有维护任务正在进行："
                    + (active == null ? "请稍候" : active);
            setStatus(status, message);
            if (interactive) toast(message);
            return;
        }
        setMaintenanceBusy(button, "检查 App 更新并安装", "正在检查…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String outcome = "检查失败";
                setStatus(status, "正在检查更新…");
                log("开始检查 App 更新（当前 " + appVersion() + "）…");
                try {
                    final String[] rel = latestRelease();
                    if (rel == null) {
                        outcome = "未找到版本";
                        setStatus(status, "未找到可用的发布版本");
                        return;
                    }
                    final String tag = rel[1];
                    final String apkName = rel[2];
                    final String local = appVersion();
                    if (!isNewer(rel[0], local)) {
                        success = true;
                        outcome = "已是最新";
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
                        int install = installApk(apk);
                        success = install == INSTALL_LAUNCHED;
                        outcome = install == INSTALL_LAUNCHED ? "等待确认"
                                : install == INSTALL_PERMISSION_REQUIRED ? "等待授权"
                                : "无法安装";
                        if (install == INSTALL_PERMISSION_REQUIRED) {
                            setStatus(status, "安装包已就绪；授权后请返回并再次点击检查更新");
                        } else if (install == INSTALL_FAILED) {
                            setStatus(status, "安装包已就绪，但无法打开系统安装器");
                        } else {
                            setStatus(status, "已打开安装界面，请确认覆盖安装");
                        }
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
                    String downloadedVer = apkVersionOf(apk);
                    if (downloadedVer == null
                            || Version.compare(downloadedVer, rel[0]) != 0
                            || !cachedApkInstallable(apk)) {
                        if (apk.exists()) apk.delete();
                        throw new SecurityException(
                                "下载的 APK 版本、包名或签名与当前应用不匹配");
                    }
                    log("更新包已下载: " + (apk.length() / 1048576) + " MB");
                    int install = installApk(apk);
                    success = install == INSTALL_LAUNCHED;
                    outcome = install == INSTALL_LAUNCHED ? "等待确认"
                            : install == INSTALL_PERMISSION_REQUIRED ? "等待授权"
                            : "无法安装";
                    if (install == INSTALL_PERMISSION_REQUIRED) {
                        setStatus(status, "下载完成；授权后请返回并再次点击检查更新");
                    } else if (install == INSTALL_FAILED) {
                        setStatus(status, "下载完成，但无法打开系统安装器");
                    } else {
                        setStatus(status, "已打开安装界面，请确认覆盖安装");
                    }
                } catch (Throwable t) {
                    success = false;
                    outcome = "检查失败";
                    log("错误: 检查更新失败: " + t);
                    setStatus(status, "检查失败：" + shorten(t));
                    if (interactive) toast("检查更新失败");
                } finally {
                    maintenanceGate.finish(OperationGate.APP_UPDATE);
                    setMaintenanceResult(button, "检查 App 更新并安装",
                            outcome, success);
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

    private void setMaintenanceBusy(final android.widget.Button button,
                                    final String idle, final String busy,
                                    final boolean on) {
        if (button == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                DshUi.setBusy(button, idle, busy, on);
            }
        });
    }

    private void setMaintenanceResult(final android.widget.Button button,
                                      final String idle, final String outcome,
                                      final boolean success) {
        if (button == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                DshUi.finishBusy(button, idle, outcome, success);
            }
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

    private static final int INSTALL_FAILED = -1;
    private static final int INSTALL_PERMISSION_REQUIRED = 0;
    private static final int INSTALL_LAUNCHED = 1;

    /** 调起系统安装器覆盖安装。 */
    private int installApk(File apk) {
        if (!ensureInstallPermission()) return INSTALL_PERMISSION_REQUIRED;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW);
            i.setDataAndType(UpdateProvider.contentUri(),
                    "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("请在安装界面确认覆盖安装");
            return INSTALL_LAUNCHED;
        } catch (Throwable t) {
            log("错误: 调起安装器失败: " + t);
            toast("无法调起安装器: " + shorten(t));
            return INSTALL_FAILED;
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
                    || !getPackageName().equals(archive.packageName)
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
    private void updatePayloadNow(final android.widget.TextView status,
                                  final android.widget.Button button) {
        if (!maintenanceGate.tryStart(OperationGate.PAYLOAD_UPDATE)) {
            String active = maintenanceGate.active();
            String message = "已有维护任务正在进行："
                    + (active == null ? "请稍候" : active);
            setStatus(status, message);
            toast(message);
            return;
        }
        setMaintenanceBusy(button, "更新运行包（DSH / 工具链）",
                "正在更新…", true);
        // 用户主动重试即解除回滚后的自动更新暂缓；失败时恢复流程会重新设回。
        setPayloadRollbackHold(false);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String outcome = "更新失败";
                payloadLastError = "";
                setStatus(status, "正在检查运行包…");
                try {
                    File root = appRoot;
                    File node = new File(root, "node");
                    boolean upToDate = ensurePayload(node, root,
                            new File(root, "dsh"), new File(root, "tools"));
                    success = true;
                    outcome = upToDate ? "已是最新" : "更新完成";
                    clearPayloadUpdatePending();
                    setStatus(status, upToDate ? "运行包已是最新" : "运行包已更新，正在重启…");
                    if (upToDate) {
                        toast("运行包已是最新");
                    } else {
                        restartAgent();
                    }
                } catch (Throwable t) {
                    success = false;
                    outcome = "更新失败";
                    log("错误: 更新运行包失败: " + t);
                    payloadLastError = shorten(t);
                    setStatus(status, "运行包更新失败，可再次点击重试：" + payloadLastError);
                } finally {
                    maintenanceGate.finish(OperationGate.PAYLOAD_UPDATE);
                    setMaintenanceResult(button, "更新运行包（DSH / 工具链）",
                            outcome, success);
                }
            }
        }).start();
    }

    /** 用户确认后恢复最近一次更新前保存的运行环境。 */
    private void restorePayloadNow(final android.widget.TextView status,
                                   final android.widget.Button button) {
        if (!maintenanceGate.tryStart(OperationGate.PAYLOAD_ROLLBACK)) {
            String active = maintenanceGate.active();
            String message = "已有维护任务正在进行："
                    + (active == null ? "请稍候" : active);
            setStatus(status, message);
            toast(message);
            return;
        }
        setMaintenanceBusy(button, "恢复上一运行环境", "正在恢复…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                boolean success = false;
                String outcome = "恢复失败";
                try {
                    File root = appRoot;
                    if (root == null) throw new IOException("运行目录尚未就绪");
                    setStatus(status, "正在验证并恢复上一运行环境…");
                    restorePayloadRollback(new File(root, "node"), root, false);
                    success = true;
                    outcome = "恢复完成";
                    setStatus(status, "上一运行环境已恢复，正在重启…");
                    restartAgent(UiText.t("已恢复上一运行环境，正在重启服务…",
                            "Previous runtime restored. Restarting…"));
                } catch (Throwable error) {
                    payloadLastError = shorten(error);
                    log("恢复上一运行环境失败: " + error);
                    setStatus(status, "恢复失败：" + payloadLastError);
                } finally {
                    maintenanceGate.finish(OperationGate.PAYLOAD_ROLLBACK);
                    setMaintenanceResult(button, "恢复上一运行环境",
                            outcome, success);
                }
            }
        }, "payload-rollback").start();
    }

    // ---------------------------------------------------------------- 运行包
    /**
     * 下载并验证运行包清单，成功后才原子替换缓存。
     *
     * <p>旧实现直接把有效缓存截断后再下载，网络失败时所谓“回退缓存”读到的
     * 可能是空文件；超时线程还可能继续写同一文件。现在每次使用唯一临时文件，
     * 超时会主动断开连接，缓存只在完整验证通过后替换。
     */
    private synchronized File refreshPayloadManifest(final File root) throws Exception {
        final File cached = new File(root, "manifest.json");
        final File staged = new File(root, "manifest-"
                + System.nanoTime() + ".next");
        final DownloadControl control = new DownloadControl();
        final java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<Throwable>();

        try {
            Thread fetch = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        downloadPath(ASSET_PATH, "manifest.json", staged, control, false);
                    } catch (Throwable t) {
                        error.set(t);
                    }
                }
            }, "manifest-fetch");
            fetch.setDaemon(true);
            fetch.start();
            try { fetch.join(20000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                control.cancel();
                throw new IOException("清单下载被中断");
            }
            if (fetch.isAlive()) {
                control.cancel();
                try { fetch.join(3000); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                throw new IOException("清单下载超时（20 秒）");
            }
            if (error.get() != null) {
                Throwable t = error.get();
                if (t instanceof Exception) throw (Exception) t;
                throw new IOException("清单下载失败", t);
            }

            String invalid = validatePayloadManifest(staged);
            if (invalid != null) throw new IOException("运行包清单不可信：" + invalid);
            TransferState.atomicReplace(staged, cached);
            log("运行包清单已验证并更新");
            return cached;
        } catch (Throwable t) {
            control.cancel();
            TransferState.discard(new File(staged.getAbsolutePath() + ".part"),
                    new File(staged.getAbsolutePath() + ".part.id"));
            if (staged.exists()) staged.delete();

            String cachedError = validatePayloadManifest(cached);
            if (cachedError == null) {
                log("清单更新失败，使用已验证的本地缓存: " + shorten(t));
                return cached;
            }
            throw new IOException("无法取得可信的运行包清单：" + shorten(t)
                    + "；本地缓存不可用：" + cachedError);
        }
    }

    /**
     * 返回 null 表示清单可信，否则返回可读原因。
     *
     * <p>本方法只负责**把 JSON 读成标量**；结构与取值的判定全在
     * {@link PayloadManifest} 里，因为那是可离线测试的部分（org.json 只有
     * Android 有，判定逻辑若留在这里就会一直没有测试覆盖）。
     */
    private String validatePayloadManifest(File file) {
        try {
            if (file == null || !file.isFile()) return "文件不存在";
            String sizeProblem = PayloadManifest.validateManifestFileSize(file.length());
            if (sizeProblem != null) return sizeProblem;
            String digest = sha256(file);
            if (!PAYLOAD_MANIFEST_SHA256.equalsIgnoreCase(digest)) return "摘要与 APK 内置值不一致";

            org.json.JSONObject manifest = new org.json.JSONObject(readText(file));
            org.json.JSONArray parts = manifest.getJSONArray("parts");
            java.util.ArrayList<PayloadManifest.Part> parsed =
                    new java.util.ArrayList<PayloadManifest.Part>();
            for (int i = 0; i < parts.length(); i++) {
                org.json.JSONObject part = parts.getJSONObject(i);
                org.json.JSONObject sentinel = part.getJSONObject("sentinel");
                java.util.List<String> removals = null;
                org.json.JSONArray remove = part.optJSONArray("remove");
                if (remove != null) {
                    removals = new java.util.ArrayList<String>();
                    for (int k = 0; k < remove.length(); k++) {
                        removals.add(remove.optString(k, null));
                    }
                }
                parsed.add(PayloadManifest.Part
                        .of(part.getString("name"), part.getString("target"),
                                part.getLong("size"), part.getString("sha256"),
                                sentinel.getString("path"), sentinel.getLong("size"),
                                sentinel.getString("sha256"))
                        .withUnpackedSize(part.has("unpacked_size")
                                ? part.getLong("unpacked_size") : -1L)
                        .withRemovals(removals));
            }
            return PayloadManifest.validate(manifest.getInt("version"), parsed);
        } catch (Throwable t) {
            return "解析失败：" + t.getClass().getSimpleName();
        }
    }

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
        File mf = refreshPayloadManifest(root);

        org.json.JSONObject man = new org.json.JSONObject(readText(mf));
        org.json.JSONArray parts = man.getJSONArray("parts");
        log("运行包清单: " + parts.length() + " 个分片（结构版本 "
                  + man.optInt("version", 0) + "）");

        // 第一遍：逐分片判定 —— 哨兵是否匹配 + 修订号是否变高。
        // 判定逻辑在纯逻辑类 PayloadUpdate 里（55 项测试）。
        java.util.Set<String> appliedRevs = appliedRevisions();
        java.util.List<String> missing = new java.util.ArrayList<String>();
        java.util.LinkedHashSet<String> changedTargets =
                new java.util.LinkedHashSet<String>();
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
                changedTargets.add(part.getString("target"));
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

        // 下载前先把流量和空间成本说清楚，并在空间明显不足时尽早失败。
        // 解压到一半才报 ENOSPC 会留下难以诊断的半更新环境。
        long compressedBytes = 0L;
        long cachedBytes = 0L;
        long unpackedBytes = 0L;
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject part = parts.getJSONObject(i);
            String name = part.getString("name");
            if (!missing.contains(name)) continue;
            long expectedSize = part.getLong("size");
            compressedBytes += expectedSize;
            unpackedBytes += part.optLong("unpacked_size", expectedSize * 3L);
            File archive = new File(root, name);
            File partial = new File(archive.getAbsolutePath() + ".part");
            long present = Math.max(archive.isFile() ? archive.length() : 0L,
                    partial.isFile() ? partial.length() : 0L);
            cachedBytes += Math.min(expectedSize, present);
        }
        long updateRequiredBytes = PayloadUpdate.requiredFreeBytes(
                compressedBytes, cachedBytes, unpackedBytes);
        boolean rollbackNeeded = payloadLocallyComplete(dshDir, toolsDir);
        long liveTargetBytes = 0L;
        if (rollbackNeeded) {
            if (changedTargets.contains("dsh")) liveTargetBytes += directorySizeNoFollow(dshDir);
            if (changedTargets.contains("tools")) liveTargetBytes += directorySizeNoFollow(toolsDir);
        }
        long requiredBytes = rollbackNeeded
                ? PayloadRollback.requiredFreeBytes(updateRequiredBytes, liveTargetBytes)
                : updateRequiredBytes;
        long usableBytes = root.getUsableSpace();
        log("运行包空间预检: 预计至少需要 " + formatMib(requiredBytes)
                + "，当前可用 " + formatMib(usableBytes)
                + (rollbackNeeded ? "（含上一运行环境快照）" : ""));
        if (usableBytes > 0L && usableBytes < requiredBytes) {
            throw new IOException("存储空间不足：运行包更新至少需要 "
                    + formatMib(requiredBytes) + "，当前可用 " + formatMib(usableBytes));
        }
        long remainingDownload = Math.max(0L, compressedBytes - cachedBytes);
        boolean[] network = networkState();
        if (remainingDownload >= 20L * 1024L * 1024L && network[2]) {
            String notice = "当前为移动网络，预计还需下载 " + formatMib(remainingDownload);
            log(notice);
            setSplashStatus(notice + "…");
            toast(notice);
        }

        // 第二遍：先把所有需要的分片下载并校验完，再改动运行目录。
        // 这样任一网络或摘要错误都不会留下半更新环境。
        long bytes = 0;
        java.util.LinkedHashMap<String, File> archives =
                new java.util.LinkedHashMap<String, File>();
        for (int i = 0; i < parts.length(); i++) {
            org.json.JSONObject part = parts.getJSONObject(i);
            String name = part.getString("name");
            if (!missing.contains(name)) continue;

            String expected = part.getString("sha256");
            File archive = new File(root, name);
            long expectedSize = part.getLong("size");
            boolean reusable = archive.isFile() && archive.length() == expectedSize
                    && expected.equalsIgnoreCase(sha256(archive));
            if (reusable) {
                log("  复用已下载并验证的分片 " + name);
            } else {
                if (archive.exists() && !archive.delete()) {
                    throw new IOException("无法清理损坏的下载文件: " + name);
                }
                download(name, archive);
            }
            String actual = sha256(archive);
            long actualSize = archive.length();
            if (actualSize != expectedSize || !expected.equalsIgnoreCase(actual)) {
                archive.delete();
                throw new IOException("运行包校验失败: " + name
                        + "\n  期望大小 " + expectedSize + "，实际 " + actualSize
                        + "\n  期望 " + expected + "\n  实际 " + actual);
            }
            bytes += archive.length();
            archives.put(name, archive);
            log("  " + name + " 校验通过");
        }

        // 第三遍：所有输入都可信之后，先保存上一套可运行环境，再开始修改。
        // 任何解压或删除失败都会自动恢复；首次安装没有旧环境，因此不创建快照。
        boolean rollbackCreated = false;
        if (rollbackNeeded) {
            setSplashStatus("正在保存上一运行环境…");
            HarnessService.stopManagedProcess();
            createPayloadRollback(node, root, changedTargets, appliedRevs);
            rollbackCreated = true;
        }
        try {
            for (int i = 0; i < parts.length(); i++) {
                org.json.JSONObject part = parts.getJSONObject(i);
                String name = part.getString("name");
                if (!missing.contains(name)) continue;
                File dir = "dsh".equals(part.getString("target")) ? dshDir : toolsDir;
                File archive = archives.get(name);

                setSplashStatus("正在解压运行包…");
                log("解压 " + name + " …");
                run(node, root, new String[]{
                        new File(root, "unpack.js").getAbsolutePath(),
                        archive.getAbsolutePath(),
                        dir.getAbsolutePath()}, null);

                // 删除清单放在成功解压之后执行。快照已落盘，删除失败也能完整恢复。
                java.util.List<String> del = removals.get(name);
                if (del != null && !del.isEmpty()) {
                    for (String rel : del) {
                        File victim = new File(dir, rel);
                        if (victim.exists()) {
                            deletePayloadEntry(victim, dir);
                            log("  已移除 " + rel);
                        }
                    }
                }
            }
        } catch (Throwable updateFailure) {
            if (rollbackCreated) {
                try {
                    setSplashStatus("更新失败，正在恢复上一运行环境…");
                    restorePayloadRollback(node, root, true);
                    setPayloadRollbackHold(true);
                    throw new IOException("运行包更新失败，已自动恢复上一运行环境："
                            + shorten(updateFailure), updateFailure);
                } catch (IOException restored) {
                    if (restored.getCause() == updateFailure) throw restored;
                    IOException combined = new IOException(
                            "运行包更新失败，自动恢复也失败；请导出诊断包："
                                    + shorten(restored), updateFailure);
                    combined.addSuppressed(restored);
                    throw combined;
                }
            }
            if (updateFailure instanceof Exception) throw (Exception) updateFailure;
            throw new IOException("运行包更新失败", updateFailure);
        }
        // 全部分片成功后才清理归档；中途失败则保留，重试时无需重复下载。
        for (File archive : archives.values()) archive.delete();
        log("增量更新完成，本次验证 " + (bytes / 1048576) + " MB");
        // 修订号只在**全部成功后**才记录：中途失败（校验不过、解压出错）
        // 若已记下，下次启动会误判为已应用，被删的文件就永远补不回来了
        rememberPayloadRevisions(parts, dshDir, toolsDir);
        setPayloadRollbackHold(false);
        return false;
    }

    // ---------------------------------------------------------------- 工作区
    /**
     * 选择 agent 的工作目录。
     *
     * <p>优先共享存储 —— 否则用户无法把文件放进 App 私有目录，agent 也就无从下手。
     * 逐个候选路径试写；全失败则创建 App 私有工作区，保证分享导入与 agent
     * 始终使用同一个可写目录。
     */
    private File resolveWorkspace() {
        File root = resolveWorkspaceRoot();
        workspaceRoot = root;
        if (root == null) return null;
        String selected = activeProjectName();
        File active = WorkspaceProjects.directory(root, selected);
        if (active == null || (!active.isDirectory() && !active.mkdirs())) {
            log("项目工作区不可用，回退到默认工作区: " + selected);
            selected = WorkspaceProjects.DEFAULT;
            rememberActiveProject(selected);
            active = root;
        }
        seedWorkspaceReadme(active);
        log("当前项目: " + WorkspaceProjects.displayName(selected)
                + "（" + active.getAbsolutePath() + "）");
        return active;
    }

    private File resolveWorkspaceRoot() {
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
                return d;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        File fallback = new File(appRoot != null ? appRoot : getFilesDir(), "workspace");
        try {
            if (!fallback.isDirectory() && !fallback.mkdirs()) return null;
            log("共享存储不可用，改用私有工作区: " + fallback);
            return fallback;
        } catch (Throwable t) {
            log("私有工作区也不可用: " + t);
        }
        return null;
    }

    private String activeProjectName() {
        try {
            String raw = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("activeProject", WorkspaceProjects.DEFAULT);
            String normalized = WorkspaceProjects.normalize(raw);
            return normalized == null ? WorkspaceProjects.DEFAULT : normalized;
        } catch (Throwable ignored) {
            return WorkspaceProjects.DEFAULT;
        }
    }

    private void rememberActiveProject(String name) {
        String normalized = WorkspaceProjects.normalize(name);
        if (normalized == null) normalized = WorkspaceProjects.DEFAULT;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("activeProject", normalized).apply();
    }

    /** 将项目覆盖写入 DSH 的当前默认模型；没有覆盖时恢复全局默认。 */
    private boolean applyProjectModelConfig(String project, File settings) {
        try {
            if (settings == null || !settings.isFile()) return true;
            File home = settings.getParentFile();
            File projectFile = new File(home, ProjectModelSettings.FILE_NAME);
            String yaml = ProjectModelSettings.readFile(settings);
            ModelConfig.Selection current = ModelConfig.readSelection(yaml);
            if (!current.valid()) {
                log("项目模型配置跳过：当前默认模型无效");
                return true;
            }
            ProjectModelSettings.State state = ProjectModelSettings.parse(
                    ProjectModelSettings.readFile(projectFile));
            boolean changedState = false;
            if (state.global == null || !state.global.valid()) {
                ProjectModelSettings.setGlobal(state, current);
                changedState = true;
            }
            ModelConfig.Selection normalizedGlobal = ModelReasoning.normalizeSelection(yaml, state.global);
            if (!normalizedGlobal.equals(state.global)) {
                state.global = normalizedGlobal;
                changedState = true;
            }
            for (java.util.Map.Entry<String, ModelConfig.Selection> entry : state.projects.entrySet()) {
                ModelConfig.Selection normalized = ModelReasoning.normalizeSelection(yaml, entry.getValue());
                if (!normalized.equals(entry.getValue())) {
                    entry.setValue(normalized);
                    changedState = true;
                }
            }
            ModelConfig.Selection effective = ProjectModelSettings.effective(
                    state, project, current);
            if (!effective.equals(current)) {
                ProjectModelSettings.writeFileAtomic(settings,
                        ModelConfig.updateSelection(yaml, effective));
                log("已应用项目模型: " + WorkspaceProjects.displayName(project)
                        + " → " + effective.provider + " / " + effective.model
                        + " / " + effective.effort);
            }
            if (changedState) {
                ProjectModelSettings.writeFileAtomic(projectFile,
                        ProjectModelSettings.serialize(state));
            }
            return true;
        } catch (Throwable t) {
            log("项目模型配置失败: " + shorten(t));
            return false;
        }
    }

    private boolean applyProjectModelConfig(String project) {
        if (appRoot == null) return true;
        return applyProjectModelConfig(project,
                new File(new File(appRoot, ".dsh"), "settings.yaml"));
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
            HarnessService.setListener(harnessListener);
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

    /** Compact top-level hub; each category opens a focused page. */
    private void showSettings() {
        log("打开工具与设置");
        try {
            android.widget.LinearLayout body = DshUi.paddedBody(this);
            body.addView(DshUi.title(this,
                    UiText.t("工具与设置", "Tools & settings")));
            body.addView(DshUi.hint(this,
                    UiText.t("常用入口集中在这里；每页只保留一类任务，减少滚动和误触。",
                            "Common actions are grouped here. Each page contains one type of task.")),
                    DshUi.fullWidth(this, 5));

            final android.widget.Button tasks = addSettingsAction(body,
                    UiText.t("任务中心", "Task center"),
                    UiText.t("当前任务、运行时间、连接恢复与最近记录",
                            "Current task, elapsed time, recovery, and recent history"));
            final android.widget.Button sessions = addSettingsAction(body,
                    UiText.t("会话管理", "Session manager"),
                    UiText.t("搜索历史会话、查看归档与恢复入口",
                            "Search session history, view archives, and restore sessions"));
            final android.widget.Button account = addSettingsAction(body,
                    UiText.t("模型中心", "Model center"),
                    UiText.t("服务商检测、默认模型、项目覆盖与 Command Code 用量",
                            "Provider checks, default model, project overrides, and Command Code usage"));
            final android.widget.Button display = addSettingsAction(body,
                    UiText.t("显示与语言", "Display & language"),
                    UiText.t("中文 / English 与文字缩放",
                            "Chinese / English and text scaling"));
            final android.widget.Button updates = addSettingsAction(body,
                    UiText.t("更新与维护", "Updates & maintenance"),
                    UiText.t("App、DSH 运行包与补丁状态",
                            "App, DSH runtime, and patch status"));
            final android.widget.Button data = addSettingsAction(body,
                    UiText.t("数据与扩展", "Data & extensions"),
                    UiText.t("文件、加密备份与插件",
                            "Files, encrypted backups, and plugins"));
            final android.widget.Button diagnostics = addSettingsAction(body,
                    UiText.t("诊断与日志", "Diagnostics & logs"),
                    UiText.t("运行日志、诊断导出与网络检测",
                            "Runtime logs, diagnostic export, and network checks"));

            android.widget.Button close = DshUi.button(this, "关闭", true);
            final android.app.Dialog dialog = DshUi.dialog(this,
                    DshUi.scroll(this, body), DshUi.footer(this, close), 650);
            close.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { dialog.dismiss(); }
            });
            tasks.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showTaskCenter(); }
                    });
                }
            });
            sessions.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showSessionManager(); }
                    });
                }
            });
            account.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showAccountSettings(); }
                    });
                }
            });
            display.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showDisplaySettings(); }
                    });
                }
            });
            updates.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showUpdateSettings(); }
                    });
                }
            });
            data.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showDataSettings(); }
                    });
                }
            });
            diagnostics.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.swapDialog(dialog, false, new Runnable() {
                        @Override public void run() { showDiagnosticsSettings(); }
                    });
                }
            });
            DshUi.onBack(dialog, null);
            dialog.show();
        } catch (Throwable t) {
            log("错误: 打开工具与设置失败: " + t);
            toast(UiText.t("打开设置失败: ", "Could not open settings: ") + shorten(t));
        }
    }

    private android.widget.Button addSettingsAction(android.widget.LinearLayout body,
                                                    String title, String detail) {
        android.widget.Button button = DshUi.button(this, title, false);
        body.addView(button, DshUi.fullWidth(this, 14));
        body.addView(DshUi.hint(this, detail), DshUi.fullWidth(this, 4));
        return button;
    }

    /** 使用 DSH 官方会话搜索与归档界面，避免复制不稳定的内部 RPC。 */
    private void showSessionManager() {
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this, UiText.t("会话管理", "Session manager")));
        body.addView(DshUi.hint(this, UiText.t(
                "搜索覆盖会话标题与可用的历史索引。归档不会删除记录；已归档会话可恢复。",
                "Search uses session titles and the available history index. Archiving keeps the record and can be undone.")),
                DshUi.fullWidth(this, 7));
        android.widget.Button search = DshUi.button(this,
                UiText.t("搜索会话", "Search sessions"), false);
        android.widget.Button archived = DshUi.button(this,
                UiText.t("查看已归档会话", "View archived sessions"), false);
        body.addView(search, DshUi.fullWidth(this, 16));
        body.addView(archived, DshUi.fullWidth(this, 8));
        body.addView(DshUi.hint(this, UiText.t(
                "归档方法：在侧栏打开会话的更多操作并选择“归档会话”。任务运行中时，DSH 会要求确认停止后再归档。",
                "To archive, open a session's More menu in the sidebar and choose Archive session. DSH asks before stopping active work.")),
                DshUi.fullWidth(this, 14));
        android.widget.Button back = DshUi.button(this, UiText.t("返回", "Back"), true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back), 520);
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        search.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                dialog.dismiss();
                try { webView.evaluateJavascript(SessionOrganizer.focusSearchScript(), null); }
                catch (Throwable t) { toast(UiText.t("会话搜索暂不可用", "Session search is unavailable")); }
            }
        });
        archived.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                dialog.dismiss();
                try { webView.evaluateJavascript(SessionOrganizer.showArchivedScript(), null); }
                catch (Throwable t) { toast(UiText.t("归档入口暂不可用", "Archive view is unavailable")); }
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
    }

    /** 当前任务、连接恢复与最近记录的统一入口。 */
    private void showTaskCenter() {
        final long openedAt = System.currentTimeMillis();
        final android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this, UiText.t("任务中心", "Task center")));
        body.addView(DshUi.hint(this,
                UiText.t("状态来自 DSH 会话列表与页面探针；历史最多保留 30 条。",
                        "Status comes from the DSH session list and page probes. Up to 30 records are kept.")),
                DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("连接", "Connection")), DshUi.fullWidth(this, 4));
        boolean[] network = networkState();
        String networkLabel = SessionStatus.networkLabel(
                network[1], network[2], network[3], network[0], UiText.isEnglish());
        final android.widget.TextView connection = DshUi.status(this,
                ConnectionRecovery.label(connectionState, UiText.isEnglish())
                        + " · " + networkLabel);
        body.addView(connection, DshUi.fullWidth(this, 10));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("当前任务", "Current task")), DshUi.fullWidth(this, 4));
        final android.widget.TextView current = DshUi.status(this,
                currentTaskText(openedAt));
        body.addView(current, DshUi.fullWidth(this, 10));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("最近记录", "Recent history")), DshUi.fullWidth(this, 4));
        java.util.List<TaskTimeline.Entry> history = taskTimeline.newestFirst();
        int shown = 0;
        boolean hasFinished = false;
        for (TaskTimeline.Entry entry : history) {
            if (!entry.isActive()) hasFinished = true;
            if (shown >= 10 || entry.isActive()) continue;
            body.addView(DshUi.status(this, taskEntryText(entry, openedAt)),
                    DshUi.fullWidth(this, 6));
            shown++;
        }
        if (shown == 0) {
            body.addView(DshUi.hint(this,
                    UiText.t("暂无已结束记录", "No finished tasks yet")),
                    DshUi.fullWidth(this, 6));
        }

        android.widget.Button back = DshUi.button(this,
                UiText.t("返回", "Back"), false);
        android.widget.Button sync = DshUi.button(this,
                UiText.t("重新同步", "Resync"), true);
        final android.widget.Button clear = DshUi.button(this,
                UiText.t("清理历史", "Clear history"), false);
        clear.setEnabled(hasFinished);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back, sync, clear), 680);

        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        sync.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                try {
                    installSessionProbe(webView);
                    installConnectionWatcher(webView);
                    installStatusWatcher();
                    if (webView != null) {
                        webView.evaluateJavascript(SessionProbe.refreshScript(), null);
                    }
                    pushStatus(lastSessionStatus);
                    toast(UiText.t("已请求重新同步任务状态",
                            "Task status resync requested"));
                } catch (Throwable t) {
                    log("任务中心重新同步失败: " + t);
                    toast(UiText.t("重新同步失败", "Resync failed"));
                }
            }
        });
        clear.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.confirm(MainActivity.this,
                        UiText.t("清理任务历史？", "Clear task history?"),
                        UiText.t("只删除已结束记录；正在运行或恢复中的任务会保留。",
                                "Only finished records are removed. Active and recovering tasks are kept."),
                        UiText.t("清理", "Clear"), new Runnable() {
                            @Override public void run() {
                                if (taskTimeline.clearFinished()) persistTaskTimeline();
                                dialog.dismiss();
                                showTaskCenter();
                            }
                        });
            }
        });

        final android.os.Handler timer =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                current.setText(currentTaskText(System.currentTimeMillis()));
                boolean[] net = networkState();
                connection.setText(ConnectionRecovery.label(
                        connectionState, UiText.isEnglish()) + " · "
                        + SessionStatus.networkLabel(
                                net[1], net[2], net[3], net[0], UiText.isEnglish()));
                timer.postDelayed(this, 1000L);
            }
        };
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface ignored) {
                timer.removeCallbacks(tick);
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
        timer.postDelayed(tick, 1000L);
    }

    private String currentTaskText(long now) {
        TaskTimeline.Entry active = taskTimeline.active();
        return active == null
                ? UiText.t("当前没有任务在运行", "No task is currently running")
                : taskEntryText(active, now);
    }

    /** 任务中心的一行：状态、开始时间、持续时长和是否经历断线。 */
    private String taskEntryText(TaskTimeline.Entry entry, long now) {
        String time;
        try {
            time = android.text.format.DateFormat.getTimeFormat(this)
                    .format(new java.util.Date(entry.startedAt()));
        } catch (Throwable ignored) {
            time = String.valueOf(entry.startedAt());
        }
        StringBuilder line = new StringBuilder();
        line.append(TaskTimeline.label(entry.state(), UiText.isEnglish()))
                .append(" · ").append(time)
                .append(" · ").append(TaskNotifier.duration(
                        entry.durationAt(now), UiText.isEnglish()));
        if (entry.connectionInterrupted()) {
            line.append(UiText.t(" · 曾发生断线", " · Connection interrupted"));
        }
        return line.toString();
    }

    private void showAccountSettings() {
        ModelCenterPanel.show(this, modelCenterHost(false), false);
    }

    private void showModelOnboarding() {
        ModelCenterPanel.show(this, modelCenterHost(false), true);
    }

    private void showProjectModelSettings(String project) {
        ModelCenterPanel.showProject(this, modelCenterHost(true), project);
    }

    private ModelCenterPanel.Host modelCenterHost(final boolean returnToProjects) {
        return new ModelCenterPanel.Host() {
            @Override public File dshHome() {
                return new File(appRoot, ".dsh");
            }

            @Override public String activeProject() { return activeProjectName(); }

            @Override public void log(String message) { MainActivity.this.log(message); }

            @Override public void openCommandCodeUsage(String key) {
                CommandCodePanel.show(MainActivity.this, key);
            }

            @Override public void refreshModelCatalog() {
                // A plain WebView reload is not enough here: the long-lived DSH
                // process can still hold the provider topology/model directory
                // created at boot. Restart the idle runtime so the settings
                // seam is read from disk before the WebUI builds its selector.
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("modelCatalogPending", true).apply();
                if (lastSessionStatus != SessionStatus.IDLE || taskTimeline.active() != null) {
                    log("模型目录已写入 DSH；当前任务运行中，暂不重启运行时（状态未知也延后）");
                    toast(UiText.t("模型目录已保存，确认空闲后自动应用",
                            "Model catalog saved; it will apply automatically when DSH is idle."));
                    return;
                }
                applyPendingModelCatalog();
            }

            @Override public void closeModelCenter(boolean onboarding, boolean saved) {
                if (onboarding) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("modelOnboardingPending", false)
                            .putBoolean("modelOnboardingCompleted", true)
                            .apply();
                    toast(saved
                            ? UiText.t("配置已保存，可以开始使用", "Setup saved. You can start using the app.")
                            : UiText.t("可随时从“模型中心”继续配置", "Continue setup any time from Model center."));
                    return;
                }
                if (saved) {
                    toast(UiText.t("已保存并同步 WebUI 模型目录；新会话使用新模型",
                            "Saved and synced to the WebUI catalog; new sessions use the new model."));
                }
                if (returnToProjects) showWorkspaceProjects();
                else showSettings();
            }
        };
    }

    private void showDisplaySettings() {
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("显示与语言", "Display & language")));
        body.addView(DshUi.sectionLabel(this,
                UiText.t("界面语言", "Interface language")), DshUi.fullWidth(this, 12));
        body.addView(DshUi.hint(this, UiText.t(
                "原生工具界面使用此语言；DSH 网页语言可在网页设置中单独调整。",
                "This controls the native tools. Change the DSH web language separately in web settings.")),
                DshUi.fullWidth(this, 5));

        final String[] choice = { uiLanguagePreference() };
        final android.widget.LinearLayout languageRow = new android.widget.LinearLayout(this);
        languageRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fillLanguageRow(languageRow, choice);
        body.addView(languageRow, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("显示缩放", "Display scale")), DshUi.fullWidth(this, 22));
        body.addView(DshUi.hint(this, UiText.t(
                "界面会按手机、横屏和平板宽度响应式适配；文字偏小可在此放大（立即生效）",
                "The layout adapts to phones, landscape, and tablets. Increase text size here; changes apply immediately.")),
                DshUi.fullWidth(this, 6));
        android.widget.LinearLayout zoomRow = new android.widget.LinearLayout(this);
        zoomRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fillZoomRow(zoomRow);
        body.addView(zoomRow, DshUi.fullWidth(this, 8));

        android.widget.Button back = DshUi.button(this, "返回", false);
        android.widget.Button apply = DshUi.button(this, "应用", true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back, apply), 560);
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        apply.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                applyUiLanguage(choice[0]);
                dialog.dismiss();
                toast(UiText.t("语言已切换", "Language changed"));
                showSettings();
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
    }

    private void fillLanguageRow(final android.widget.LinearLayout row,
                                 final String[] choice) {
        row.removeAllViews();
        String[] values = { UiText.AUTO, UiText.ZH, UiText.EN };
        String[] labels = { UiText.t("跟随系统", "System"), "中文", "English" };
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            android.widget.Button b = DshUi.toggleButton(this, labels[i],
                    value.equals(choice[0]));
            b.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.choiceActivated(v);
                    choice[0] = value;
                    fillLanguageRow(row, choice);
                    DshUi.animateChoiceChange(row);
                }
            });
            addEqualButton(row, b, i == 0 ? 0 : 6);
        }
    }

    private void showUpdateSettings() {
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("更新与维护", "Updates & maintenance")));
        final android.widget.TextView status =
                DshUi.status(this, UiText.t("当前 App 版本 ", "Current app version ")
                        + appVersion());
        body.addView(status, DshUi.fullWidth(this, 6));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("更新通道", "Update channel")), DshUi.fullWidth(this, 18));
        body.addView(DshUi.hint(this, UiText.t(
                "此选项只决定检查哪个更新源，不代表当前安装包类型。稳定版只接收正式发布；"
                        + "测试版可提前安装新功能。当前安装包：" + appVersion(),
                "This selects which update feed to check; it does not describe the installed build. "
                        + "Stable receives public releases, while Test can receive previews. Installed build: "
                        + appVersion())),
                DshUi.fullWidth(this, 5));
        final android.widget.LinearLayout channelRow = new android.widget.LinearLayout(this);
        channelRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        fillUpdateChannelRow(channelRow, status);
        body.addView(channelRow, DshUi.fullWidth(this, 8));

        final android.widget.Button payload =
                DshUi.button(this, UiText.t("更新运行包（DSH / 工具链）",
                        "Update runtime (DSH / toolchain)"), false);
        payload.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                log("用户点击: 更新运行包"); updatePayloadNow(status, payload);
            }
        });
        body.addView(payload, DshUi.fullWidth(this, 12));
        final android.widget.TextView rollbackInfo =
                DshUi.hint(this, payloadRollbackSummary()
                        + (payloadRollbackHold()
                        ? "。自动更新已暂缓，手动更新成功后会解除。" : ""));
        body.addView(rollbackInfo, DshUi.fullWidth(this, 8));
        final android.widget.Button rollback =
                DshUi.button(this, UiText.t("恢复上一运行环境",
                        "Restore previous runtime"), false);
        final boolean rollbackAvailable = appRoot != null
                && readPayloadRollback(appRoot, false) != null;
        rollback.setEnabled(rollbackAvailable);
        rollback.setAlpha(rollbackAvailable ? 1f : 0.58f);
        rollback.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (!rollbackAvailable) return;
                DshUi.confirm(MainActivity.this,
                        UiText.t("恢复上一运行环境？", "Restore the previous runtime?"),
                        UiText.t("仅恢复 DSH 与工具链，不改动会话、账户密钥、项目文件或 App。"
                                        + "恢复后会重启服务，并暂缓自动更新，直到你手动重试。",
                                "Only DSH and its toolchain are restored. Sessions, account keys, project files, and the app are unchanged. "
                                        + "The service restarts and automatic runtime updates pause until you retry manually."),
                        UiText.t("恢复并重启", "Restore & restart"), new Runnable() {
                            @Override public void run() {
                                log("用户确认: 恢复上一运行环境");
                                restorePayloadNow(status, rollback);
                            }
                        });
            }
        });
        body.addView(rollback, DshUi.fullWidth(this, 8));
        final android.widget.Button app =
                DshUi.button(this, UiText.t("检查 App 更新并安装",
                        "Check and install app update"), false);
        app.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                log("用户点击: 检查 App 更新"); checkAppUpdate(true, status, app);
            }
        });
        body.addView(app, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("维护状态", "Maintenance status")), DshUi.fullWidth(this, 22));
        android.widget.TextView patch = DshUi.hint(this, "");
        patch.setText(buildPatchReport());
        body.addView(patch, DshUi.fullWidth(this, 6));

        android.widget.Button back = DshUi.button(this, "返回", true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back), 620);
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
    }

    private void fillUpdateChannelRow(final android.widget.LinearLayout row,
                                      final android.widget.TextView status) {
        row.removeAllViews();
        String current = updateChannelPreference();
        String[] values = { ReleaseChannel.STABLE, ReleaseChannel.TEST };
        String[] labels = { UiText.t("稳定版", "Stable"), UiText.t("测试版", "Test") };
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            android.widget.Button button = DshUi.toggleButton(this, labels[i],
                    value.equals(current));
            button.setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    DshUi.choiceActivated(v);
                    setUpdateChannelPreference(value);
                    fillUpdateChannelRow(row, status);
                    DshUi.animateChoiceChange(row);
                    setStatus(status, UiText.t(
                            "已切换到" + ReleaseChannel.label(value) + "，下次检查立即生效",
                            "Switched to " + (ReleaseChannel.TEST.equals(value) ? "Test" : "Stable")
                                    + "; the next check uses this channel."));
                }
            });
            addEqualButton(row, button, i == 0 ? 0 : 6);
        }
    }

    private android.text.SpannableStringBuilder buildPatchReport() {
        android.text.SpannableStringBuilder report = new android.text.SpannableStringBuilder();
        if (patchReport.isEmpty()) {
            report.append(UiText.text("（暂无补丁记录）"));
        } else {
            for (String line : patchReport.values()) {
                boolean warn = line.startsWith("\u0001");
                String text = line.length() > 0 ? line.substring(1) : line;
                int start = report.length();
                report.append(text).append('\n');
                report.setSpan(new android.text.style.ForegroundColorSpan(
                                warn ? DshUi.WARN() : DshUi.TEXT_2()),
                        start, report.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        int tail = report.length();
        report.append("App ").append(appVersion()).append("  ")
                .append(UiText.t("运行包 ", "Runtime ")).append(payloadSummary());
        report.setSpan(new android.text.style.ForegroundColorSpan(DshUi.TEXT_3()),
                tail, report.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return report;
    }

    private void showDataSettings() {
        final File dshHome = new File(appRoot, ".dsh");
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("数据与扩展", "Data & extensions")));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("项目与工作区", "Projects & workspace")), DshUi.fullWidth(this, 12));
        body.addView(DshUi.hint(this, UiText.t(
                "当前：" + UiText.text(WorkspaceProjects.displayName(activeProjectName()))
                        + "。每个项目使用独立工作目录，切换时会重启 agent。",
                "Current: " + UiText.text(WorkspaceProjects.displayName(activeProjectName()))
                        + ". Each project has its own working directory; switching restarts the agent.")),
                DshUi.fullWidth(this, 6));
        final android.widget.Button projects = DshUi.button(this,
                UiText.t("管理项目", "Manage projects"), false);
        body.addView(projects, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("文件", "Files")), DshUi.fullWidth(this, 22));
        body.addView(DshUi.hint(this, UiText.t(
                "浏览应用私有目录、工作区与共享存储；文本文件可直接编辑",
                "Browse app storage, workspaces, and shared storage; edit text files directly.")),
                DshUi.fullWidth(this, 6));
        android.widget.Button files = DshUi.button(this,
                UiText.t("浏览文件", "Browse files"), false);
        files.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                FileBrowser.show(MainActivity.this, appRoot);
            }
        });
        body.addView(files, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("配置备份", "Configuration backup")), DshUi.fullWidth(this, 22));
        body.addView(DshUi.hint(this, UiText.t(
                "把账户密钥与模型配置导出到共享存储；重装或换机后可恢复",
                "Export encrypted account and model settings for reinstall or device migration.")),
                DshUi.fullWidth(this, 6));
        android.widget.Button backup = DshUi.button(this,
                UiText.t("备份与恢复", "Backup & restore"), false);
        backup.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                ConfigBackupPanel.show(MainActivity.this, appRoot, dshHome);
            }
        });
        body.addView(backup, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("插件", "Plugins")), DshUi.fullWidth(this, 22));
        body.addView(DshUi.hint(this, UiText.t(
                "启用内置插件，或从 npm 安装社区插件（重启后生效）",
                "Enable built-in plugins or install community plugins from npm. Restart to apply changes.")),
                DshUi.fullWidth(this, 6));
        android.widget.Button plugins = DshUi.button(this,
                UiText.t("管理插件", "Manage plugins"), false);
        final File pluginDshDir = dshDirRef;
        plugins.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                if (pluginDshDir == null || toolsDirRef == null || nodeRef == null) {
                    toast(UiText.t("运行环境尚未就绪，请稍后再试",
                            "The runtime is not ready yet. Try again shortly."));
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
                    @Override public boolean hasPermissionGrant(String key) {
                        return pluginPermissionGrants().contains(key);
                    }
                    @Override public void savePermissionGrant(String key) {
                        rememberPluginPermissionGrant(key);
                    }
                });
            }
        });
        body.addView(plugins, DshUi.fullWidth(this, 8));

        android.widget.Button back = DshUi.button(this, "返回", true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back), 650);
        projects.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                File availableRoot = workspaceRoot != null
                        ? workspaceRoot : resolveWorkspaceRoot();
                if (availableRoot == null) {
                    toast(UiText.t("工作区不可用", "Workspace unavailable"));
                    return;
                }
                DshUi.swapDialog(dialog, false, new Runnable() {
                    @Override public void run() { showWorkspaceProjects(); }
                });
            }
        });
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
    }

    private void showWorkspaceProjects() {
        final File root = workspaceRoot != null ? workspaceRoot : resolveWorkspaceRoot();
        if (root == null) {
            toast(UiText.t("工作区不可用", "Workspace unavailable"));
            return;
        }
        workspaceRoot = root;
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("项目与工作区", "Projects & workspace")));
        body.addView(DshUi.hint(this, UiText.t(
                "命名项目保存在工作区的 projects 目录。默认工作区保留原有文件，"
                        + "不会自动迁移或删除。",
                "Named projects are stored under the workspace's projects directory. "
                        + "Existing files remain in the default workspace and are never moved or deleted automatically.")),
                DshUi.fullWidth(this, 5));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("新建项目", "New project")), DshUi.fullWidth(this, 18));
        final android.widget.EditText name = DshUi.input(this, "", false);
        name.setHint(UiText.t("例如：语文备课、南溟项目",
                "For example: Lesson plans or My project"));
        name.setSingleLine(true);
        body.addView(name, DshUi.fullWidth(this, 6));
        final android.widget.Button create = DshUi.button(this,
                UiText.t("新建并切换", "Create & switch"), true);
        body.addView(create, DshUi.fullWidth(this, 6));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("已有项目", "Existing projects")), DshUi.fullWidth(this, 22));
        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        body.addView(list, DshUi.fullWidth(this, 6));
        android.widget.Button browse = DshUi.button(this,
                UiText.t("浏览当前项目", "Browse current project"), false);
        android.widget.Button back = DshUi.button(this, "返回", true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, browse, back), 680);
        browse.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                File target = workspace != null ? workspace : root;
                FileBrowser.show(MainActivity.this, target);
            }
        });
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showDataSettings(); }
                });
            }
        });
        create.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                String normalized = WorkspaceProjects.normalize(name.getText().toString());
                if (normalized == null || normalized.length() == 0) {
                    toast(UiText.t("项目名需为 1 至 48 个字符，且不能包含路径符号",
                            "Use 1–48 characters and no path separators."));
                    return;
                }
                if (!WorkspaceProjects.create(root, normalized)) {
                    toast(UiText.t("无法创建项目目录",
                            "Could not create the project directory."));
                    return;
                }
                requestWorkspaceProjectSwitch(normalized, dialog, create,
                        UiText.t("新建并切换", "Create & switch"));
            }
        });
        fillWorkspaceProjectList(list, root, dialog);
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showDataSettings(); }
        });
        dialog.show();
    }

    private void fillWorkspaceProjectList(android.widget.LinearLayout list, File root,
                                          android.app.Dialog dialog) {
        list.removeAllViews();
        addWorkspaceProjectRow(list, root, WorkspaceProjects.DEFAULT, dialog);
        for (String project : WorkspaceProjects.list(root)) {
            addWorkspaceProjectRow(list, root, project, dialog);
        }
    }

    private void addWorkspaceProjectRow(android.widget.LinearLayout list, File root,
                                        final String project,
                                        final android.app.Dialog dialog) {
        boolean active = project.equals(activeProjectName());
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView label = DshUi.label(this,
                WorkspaceProjects.displayName(project));
        row.addView(label, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Button button = DshUi.toggleButton(this,
                active ? UiText.t("正在使用", "Active") : UiText.t("切换", "Switch"), active);
        button.setEnabled(!active);
        android.widget.Button modelButton = DshUi.button(this,
                UiText.t("模型", "Model"), false);
        android.widget.LinearLayout.LayoutParams modelParams =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        modelParams.rightMargin = DshUi.dp(this, 6);
        row.addView(modelButton, modelParams);
        final android.widget.Button switchButton = button;
        modelButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, false, new Runnable() {
                    @Override public void run() { showProjectModelSettings(project); }
                });
            }
        });
        button.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                requestWorkspaceProjectSwitch(project, dialog, switchButton,
                        UiText.t("切换", "Switch"));
            }
        });
        row.addView(button, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(row, DshUi.fullWidth(this, 4));
    }

    private void requestWorkspaceProjectSwitch(final String project,
                                               final android.app.Dialog origin,
                                               final android.widget.Button button,
                                               final String idleLabel) {
        DshUi.setBusy(button, idleLabel, UiText.t("切换中…", "Switching…"), true);
        final Runnable apply = new Runnable() {
            @Override public void run() {
                if (!applyProjectModelConfig(project)) {
                    toast(UiText.t("无法应用项目模型配置，项目未切换",
                            "Could not apply the project model; the project was not switched."));
                    DshUi.setBusy(button, idleLabel,
                            UiText.t("切换中…", "Switching…"), false);
                    return;
                }
                if (origin != null) origin.dismiss();
                rememberActiveProject(project);
                String display = UiText.text(WorkspaceProjects.displayName(project));
                restartAgent(UiText.t("正在切换到“" + display + "”…",
                        "Switching to \"" + display + "\"…"));
            }
        };
        final Runnable cancel = new Runnable() {
            @Override public void run() {
                DshUi.setBusy(button, idleLabel,
                        UiText.t("切换中…", "Switching…"), false);
            }
        };
        if (lastSessionStatus == SessionStatus.RUNNING
                || lastSessionStatus == SessionStatus.AWAITING_APPROVAL) {
            DshUi.confirm(this,
                    UiText.t("切换项目会中断当前任务",
                            "Switching projects interrupts the current task"),
                    UiText.t("agent 需要重启后才能使用新的工作目录。正在运行的任务会被中断，是否继续？",
                            "The agent must restart to use the new working directory. The running task will be interrupted. Continue?"),
                    UiText.t("切换并重启", "Switch & restart"), apply, cancel);
        } else {
            apply.run();
        }
    }

    private void showDiagnosticsSettings() {
        android.widget.LinearLayout body = DshUi.paddedBody(this);
        body.addView(DshUi.title(this,
                UiText.t("诊断与日志", "Diagnostics & logs")));

        android.widget.Button logButton = DshUi.button(this,
                UiText.t("查看运行日志", "View runtime log"), false);
        logButton.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { showLog(); }
        });
        body.addView(logButton, DshUi.fullWidth(this, 12));
        body.addView(DshUi.hint(this, UiText.t(
                "诊断包包含设备、布局、网络、通知、运行环境与回滚状态，以及脱敏后的"
                        + "最近日志；不会包含凭据、会话正文、附件或项目文件。",
                "The diagnostic bundle includes device, layout, network, notification, runtime, rollback, "
                        + "and redacted recent-log details. It excludes credentials, session content, attachments, and project files.")),
                DshUi.fullWidth(this, 6));
        android.widget.Button export = DshUi.button(this,
                UiText.t("导出诊断包", "Export diagnostic bundle"), false);
        export.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                log("用户点击: 导出诊断包"); exportDiagnostics();
            }
        });
        body.addView(export, DshUi.fullWidth(this, 8));

        body.addView(DshUi.sectionLabel(this,
                UiText.t("网络", "Network")), DshUi.fullWidth(this, 22));
        body.addView(DshUi.hint(this, UiText.t(
                "检测更新功能依赖的各个源是否可用（直连与镜像分开报告）",
                "Check every update source and report direct and mirror connectivity separately.")),
                DshUi.fullWidth(this, 6));
        android.widget.Button network = DshUi.button(this,
                UiText.t("网络诊断", "Network diagnostics"), false);
        network.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                NetworkDiag.show(MainActivity.this, apkDownloadUrl());
            }
        });
        body.addView(network, DshUi.fullWidth(this, 8));

        android.widget.Button back = DshUi.button(this, "返回", true);
        final android.app.Dialog dialog = DshUi.dialog(this,
                DshUi.scroll(this, body), DshUi.footer(this, back), 560);
        back.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                DshUi.swapDialog(dialog, true, new Runnable() {
                    @Override public void run() { showSettings(); }
                });
            }
        });
        DshUi.onBack(dialog, new Runnable() {
            @Override public void run() { showSettings(); }
        });
        dialog.show();
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
        restartAgent(UiText.t("正在重启服务…", "Restarting service…"));
    }

    private boolean modelCatalogRestartScheduled;

    private void applyPendingModelCatalog() {
        if (modelCatalogRestartScheduled || lastSessionStatus != SessionStatus.IDLE
                || taskTimeline.active() != null || !getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("modelCatalogPending", false)) return;
        modelCatalogRestartScheduled = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                modelCatalogRestartScheduled = false;
                if (isFinishing() || isDestroyed() || lastSessionStatus != SessionStatus.IDLE
                        || taskTimeline.active() != null) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("modelCatalogPending", false).apply();
                try {
                    lastSessionStatus = SessionStatus.UNKNOWN;
                    restartAgent(UiText.t("正在应用上游模型目录…", "Applying the upstream model catalog…"));
                    log("已应用模型目录，正在重启空闲 DSH 并刷新 WebUI");
                } catch (Throwable error) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("modelCatalogPending", true).apply();
                    log("应用模型目录失败: " + shorten(error));
                }
            }
        }, 1200L);
    }

    private void restartAgent(final String progressMessage) {
        HarnessService.stopManagedProcess();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove("dshPort").remove("dshToken").apply();
        dshPageLoaded = false;
        splashHidden = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (splashView != null) {
                    splashView.clearAnimation();
                    splashView.setAlpha(1f);
                    splashView.setVisibility(android.view.View.VISIBLE);
                }
                if (splashStatus != null) splashStatus.setText(progressMessage);
                startSplashAnimation();
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
    private void applyRootInsets() {
        if (rootView == null) return;
        rootView.setPadding(
                UiPolicy.safeSide(safeInsetLeft, 0),
                statusBarHeight(),
                UiPolicy.safeSide(safeInsetRight, 0),
                UiPolicy.mergedIme(imeInsetPadding, imeFramePadding));
    }

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
                        int left = insets.getSystemWindowInsetLeft();
                        int right = insets.getSystemWindowInsetRight();
                        int cutoutLeft = 0;
                        int cutoutRight = 0;
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            android.view.DisplayCutout cut = insets.getDisplayCutout();
                            if (cut != null) {
                                cutoutLeft = cut.getSafeInsetLeft();
                                cutoutRight = cut.getSafeInsetRight();
                            }
                        }
                        safeInsetLeft = UiPolicy.safeSide(left, cutoutLeft);
                        safeInsetRight = UiPolicy.safeSide(right, cutoutRight);
                        imeInsetPadding = UiPolicy.imeFromInsets(bottom, nav);

                        if (imeInsetPadding != lastPad) {
                            lastPad = imeInsetPadding;
                            log("键盘内边距 " + imeInsetPadding
                                    + "px（底部 inset=" + bottom + ", 导航栏=" + nav + "）");
                        }
                        if (safeInsetLeft != lastLeft || safeInsetRight != lastRight) {
                            lastLeft = safeInsetLeft;
                            lastRight = safeInsetRight;
                            log("左右内边距 " + safeInsetLeft + " / "
                                    + safeInsetRight + "px（刘海/导航栏）");
                        }
                        // 无论变化的是底部还是左右安全区都重新应用，
                        // 避免横竖屏时因键盘高度未变而保留旧侧边距。
                        applyRootInsets();
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

        // 可见窗口是第二条键盘探测路径。两者取较大值，但这里绝不
        // 改写左右 padding，否则会在横屏键盘弹出时把刘海安全区清零。
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
                        int nav = navigationBarHeight();
                        int kb = UiPolicy.imeFromVisibleFrame(
                                screenH, visible.bottom, nav);
                        if (kb != lastKb) {
                            lastKb = kb;
                            imeFramePadding = kb;
                            log("键盘检测: 高 " + kb + "px（窗口 " + screenH
                                    + ", 可见底 " + visible.bottom + ", 导航栏 " + nav + "）");
                            applyRootInsets();
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
        splashLogo = logo;
        int id = getResources().getIdentifier(
                "ic_launcher_foreground", "mipmap", getPackageName());
        if (id > 0) logo.setImageResource(id);
        // 鲸鱼是纯装饰：让读屏跳过它，而不是念出一个无意义的图标名
        logo.setImportantForAccessibility(
                android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int boxSize = (int) (196 * d);          // 前景里鲸鱼约占 58%，故视图取得大些
        col.addView(logo, new android.widget.LinearLayout.LayoutParams(boxSize, boxSize));

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
        startSplashAnimation();
        return box;
    }

    /** 启动开屏呼吸动画；系统关闭动画时保持静态。 */
    private void startSplashAnimation() {
        stopSplashAnimation();
        if (splashLogo == null || !DshUi.animationsEnabled(this)) return;
        try {
            android.animation.PropertyValuesHolder a =
                    android.animation.PropertyValuesHolder.ofFloat("alpha", 0.45f, 1f);
            android.animation.PropertyValuesHolder sx =
                    android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.93f, 1f);
            android.animation.PropertyValuesHolder sy =
                    android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.93f, 1f);
            splashAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    splashLogo, a, sx, sy);
            splashAnimator.setDuration(1150);
            splashAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            splashAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            splashAnimator.start();
        } catch (Throwable ignored) {
            splashAnimator = null;
        }
    }

    /** 取消无限动画并复位，避免开屏隐藏后继续消耗 CPU/GPU。 */
    private void stopSplashAnimation() {
        try {
            if (splashAnimator != null) splashAnimator.cancel();
        } catch (Throwable ignored) { }
        splashAnimator = null;
        try {
            if (splashLogo != null) {
                splashLogo.setAlpha(1f);
                splashLogo.setScaleX(1f);
                splashLogo.setScaleY(1f);
            }
        } catch (Throwable ignored) { }
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
                stopSplashAnimation();
                // 立即停止接收触摸：即使动画因故未结束，也不会再挡住界面
                sv.setClickable(false);
                sv.setFocusable(false);
                if (!DshUi.animationsEnabled(MainActivity.this)) {
                    sv.clearAnimation();
                    sv.setVisibility(android.view.View.GONE);
                    return;
                }
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
        return YamlBlocks.hasTopLevelKey(yaml, key);
    }

    /**
     * 把预置 YAML 中「目标文件尚不存在的顶层块」追加进去。
     *
     * @return 实际追加的顶层键名
     */
    private java.util.List<String> mergeTopLevelBlocks(String preset, File dst) throws IOException {
        String target = dst.exists() ? readText(dst) : "";
        YamlBlocks.Result merged = YamlBlocks.mergeMissingBlocks(preset, target);
        if (merged.isEmpty()) return merged.added;
        writeText(dst, merged.content);
        return merged.added;
    }

    /**
     * 把源凭据文件 refs: 段下的条目合并进目标文件（跳过已存在的键）。
     *
     * <p>解析与合并判定在 {@link CredentialMerge}（纯逻辑、可离线测试），
     * 这里只负责读写文件。改动它之前请先看那边的测试。
     *
     * @return 实际导入的键值对
     */
    private java.util.List<String> mergeCredentials(File src, File dst) throws IOException {
        String mine = readText(src);
        String target = dst.exists() ? readText(dst) : "";
        CredentialMerge.Result merged = CredentialMerge.merge(mine, target);
        if (merged.isEmpty()) return merged.added;
        writeText(dst, merged.content);
        return merged.added;
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
            CrashReporter.install(crashFile);
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
        patchModelEffortUi(dshDir);
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

    private void patchModelEffortUi(File dshDir) {
        try {
            File client = new File(dshDir,
                    "node_modules/@deepseek-ai/dsh-client-ui-model-selection/lib/client.js");
            String source = readText(client);
            String patched = ModelEffortUi.patch(source);
            if (patched == null) {
                recordPatch("max 请求标记", false, "上游选择器结构变化，未修改文件");
                log("  [警告] max 请求标记未应用：选择器结构变化");
                return;
            }
            if (!source.equals(patched)) writeText(client, patched);
            recordPatch("max 请求标记", true, "聊天框显示 Max（说明在悬浮提示里）");
        } catch (Throwable error) {
            recordPatch("max 请求标记", false, "文件读取或写入失败");
            log("  [警告] max 请求标记补丁失败: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 手机端适配：为前端写入安全的最小 viewport 与响应式 CSS。
     *
     * <p>DSH 的 Web 界面是桌面优先设计：设置弹窗需要约 600 CSS px，
     * 而手机纵向只有约 400 px，导致内容横向溢出、被切割挤压。
     *
     * <p>窄屏保留 480 CSS px 防止桌面组件被压坏；横屏与平板使用真实宽度，
     * 不再一律缩小到 0.67 倍。对话框、输入框、代码块和触控目标另加移动端规则。
     */
    private int viewportWidthFor(android.content.res.Configuration cfg) {
        return MobileLayout.viewportWidth(cfg != null ? cfg.screenWidthDp : 400);
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
            String patched = MobileLayout.patchHtml(src, want);
            if (patched == null) {
                log("  [警告] viewport 标签格式不符，未做适配");
                recordPatch("前端 viewport", false, "标签格式不符，未适配");
                return;
            }
            if (!patched.equals(src)) {
                writeText(html, patched);
                log("  已写入响应式布局与 viewport：" + want + "px");
            } else {
                log("  响应式布局已是最新（" + want + "px）");
            }
            appliedViewportWidth = want;
            recordPatch("前端 viewport", true, want + "px");
            recordPatch("移动端响应式", true, "对话框、输入框与触控目标");
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
        downloadPath(basePath, assetName, out, new DownloadControl(), true);
    }

    /** 可取消、可跨次续传的分块下载；完成前只写入 .part 临时文件。 */
    private void downloadPath(String basePath, String assetName, File out,
                              DownloadControl control, boolean resume) throws IOException {
        final boolean quiet = assetName.endsWith(".json");
        final int chunkSize = 2 * 1024 * 1024;
        final int maxRetryPerSource = 4;

        File partial = new File(out.getAbsolutePath() + ".part");
        File identityFile = new File(out.getAbsolutePath() + ".part.id");
        long prepared = TransferState.prepare(partial, identityFile,
                basePath + assetName, resume);
        long total = -1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(partial, "rw");
        try {
            long done = prepared;
            if (raf.length() != done) raf.setLength(done);
            if (done > 0 && !quiet) log("  从 " + (done / 1048576) + " MB 继续下载");
            int chunkIndex = (int) (done / chunkSize);
            int retries = 0;
            long startedAt = System.currentTimeMillis();
            int lastLoggedPct = -1;
            String lastError = null;

            while (total < 0 || done < total) {
                control.check();
                long end = total < 0 ? done + chunkSize - 1
                        : Math.min(done + chunkSize - 1, total - 1);
                RangeResult result = null;

                for (int source = 0; source < SOURCES.length && result == null; source++) {
                    String url = SOURCES[source] + basePath + assetName;
                    for (int attempt = 0; attempt < maxRetryPerSource; attempt++) {
                        try {
                            result = fetchRange(url, done, end, control);
                            if (total < 0 && result.total != null) {
                                total = result.total.longValue();
                            }
                            if (source > 0) log("  已切换到镜像源 #" + source);
                            break;
                        } catch (Exception e) {
                            lastError = shorten(e);
                            retries++;
                            control.check();
                            try {
                                Thread.sleep(500L * (attempt + 1));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("下载被中断");
                            }
                        }
                    }
                    if (result == null) {
                        log("  源 #" + source + " 失败（" + lastError + "），尝试下一个");
                    }
                }

                if (result == null) {
                    throw new IOException("下载失败（块 " + chunkIndex + "，已下载 "
                            + (done / 1048576) + "MB）：所有来源均不可用\n最后错误："
                            + lastError + "\n请检查网络后重试，已下载部分会继续保留。");
                }

                long wanted = end - done + 1;
                byte[] data = result.data;
                if (data.length <= 0) throw new IOException("块 " + chunkIndex + " 内容为空");
                if (data.length < wanted && total > 0 && done + data.length < total) {
                    throw new IOException("块 " + chunkIndex + " 长度不足: "
                            + data.length + " / " + wanted);
                }
                if (total > 0 && done + data.length > total) {
                    throw new IOException("块 " + chunkIndex + " 超出文件总长度");
                }
                raf.seek(done);
                raf.write(data);
                done += data.length;
                chunkIndex++;
                if (total < 0 && data.length < wanted) total = done;

                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 10 != lastLoggedPct / 10) {
                        lastLoggedPct = pct;
                        long seconds = Math.max(1,
                                (System.currentTimeMillis() - startedAt) / 1000);
                        if (!quiet) {
                            log("  " + pct + "%  (" + (done / 1048576) + "/"
                                    + (total / 1048576) + " MB, "
                                    + (done / 1048576 / seconds) + " MB/s, 重试 "
                                    + retries + " 次)");
                            setSplashStatus("正在下载运行包 " + pct + "%");
                        }
                    }
                }
            }
            control.check();
            raf.getFD().sync();
            log("  下载完成 " + (done / 1048576) + " MB，重试 " + retries + " 次");
        } finally {
            raf.close();
        }
        control.check();
        TransferState.commit(partial, identityFile, out);
    }

    /** disconnect 用于中止 HttpURLConnection 的阻塞读取。 */
    private static final class DownloadControl {
        private boolean cancelled;
        private HttpURLConnection active;

        synchronized void bind(HttpURLConnection connection) throws IOException {
            if (cancelled) {
                connection.disconnect();
                throw new IOException("下载已取消");
            }
            active = connection;
        }

        synchronized void unbind(HttpURLConnection connection) {
            if (active == connection) active = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (active != null) active.disconnect();
            active = null;
        }

        synchronized void check() throws IOException {
            if (cancelled) throw new IOException("下载已取消");
        }
    }

    private static final class RangeResult {
        final byte[] data;
        final Long total;

        RangeResult(byte[] data, Long total) {
            this.data = data;
            this.total = total;
        }
    }

    /** 压缩异常信息，避免日志刷屏。 */
    private String shorten(Throwable e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }

    /** 取指定字节范围；响应严格限制在本块大小内，避免镜像返回整包导致 OOM。 */
    private RangeResult fetchRange(String url, long start, long end,
                                   DownloadControl control) throws IOException {
        HttpURLConnection c = null;
        try {
            // 手动跟随重定向：CDN 会 302 到签名 URL，且签名地址可能变化
            String cur = url;
            for (int hop = 0; hop < 8; hop++) {
                control.check();
                c = (HttpURLConnection) new URL(cur).openConnection();
                control.bind(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.setRequestProperty("User-Agent", "DSHNative/0.2.0");
                c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    control.unbind(c);
                    c.disconnect();
                    c = null;
                    if (loc == null) throw new IOException("重定向缺少 Location");
                    cur = loc;
                    continue;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                long maximum = end - start + 1;
                Long total = null;
                if (code == 206) {
                    String cr = c.getHeaderField("Content-Range");   // bytes 0-1/33540352
                    if (cr == null || !cr.startsWith("bytes ")) {
                        throw new IOException("206 响应缺少 Content-Range");
                    }
                    try {
                        int dash = cr.indexOf('-', 6);
                        int slash = cr.lastIndexOf('/');
                        long actualStart = Long.parseLong(cr.substring(6, dash).trim());
                        long actualEnd = Long.parseLong(cr.substring(dash + 1, slash).trim());
                        long parsedTotal = Long.parseLong(cr.substring(slash + 1).trim());
                        if (actualStart != start || actualEnd < actualStart || actualEnd > end
                                || parsedTotal <= actualEnd) {
                            throw new IOException("Content-Range 与请求不一致: " + cr);
                        }
                        total = Long.valueOf(parsedTotal);
                        maximum = actualEnd - actualStart + 1;
                    } catch (NumberFormatException badRange) {
                        throw new IOException("Content-Range 格式错误: " + cr);
                    }
                } else {
                    if (start > 0) throw new IOException("服务器忽略 Range，无法安全续传");
                    String length = c.getHeaderField("Content-Length");
                    if (length != null) {
                        try {
                            long parsed = Long.parseLong(length.trim());
                            if (parsed > maximum) {
                                throw new IOException("服务器忽略 Range，返回整包 "
                                        + parsed + " 字节");
                            }
                            if (parsed >= 0) total = Long.valueOf(parsed);
                        } catch (NumberFormatException ignored) { }
                    }
                }
                InputStream in = c.getInputStream();
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                            (int) Math.min(maximum, 262144L));
                    byte[] b = new byte[131072];
                    long received = 0;
                    int n;
                    while ((n = in.read(b)) > 0) {
                        control.check();
                        received += n;
                        if (received > maximum) {
                            throw new IOException("响应超过请求块大小，已中止以避免内存溢出");
                        }
                        bos.write(b, 0, n);
                    }
                    if (code == 200 && total == null) total = Long.valueOf(received);
                    return new RangeResult(bos.toByteArray(), total);
                } finally {
                    in.close();
                }
            }
            throw new IOException("重定向次数过多");
        } finally {
            if (c != null) {
                control.unbind(c);
                c.disconnect();
            }
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
                WebView view = webView;
                if (view == null || isFinishing() || isDestroyed()) return;
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
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
        // onDestroy 之后只保留 logcat，不再复活会持有旧 Activity 的写盘线程。
        if (activityWorkers.isStopped()) return;
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
                try {
                    while (!activityWorkers.isStopped()) {
                        final long sent = System.currentTimeMillis();
                        final java.util.concurrent.CountDownLatch done =
                                new java.util.concurrent.CountDownLatch(1);
                        if (!ui.post(new Runnable() {
                            @Override public void run() { done.countDown(); }
                        })) {
                            return;   // 主线程已退出
                        }
                        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            long ms = System.currentTimeMillis() - sent;
                            log("[卡顿] 主线程已阻塞 " + (ms / 1000) + " 秒（看门狗）");
                            // 等它恢复，避免卡顿时刷屏；最多等一分钟
                            done.await(60, java.util.concurrent.TimeUnit.SECONDS);
                        }
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException ignored) {
                    // Activity 销毁时由 WorkerRegistry 主动中断。
                } finally {
                    activityWorkers.finished(Thread.currentThread());
                }
            }
        }, "dsh-ui-watchdog");
        t.setDaemon(true);
        if (activityWorkers.start(t)) {
            log("已启动主线程卡顿看门狗（阻塞超过 5 秒会记日志）");
        }
    }

    private synchronized void startLogWorker() {
        if (logWorkerStarted || activityWorkers.isStopped()) return;
        logWorkerStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (!activityWorkers.isStopped()) {
                        String m;
                        m = logQueue.take();
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
                } catch (InterruptedException ignored) {
                    // Activity 销毁时由 WorkerRegistry 主动中断。
                } finally {
                    logWorkerStarted = false;
                    activityWorkers.finished(Thread.currentThread());
                }
            }
        }, "dsh-log-writer");
        t.setDaemon(true);
        if (!activityWorkers.start(t)) logWorkerStarted = false;
    }

    /** 初始化应用私有日志文件；共享存储只用于用户主动导出的脱敏诊断。 */
    private void initSharedLog() {
        try {
            File dir = new File(getFilesDir(), "logs");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建私有日志目录");
            sharedLog = new File(dir, "launch.log");
            removeLegacySharedLogs();
            // 追加而非覆盖：否则每次启动都会抹掉上一个会话的记录，
            // 跨会话的问题（例如"应用内更新到底下载成功没有"）就无从追查。
            // 超过上限时轮转一次，保留上一份，避免无限增长。
            final long MAX_BYTES = 512 * 1024;
            if (sharedLog.exists() && sharedLog.length() > MAX_BYTES) {
                File prev = new File(sharedLog.getAbsolutePath() + ".1");
                if (prev.exists()) prev.delete();
                sharedLog.renameTo(prev);
            }
            java.io.FileWriter w = new java.io.FileWriter(sharedLog, true);
            w.write("\n\n=== DSH Native 启动日志 ===\n");
            w.write("时间: " + new java.util.Date() + "\n");
            w.write("设备: " + android.os.Build.MODEL + " / Android "
                    + android.os.Build.VERSION.RELEASE + " (SDK "
                    + android.os.Build.VERSION.SDK_INT + ")\n");
            w.write("APK 版本: 0.33.1\n");
            w.write("路径: " + sharedLog.getAbsolutePath() + "\n");
            w.write("说明: 本文件位于应用私有目录；主动导出时会再次脱敏。\n\n");
            w.close();
            Log.i(TAG, "private log: " + sharedLog.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "initSharedLog failed", t);
            sharedLog = null;
        }
    }

    /** 清理旧版遗留在共享存储里的常驻日志，避免历史密钥继续明文暴露。 */
    private void removeLegacySharedLogs() {
        String[] paths = {
                "/sdcard/DSHNative/launch.log",
                "/sdcard/Download/DSHNative/launch.log",
                "/storage/emulated/0/DSHNative/launch.log",
                "/sdcard/launch.log",
        };
        for (String path : paths) {
            File current = new File(path);
            File previous = new File(path + ".1");
            if (current.isFile() && !current.delete()) {
                Log.w(TAG, "unable to remove legacy shared log: " + path);
            }
            if (previous.isFile() && !previous.delete()) {
                Log.w(TAG, "unable to remove legacy shared log: " + previous);
            }
        }
    }

    /**
     * 日志脱敏。
     *
     * <p>曾经只处理了 {@code token=}，结果导入凭据时把**完整 API Key 明文写进了日志**。
     * 现在覆盖常见密钥形态，并兜底屏蔽超长无空格串。
     */
    private static String maskSecrets(String s) {
        return SecretMasker.mask(s);
    }

    /** 追加一行到私有日志；即使在私有目录也先做脱敏。 */
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
        if (granted) removeLegacySharedLogs();
        log("存储权限结果: " + (granted ? "已授予，共享工作区与诊断导出可用"
                : "被拒绝 —— 共享工作区与诊断导出不可用，不影响私有运行日志"));
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

    private static final Object SHARE_IMPORT_LOCK = new Object();
    private static final long MAX_SHARED_FILE_BYTES = 512L * 1024L * 1024L;
    private static final long SHARE_SPACE_RESERVE = 32L * 1024L * 1024L;

    /** 处理从其他 App 分享过来的单个或多个文件及文本。 */
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
                File base = appRoot != null ? appRoot : new File(getFilesDir(), "dsh");
                ws = new File(base, "workspace");
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

            final java.util.List<android.net.Uri> streams = sharedUris(intent);
            final String text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            final String subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT);
            if (streams.isEmpty() && (text == null || text.length() == 0)) return;

            final File wsFinal = ws;
            final String stampFinal = stamp;
            final int totalItems = streams.size()
                    + (text != null && text.length() > 0 ? 1 : 0);
            final DshUi.TaskProgress importProgress = DshUi.taskProgress(this,
                    UiText.t("正在接收分享内容", "Receiving shared items"),
                    UiText.t("准备接收 0 / " + totalItems + " 项",
                            "Preparing 0 / " + totalItems + " items"),
                    totalItems);
            shareImportProgresses.add(importProgress);
            new Thread(new Runnable() {
                @Override public void run() {
                    synchronized (SHARE_IMPORT_LOCK) {
                        int saved = 0;
                        int completed = 0;
                        java.util.List<File> savedFiles = new java.util.ArrayList<File>();
                        java.util.List<String> failures = new java.util.ArrayList<String>();
                        for (android.net.Uri stream : streams) {
                            try {
                                File out = importSharedUri(stream, wsFinal, stampFinal);
                                saved++;
                                savedFiles.add(out);
                                log("已接收分享文件: " + out.getAbsolutePath());
                            } catch (Throwable t) {
                                failures.add(shorten(t));
                                log("错误: 保存分享文件失败: " + t);
                            }
                            completed++;
                            updateShareImportProgress(importProgress, completed, totalItems);
                        }
                        if (text != null && text.length() > 0) {
                            try {
                                File out = importSharedText(text, subject, wsFinal, stampFinal);
                                saved++;
                                savedFiles.add(out);
                                log("已接收分享文本: " + out.getAbsolutePath());
                            } catch (Throwable t) {
                                failures.add(shorten(t));
                                log("错误: 保存分享文本失败: " + t);
                            }
                            completed++;
                            updateShareImportProgress(importProgress, completed, totalItems);
                        }
                        finishShareImport(importProgress, saved, savedFiles, failures);
                    }
                }
            }, "share-import").start();
        } catch (Throwable t) {
            log("错误: 处理分享内容失败: " + t);
            toast("接收分享内容失败");
        }
    }

    private void updateShareImportProgress(final DshUi.TaskProgress progress,
                                           final int completed, final int total) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                progress.update(UiText.t("正在接收 " + completed + " / " + total + " 项",
                        "Receiving " + completed + " / " + total + " items"), completed);
            }
        });
    }

    private void finishShareImport(final DshUi.TaskProgress progress, final int saved,
                                   java.util.List<File> savedFiles,
                                   java.util.List<String> failures) {
        final java.util.List<File> files =
                new java.util.ArrayList<File>(savedFiles);
        final int failed = failures.size();
        final String firstFailure = failed > 0 ? failures.get(0) : "";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                final String summary;
                final int result;
                if (failed == 0) {
                    summary = UiText.t("已将 " + saved + " 项内容放入工作区",
                            saved + " item(s) saved to the workspace");
                    result = DshUi.RESULT_SUCCESS;
                } else {
                    summary = UiText.t("已保存 " + saved + " 项，失败 " + failed
                                    + " 项：" + firstFailure,
                            saved + " saved, " + failed + " failed: " + firstFailure);
                    result = saved > 0 ? DshUi.RESULT_WARNING : DshUi.RESULT_ERROR;
                }
                progress.finish(summary, result, new Runnable() {
                    @Override public void run() {
                        shareImportProgresses.remove(progress);
                        if (!files.isEmpty()) offerSharedTask(files);
                    }
                });
            }
        });
    }

    /** 分享导入成功后让用户决定：只保存，或直接交给当前 DSH 会话处理。 */
    private void offerSharedTask(final java.util.List<File> files) {
        final java.util.List<String> names = new java.util.ArrayList<String>();
        for (File file : files) names.add(ShareTargets.displayName(file));
        final String prompt = ShareTask.prompt(activeProjectName(), names);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                StringBuilder detail = new StringBuilder();
                detail.append("已保存 ").append(files.size()).append(" 项到“")
                        .append(WorkspaceProjects.displayName(activeProjectName()))
                        .append("”。\n\n");
                int shown = Math.min(6, names.size());
                for (int i = 0; i < shown; i++) detail.append("- ").append(names.get(i)).append('\n');
                if (names.size() > shown) detail.append("- 另有 ")
                        .append(names.size() - shown).append(" 项\n");
                detail.append("\n选择“创建任务”会把这些文件交给当前 DSH 会话处理。内容已保存，取消不会删除文件。");
                DshUi.confirm(MainActivity.this, "用分享内容创建任务？",
                        detail.toString(), "创建任务", new Runnable() {
                    @Override public void run() { dispatchShareTaskPrompt(prompt); }
                });
            }
        });
    }

    private void dispatchShareTaskPrompt(final String prompt) {
        if (prompt == null || prompt.length() == 0) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                @Override public void run() { dispatchShareTaskPrompt(prompt); }
            });
            return;
        }
        if (webView == null || !dshPageLoaded) {
            pendingSharedTaskPrompt = prompt;
            toast("任务已排队，DSH 界面就绪后自动提交");
            return;
        }
        if (shareSubmitProgress != null) shareSubmitProgress.dismiss();
        final int generation = InteractionFeedback.nextGeneration(shareSubmitGeneration);
        shareSubmitGeneration = generation;
        shareSubmitProgress = DshUi.taskProgress(this,
                UiText.t("正在提交任务", "Submitting task"),
                UiText.t("正在写入当前 DSH 会话…",
                        "Writing to the current DSH session…"), 0);
        try {
            webView.evaluateJavascript(ShareTask.javascript(prompt), null);
            log("正在把分享内容提交到当前 DSH 会话");
            webView.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!InteractionFeedback.isCurrent(
                            generation, shareSubmitGeneration)) return;
                    finishShareTaskSubmission(
                            UiText.t("提交状态未确认，请检查输入框",
                                    "Submission was not confirmed. Check the task editor."),
                            DshUi.RESULT_WARNING);
                }
            }, InteractionFeedback.SUBMIT_TIMEOUT_MS);
        } catch (Throwable t) {
            pendingSharedTaskPrompt = prompt;
            log("分享任务提交失败，已保留待重试: " + t);
            finishShareTaskSubmission(
                    UiText.t("文件已保存，任务将在界面就绪后重试",
                            "Files saved. The task will retry when the interface is ready."),
                    DshUi.RESULT_ERROR);
        }
    }

    private void finishShareTaskSubmission(final String message, final int result) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(new Runnable() {
                @Override public void run() { finishShareTaskSubmission(message, result); }
            });
            return;
        }
        shareSubmitGeneration = InteractionFeedback.nextGeneration(shareSubmitGeneration);
        DshUi.TaskProgress progress = shareSubmitProgress;
        shareSubmitProgress = null;
        if (progress != null) progress.finish(message, result, null);
    }

    /** 同时读取 EXTRA_STREAM 与 ClipData，兼容各类分享来源。 */
    private java.util.List<android.net.Uri> sharedUris(android.content.Intent intent) {
        java.util.List<android.net.Uri> out = new java.util.ArrayList<android.net.Uri>();
        try {
            if (android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                java.util.ArrayList<android.net.Uri> many = intent.getParcelableArrayListExtra(
                        android.content.Intent.EXTRA_STREAM);
                if (many != null) {
                    for (android.net.Uri uri : many) addUniqueUri(out, uri);
                }
            } else {
                android.net.Uri one = intent.getParcelableExtra(
                        android.content.Intent.EXTRA_STREAM);
                addUniqueUri(out, one);
            }
            android.content.ClipData clip = intent.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    addUniqueUri(out, clip.getItemAt(i).getUri());
                }
            }
        } catch (Throwable t) {
            log("读取分享 URI 失败: " + t);
        }
        return out;
    }

    private static void addUniqueUri(java.util.List<android.net.Uri> list,
                                     android.net.Uri uri) {
        if (uri != null && !list.contains(uri)) list.add(uri);
    }

    private File importSharedUri(android.net.Uri source, File workspaceDir, String stamp)
            throws Exception {
        String rawName = queryDisplayName(source);
        String safeName = ShareTargets.incomingName(rawName, "分享文件-" + stamp);
        long declared = querySharedSize(source);
        if (declared > MAX_SHARED_FILE_BYTES) {
            throw new IOException("文件超过 512 MB 上限");
        }
        long usable = workspaceDir.getUsableSpace();
        if (declared > 0 && usable > 0 && declared + SHARE_SPACE_RESERVE > usable) {
            throw new IOException("工作区空间不足");
        }

        File target = reserveShareTarget(workspaceDir, safeName);
        File temp = new File(workspaceDir, ".dsh-import-" + System.nanoTime() + ".part");
        if (!ShareTargets.isContained(workspaceDir, temp)) {
            target.delete();
            throw new IOException("接收路径越过工作区");
        }

        InputStream in = null;
        FileOutputStream output = null;
        boolean committed = false;
        try {
            in = getContentResolver().openInputStream(source);
            if (in == null) throw new IOException("无法读取分享文件");
            output = new FileOutputStream(temp);
            byte[] buffer = new byte[65536];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                copied += n;
                if (copied > MAX_SHARED_FILE_BYTES) {
                    throw new IOException("文件超过 512 MB 上限");
                }
                output.write(buffer, 0, n);
            }
            output.getFD().sync();
            output.close();
            output = null;
            TransferState.atomicReplace(temp, target);
            committed = true;
            return target;
        } finally {
            if (output != null) try { output.close(); } catch (Throwable ignored) { }
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (!committed) {
                temp.delete();
                target.delete();
            }
        }
    }

    private File importSharedText(String text, String subject, File workspaceDir, String stamp)
            throws Exception {
        String base = subject != null && subject.trim().length() > 0
                ? subject.trim() : "分享内容";
        String name = ShareTargets.incomingName(base + "-" + stamp + ".txt",
                "分享内容-" + stamp + ".txt");
        File target = reserveShareTarget(workspaceDir, name);
        File temp = new File(workspaceDir, ".dsh-import-" + System.nanoTime() + ".part");
        boolean committed = false;
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                byte[] data = text.getBytes("UTF-8");
                if (data.length > MAX_SHARED_FILE_BYTES) throw new IOException("文本内容过大");
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            TransferState.atomicReplace(temp, target);
            committed = true;
            return target;
        } finally {
            if (!committed) {
                temp.delete();
                target.delete();
            }
        }
    }

    /** 创建零字节占位以保留名称，避免并发分享覆盖同名文件。 */
    private File reserveShareTarget(File workspaceDir, String safeName) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            File target = ShareTargets.uniqueDestination(workspaceDir, safeName);
            if (target == null) break;
            if (!ShareTargets.isContained(workspaceDir, target)) break;
            if (target.createNewFile()) return target;
        }
        throw new IOException("无法生成不重复的文件名");
    }

    private String queryDisplayName(android.net.Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int i = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (cursor.moveToFirst() && i >= 0) return cursor.getString(i);
            }
        } catch (Throwable t) {
            log("读取分享文件名失败: " + t);
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private long querySharedSize(android.net.Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Throwable t) {
            log("读取分享文件大小失败: " + t);
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
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
        // 快速连按只保留一个确认框。不能在第二次直接调用
        // super.onBackPressed()：那会销毁 Activity，与“退到后台、保留会话”的文案矛盾。
        if (exitDialog != null && exitDialog.isShowing()) return;

        android.widget.LinearLayout box = DshUi.paddedBody(this);
        box.addView(DshUi.title(this, "退出 DeepSeek Harness？"));
        box.addView(DshUi.hint(this,
                "退出后 agent 会在后台继续运行（通知栏可以看到状态），"
                + "下次打开会立即回到当前界面。"), DshUi.fullWidth(this, 8));
        android.widget.Button cancel = DshUi.button(this, "取消", false);
        android.widget.Button exit = DshUi.button(this, "退出", true);
        final android.app.Dialog d = DshUi.dialog(this, box,
                DshUi.footer(this, cancel, exit), 360);
        exitDialog = d;
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                if (exitDialog == d) exitDialog = null;
            }
        });
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

    @Override
    protected void onDestroy() {
        stopSplashAnimation();
        android.app.Dialog exiting = exitDialog;
        exitDialog = null;
        if (exiting != null) {
            try { exiting.dismiss(); } catch (Throwable ignored) { }
        }
        shareSubmitGeneration = InteractionFeedback.nextGeneration(shareSubmitGeneration);
        if (shareSubmitProgress != null) shareSubmitProgress.dismiss();
        shareSubmitProgress = null;
        for (DshUi.TaskProgress progress : shareImportProgresses) progress.dismiss();
        shareImportProgresses.clear();

        if (pendingFileCallback != null) {
            try { pendingFileCallback.onReceiveValue(null); }
            catch (Throwable ignored) { }
            pendingFileCallback = null;
        }

        HarnessService.clearListener(harnessListener);
        DshUi.clearLogSink(dshUiLogSink);
        activityWorkers.stop();

        // WebView 持有 Activity、回调和渲染线程。主题切换会重建 Activity，
        // 旧实例若不显式销毁，会与看门狗一样累积到进程结束。
        WebView oldWebView = webView;
        webView = null;
        if (oldWebView != null) {
            try { oldWebView.stopLoading(); } catch (Throwable ignored) { }
            try { oldWebView.setWebChromeClient(null); } catch (Throwable ignored) { }
            try { oldWebView.setWebViewClient(null); } catch (Throwable ignored) { }
            try {
                if (oldWebView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) oldWebView.getParent()).removeView(oldWebView);
                }
            } catch (Throwable ignored) { }
            try { oldWebView.removeAllViews(); } catch (Throwable ignored) { }
            try { oldWebView.destroy(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}
