# 构建、发布与维护脚本

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**1.0.0**；源码：**1.0.0**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

本目录服务于仓库开发、Linux CI 和正式发布。用户运行 App 不需要这里的开发工具。完整流程见 [BUILD](../docs/BUILD.md)，当前版本事实见 [STATUS](../docs/STATUS.md)。

## 脚本接口

| 脚本 | 用途 | 条件与边界 |
|---|---|---|
| `check_java.py` | 静态语法、引用与约束检查 | Python；javalang 可选，不代替 CI 编译 |
| `run_tests.sh` | JVM、JS、HTTP、元数据与文档逻辑回归 | JDK / Python / Node；数量以本次日志为准 |
| `local_build.sh <dir> <已发布APK>` | 官方本机 SDK 与已校验旧 APK 本地构建 | macOS/Linux、JDK、Python、Node；保留全部闸门与既有签名 |
| `ci_build.sh <dir>` | SDK 准备、隔离工作区、完整 APK 构建 | Linux、JDK 17、Android SDK、网络 |
| `ci_stage.sh <dir>` | 源码/资源与上一正式 APK 内置负载准备 | 会重建指定目录，只传专用临时目录 |
| `build_bootstrap.sh` | 测试、闸门、编译、DEX、资源、打包和签名 | 已准备的工作区；Linux/bionic 工具分别处理 |
| `check_model_consumer.sh` | 验证源码引用的真实 payload 与聊天框组件 | gh / npm / JDK / Node / zstd，模型请求离线捕获 |
| `bump_version.sh X.Y.Z` | 同步 versionName、versionCode 与启动日志 | 从现有源码读版本，不硬编码旧值 |
| `sync_project_metadata.py` | 正式清单、双 README 与全部文档同步/检查 | 发布更新由 release.sh 提供真实产物参数 |
| `project_docs.py` | 状态块、STATUS 事实表、模型表与本地链接逻辑 | 由 sync_project_metadata 调用，不单独发版 |
| `check_signer.sh <APK或URL>` | 既有证书与 APK 签名一致性 | 需要 apksigner；不生成新密钥 |
| `release.sh <version> <build> <notes> [channel]` | 上传、验证下载、最后写清单并同步文档 | 由 release.yml 调用，已有构建产物与 gh 权限 |
| `verify_release.sh <version>` | bootstrap 正式版本辅助验证 | 在实际 APK 所在目录运行；不通用适配 test/payload |
| `prepare_core_payload.py` | 从固定官方归档与依赖锁构建 Android DSH 片 | 保留已验证 Android PTY/垫片，参数见 help |
| `check_payload_manifest.sh` | 用 App 规则检查真实发布清单的大小、固定摘要、结构和路径 | CI 构建前自动执行；可传本地清单路径 |
| `make_payload_parts.py` | 工具链分片与 manifest 生成 | 按脚本环境变量指定工具链/输出目录 |
| `release_payload.py` | 独立运行包资产校验与发布 | manifest 最后上传；不改 App latest 清单 |
| `mkmanifest.py` | 二进制 AndroidManifest 生成 | 配合实际资源与构建工作区 |
| `mkzip.py` | 将构建产物组装为 APK | 不负责签名或发布 |

## 日常开发

`check_model_consumer.sh` 还会运行 `upgrade-runtime-consumer.mjs`：用实际 manifest 构造增量升级遗留目录，复现旧错误后执行 App 的 CoreUpgrade，验证新建/重启恢复会话、旧账号缓存和调试 Worker。账号消费回归另验证合成授权、实时目录及显式覆盖保留。

0.35.0 增加实际文件后端的 `fs-write-consumer.mjs`、语音组件合成事件 `android-voice-simulation.mjs`，并在 App 设置回归中通过生产 Java RPC 验证内核任务状态。触屏回归启用真实语音插件并检查展开状态与草稿插入；合成识别结果不能当作 Android 系统录音验收。

`run_tests.sh` 从 0.35.1 起执行 `tests/check_native_voice.py`：临时生成 Android API 边界替身，编译并执行生产 NativeVoiceController，验证默认引擎缺失、错误 5 的系统窗口回退、停止时序、超时、权限与生命周期。替身和依赖不进入 APK，无设备/音频/ASR；完整 Android SDK 编译仍由构建检查。

```bash
python3 scripts/check_java.py
bash scripts/run_tests.sh
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

run_tests 自动识别仓库与 CI 工作区布局；javac 缺失时尝试 JDK 编译器模块。纯逻辑不能导入 Android。它同时验证 Web 工具入口、状态/连接/草稿、运行包快照、HTTP 认证与元数据等路径。不要将历史断言数量写成固定的当前总量。

真实运行包回归独立执行，React/jsdom 依赖位于 `tests/js` 的固定锁文件，只用于 CI，不能加进 APK。该目录已有 CommonJS 测试与 `.mjs` 测试并存，不通过全局 `type: module` 破坏旧测试。

## 构建工作区

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash scripts/ci_build.sh /tmp/dsh-build
```

