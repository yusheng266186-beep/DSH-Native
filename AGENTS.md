# 给接手开发者与 Agent 的工作约定

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.10**；源码：**0.33.10**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](docs/STATUS.md)。
<!-- dsh-doc-status:end -->

本项目是 DeepSeek Harness（DSH）的 Android 原生适配客户端：Android/bionic Node 与工具链运行在 App 私有目录，不依赖用户安装 Termux 或 proot。

开发前先读 [当前状态](docs/STATUS.md)、[交接说明](docs/HANDOVER.md) 和 [踩坑记录](docs/GOTCHAS.md)。当前事实与验证边界集中在 STATUS；阶段文档和旧发布说明保留历史语境，不能用旧“候选”状态替代当前主分支事实。

## 1. 开工与交付

1. 在动手前 `git fetch origin main`，核对分支、HEAD 和工作区，保留用户改动。
2. 核对受影响的真实代码和消费路径；不要只修改 UI 标签或孤立的配置字段。
3. 保持逻辑与 Android UI 分离；新增判断优先进入可在 JVM 验证的纯逻辑类。
4. 执行适当检查，更新中英文入口、当前状态和相关技术文档。
5. 用户已约定每轮修改完成后递增版本，并直接发布 stable；须先通过完整 Android CI，再合并 main，经 `release.yml` / `release.sh` 发布。不得绕过签名、下载或清单校验。
6. 若任务实际安排了并行协作，必须取得所有相关任务的明确交回；文件一段时间不变不是完成证据。

## 2. 必须保留的红线

| 约束 | 要求 |
|---|---|
| 源码、脚本与注释 | 不使用 emoji；构建闸门会检查 |
| 纯逻辑 | 不导入 `android.` / `androidx.`，在普通 JVM 测试 |
| 原生 UI | 新功能走 `DshUi`，禁止 `AlertDialog.Builder`；颜色调用主题方法，不用会被 javac 内联的颜色常量 |
| 主题来源 | 跟随 DSH 网页，不只跟 Android 系统；构建视图前应用主题 |
| Activity 重建 | 必须有去抖复核、跨重建持久化限流、超限硬停止 |
| WebView 注入 | 每次页面加载执行；SessionProbe 必须在网页模块前初始化并解析完整 RPC 响应 |
| 状态判定 | 会话列表 `running` 是运行状态权威；不凭按钮文案假报运行/空闲；过期证据变未知 |
| 凭据和日志 | 不输出密钥、认证头、目录响应正文；诊断 ZIP 不包含会话正文、附件或项目文件 |
| 用户数据 | 运行包替换、删除与恢复只涉及运行目录；保护 `.dsh`、凭据、历史及项目 |
| 运行包恢复 | 校验记录、大小、摘要和目标集合；恢复后暂缓再次更新，避免循环 |
| App 更新 | 保持 `dev.dsh.native` 包名与既有签名；不通过卸载解决签名问题 |
| 签名 | 不换密钥，不在缺失时生成新的；证书 SHA-256 见交接第 8 节 |
| APK | 不提交进 Git；只由 Actions 构建，通过 Releases 分发 |
| 发布清单 | 只用发布脚本生成 `latest.json` / `latest-test.json`，先验证资产与下载路径 |
| 源码与发布版本 | 候选源码可以领先已发布清单；清单仍绑定自身 APK 和 payload，不假造已经发布 |
| 文档生成区 | 运行同步脚本，不手工改版本、摘要、状态块或固定模型表 |
| CLI 参数 | `--patch` 写在 `--profile` 前面 |
| MCP 插件 | 未配置 server 时不要启用 `dsh-mcp-client`，可能阻断整个启动 |
| Android 架构 | minSdk 24、targetSdk 28、ARM64；提高 targetSdk 先重新设计可执行文件部署 |
| XML 注释 | 不出现连续两个减号，避免 aapt2 解析失败 |
| 外部能力 | 不把未知视觉能力标为支持；不把强制 max 请求说成服务商已保证支持 |
| 静默捕获 | `catch (Throwable ignored)` 总数**不得超过 `check_java.py` 的 `SILENT_CATCH_BASELINE`**；新增前先判断这里该不该 `log()`。清理类（关流、取消动画、销毁 WebView）可静默但要就近写清理由 |
| 前端布局 | **禁止全局元素选择器 + `!important`**（如 `button{...}`）——会压倒上游组件自身规则，曾把设置界面顶乱。修窄屏挤压只准用 `aria-label` 等稳定锚点定点修，改前先用 `LayoutProbe` 取真实数据；禁止写依赖 CSS Module 哈希类名的选择器（编译后形如 `_action_1pq26_43`，匹配不到）。窄屏视口维持 480，改小会让全局元素放大约 20% |

