# 交接说明

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**1.1.1**；源码：**1.1.2**；源码运行包：`payload-v14`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 1. 接手顺序

先读 [STATUS](STATUS.md) 的生成事实表和验证边界，再读 [AGENTS](../AGENTS.md)。这份交接按当前源码整理；旧的 0.25.5 / payload-v7 顶部状态和“所有真机验证从未做过”已被纠正。历史候选记录移交到各阶段文档及 Git 历史，不再作为当前待办重复维护。

```bash
git fetch origin main
git status --short --branch
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

源码可以高于已发布版本；此时下载和摘要仍以稳定清单为准。不要为了消除差异手改清单。

## 2. 当前运行与分发

| 范围 | 现行事实 |
|---|---|
| App | 包名 `dev.dsh.native`，minSdk 24 / targetSdk 28 / ARM64 |
| 执行 | `<root>` 为 `getFilesDir()/dsh`；Node、DSH 与工具不依赖用户的 Termux/proot |
| 内核 | 固定来源和锁文件位于 `runtime/`；当前使用 DSH 0.2.1-rc.3，上游候选版 |
| 运行包 | payload-v13：DSH 0.2.1-rc.3，Android 二进制和工具片继承 v12；精确大小见发布清单 |
| 空间 | DSH 片实际展开分配约 491 MiB；还要计算工具、内置负载、下载和快照，不能沿用旧 316MB / 550MiB 总预算 |
| 配置 | `.dsh` 含凭据、profile、历史与附件；全局/项目模型覆盖单独保存 |
| 工作区 | 默认 `/sdcard/DSHNative/workspace`，命名项目在其 `projects/` 下 |
| 发布 | 两个工作流共用 `ci_build.sh`；正式版只经 `release.yml` / `release.sh` |

源码、已发布 stable / test 与摘要的精确值只在 [STATUS](STATUS.md) 生成表维护。

0.39.0 内置 CodeBuddy SDK/CLI，构建入口通过 `prepare_codebuddy_sdk.py` 按 `runtime/codebuddy/package-lock.json` 安装并打包，产物只进 APK、不提交 Git。`CoreUpgrade.codeBuddy` 幂等移除旧认证头并将 App 自有 CodeBuddy 路由交给 `codebuddy-cli.cjs`。主进程设置 `DSH_NATIVE_APP_ROOT`，未设置时保留标准 HTTP，离线模型 SDK 消费测试仍可使用原有 seam。

0.39.1 通过固定 headless 文件白名单裁剪 SDK 归档，保留文件不改写；`codebuddy-cli-consumer.mjs` 检查体积预算和已移除组件。工具请求后仅预热一个全新官方 Session，先 connect 并读取模式元数据，尚未 send；按含凭据与工具声明的摘要匹配，10 秒失效，一次领取。预热失败不得替换已完成的工具请求，取消不得新建预热。已发送对话的进程仍关闭，避免跨会话污染。编译缓存只在受保护的 CLI home。

`codebuddy-cli.cjs` 校验归档摘要与版本，惰性准备私有 SDK；使用官方 CLI 标准代理人格，DSH 提供历史、图片与 MCP 工具 Schema，完整工具请求交回 DSH。不要再把 DSH 组合系统人格替换进 CLI——真实服务会拒绝；这是一种明确的 CLI 代理接入模式，不是直接模型 API 的无损替换。取消与期限必须结束 SDK 子进程。连接检查通过私有 stdin 传 Key，目录刷新不调用模型；两者状态不可混淆。原 App 根目录固定为 files/dsh。真实服务证据见 STATUS，按用户要求没有手机测试。

## 3. 关键调用链

### 启动与存活探测

`MainActivity` 初始化环境、配置、补丁与 WebView；`SessionPersistencePatch` 在每次启动修复首次保存和格式迁移的两个硬链接发布点，兼容旧 payload-v12。链接 EACCES 等兼容错误才降级为排他复制并 fsync，同名历史拒绝覆盖，真实写权限和磁盘错误仍传播。旧进程若加载的是较早 APK 的模块，明确要求从更新与维护重启，不误报已采用补丁。`LocalServerProbe` 在同一 localhost origin 完成 token → 303 → Cookie 探测，限跳转、响应大小与超时，不向其他 origin 发送凭据。已可用实例尽量复用，进程失败按 `ProcessSupervisor` 有界退避。

0.40.0 的 `connection-ui.js` 经 `ConnectionRecovery.patchClient` 幂等注入固定 Connection 客户端（browser/node），`MainActivity` 与真实运行包消费脚本应用同一补丁，rc.2 回退跳过。loopback 不监听外网 online/offline 暂停；非 loopback 保持原策略。原 Connection 状态上报、150ms 前台事件合并及 2 秒恢复限流只作用于未连接状态；健康连接不动。`onResume` 发固定页面事件，不暴露原生权限。不要改回自动重发用户消息或每次回前台整页刷新。独立 App 入口已去除，WebToolsEntry 只清理旧节点与确认原设置槽位。

页面每次加载都重新安装辅助脚本。0.35.0 起 `HarnessService` 通过 `CoreTaskState` 每约 3 秒独立读取认证 `session/list`，15 秒内有效；查询失败立即未知，不发送完成。跨端口/令牌切换丢弃旧响应与任务计时，不依赖 WebView 先发出 fetch。`SessionProbe` 仍完整解析网页 RPC，DOM 只补充等待批准及连接信息。常驻通知只首次 startForeground，后续状态更新不得用占位文案覆盖后再被签名去重。

启动时 `ToolEnvironment` 准备 `.native-bin` 包装、托管 shebang 与私有 tmp；`FsWritePatch` 修复新文件发布硬链接，沿用排他复制 helper。自检失败不能 rememberPreflightKey。环境事实与旧报告更正在[环境指南复核](ENVIRONMENT_UPGRADE.md)。

语音沿用原插件三个 slot，`VoiceUi` 幂等修补真实模块；`NativeVoiceController` 在前台准确本机 origin 处理受限命令。无任意 WebView 权限桥，不记录语音正文。处理权限先于 onResume 返回、系统窗口导致 onPause、取消后晚回调及草稿变化；详情见[语音输入](VOICE_INPUT.md)。

0.35.1 核对实际默认识别服务，而不是只用 isRecognitionAvailable；默认缺失或直接启动错误 5 等情况按有界顺序回退系统窗口。清理前递增 generation，延迟回退复核前台/请求；停止只在 ready 且未 stopping 时执行。失败保留 stage/reason/异常类/尝试链，不能再把各类本地错误都折叠成无来源的 5。`tests/check_native_voice.py` 执行生产控制器和 Android API 替身，不进 APK，也不等于真机 ASR。

0.36.0 改为页面内优先：默认/系统窗口同提供方的公开服务通过显式 component 绑定，手选服务持久化到私有 voiceEngine。只绑定实际列出的启用导出组件，不硬编码厂商包、不偷偷换无关服务。start 不打开 Activity，start-window 才是用户手动的窗口入口；不要恢复自动弹框。RMS 按 120ms 节流，partial 仅进页面预览不进日志/草稿。UI 使用原主题和控件，CSS 通过变量压缩短屏装饰，避免 inline style 覆盖媒体规则。

服务地址可能早于 legacy 模型导入完成，完成日志通常不输出到终端。存在待导入文件时，启动前保存预期配置，`CoreReadiness` 通过真实 `settings/describe` 核对默认、目录与能力，最多等待 30 秒；超时也不绕过该条件。系统日志同样必须走 `SecretMasker`。

0.36.2 修复 `#!` 后空白的 shebang，并核对解释器实际目标仍在 tools 内。`runtime-checks.js` 通过真实 LocalFileSystem API 自检，在唯一临时目录中验证新建/读回/拒绝同名/按版本更新，只清理自己创建的目录。preflight 分别检查私有 tmp 与所选工作区；缓存键包含工作区，失败不缓存为通过（1.1.2 起另记一小时尝试退避）；旧 rc.2 明确跳过不支持的新版探针。可选 Python import 是 INFO，不能为了“全通过”自动装包或启用插件。新 helper 必须进入 APK assets 并随版本提取，实际运行包消费回归执行生产 helper 和 preflight 接线。

