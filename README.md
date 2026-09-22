# DeepSeek Harness (Android)

> ## ⬇️ 直接下载
>
> **[DSHNative-bootstrap.apk](https://github.com/yusheng266186-beep/DSH-Native/releases/download/v0.9.0-bootstrap/DSHNative-bootstrap.apk)**（34 MB）
>
> 安装后打开，保持联网。首启会先自检（3 秒内确认架构是否成立），
> 然后经 **GitHub 镜像**分块下载约 48MB 运行包（约 50 秒）。
> 完成后在 Models 页面填 API Key 即可使用。
>
> **上传文件**：点输入框左下角 **➕** → **「文件 file」**（DSH 原生入口）。
>
> **从其他 App 分享**：在任意应用里选「分享」→「DeepSeek Harness」，
> 文件或文本会直接落到工作区，agent 立刻可用。
>
> 首次启动会请求**存储权限**，请点「允许」——
> 日志会写入 `/sdcard/DSHNative/launch.log`，便于排查问题（可随时删除）。
>
> SHA-256：`2b6ef2dfc7d43e14487870f7e64caee7436addeaf0f429e2a64a174d16c6490d`


**把 Node.js 运行时 + DeepSeek Harness agent 直接打包进一个 Android APK —— 不依赖 Termux，不使用 proot。**

---

## 📦 两个版本

| 版本 | 说明 | 体积 |
|---|---|---|
| **[v0.9.0 引导式（推荐）](https://github.com/yusheng266186-beep/DSH-Native/releases/tag/v0.9.0-bootstrap)** | **完整 DSH agent**。APK 内置 Node，首启分块下载运行包（带重试与 SHA 校验） | APK 34MB + 首启 48MB |
| [v0.1.0 PoC](https://github.com/yusheng266186-beep/DSH-Native/releases/tag/v0.1.0-poc) | 仅运行时自检（验证可行性用） | 34MB |

### v0.9.0 使用步骤

1. 安装 APK（34MB）
2. 打开 App，**保持联网** —— 首启分块下载约 48MB 运行包
   （面板显示百分比 / 速率 / 重试次数；网络抖动会自动重试）

   启动前会先做一次**自检**（执行 `node --version`），
   3 秒内即可确认架构是否成立，无需等下载完才知道。
3. 等待解压与启动（约 1–2 分钟）
4. 界面加载后，在 **Models 页面填入 API Key** 即可开始使用

> **v0.2.1 修复了 v0.2.0 的一个真实缺陷**：原下载实现是单次流式下载，没有分块与重试。
> 实测发现本机网络下 34MB 文件连续多次失败（`ETIMEDOUT` / `timeout`），
> 且不校验完整性——移动网络中断会产生静默损坏的归档。
> 现改为 2MB 分块 + 块级重试 + SHA-256 校验，策略已通过故障注入测试验证。

> 引导式架构的原因：完整运行包 330MB，压缩后 48MB，但直接塞进 APK 会超过
> GitHub 的 100MB 单文件上限，且构建迭代极慢。拆成「轻量 APK + 独立运行包」后，
> APK 可快速迭代，且以后升级 agent 无需重装 App。

---

## 🔄 应用内更新

**通知栏 →「设置」→ 底部「更新」区**：

| 按钮 | 作用 |
|---|---|
| 更新运行包 | 检查 DSH / 工具链更新（走分片清单，只下变化部分），完成后自动重启 agent |
| 检查 App 更新并安装 | 读取 `latest.json` → 下载新 APK → 调起系统安装器覆盖安装 |

> ⚠️ 发版时**必须同步更新仓库根目录的 `latest.json`**，否则 App 检测不到新版本。
> 详见 [scripts/release_checklist.md](scripts/release_checklist.md)。

## ⚡ 增量更新

运行包按功能切分为五个分片（DSH / 基础工具 / 共享库 / Python / npm），
启动时用**哨兵文件**校验各分片，**只下载变化的部分**：

| 改动 | 下载量 |
|---|---|
| 加一个 npm 包 | 2.2 MB |
| 加一个 bash 工具 | 13.6 MB |
| Python 升版本 | 4.9 MB |
| DSH 升版本 | 33.6 MB |

且哨兵机制不需要状态文件，本地文件损坏时会自动修复对应分片。

## 🗂️ 工作区（重要）

agent 的工作目录是 **`/sdcard/DSHNative/workspace`** —— 位于手机共享存储，
任何文件管理器都能访问。**把项目或文档放进去，agent 就能直接读写，
它生成的产物也会出现在这里。**

## 它要解决什么问题

在手机上跑 Node/CLI 类工具，通行做法是 **Termux + proot-distro**：Termux 提供 Linux 用户空间，
proot 用 `ptrace` 逐系统调用翻译路径和 UID，在中间套一个 Debian 容器。

这套方案能用，但代价是：

| 代价 | 说明 |
|---|---|
| 三层嵌套 | Android → Termux → proot → Debian，任一环出问题都难排查 |
| 进程创建变慢 | 实测 **+55%**（每次 spawn 多约 1.4ms） |
| 两个 App | Termux 本体 + 你的前端 App，靠 `am start` 互相拉起 |
| 配置脆弱 | Termux 认 uid 0 就拒绝运行包管理器，`/proc` 被虚拟化导致读数不可信 |

**DSH Native 的思路**：既然目标是"跑一个 Node 程序"，那就只打包 Node 本身，
把它当作 App 的资源随 APK 分发，启动时解压到私有目录直接执行。没有中间层。

---

## 实测验证结果

全部在 **Xiaomi 25128PNA1C / Android 17 (SDK 37) / arm64** 上完成，均为实测非推断。

### 运行时基础能力

| 验证项 | 结果 |
|---|---|
| Node 二进制可移植性 | 解释器为 `/system/bin/linker64`（Android 原生，非 Termux 私有路径） |
| Node 版本 | `v26.4.0 (arm64)`，`libc = bionic` |
| 自带库加载 | `LD_LIBRARY_PATH` 指向 App 内 `lib/`，10 个库全部生效 |
| HTTP 服务 | `200 {"ok":true,"version":"v26.4.0"}` |
| 加密 / HTTPS | 正常（调用模型 API 的前提） |
| 子进程执行 | ✅ |
| ESM 支持 | `require(esm)` 可用（DSH 是 ESM 包） |
| 计算性能 | 3×10⁷ 循环 85–111ms（JIT 正常） |
| ELF 页对齐 | **`p_align=0x4000`（16KB）**——设备当前 4KB 内核，但已为 16KB 做好准备 |
| **私有目录 exec 权限** | ✅ **真机确认**（`targetSdk 28` 确实绕过 Android 10+ 的 `execve` 限制） |
| **端到端启动** | ✅ **真机确认**（Node 自检 → 下载 → 解压 → 自检 11 项 → dsh web 启动 → 界面加载） |

### agent 端到端

```
$ dsh --profile headless-test 'Run: rg --version && git --version...'

dsh: reasoning:
Both commands succeeded (exit code 0). The exact first line of each:
- `rg --version` → `ripgrep 15.2.0`
- `git --version` → `git version 2.55.0`
退出码: 0
```

**LLM 推理 + 工具链调用全部工作**，agent 还能正确解析输出、理解管道退出码语义。

### Web 界面

```
dsh web: http://127.0.0.1:3099/?token=…
GET /?token=…  → 303 + set-cookie
GET /          → 200, 31252B, <title>DeepSeek Harness</title>
/assets/index-8VXBH-f-.js   → 200, 616090B
/assets/vendor-CCJJTK99.js  → 200, 740575B
```

### 内置工具链（Termux bionic 构建，全部实测可用）

| 工具 | 版本 | 功能级验证 |
|---|---|---|
| ripgrep | 15.2.0 | ✅ 实际搜索命中 |
| git | 2.55.0 | ✅ init → add → commit → log |
| bash | 5.3.15 | ✅ 循环 / 算术 / 管道 / `PIPESTATUS` |
| fd | 10.5.0 | ✅ |
| jq | 1.8.2 | ✅ |

---

## 三个关键 Android 补丁

DSH 直接跑在 Android 上会撞到三个不兼容点，均已修复（见 `patch/`）：

| # | 问题 | 修复 |
|---|---|---|
| 1 | `node-addon-require-builtin` **无 android 构建**，且 Android 无 glibc | 纯 JS 垫片替代 —— `--expose-internals` 下可直接 require Node 私有内部模块 |
| 2 | 会话日志用硬 `link()` 发布，**bionic 拒绝并返回 EACCES** | 改为 `lstat` + `rename()`（同目录下同样原子） |
| 3 | `flock` 平台白名单拒绝 android，且无原生绑定 | 放开 android + no-op 降级（单用户场景不需要跨进程锁） |

另外 `node-pty` 用 npm 上的 `@mmmbuto/node-pty-android-arm64`（bionic 构建）替换；
`koffi` 经排查**非必需**（仅 Windows 路径 + 可选强管控，缺失时自动降级并告警）。

---

## 两个关键技术决策

**1. `targetSdkVersion = 28`（核心）**

Android 10 起，`targetSdk ≥ 29` 的 App **不允许对自身私有目录里的文件调用 `execve()`**
（AOSP `app_neverallows.te`：`neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 … } app_data_file:file execute_no_trans`）。

不降 targetSdk，就没法执行自带的可执行文件。本方案选 **28** 是为了保留这条路径。

**2. Node 二进制取自 Termux 仓库**

Node.js 官方**不提供 Android 构建**（`BUILDING.md` 明确写 "Android is not a supported platform"）。
Termux 的 `nodejs` 包是用 NDK r28 编译的 bionic 版本，解释器指向 `/system/bin/linker64`，
因此可脱离 Termux 独立运行 —— 这是整个方案能成立的基础。

---

## 仓库结构

```
.
├── DSHNative-bootstrap.apk              # v0.2.0 引导式 APK（34MB）
├── DSHNative-poc.apk                    # v0.1.0 自检 PoC（34MB）
├── src/dev/dsh/nativeapp/
│   └── MainActivity.java                # 引导逻辑：解压 → 下载 → 解包 → 启动 → WebView
├── payload/
│   ├── srv.js                           # PoC 的自检服务
│   └── unpack.js                        # tar.zst 解压器（纯 Node，零依赖）
├── patch/                               # 三个 Android 兼容补丁（含说明与 diff）
├── scripts/
│   ├── build.sh / build_bootstrap.sh    # 一键构建
│   ├── mkmanifest.py                    # 纯 Python 生成二进制 AndroidManifest.xml
│   ├── mkzip.py                         # 纯 Python 打包 APK
│   └── AndroidManifest.xml
└── docs/
    └── PHASE2-STATUS.md                 # 完整技术记录（实测证据 / 阻断分析 / 待办）
```

### 为什么需要 `mkmanifest.py`

常规 Android 构建靠 `aapt2` 把 XML 清单编译成二进制格式。本项目的构建环境（设备本地）
`aapt2` 无法运行，因此改为**用纯 Python 直接生成二进制 AXML**。

该脚本经过 6 层独立验证：字节级结构审计、14 项负向对照测试、与 35 个真实 APK 清单的
解析回归、以及用**真实 aapt2 `dump badging` 交叉复核**。

---

## 构建

前置：JDK 21、`android.jar`、`d8.jar`、`apksigner.jar`、Termux 仓库的 `nodejs`/各 `lib*.deb`。

```bash
bash scripts/build_bootstrap.sh      # 引导式 APK
bash scripts/build.sh                # PoC APK
```

流水线：

```
手写 AndroidManifest.xml           (mkmanifest.py)
        ↓
javac --release 8                  → class
        ↓
d8 --min-api 24                    → classes.dex
        ↓
内置 Node + 10 个共享库 + 引导脚本  → assets/payload (93MB)
        ↓
Python zip 打包（全部 DEFLATE）    → unsigned.apk (33MB)
        ↓
apksigner sign                     → DSHNative-bootstrap.apk (34MB)
```

---

## 局限（如实说明）

1. **`targetSdk 28` 是过渡方案**
   Android 已在收紧对低 targetSdk 的支持。长期应迁移到 `nativeLibraryDir` + `lib*.so` 方案。

2. **首次启动需联网**
   引导式架构的代价：APK 本体不含运行包。

3. **Office→PDF 转换不可用**
   已裁剪 `libreoffice-kit-wasm`（186MB 的 WASM 引擎），这是体积从 498MB 降到 312MB 的主因。

4. **图片附件功能不可用**
   `sharp` 无 android 预编译绑定。

5. **运行时工具无法在线更新**
   冻结式分发：升级工具只能重新打包运行包。

---

## 后续计划

- [ ] 你在手机上验证 `targetSdk 28` 下 exec 是否被 SELinux 放行（决定架构走向）
- [ ] 若被拦截 → 迁移到 `nativeLibraryDir` + `lib*.so` 方案
- [ ] 进一步裁剪 DSH（`@opentelemetry` 36MB、其他 provider SDK 45MB）
- [ ] App 内 API Key 配置界面
- [ ] 用 ICU small-icu 重编 Node（32MB → ~2MB）

---

## 参考

- Termux（Node 二进制来源、`nativeLibraryDir` 方案先例）：https://github.com/termux/termux-app
- DSH 官方 Discussion #1588「dsh runs on Termux (Android) — with 5 small patches」：https://github.com/deepseek-ai/deepseek-harness/discussions/1588
- `oonid/pr`（在 Android 16 上验证 `lib*.so` exec 路径）：https://github.com/oonid/pr
- Android 10 行为变更（`execve()` 限制）：https://developer.android.com/about/versions/10/behavior-changes-10
- Node.js 不支持 Android：https://github.com/nodejs/node/blob/main/BUILDING.md

## 许可

MIT（见 [LICENSE](LICENSE)）。内置的 Node.js 运行时来自 Termux 发行版，
遵循其原有许可（Node.js MIT + 各依赖库许可）。