## 3. 常用检查

```bash
python3 scripts/check_java.py
bash scripts/run_tests.sh
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

`check_java.py` 无需 JDK，但不能代替真实编译；未安装 javalang 时语法与跨类检查会明确跳过。`run_tests.sh` 需要 JDK、Python 与 Node，可使用编译器模块代替缺失的 javac 启动器。

完整 Linux / CI 构建：

```bash
bash scripts/ci_build.sh /tmp/dsh-build
```

需要 Android SDK 和官方 Linux build-tools。旧 Termux/bionic 工具属于历史设备构建路径，不是 Linux CI 的依赖。输出 `/tmp/dsh-build/bootstrap/DSHNative-bootstrap.apk`；底层 `build_bootstrap.sh` 要求已经准备好的隔离工作区。

真实运行包消费回归：

```bash
bash scripts/check_model_consumer.sh
```

它读取源码 payload 标签、下载并校验实际归档，验证真实 Host、LLM、聊天框 React 选择组件与内核持久化；测试依赖位于 `tests/js`，不进入 APK。不会发送付费模型提示词。

## 4. 核心代码导航

| 范围 | 主要入口 |
|---|---|
| 启动、配置和生命周期 | `MainActivity.java`、`HarnessService.java`、`LocalServerProbe.java` |
| 模型与目录 | `ModelConfig`、`ProviderCheck`、`LiveModelCatalog`、`ModelCatalogSync`、`ModelReasoning`、`ModelEffortUi`、`ProjectModelSettings` |
| 状态与恢复 | `SessionProbe`、`SessionStatus`、`TaskTimeline`、`ConnectionRecovery`、`DraftRecovery`、`ProcessSupervisor` |
| 文件与项目 | `FileOps`、`FileBatch`、`FileTrash`、`FilePreview`、`WorkspaceProjects`、`FileBrowser`、`TextEditor` |
| 安全与维护 | `PayloadUpdate`、`PayloadManifest`、`PayloadRollback`、`RuntimeDir`、`ConfigBackup`、`CredentialMerge`、`SecretMasker`、`DiagnosticReport`、`PluginPermissions` |
| 配置与链接 | `YamlBlocks`、`WebUrl`、`ModelConfig`、`ModelImageSupport` |
| 原生界面与交互 | `DshUi`、`DeviceLayout`、`MobileLayout`、`UiText`、`OperationGate`、`InteractionFeedback` |
| 分发与内核 | `payload/`、`runtime/`、`scripts/prepare_core_payload.py`、`scripts/release_payload.py` |

路径以当前仓库 `src/dev/dsh/nativeapp/` 为准；不要把 CI 工作区的 `bootstrap/src` 错当仓库布局。

## 5. 模型修改的真实验证边界

- 可见 ID 来自当前上游目录，本地能力记录只补充声明。
- 刷新成功直接写入完整 provider catalog；空闲后重载应用，运行/未知时延后。
- `ModelReasoning.supportedDeclaration` 保留原能力来源；`declaration` 添加字面量 max，所有生成与离线修复路径使用同一规则。
- 未声明档位的模型保留不传参数的默认路径；不偷偷降级 max。
- 必须验证 Host `buildModelCatalog`、LLM `resolveCallConfig`、离线 SDK 请求体和注册到聊天框的组件/RPC。原生列表正确不能证明聊天框可用。
- 默认值迁移不改写历史会话；用户可显式修改聊天框后续请求的模型与强度。

## 6. 验证结果如何表述

严格区分逻辑测试、实际运行包消费、Android 构建、真机反馈和真实服务商请求。测试数字以本次输出为准；历史阶段的数字不重新累计为当前总量。

阶段四 B 于 2026-09-27 有用户真机验收确认；其他功能按具体证据表述。不要再写“从未做过任何真机验证”，也不要把一个阶段通过扩大成所有页面和机型已验收。

签名事故、主题振荡、会话探针、用户数据和当前未验证项见 [交接说明第 8 节](docs/HANDOVER.md#8-持续有效的事故防护与红线)。