0.37.0 原生桥由 HarnessService 创建/停止 NativeBridgeServer，Activity 只以弱引用绑定前台能力。纯协议/文件/尺寸规则在 NativeBridgePolicy；NativeBridge 在主线程复核窗口与权限，再执行 Android 调用；文件暂存不阻塞 UI。NativeScreenCapture 单请求授权协调，NativeCaptureService 独立 mediaProjection 类型，只取一帧，回调与资源必须幂等释放。HTTP 不给网页 CORS，不接受浏览器 Origin 或 Sec-Fetch，令牌文件不能进普通日志或分享。客户端/插件的 .cjs 也必须随 APK 更新；在 --profile 前加载 App 自有 insert patch，实际 ToolRuntime 与 Web profile 消费回归必须通过。见[原生能力说明](ANDROID_BRIDGE.md)。

### 模型目录与强度

`ProviderCheck` 只读当前服务商目录；`LiveModelCatalog` 以返回 ID 为可见集合，保留视觉、token 和兼容字段。`ModelCatalogSync` 写入完整 DSH provider models 并保留 live-catalog 标记，防止升级静态预设覆盖实时目录。

DSH 配置涉及 legacy `settings.yaml` 导入与 `.dsh/profiles/web/cordis.patch.yml` 的 active profile；排查时不能只看一个文件就认为运行时已应用。目录成功保存后，空闲时受控重载；运行或未知时持久化 pending，确认空闲后执行。仅刷新 WebView 不足以重建 provider topology。

