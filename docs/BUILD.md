# 构建、验证与正式发布

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.2**；源码：**0.33.3**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 1. 选择正确入口

| 任务 | 入口 | 环境 |
|---|---|---|
| 本地静态检查 | `check_java.py` | Python；javalang 可选，不代替真实编译 |
| 逻辑和脚本回归 | `run_tests.sh` | JDK、Python 3、Node.js |
| 元数据与文档检查 | `sync_project_metadata.py --check` | 仓库完整文件，Python 3 |
| 完整 Linux / CI 构建 | `ci_build.sh <隔离目录>` | JDK 17、Android SDK、网络；GitHub CLI 推荐并用于消费者回归 |
| 底层打包 | `build_bootstrap.sh` | 已由 ci_stage 或历史设备路径准备的工作区 |
| 实际 payload 消费验证 | `check_model_consumer.sh` | JDK、Node/npm、gh、zstd/tar、网络 |
| 正式 App 发布 | `.github/workflows/release.yml` | GitHub Actions 与现有签名 |
| 独立 payload 发布 | `release_payload.py` | 已构建并验证的分片、GitHub 发布凭据 |

用户安装与运行 App 不需要 Termux。旧设备构建使用 bionic aapt2；当前 Actions 在 Ubuntu 使用官方 Linux 工具，二者不能混用。

## 2. 本地检查

```bash
python3 scripts/check_java.py
bash scripts/run_tests.sh
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

若 JDK 镜像没有 javac 启动器但有 `jdk.compiler` 模块，测试脚本自动使用 `java -m jdk.compiler/com.sun.tools.javac.Main`。没有 javalang 时静态解析会明确跳过；CI 的真实编译仍必须完成。

`--allow-unpublished-source` 只允许源码严格高于已发布 stable/test，适用于候选 PR 和待发布 main。它不允许版本倒退，也不允许修改已发布清单绑定的 APK/payload。发版后用不带该参数的严格检查。

## 3. 完整 Linux 构建

准备 JDK 17、Python、curl/unzip、Android SDK command-line tools，并设置 SDK 路径。`ci_build.sh` 会请求 Android 28/34 平台和 build-tools 34.0.0，随后核对文件；SDK 安装失败不会被当成成功构建。

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash scripts/ci_build.sh /tmp/dsh-build
```

该目录是**专用临时构建目录**；ci_stage 会重新创建它，不要传项目仓库、用户工作区或需要保留的数据目录。

步骤如下：

1. 准备 Android 平台、aapt2、d8、apksigner。
2. 复制仓库源码、测试、脚本、图标资源和既有 keystore。
3. 从上一已发布 bootstrap APK 提取 Node 与十个必需动态库，不从 payload Release 取 APK。
4. 执行逻辑测试、架构/UI/无 emoji 闸门及资源检查。
5. Java 编译、DEX 转换、资源处理、二进制清单生成、APK 组装与签名。
6. 校验 minSdk、targetSdk、包名、产物和签名。

**输出：** `/tmp/dsh-build/bootstrap/DSHNative-bootstrap.apk`。

CI 工作区中源码在 `bootstrap/src/`，测试在工作区 `tests/`；仓库源码在 `src/`。不要用旧 `/root/build` 硬编码替代脚本的目录发现。

## 4. CI 分层

`.github/workflows/build.yml` 在 PR、main 与手动触发中复用 `ci_build.sh`，先检查元数据和文档，再构建，并消费源码实际引用的运行包。验证 APK 作为 Actions artifact 上传，不等于公开正式 Release。

`check_model_consumer.sh` 下载对应 DSH 片并校验摘要，生成真实原生配置供 Host 消费；执行模型目录、视觉、思考声明、LLM 调用配置、离线 SDK 请求体，以及聊天框真实 React 组件和会话 RPC 回归。`tests/js` 固定测试依赖不进入 APK。

同一路径还运行真实内核持久化、锁、FUSE 降级、磁盘错误、Web profile 与 token/Cookie 探测。所有模型请求只离线捕获，没有调用付费服务。

检查数量随版本变化，记录本次日志。Android 页面布局、真机 PTY、真实 provider max 行为与 ROM 后台策略另行验收，见 [STATUS](STATUS.md)。

## 5. 版本与签名

```bash
bash scripts/bump_version.sh 0.32.2
python3 scripts/sync_project_metadata.py --docs-only
```

版本脚本同时更新 `mkmanifest.py` 的 versionName/versionCode 与 MainActivity 启动日志。已发布清单保持原值，直到新资产通过正式发布验证；文档同时显示源码和 published stable。

