# 交接说明

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.12**；源码：**0.33.13**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
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
| 内核 | 固定来源和锁文件位于 `runtime/`；当前使用 DSH 0.2.0-rc.2，上游候选版 |
| 运行包 | payload-v10：DSH 修订 5，工具片沿用 v9 修订 4；全量压缩约 118.5 MiB |
| 空间 | DSH 片实际展开分配约 491 MiB；还要计算工具、内置负载、下载和快照，不能沿用旧 316MB / 550MiB 总预算 |
| 配置 | `.dsh` 含凭据、profile、历史与附件；全局/项目模型覆盖单独保存 |
| 工作区 | 默认 `/sdcard/DSHNative/workspace`，命名项目在其 `projects/` 下 |
| 发布 | 两个工作流共用 `ci_build.sh`；正式版只经 `release.yml` / `release.sh` |

源码、已发布 stable / test 与摘要的精确值只在 [STATUS](STATUS.md) 生成表维护。

## 3. 关键调用链

### 启动与存活探测

`MainActivity` 初始化环境、配置、补丁与 WebView；`SessionPersistencePatch` 在每次启动修复首次保存和格式迁移的两个硬链接发布点，兼容旧 payload-v12。链接 EACCES 等兼容错误才降级为排他复制并 fsync，同名历史拒绝覆盖，真实写权限和磁盘错误仍传播。旧进程若加载的是较早 APK 的模块，明确要求从更新与维护重启，不误报已采用补丁。`LocalServerProbe` 在同一 localhost origin 完成 token → 303 → Cookie 探测，限跳转、响应大小与超时，不向其他 origin 发送凭据。已可用实例尽量复用，进程失败按 `ProcessSupervisor` 有界退避。

页面每次加载都重新安装辅助脚本。会话探针必须早于模块脚本，完整解析真实列表 RPC，不能截断正文后数 `running:true`。复用已观察到的认证只读 RPC，每 5 秒刷新并使用新 rpcId；90 秒没有可信数据时报告未知。DOM 只补充等待批准，不承担运行状态权威。

服务地址可能早于 legacy 模型导入完成，完成日志通常不输出到终端。存在待导入文件时，启动前保存预期配置，`CoreReadiness` 通过真实 `settings/describe` 核对默认、目录与能力，最多等待 30 秒；超时也不绕过该条件。系统日志同样必须走 `SecretMasker`。

### 模型目录与强度

`ProviderCheck` 只读当前服务商目录；`LiveModelCatalog` 以返回 ID 为可见集合，保留视觉、token 和兼容字段。`ModelCatalogSync` 写入完整 DSH provider models 并保留 live-catalog 标记，防止升级静态预设覆盖实时目录。

DSH 配置涉及 legacy `settings.yaml` 导入与 `.dsh/profiles/web/cordis.patch.yml` 的 active profile；排查时不能只看一个文件就认为运行时已应用。目录成功保存后，空闲时受控重载；运行或未知时持久化 pending，确认空闲后执行。仅刷新 WebView 不足以重建 provider topology。

导入后 legacy 文件变为 `settings.yaml.imported`。没有待导入文件时，`NativeCoreApi.modelSettings` 通过 `settings/describe` 与 `ModelSettingsSnapshot` 读取公开模型 namespace；模型面板与项目切换使用同一快照。切换项目的未知任务状态也必须进入中断确认，不能当作空闲。

`ModelReasoning` 区分官方支持声明与用户要求的全模型 max 请求扩展。所有模型添加 `max: max`，未知/非可调模型保留 `off: null` 默认路径。`ModelEffortUi` 有锚点、幂等地修改实际聊天框组件标签；不匹配时不写文件并记录诊断。

全局/项目默认只影响新会话；聊天框可显式更改已有会话的后续调用配置。不回写历史，不改变已发请求，不把上游拒绝 max 隐藏为其他强度。

### App 设置与会话

0.33.12 将 App 设置注册到 DSH 原始框架；设备工具从对应类别进入并返回。`AppSettingsUi` 启动时严格修补 General、SessionController 和会话客户端缓存，辅助脚本从 APK 更新。不要恢复旧 `SessionOrganizer` 按文字找按钮的方案。会话列表支持标题筛选、分页、重命名、归档、恢复继续和可恢复回收站。恢复必须清除缓存 removed 状态；journal 不能按 Remote 代理 WeakMap 分组，要按 DSH_HOME 共享队列。配置恢复用静态 `runtimeConfigLock` 并等待托管进程退出，插件有应用重启。详见 [App 设置](APP_SETTINGS.md)。

### DeepSeek 账号

模型刷新位于 App 工具的模型中心。App 工具的 DeepSeek 账号入口通过页面内的无权限事件打开 DSH 原始账号设置；`AccountUi` 仅适配桌面判断、浏览器授权和原设置导航，保留 profile、充值/赠金余额、用量、充值、取消与退出。不存在伪造的 Desktop 桥，旧 `DeepSeekAccountPanel` 已删除。账号 provider 与 API Key 独立，API Key 继续使用内核 credentials RPC，避免覆盖 grant。详情见 [账号交接](DEEPSEEK_ACCOUNT.md)。

### 文件、备份与恢复

文件操作经过规范路径白名单；目录复制不跟随符号链接，默认删除进入同卷回收站。恢复同名保留两份。配置备份包含凭据并要求至少 8 位口令认证加密，覆盖全局/项目模型设置、`settings.yaml.imported` 和 active Web profile。嵌套路径严格白名单，全部目标先检查并拒绝越界符号链接；不含完整工作区、历史与附件。

运行包快照只允许 `dsh` / `tools`；记录与归档必须核对大小和摘要。恢复不触碰用户 `.dsh`，不降低 APK。自动恢复设置 hold，避免重启后再次触发同一失败更新。

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

## 8. 持续有效的事故防护与红线

### 8.1 签名与覆盖安装

现有签名证书 SHA-256：

`9bf19e2127afec00a883343baa51b92b118cac9ad537ea7fe365145a00089f16`

使用仓库既有 `scripts/release.keystore` 并核对正式 APK。不要生成新密钥、提交 APK 或建议卸载后重装。发布签名参考必须筛选 bootstrap APK 标签，payload Release 不是签名参考。

### 8.2 主题与生命周期

主题跟随 DSH `ui-theme.preference`。颜色使用 `DshUi.BG()` / `TEXT()` 等方法；常量可能被 javac 内联。`recreate()` 必须具有去抖复核、跨重建持久化限流、超限硬停止，否则实例字段重建后归零会造成无限振荡。

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