导入后 legacy 文件变为 `settings.yaml.imported`。没有待导入文件时，`NativeCoreApi.modelSettings` 通过 `settings/describe` 与 `ModelSettingsSnapshot` 读取公开模型 namespace；模型面板与项目切换使用同一快照。切换项目的未知任务状态也必须进入中断确认，不能当作空闲。

`ModelReasoning` 区分官方支持声明与用户要求的全模型 max 请求扩展。所有模型添加 `max: max`，未知/非可调模型保留 `off: null` 默认路径。`ModelEffortUi` 有锚点、幂等地修改实际聊天框组件标签；不匹配时不写文件并记录诊断。

全局/项目默认只影响新会话；聊天框可显式更改已有会话的后续调用配置。不回写历史，不改变已发请求，不把上游拒绝 max 隐藏为其他强度。

### 统一设置、文件和 CodeBuddy

0.38.0 的 WebSettingsHost 接管 DshUi.dialog，使用真实控制器和封闭的 WebSettingsProtocol。前端按 snapshot 渲染原 DSH primitives；不要把密码值或任意脚本塞进控件协议。只读字段判断 getKeyListener，不能单看 isFocusable；未附着 View.post 不会驱动文件分批列表，逻辑调度必须用主线程 Handler。关闭页面执行原 onDismiss，异步 TaskProgress 使用自身 Handler。

FileActionsUi 给原文件预览头添加稳定属性、给交付 slot 补充绝对路径；AppSettingsUi 注册原 slot 的编辑/打开/分享。ExternalFileActions 只在明确编辑时授予单文件写权限，UpdateProvider 的 /f/ 只读，/e/ 只允许可写根中的既有常规文件。TextEditor 的查看路径必须显式只读；新建文件用 createNewFile 拒绝同名。

CodeBuddy 没有通用 GET /models；CodeBuddyCatalog 是固定 CLI 元数据，探测不是账号授权目录。CoreUpgrade.codeBuddy 在真实 SDK dispatch 注入从 credentials 解析的 X-API-Key，不把密钥放进公开 headers。私人 AES-GCM 导入文件由 tools/export_codebuddy_connection.cjs 生成，不进入 Git/APK。provider-cleanup.cjs 在启动前清理旧服务商的 active/legacy/凭据/hex 项目设置；先规范化可信根，再拒绝 .dsh 软链，不能因根别名误阻断。预设只替换受管 CodeBuddy 路由，保留其他 providers。

### App 设置与会话

0.33.12 将 App 设置注册到 DSH 原始框架；设备工具从对应类别进入并返回。`AppSettingsUi` 启动时严格修补 General、SessionController 和会话客户端缓存，辅助脚本从 APK 更新。不要恢复旧 `SessionOrganizer` 按文字找按钮的方案。会话列表支持标题筛选、分页、重命名、归档、恢复继续和可恢复回收站。恢复必须清除缓存 removed 状态；journal 不能按 Remote 代理 WeakMap 分组，要按 DSH_HOME 共享队列。配置恢复用静态 `runtimeConfigLock` 并等待托管进程退出，插件有应用重启。详见 [App 设置](APP_SETTINGS.md)。

