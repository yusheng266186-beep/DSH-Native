# Agent 调用手机原生功能

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.39.0**；源码：**0.39.1**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

0.37.0 将手机原生能力接入 DSH：agent 可直接使用注册的 `android` 工具，也可在命令中使用 `dsh-android`。覆盖安装后，从更新与维护重启内核，让实际 DSH 进程加载新适配器；不需要另外配置 MCP 或安装 Termux。

## 能力和调用结果

| method | 参数 | 行为与结果 |
|---|---|---|
| `capabilities` | 无 | 列出可调用方法、前台状态、文本/文件限制与截图授权要求 |
| `clipboard.get` | 无 | 读取首条纯文本；无文本返回 `hasText:false`，不把 URI 剪贴板转换为私有文件读取 |
| `clipboard.set` | `text` | 写入文字，返回已复制与字符数；支持空字符串，最多 16000 字符 |
| `notification.post` | `title`, `text` | 发到独立 Agent 消息渠道；通知或渠道被禁用时明确失败，最多每两秒一次，不覆盖任务常驻通知 |
| `share.text` | `text` | 打开 Android 分享面板，由用户选择目标；返回 `chooserOpened`，不声称已经发送 |
| `share.file` | `path` | 当前工作区或 native-captures 的常规文件，最多 32 MiB；复制到私有缓存，通过只读 content URI 打开分享面板 |
| `url.open` | `url` | 打开 HTTP(S) 链接；不接受 javascript、intent、file URL 或包含用户名密码的 URL |
| `file.open` | `path` | 同文件范围/大小规则，通过 content URI 打开系统文件查看器选择面板 |
| `app.open` | `package` | 打开已安装 App 的启动 Activity；无可用入口则明确失败 |
| `keepalive.status` | 无 | 返回前台服务、内核进程、任务状态是否新鲜、实际 WakeLock 与电池优化状态；不伪报永久保活 |
| `screenshot` | 无 | 每次请求系统授权，用户选择单 App 或屏幕；仅保存一张 PNG，返回私有路径、尺寸和字节数后停止投影 |

剪贴板、分享、打开及截图授权入口要求 DSH 窗口在前台并有焦点；不满足时返回 `FOREGROUND_REQUIRED`。通知和保活查询可在托管服务运行时后台调用。窗口前台判断不是系统对所有 ROM 的成功保证，真实服务拒绝仍返回错误。

截图是单次系统 MediaProjection，不是后台连续监控；每次重新授权，不重用旧令牌。最长等待授权 90 秒，收到授权后等待图像上限 8 秒，长边最多 2048 像素且保持比例。取消、断开调用、停止服务与超时会释放请求、投影、ImageReader 和 VirtualDisplay；系统保护窗口可能无法捕获。生成图片位于私有运行目录 `native-captures/`，不加入普通诊断，是否提供给模型仍由该任务的工具调用决定。

0.37.1 起每次授权使用独立请求编号，晚到的旧批准或拒绝均不影响新请求；本进程内不重用这些编号。正常截图在投影资源与服务排队工作清理后才返回。取消后旧服务尚未结束时，新调用返回 BUSY，可在清理结束后重新发起；不会提前弹出下一次授权。已取消且未交付的图片删除，先前成功交付的图片保留。完成请求的计时器移除，重复服务 Intent 不停止当前截图。