签名必须使用既有 `scripts/release.keystore`，证书摘要见 [HANDOVER](HANDOVER.md#81-签名与覆盖安装)。缺失不能临时生成新的，否则已安装用户无法覆盖安装。

```bash
bash scripts/check_signer.sh /path/to/known-good.apk
```

签名核对需要 apksigner，可按脚本要求通过 `APKSIGNER_JAR` 指定。Linux 与 Android 工具环境要分别准备，不把桌面 binary 复制到手机执行。

## 6. 正式 App 发布

用户约定每轮修改完成后递增并发布 stable。先准备可审阅变更和 `release-notes/vX.Y.Z.md`，完成 PR Android CI 后合并，再手动运行既有“发版”工作流。

| 输入 | 含义 |
|---|---|
| `version` | 目标 X.Y.Z，须递增 |
| `channel` | `stable` 正式版或 `test` 预发布 |
| `notes_file` | 本版说明，默认从 release-notes 读取 |
| `dry_run` | 可选构建与权限演练，不上传、不改正式清单 |
| `signer_ref` | 可选已确认可覆盖安装的 bootstrap 标签；默认自动筛选 |

发布步骤：既有签名比对 → 版本同步 → 完整构建 → 真实 payload 消费验证 → 资产上传 → 资产与两条下载路径核验 → 更新相应清单 → 同步文档 → 回提交 main。

| 通道 | 标签 | 清单 |
|---|---|---|
| stable | `vX.Y.Z-bootstrap` | `latest.json`，正式发布并同步 README 下载信息 |
| test | `vX.Y.Z-test` | `latest-test.json`，Prerelease；README 继续指向 stable |

禁止手工上传替代此流程、预先手写 latest、提交 APK、换密钥或卸载用户旧版。可选择先发 test 的专项实验，但不能把历史“所有版本先 test”的规则覆盖用户当前 stable 交付约定。

## 7. payload 更新

内核来源和锁文件在 `runtime/`。构建示例：

```bash
python3 scripts/prepare_core_payload.py --base /path/to/verified-v9-runtime \
  --previous-manifest /path/to/verified-v9-manifest.json --out /path/to/payload-v10
python3 scripts/release_payload.py --help
```

输出与发布参数以脚本 help 为准。保留 Android PTY/Node 垫片，按实际展开占用生成 manifest，限定 remove 范围。工具片复用时必须保持字节、摘要与哨兵一致。

### 7.1 必须用 `npm ci` 的全新运行树，不要复用设备上那份

脚本会断言**每个** `@deepseek-ai/dsh-*` 包的版本都等于
`runtime/core-source.json` 固定的版本。这道断言是有用的，不要绕过。

踩过的坑：用 `--prepared-runtime` 复用「设备上已解压的运行包」来省掉下载，
结果构建直接失败：

```
AssertionError: …/dsh-client-ui-settings-unarchive-sessions/package.json
```

原因是**多次增量升级会在设备运行包里留下旧版残留**。实测一台从
0.26.x 一路升到 0.32.3 的设备，有 4 个包仍停在 `0.1.6-alpha.2`
（`dsh-agent-presets`、`dsh-client-ui-settings-unarchive-sessions`、
`dsh-experimental-agent-team-web-profile`、`dsh-settings-file`），
而仓库锁文件里 285 个包**全部**是 `0.2.0-rc.2`。

这些残留包本身不影响运行（日志里唯一的加载失败是 node-pty），但会让
`--prepared-runtime` 这条捷径失效。正确做法是在能联网的环境按锁文件
`npm ci` 出全新运行树 —— 这样产出的分片才是干净且版本一致的。

`--prepared-runtime` 只适用于**刚由本次 `npm ci` 生成、未经增量升级**的树。

### 7.2 必须在 linux-x64 上构建，不能在 arm64 机器上

**这是比 7.1 更硬的约束，实测踩过。**

`npm ci` 只会安装与**构建机平台匹配**的 optionalDependencies。锁文件里
`@deepseek-ai/node-addon-system-linux-x64` 与 `-linux-arm64` 都在，
但在一台 arm64 机器（手机）上跑 `npm ci`，x64 那份**根本不会被安装**。

后果是产出的分片里没有任何 x64 原生绑定，而 CI 的
`check_model_consumer.sh`（真实运行包消费验证）**跑在 x86_64 上**，
会直接失败：

```
Cannot find module '@deepseek-ai/node-addon-system-linux-x64/package.json'
```

反过来在 x64 上构建，arm64 变体同样缺失 —— 所以**剪枝规则必须与构建平台
配套**：只有在 x64 上构建、且剪枝保留 `linux-x64` 时，产出的分片才能同时
满足 App（android-arm64 槽位）与 CI 消费验证。

不要在手机上构建 payload。手机只适合消费与验证产物。

### 7.3 用 `build-payload.yml` 构建，别手动搭环境

因为 7.2 的平台约束，**本地构建 payload 几乎必然出错**：在 arm64 机器上
`npm ci` 装不到 x64 依赖，产物是不合格的。

仓库提供了 `.github/workflows/build-payload.yml`，把构建固定在 GitHub 的
x86_64 runner 上：

```bash
gh workflow run build-payload.yml -f base_tag=payload-v10
```

它会下载基底 payload、校验 SHA、解包出已验证的 Android 二进制作为 `--base`、
按锁文件 `npm ci`、打补丁、剪枝、打包，最后核对：

* 分片里有 `node-pty/prebuilds/android-arm64/pty.node`（App 需要）
* 分片里有 `node-addon-system-linux-x64/package.json`（CI 消费验证需要）

任一缺失即失败。产物作为 artifact 上传，**不发布、不写清单** —— 发布仍由
`scripts/release_payload.py` 单独执行。

工作流第一件事就是断言 `uname -m` 等于 `x86_64`：runner 类型将来若变化，
会立刻失败而不是静默产出废分片。

两次独立构建产出**完全相同的 manifest SHA**（实测 `d0806178…`）——
构建可复现，这是把 payload 构建搬进 CI 的主要收益之一。

### 7.4 发布也在 CI 上做，但默认关闭

`publish=true` 时才会发布，且必须显式给出 `new_tag`（校验格式为
`payload-v<N>`、且不能与基底相同）。权限按最小给：build job 只有
`contents: read`，只有 publish job 有 `contents: write`。

发布前从基底 Release 取回四个工具片，逐个核对与基底清单 SHA 一致，
不一致直接拒绝 —— 工具片必须字节复用。

写这个工作流时踩到两个**会在运行时才炸**的坑，都已修：

* publish 与 build 是两个 job、两个 runner，`$RUNNER_TEMP` **不共享**。
  原先直接引用 `needs.build.outputs.dir`，在 publish job 里指向一个不存在
  的目录。必须经 `upload-artifact` / `download-artifact` 传递。
* 手写的 action SHA 未必存在。`download-artifact` 我先填了一个看起来
  合理的 SHA，用 GitHub API 查证才发现根本不存在。现 workflow 里每个
  action 的 SHA 都经 API 验证过；**新增或修改 action 引用时务必复核**：

  ```bash
  gh api repos/actions/<action>/commits/<sha> --jq .sha
  ```

先校验本地所有分片与 SHA256SUMS，创建 draft，逐资产上传并核对 GitHub 摘要，manifest 最后上传，再公开并核验下载。payload 发布不修改 App latest 清单；App 必须等完整 payload 可用后引用它。

具体内容见 [CORE_UPGRADE](CORE_UPGRADE.md)。

## 8. 发布完成后

```bash
git fetch origin main
python3 scripts/sync_project_metadata.py --check
```

确认：Release 非草稿、通道正确、APK 存在、摘要/体积/标签与 manifest 一致、README 链接正确、所有状态块一致。下载可用与用户手机已覆盖安装是两种证据，分别报告。

`verify_release.sh` 是针对 bootstrap 正式标签的独立辅助检查，须在本地实际 APK 所在目录运行；不是替代 release.sh，也不是通用 test/payload 检查。

## 9. 失败定位

| 现象 | 先检查 |
|---|---|
| 缺 Node / so | ci_stage 选中的是否 bootstrap APK，十个库是否完整 |
| aapt2 加载错误 | Linux/bionic 是否混用，build-tools 与 lib64 路径 |
| 版本领先清单 | 是否是严格递增候选；检查允许源码领先参数，不能改旧清单冒充发布 |
| 文档检查失败 | 运行 docs-only，检查生成标记和本地链接 |
| 覆盖安装失败 | 包名与签名证书，不执行卸载绕过 |
| 目录保存但网页未更新 | 实际 profile、live 标记、pending 和空闲重载，而不只 WebView reload |
| Release 404 | 是否仍 draft、资产是否完整、直连/镜像是否可下载，禁止先写清单 |