官方 Linux build-tools 默认 34.0.0，Android 平台文件需要 28 和 34。ci_stage 从上一 bootstrap APK 提取 Node 与十个库，payload Release 不能当作 APK 来源。

GitHub 不可用时执行 `bash scripts/local_build.sh /tmp/dsh-local-build /path/to/DSHNative-bootstrap.apk`；参考 APK 必须匹配 published stable 大小、摘要与签名。官方 macOS build-tools 34 的 aapt2 可在 Apple Silicon 原生运行。本地流程不改公开清单、不上传。

输出 `/tmp/dsh-build/bootstrap/DSHNative-bootstrap.apk`。底层 build_bootstrap 需要 `DSH_BUILD_DIR` 指定已准备的目录；手写清单与 DEX 工具链不意味着“零资源 APK”，图标、主题、快捷方式与动效资源必须编译验证。

旧设备环境的 bionic aapt2 不能在 Linux 直接执行；Android 也不能加载 glibc 二进制。工作区是可重建临时目录，不能使用用户项目或运行数据目录。

## 文档同步接口

```bash
# 更新文档，保持已发布 latest 清单不变
python3 scripts/sync_project_metadata.py --docs-only

# 只验证所有生成区和本地文件链接
python3 scripts/sync_project_metadata.py --docs-only --check

# 发布完成后的严格元数据 + 全文档检查
python3 scripts/sync_project_metadata.py --check
```

同步范围包含根 README/AGENTS，以及 docs、scripts、tools、patch、release-notes 的 Markdown。状态块从 stable/test、源码版本、payload 标签和固定 core-source 生成；模型表从官方固定 fixture 生成。链接检查针对本地文件，不重新核查外部研究来源。

`--allow-unpublished-source` 只适用于严格递增的待发布源码，文档必须同步显示 published stable 和 source。生成区不得手改；功能正文、验证结论和中文/英文仍需技术审阅。

正式 stable 更新模式由 release.sh 传入真实 APK 与 payload manifest，计算大小与 SHA-256，修改清单并同步双 README 和所有文档。test 发布只更新 test 清单与状态块，下载入口继续指向 stable。

仓库版本说明保留自动状态块；复制到 GitHub Release 的公开正文时，工作流移除这段可变仓库状态，保留本版原始变更，避免冻结发版前 stable 版本和无效相对链接。

## 发布

用户已约定每轮修改递增并发布 stable。先完整 Android CI，后 main 合并，再运行 release.yml。发布保持包名与签名，先资产和下载验证、后清单回提交。

不要将 `release.sh` 当作任意工作区手动上传的快捷命令，不预写 latest，不生成新密钥，不提交 APK。独立 payload 先完成所有资产并最后上传 manifest，App 才能引用它。

签名事故与运行安全边界见 [HANDOVER 第 8 节](../docs/HANDOVER.md#8-持续有效的事故防护与红线)，流程清单见 [release_checklist](release_checklist.md)。

0.36.1 将 VoiceStartGate 加入纯逻辑检查与 JVM 回归。生产 NativeVoiceController 的边界测试覆盖授权先于焦点返回、焦点抖动、等待超时、权限撤销、一次启动重试与取消/后台清理；0.36.0 起保持默认无自动系统窗口，只有显式 start-window 打开 Activity。真实 WebUI 触屏回归也检查前台等待状态的溢出与取消入口。

0.36.2 的 `runtime-checks.js` 随 APK 下发，在私有 tmp / 当前工作区检查实际 LocalFileSystem API；`runtime-environment-consumer.mjs` 在校验后的真实运行包上验证链接拒绝、保护、失败和清理，并执行生产 preflight（工具命令/网络边界替身）。它不安装包、不读用户会话，也不等于手机测试。

0.37.0 的 NativeBridgeTest 执行生产协议/文件策略/HTTP server；check_native_bridge.py 执行生产 NativeBridge/NativeScreenCapture 与 Android API 替身。真实运行包检查额外执行 android-bridge-consumer.mjs：实际 ToolRuntime、APK 插件/客户端、Java server、CLI 和 Web profile 插件加载。不访问用户手机、剪贴板、通知或真实截图。

0.37.1 的 check_native_bridge.py 也编译执行真实 NativeCaptureService，通过 NativeCaptureServiceTest 检查销毁后交付、取消与服务启动竞争、重复 Intent、旋转、系统停止、内存失败和计时器/图像资源释放。Android API/编码器是替身；NativeBridgePolicy 的 ByteBuffer 校验直接执行 JVM 实现。没有真机捕获或真实 PNG 证据。

0.37.2 新增 NativeActionReliabilityTest：确定性主线程排队/窗口更换/系统拒绝替身执行生产 NativeBridge，真实等待其 5 秒 UI 超时，验证待执行动作移除和原文件保留。android-bridge-consumer 额外检查实际 HTTP 400/403 到 CLI/ToolRuntime 的错误保留，以及真实本机无响应 peer 的 12 秒客户端期限与无自动重试；不发送付费提示词。