帧数据允许缺少最后一行的尾部填充，但不能缺少真实像素；步长不合法、图像截断或初始化/旋转时内存不足会明确失败并释放。缓冲规则依据 [Image.Plane.getBuffer](https://developer.android.com/reference/android/media/Image.Plane#getBuffer())。

平台依据：[Android 剪贴板](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[MediaProjection](https://developer.android.com/media/grow/media-projection)、[通知权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。

## 使用例子

直接告诉 agent：“把这段文字复制到手机剪贴板”“用手机分享面板分享刚生成的报告”“发一条任务提醒”“请求截取当前屏幕并查看图片”。agent 应按本次任务使用能力，不轮询剪贴板、不把打开面板当成已经发送。

命令行同样可用：

```bash
dsh-android capabilities
dsh-android clipboard.get
dsh-android clipboard.set '{"text":"要复制的文字"}'
dsh-android notification.post '{"title":"报告已完成","text":"可以打开工作区查看"}'
dsh-android share.file '{"path":"/sdcard/DSHNative/workspace/report.pdf"}'
dsh-android keepalive.status
dsh-android screenshot
```

参数也可用 `-` 从标准输入读取 JSON，避免把长文本放进命令参数。每次先读取最新私有端点配置，不在参数、工具结果或日志中暴露令牌。普通错误返回 `ok:false` 与错误码，命令非零退出；通知的 `posted:true` 表示已提交系统，分享的选择器打开不证明送达。

0.37.2 起，分享/打开文件在准备私有副本前记录窗口和项目，打开面板前再次核对。准备期间切换项目、窗口重建或进入后台会停止旧请求并删除本次未交付副本，回到目标项目后重新请求即可。之前成功分享的缓存与原文件保留。

原生界面调度最多等待 5 秒；超时、取消或托管服务停止会移除未开始的动作。已取消调用不会再排队，截图授权启动期间取消也撤销等待请求。系统异常只映射到固定错误，不回传可能包含私有路径或正文的原始异常信息。副作用已经发生时，后续取消不能撤销；客户端不会自动重试复制、打开、通知或截图。普通 HTTP 等待上限 12 秒、截图 95 秒，期限与断连分别报告。

平台接口依据：[启动 Activity 的失败](https://developer.android.com/reference/android/content/Context#startActivity(android.content.Intent))、[移除 Handler 回调](https://developer.android.com/reference/android/os/Handler#removeCallbacks(java.lang.Runnable))、[查询通知渠道](https://developer.android.com/reference/android/app/NotificationManager#getNotificationChannel(java.lang.String))。

## 接口与数据范围

- 服务只监听 `127.0.0.1` 的随机端口，使用 256-bit 随机令牌。配置文件 `.native-bridge.json` 只允许应用身份读取；服务重建更换令牌，停止时删除当前实例配置。
- 只接受鉴权 JSON POST 和封闭的方法/字段集合；拒绝 Origin、浏览器 Fetch 元数据、重复头、分块请求、错误 Host 和过大请求，不添加 WebView JS 权限桥。
- 两个工作线程和有界队列，主线程操作有期限；调用断开会取消仍在等待的操作。已经执行的复制或打开不会因后续取消撤销。
- 文件使用规范路径校验，拒绝工作区外文件、链接越界、`.dsh`、`.git`、`.ssh`、凭据、私钥、环境密钥等；不把任意私有路径直接授权给其他 App。分享缓存保留供接收方读取，由 Android 缓存机制和用户维护清理。
- 凭据、剪贴板正文、通知正文、分享内容和截图不进入原生普通日志或诊断。用户要求的 agent 工具结果仍可能成为本次会话内容，和“普通诊断日志”是不同层。

## 排查

| 错误 | 处理 |
|---|---|
| `BRIDGE_NOT_READY` / `BRIDGE_UNAVAILABLE` | 打开 App，确认托管服务已启动；覆盖安装后重启内核 |
| `BRIDGE_STOPPED` / `UI_TIMEOUT` / `BRIDGE_TIMEOUT` | 返回 App，确认服务与界面恢复；先检查动作是否已发生，再决定重试，避免重复 |
| `FOREGROUND_REQUIRED` | 返回 DSH，关闭系统弹窗后重试；不要求后台剪贴板权限 |
| `CONTEXT_CHANGED` | 文件准备时窗口或项目发生变化，在目标项目中重新发起 |
| `SYSTEM_DENIED` | 检查系统权限、隐私开关及 DSH 前台状态；不循环重试 |
| `NO_ACTIVITY_HANDLER` | 安装或启用能够处理该链接/文件/分享的 App |
| `NOTIFICATIONS_DISABLED` | 在系统 App 通知设置允许通知和 Agent 消息渠道 |
| `NOTIFICATION_CHANNEL_UNAVAILABLE` | 系统未能创建 Agent 消息渠道，检查 App 通知设置后重试 |
| `FILE_NOT_ALLOWED` | 使用当前工作区中的常规产物文件，核对绝对路径及 32 MiB 上限 |
| `FILE_PREPARE_FAILED` | 检查原文件访问权限和 App 可用空间；原文件不会被覆盖 |
| `INVALID_REQUEST` / `FORBIDDEN` | 分别为参数错误/认证失败；检查参数或打开 App 恢复服务，不输出或手工分享令牌 |
| `APP_UNAVAILABLE` | 核对安装包名及是否存在启动入口 |
| `CANCELLED` / `CAPTURE_TIMEOUT` | 如需截图，重新发起并在系统提示中授权 |
| `BUSY` | 等当前截图或取消后的服务清理结束，再重新发起；每次仍需系统授权 |

诊断报告的 Native capability bridge 只记录桥是否运行，不含端点令牌或操作正文。保持既有 package、targetSdk、签名和用户配置。

## 验证边界

生产 Java HTTP/鉴权/文件策略与尺寸/帧缓冲限制在 JVM 检查；Android clipboard/notification/Intent/授权协调器及真实 NativeCaptureService 使用确定性 API 替身，图像编码同样是替身。实际 payload-v13 的 ToolRuntime、适配器、命令、Java HTTP server 与 Web profile 加载链有消费回归；并完成完整 Android Java/APK 构建。它们不能替代真实手机的剪贴板、分享目标或截图帧验收，按用户要求由用户安装验证。