### DeepSeek 账号

模型刷新位于 App 工具的模型中心。App 工具的 DeepSeek 账号入口通过页面内的无权限事件打开 DSH 原始账号设置；`AccountUi` 仅适配桌面判断、浏览器授权和原设置导航，保留 profile、充值/赠金余额、用量、充值、取消与退出。不存在伪造的 Desktop 桥，旧 `DeepSeekAccountPanel` 已删除。账号 provider 与 API Key 独立，API Key 继续使用内核 credentials RPC，避免覆盖 grant。详情见 [账号交接](DEEPSEEK_ACCOUNT.md)。

### 文件、备份与恢复

文件操作经过规范路径白名单；目录复制不跟随符号链接，默认删除进入同卷回收站。恢复同名保留两份。配置备份包含凭据并要求至少 8 位口令认证加密，覆盖全局/项目模型设置、`settings.yaml.imported` 和 active Web profile。嵌套路径严格白名单，全部目标先检查并拒绝越界符号链接；不含完整工作区、历史与附件。

运行包快照只允许 `dsh` / `tools`；记录与归档必须核对大小和摘要。恢复不触碰用户 `.dsh`，不降低 APK。自动恢复设置 hold，避免重启后再次触发同一失败更新。

0.36.1 用 VoiceStartGate 同时约束请求授权与启动录音；Host.canListen 只表示前台和页面身份，canStartRecording 再要求窗口焦点与 WebView 可见。权限回调不直接 begin：重新等待连续 250ms 就绪，4 秒上限；onResume 不能替代 onWindowFocusChanged，onPause 先清前台标志。真正 begin 再检查实际权限。服务错误 5/9 只在 ready 前且授权仍有效时最多重试一次；取消、后台及销毁清理独立 startCheck，旧 generation 不得回调。只允许相同本机 origin 的 cancel 穿过后台标志，避免页面超时无法撤销正在等待授权的请求；其他命令仍要求前台。不调用 AudioRecord 预热，不做后台麦克风服务，不自动打开系统语音框。

0.37.1 的授权路由以 Activity 和独立 requestCode 双重匹配，编号在本进程内不回收。NativeScreenCapture 还保留已启动服务的 requestId 屏障，取消后不能先接受新授权；只有 NativeCaptureService.onDestroy 释放资源/队列并调用 serviceFinished 才清屏障和交付图片。不要恢复为写完文件立即 complete，或因重复 onStartCommand 停掉当前实例。帧缓冲验证走 NativeBridgePolicy.captureBuffer，真实服务已接入 Android 边界回归；编码器仍是替身。

0.37.2 的 NativeBridge.onMain 管理 pendingActions，超时/中断/服务关闭时取消并从 Handler 队列移除；post 拒绝要立即失败。FileContext 绑定最初 Activity 与工作区，副本准备结束后主线程复核，错误只清本次副本。系统异常映射固定错误码/消息，不能把 exception.getMessage 传入工具。截图授权开始与调用取消之间通过 abandoned/started 双向检查清理，不能只依赖成功取得的 FutureTask 结果。客户端不自动重试有副作用的命令。

## 4. 开发入口

| 领域 | 入口 |
|---|---|
| 生命周期、整合 | `MainActivity.java`、`HarnessService.java` |
| 原生设计 | `DshUi.java`、`DeviceLayout.java`、`UiText.java` |
| 模型 | `ModelConfig.java`、`ModelCatalogSync.java`、`ModelReasoning.java`、`ModelEffortUi.java` |
| 内核 RPC 与账号 | `CoreRpcClient.java`、`NativeCoreApi.java`、`ModelSettingsSnapshot.java`、`AccountUi.java` |
| 连接与任务 | `SessionProbe.java`、`SessionStatus.java`、`ConnectionRecovery.java`、`TaskTimeline.java` |
| 更新 | `PayloadUpdate.java`、`PayloadRollback.java`、`TransferState.java` |
| 诊断 | `SecretMasker.java`、`DiagnosticReport.java`、`LogViewer.java` |
| 工程 | [脚本目录](../scripts/README.md)、[构建指南](BUILD.md) |

