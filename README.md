# DeepSeek Harness Native for Android

[中文](README.md) | [English](README.en.md)

把 Node.js 运行时、DeepSeek Harness（DSH）和常用开发工具直接带到 Android。无需 Termux、无需 proot，也不需要把项目交给远程服务器执行。

**当前版本：0.30.0**（payload-v9）

## 下载与安装

正式版 APK：

**[下载 DSHNative-bootstrap.apk](https://github.com/yusheng266186-beep/DSH-Native/releases/download/v0.30.0-bootstrap/DSHNative-bootstrap.apk)**（33.7 MiB）

SHA-256：`29f9ba3b4a99fe7d7d2dbbbeb58cd7858de85ba1b5ef3fb12354effeda884f25`

要求：

- Android 7.0 或更高版本（minSdk 24）
- ARM64 设备
- 首次启动保持联网
- 建议至少预留 550 MiB 可用空间

首次启动流程：

1. App 在 3 秒左右完成 Node 架构自检。
2. 从 GitHub 镜像分块下载约 117.2 MiB 运行包，支持断点续传、块级重试和 SHA-256 校验。
3. 解压 DSH 与工具链并执行运行环境自检。
4. 打开模型中心，填写 Command Code 或 DeepSeek API Key。
5. 从服务商实时目录选择模型，然后开始新会话。

覆盖安装同签名新版不会删除会话、密钥、项目、任务历史或配置。不要先卸载旧版；卸载会清除 App 私有数据。

## 主要能力

### 完整 DSH 体验

- 在 App 私有目录直接运行 Android 原生 Node.js 与 DSH WebUI。
- agent 可使用内置 `git`、`rg`、`fd`、`jq`、`bash` 和 Python 工具链。
- 前台服务保持任务在后台或锁屏时继续运行。
- WebSocket 与页面探针共同判断空闲、运行、等待批准、恢复和结束状态。
- 通知栏显示任务开始时间、已运行时长、连接状态，并在等待批准或后台完成时提醒。

### 模型中心

支持两条路由：

| 服务商 | 模型目录来源 | 只读端点 |
|---|---|---|
| Command Code | 每次打开或刷新时从 Command Code 上游读取 | `GET https://api.commandcode.ai/provider/v1/models` |
| DeepSeek 官方直连 | 每次打开或刷新时从 DeepSeek 官方读取 | `GET https://api.deepseek.com/models` |

模型选择器不再把 App 内置预设直接当作服务商列表。它只展示本次上游响应中实际返回的模型 ID；读取仅请求模型目录，不发送提示词，也不会产生模型生成费用。

上游目录是模型 ID 的权威来源：只要服务商在本次 `/models` 响应中返回，模型中心就允许直接选择和保存，不会因为本地能力目录尚未更新而禁用新模型。图片输入和思考能力标签仅在本地有声明时显示，缺少标签不会阻止使用。当前已保存的模型在临时断网时仍可原样保留；首次配置或切换到新模型必须完成一次实时目录读取。

模型中心还支持：

- 全局默认模型与每个项目的独立覆盖
- `off / low / medium / high / xhigh / max` 思考强度
- Command Code 用量入口
- 密钥拒绝、限流、端点变化、服务异常和无效响应的明确提示
- 搜索上游模型 ID
- 显式“返回工具与设置”按钮与 Android 返回手势

模型变更用于新会话；已经发送过请求的会话继续保留其日志中记录的模型。

### 项目、文件与会话

- 默认共享工作区：`/sdcard/DSHNative/workspace`
- 可创建互相隔离的命名项目，并为每个项目设置独立模型
- 文件浏览、文本编辑、图片预览、批量复制/移动和可恢复回收站
- 从其他 App 分享文件或文本到当前项目，并可直接创建任务
- 调用 DSH 官方会话搜索、归档和恢复入口，不复制不稳定的内部 RPC
- 输入草稿在页面刷新或短暂断线后恢复，但不会自动发送

### 更新、回滚与诊断

“工具与设置 → 更新与维护”提供：

| 功能 | 行为 |
|---|---|
| 更新运行包 | 按分片清单只下载变化的 DSH / 工具链内容，校验后重启 agent |
| 恢复上一运行环境 | 恢复更新前快照，不修改会话、密钥、项目文件或 APK |
| 检查 App 更新 | 校验版本、包名、签名和 SHA-256 后调用系统安装器覆盖安装 |
| 稳定 / 测试通道 | 稳定通道只读 `latest.json`；测试通道同时比较 `latest-test.json` |

运行包更新采用“先快照、后替换、失败自动恢复”。诊断中心可以导出脱敏 ZIP，内容包括设备、布局、网络、通知、运行环境、回滚状态和最近日志，但不包含凭据、会话正文、附件或项目文件。

### 中文与 English

在“工具与设置 → 显示与语言”选择跟随系统、中文或 English。原生设置首页、模型中心、任务中心、项目管理、更新、诊断和桌面快捷方式均支持英文。DSH WebUI 的语言由网页设置单独控制。

新安装会显示语言与环境引导；已存在 `.dsh` 数据的升级用户不会被强制补弹首次向导。

## 如何打开 App 工具

推荐入口：展开 DSH 侧边栏，在底部点击“App 工具 / App tools”。

侧边栏收起时，页面上的齿轮属于 DSH WebUI 自己的设置，不会被原生 App 劫持。以下入口可作为备用：

- 长按页面顶部
- 通知栏中的“设置”操作
- 桌面图标长按后的“设置”“运行日志”“检查更新”快捷方式

原生设置子页均提供可见返回按钮；Android 返回键和边缘返回手势执行相同的父级导航，不会静默关掉子页或直接触发 App 退出确认。

## 文件、隐私与安全边界

- API Key 只保存在 App 私有目录的 `.credentials.yaml`。
- 模型目录请求使用当前服务商的官方 HTTPS 地址；日志不记录密钥、请求头或响应正文。
- 原生层没有新增高权限 `JavascriptInterface`；WebUI 辅助入口使用受限脚本注入。
- 文件写入限制在 App 私有目录和 `/sdcard/DSHNative` 白名单内。
- 配置备份包含密钥，因此导出文件必须设置至少 8 位口令并使用认证加密。
- 更新 APK 必须保持包名与发布签名一致，否则 Android 会拒绝覆盖安装。

## 架构

```text
Android Activity / WebView
        |
        +-- Native tools and settings
        +-- Foreground task service and notifications
        +-- Update, rollback, backup and diagnostics
        |
        +-- Node.js (Android/bionic, arm64)
                |
                +-- DeepSeek Harness WebUI
                +-- git / rg / fd / jq / bash / Python
                +-- Shared workspace
```

完整运行包解压后有数百 MiB，无法作为普通 GitHub 单文件稳定分发。因此 APK 只携带 Node、引导逻辑和清单，首次启动再下载经过哈希验证的分片。之后更新只替换变化分片。

Android 10 起，targetSdk 29 及以上的普通 App 不能直接执行私有数据目录中的文件。本项目暂时固定 `targetSdkVersion 28` 以维持当前原生执行架构；这是一项明确的架构约束，不应在普通功能 PR 中随意提高。

## 构建与测试

仓库构建需要 JDK 17/21、Android SDK build-tools、`d8`、`aapt2` 与 `apksigner`。

```bash
bash scripts/run_tests.sh
bash scripts/build_bootstrap.sh
```

当前回归包括：

- 1049 项纯逻辑断言
- WebUI 工具入口 DOM 模拟
- WebSocket 连接恢复模拟
- 草稿恢复模拟
- 会话状态 fetch 与 DOM 模拟
- 运行包快照与恢复模拟
- Java 架构、资源 XML、无 emoji、无系统 AlertDialog 和无高权限桥闸门
- Android CI 的 javac、DEX、aapt2、签名、清单与 APK 产物验证

发布必须使用 `.github/workflows/release.yml` 与 `scripts/release.sh`。不要手工上传 APK、手改更新清单或迁移签名密钥。

## 仓库结构

```text
src/dev/dsh/nativeapp/   Android 原生外壳、模型中心、任务与工具面板
payload/                 首启、解压、快照与运行时辅助脚本
patch/                   DSH 在 Android/bionic 上运行所需补丁
tests/                   纯逻辑测试与 JavaScript 模拟
icon/                    图标、主题、动效与中英文快捷方式资源
scripts/                 构建、CI、签名核验与发布脚本
docs/                    架构、踩坑、交接和各阶段验收文档
release-notes/           已发布版本说明
```

## 已知限制

- 当前仅提供 ARM64 构建。
- 首次安装必须联网下载运行包。
- `targetSdk 28` 是现有私有目录执行方案的约束；长期需要迁移到 `nativeLibraryDir` 或其他受支持架构。
- 上游 `/models` 通常只返回 ID，不提供完整图片、上下文和推理能力；未经当前运行环境声明的新模型会显示但不会被误标为可用。
- Office 到 PDF 的大型转换引擎未打包，以控制运行包体积。
- 运行环境恢复只恢复 DSH 与工具链，不是 APK 降级。

## 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 接手开发前必须阅读的红线、结构与命令 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 两段式架构、运行包、补丁和组件边界 |
| [docs/GOTCHAS.md](docs/GOTCHAS.md) | Android、网络、布局、更新和发布踩坑 |
| [docs/HANDOVER.md](docs/HANDOVER.md) | 当前主分支事实、签名与交接状态 |
| [docs/BUILD.md](docs/BUILD.md) | 本地与 CI 构建、校验和发布流程 |
| [docs/PHASE5A-TASK-RECOVERY.md](docs/PHASE5A-TASK-RECOVERY.md) | 任务状态、断线恢复与草稿保护 |
| [docs/PHASE5B-FILES-SESSIONS.md](docs/PHASE5B-FILES-SESSIONS.md) | 文件工作流、回收站与会话管理 |
| [docs/PHASE5C-MODEL-ONBOARDING.md](docs/PHASE5C-MODEL-ONBOARDING.md) | 模型中心、项目覆盖与首次配置 |
| [docs/PHASE5D-ROLLBACK-DIAGNOSTICS.md](docs/PHASE5D-ROLLBACK-DIAGNOSTICS.md) | 回滚、诊断、无障碍和多设备适配 |
| [docs/PHASE5E-MODEL-CATALOG-I18N.md](docs/PHASE5E-MODEL-CATALOG-I18N.md) | 实时上游模型目录、返回导航与英文完善 |

## 许可证与来源

本仓库采用 MIT License。Node.js Android 构建来自 Termux 发行版，并保留各上游组件原有许可证。

- DeepSeek Harness：<https://github.com/deepseek-ai/deepseek-harness>
- Termux：<https://github.com/termux/termux-app>
- Android 10 行为变更：<https://developer.android.com/about/versions/10/behavior-changes-10>
