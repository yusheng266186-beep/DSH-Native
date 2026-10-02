# 构建、发布与维护脚本

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.9**；源码：**0.33.9**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

本目录服务于仓库开发、Linux CI 和正式发布。用户运行 App 不需要这里的开发工具。完整流程见 [BUILD](../docs/BUILD.md)，当前版本事实见 [STATUS](../docs/STATUS.md)。

## 脚本接口

| 脚本 | 用途 | 条件与边界 |
|---|---|---|
| `check_java.py` | 静态语法、引用与约束检查 | Python；javalang 可选，不代替 CI 编译 |
| `run_tests.sh` | JVM、JS、HTTP、元数据与文档逻辑回归 | JDK / Python / Node；数量以本次日志为准 |
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
| `make_payload_parts.py` | 工具链分片与 manifest 生成 | 按脚本环境变量指定工具链/输出目录 |
| `release_payload.py` | 独立运行包资产校验与发布 | manifest 最后上传；不改 App latest 清单 |
| `mkmanifest.py` | 二进制 AndroidManifest 生成 | 配合实际资源与构建工作区 |
| `mkzip.py` | 将构建产物组装为 APK | 不负责签名或发布 |

## 日常开发

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