新增 UI 走 `DshUi`；异步回调要复核 Dialog/Activity 生命周期与 generation/revision。重复点击的同一操作应使用 gate，耗时操作离开主线程，不能用吞异常代替用户可见错误。

## 5. 必做回归

```bash
python3 scripts/check_java.py
bash scripts/run_tests.sh
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

正常发布由 Actions 验证；真实运行包另执行 `check_model_consumer.sh`。2026-10-02 用户确认 GitHub 账号暂停并明确要求本地编译，新增 `local_build.sh` 复用官方 macOS SDK、已校验旧 APK 与同一打包链。本地产物与公开清单分开记录；2026-10-06 GitHub 恢复后，用户授权继续正式 CI 与发布。测试计数看当次日志，不把旧 1049 项等数字写成长期固定承诺。

模型回归包含实际 Host、目录投影、`resolveCallConfig`、离线 SDK 请求体，以及真实聊天框 React 组件的模型/强度 RPC；两种语言均验证。内核回归包含持久化、独占发布/FUSE 降级、磁盘错误、Linux 锁及认证 Web profile。没有调用付费模型服务。

账号回归使用真实内核、生产 Java RPC 与本机合成平台，覆盖授权、凭据保留、账号默认消费、重启、退出、取消、过期和网络错误。它不代表真实 DeepSeek 平台授权或本版上游账号界面适配已在手机验收。

## 6. 已有证据与剩余事项

阶段四 B 的明确清单于 2026-09-27 经用户确认真机验收；用户后来确认模型目录可更新、聊天框思考选项已出现。2026-10-02 用户确认 0.33.9 布局问题基本修复，当前未看到问题。完整构建和签名由每次 CI 核验。

仍需分别验证新内核真机终端/图片、全量与增量更新、恢复、所有页面深浅色/大字体/横屏/平板、不同 Android 版本与 ROM 后台行为。服务商实际接受 max 的结果没有付费在线证据。历史主线程卡死尚无完整复现结论。

最新验证矩阵以 [STATUS](STATUS.md) 为准；新增证据记录日期、版本、设备、步骤和结果。

## 7. 发布与文档交付

用户已要求每轮修改递增并直接发布 stable。正常流程为完整 PR Android CI 成功后合并，再运行 release 工作流，保持既有密钥、先资产后清单。2026-10-02 用户因 GitHub 账号暂停明确改为本地构建，当日不上传、不改公开 latest。2026-10-06 账号访问恢复，用户授权继续 PR、完整 CI、合并与正式发布流程。

文档同步脚本覆盖中英文 README、所有 Markdown 状态块、STATUS 事实表、固定模型表和本地链接。每版来源字段要从实际产物计算，不使用估算 SHA 或预先宣布尚未发布的 APK。

阶段文档与旧版本说明保留原始内容；标题版本不改成最新号。新增变更在本版 release-notes 描述。

## 启动维护（0.40.2）

`StartupState` 管理页面代次、真实握手和首帧证据，`StartupView` 只负责 720ms 小鲸鱼游动、水波与周期末交接。Slot 外层是 `display:contents`，必须检查子按钮的矩形，不能只看 slot 自身。`onPageFinished` 不等于 UI 可用；双帧后的消息还要经 `postVisualStateCallback` 确认。旧 8 秒/45 秒强撤遮罩已移除，前台 UI 超时直接给出失败页，后台取消计时、回前台重新计时。

快速复用必须满足 `corePreparedKey`、同版资源、完整本地环境、工具指纹和可信清单（恢复暂缓沿用已有恢复验证），并实际认证探测服务；任何条件缺失回完整准备。`corePreparedKey` 仅完整准备成功后记录，不能提前写成“完成”。变更工作区/外部凭据、组件内容或切片必须失效。1.1.2 将完整能力检查移至首页交接后的前台空闲阶段，自检成功不再是复用门槛，失败同一指纹一小时退避；聊天、后台和重启/维护会中止检查，防止占住环境锁。工具复核与更新检查仍在界面交接后执行。Node 编译缓存在 Android 私有 cache，正常丢失可重建；本地检查不是手机测速。

## 8. 持续有效的事故防护与红线

### 8.1 签名与覆盖安装

现有签名证书 SHA-256：

`9bf19e2127afec00a883343baa51b92b118cac9ad537ea7fe365145a00089f16`

使用仓库既有 `scripts/release.keystore` 并核对正式 APK。不要生成新密钥、提交 APK 或建议卸载后重装。发布签名参考必须筛选 bootstrap APK 标签，payload Release 不是签名参考。

### 8.2 主题与生命周期

1.1 由 ThemeController/NativeTheme 原位同步，主题与方向变化均不重建 Activity 或重载 WebView；启动、安装、下载和设置模块边界见 [重构记录](REFACTOR_1_1.md)。主题跟随 DSH `ui-theme.preference`。颜色使用 `DshUi.BG()` / `TEXT()` 等方法；常量可能被 javac 内联。`recreate()` 必须具有去抖复核、跨重建持久化限流、超限硬停止，否则实例字段重建后归零会造成无限振荡。

每次页面加载重注入。主线程日志使用有界队列与后台写盘，不能每个网络回调同步写日志。内部探针消息由原生消费，不应完整刷入日志。

### 8.3 任务状态与自动操作

不能凭 DOM 按钮判定运行；不能凭设备有网判定 WebSocket 已连。证据过期应未知。运行或未知时不得为了应用模型目录重启任务；草稿只恢复同源本地文本，不自动发送。

### 8.4 用户数据与恢复

`.dsh`、凭据、历史、附件、工作区不能进入 payload 删除或快照恢复目标。完整私有数据备份与加密配置备份是不同东西；共享存储的 FUSE 不支持所有链接行为，备份需要正确的归档方案。

`tools/install_and_log.sh` 是会先卸载的旧实验脚本，禁止当作用户升级流程。优先 `adb install -r`，不执行 `uninstall` 或 `pm clear`。

### 8.5 Android 兼容层

保留 `--expose-internals` 的 Node 垫片、已验证 Android/bionic PTY、Python/Pillow 图像实现。不能把 Linux/glibc ELF 装进 Android。

当前会话发布硬链接优先，兼容性失败才用 `COPYFILE_EXCL`；禁止套用旧“检查后 rename”补丁，它不能保证排他性。flock no-op 降级仅在 Android，不能抑制桌面锁失败；降级本身不提供跨进程互斥。

### 8.6 配置与命令

`--patch` 在 `--profile` 前；没有 server 配置时不启用 `dsh-mcp-client`。升级修改静态字段时保留实时目录、能力声明和用户设置，不以旧预设覆盖新目录。不依赖旧附件 `.dshorig` 补丁备份作为新内核来源。

### 8.7 发布顺序

先资产、后清单；先 payload 完整发布、后 App 引用；稳定与测试清单绑定各自 APK；源码领先时只放宽严格递增候选，不取消正式校验。不要手工修 latest 文件来掩盖失败。

### 8.8 协作与验收

开工先 fetch；若安排并行任务，收到明确交回后再发布。源码/脚本/注释无 emoji，纯逻辑无 Android 导入，新 UI 无系统 AlertDialog。编译、模拟、真实内核消费、真机与付费服务验证分别报告。

## 1.0 候选的数据恢复维护

配置恢复的私有 `.native-config-restore` 是有界、带 SHA-256 的原配置撤销记录，不是用户备份格式。全部文件提交后删除即提交事务；中途失败或进程退出，启动在复用内核之前读取并恢复。无法验证/写回时保留记录并阻止启动混合配置。它含凭据原值，不得加入诊断、分享或普通日志；不要把它当缓存清理。

运行包 `.payload-update-in-progress` 在首个目录修改前落盘。update/restore 必须先核验真实快照、等待旧进程退出再恢复；fresh 则强制重新消费全部片。恢复过程中保持原标志连续存在，只有目录和修订状态都恢复后才删除。不能通过删除标志伪装修复。生产 Java 恢复方法与实际 Node 归档在 `tests/check_payload_recovery.py` 中执行进程中断及失败重试回归。
