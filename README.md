# DSH Native

**把 Node.js 运行时直接打包进一个 Android APK —— 不依赖 Termux，不使用 proot。**

这是一个可行性验证版（PoC）。装上后 App 会在自己的私有目录里启动一个**自带的 Node.js 26.4.0**，
并起一个本地 HTTP 服务，用 WebView 展示运行时自检结果。

---

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

## 当前状态：PoC 已跑通 ✅

已在 **Xiaomi 25128PNA1C / Android 17 / arm64** 上完成以下验证（全部实测，非推断）：

| 验证项 | 结果 |
|---|---|
| Node 二进制可移植性 | 解释器为 `/system/bin/linker64`（Android 原生，非 Termux 私有路径） |
| 运行 Node | `v26.4.0 (arm64)` ✅ |
| 自带库加载 | `LD_LIBRARY_PATH` 指向 App 内 `lib/`，10 个库全部生效 |
| HTTP 服务 | `200 {"ok":true,"pid":…,"version":"v26.4.0"}` ✅ |
| 文件读写 | 正常 ✅ |
| 加密模块 | SHA256 正常 ✅ |
| HTTPS 模块 | 可用（调用模型 API 的前提）✅ |
| 子进程执行 | `subprocess_ok` ✅ |
| ESM 支持 | `require(esm)` 可用（DSH 是 ESM 包）✅ |
| 计算性能 | 3×10⁷ 次循环约 85–111ms（JIT 正常，未被 Android 禁用） |
| ELF 页对齐 | **`p_align=0x4000`（16KB）** —— 设备当前是 4KB 内核，但已为 16KB 内核做好准备 |

### 两个关键技术决策

**1. `targetSdkVersion = 28`（核心）**

Android 10 起，`targetSdk ≥ 29` 的 App **不允许对自身私有目录里的文件调用 `execve()`**
（AOSP `app_neverallows.te`：`neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 … } app_data_file:file execute_no_trans`）。

也就是说：不降 targetSdk，就没法执行自带的可执行文件。本 PoC 选 **28** 是为了保留这条路径。
（另有更"正统"的 `nativeLibraryDir` + `lib*.so` 方案，见下方「后续计划」。）

**2. Node 二进制取自 Termux 仓库**

Node.js 官方**不提供 Android 构建**（`BUILDING.md` 明确写 "Android is not a supported platform"）。
Termux 的 `nodejs` 包恰好是用 NDK r28 编译的 bionic 版本，解释器指向 `/system/bin/linker64`，
因此可以脱离 Termux 环境独立运行——这是整个方案能成立的基础。

---

## 安装

1. 下载本仓库的 `DSHNative-poc.apk`
2. 手机上允许「安装未知来源应用」，安装
3. 打开 App，等待约 2–4 秒（首次启动需解压 93MB 资源）

**首次启动会解压约 93MB 到 App 私有目录**，所以第一次打开会有几秒黑屏，属正常现象。
界面上方会滚动显示启动日志（解压进度、进程 pid、加载 URL），下方是 WebView 展示的自检结果。

> ⚠️ 这是**自签名**的测试包，不是应用商店版本。签名指纹：
> `SHA-256: 95e4da30b41be27c0e094bd5e09a35a4e32802df31f1b13a01b5f722a8b34e22`

---

## 仓库结构

```
.
├── DSHNative-poc.apk                    # 可直接安装的成品（34MB）
├── src/dev/dsh/nativeapp/
│   └── MainActivity.java                # 唯一一个 Java 类：解压资源 → 起 Node → 装载 WebView
├── payload/
│   └── srv.js                           # 随 APK 分发的 Node 服务（零依赖自检页）
└── scripts/
    ├── build.sh                         # 一键构建脚本
    ├── mkmanifest.py                    # 纯 Python 生成二进制 AndroidManifest.xml
    ├── mkzip.py                         # 纯 Python 打包（带压缩策略）
    ├── AndroidManifest.xml              # 已生成的二进制清单
    └── release.keystore                 # 签名密钥（口令均为 dshnative）
```

### 为什么需要 `mkmanifest.py`

常规 Android 构建靠 `aapt2` 把 XML 清单编译成二进制格式。本项目的构建环境（设备本地）
`aapt2` 无法运行，因此改为**用纯 Python 直接生成二进制 AXML**。

该脚本经过 6 层独立验证：字节级结构审计、14 项负向对照测试、与 35 个真实 APK 清单的
解析回归、以及用**真实 aapt2 `dump badging` 交叉复核**（输出见下）。

---

## 构建

前置：JDK 21、`android.jar`、`d8.jar`、`apksigner.jar`、Termux 仓库的 `nodejs`/各 `lib*.deb`。

```bash
bash scripts/build.sh
```

构建流水线：

```
手写 AndroidManifest.xml           (mkmanifest.py)
        ↓
javac --release 8                  → class
        ↓
d8 --min-api 24                    → classes.dex
        ↓
收集 node + 10 个共享库 + srv.js   → payload (93MB)
        ↓
Python zip 打包（全部 DEFLATE）    → unsigned.apk (33MB)
        ↓
apksigner sign                     → DSHNative-poc.apk (34MB)
```

验证结果：

```
$ aapt2 dump badging DSHNative-poc.apk
package: name='dev.dsh.native' versionCode='1' versionName='0.1.0-poc'
minSdkVersion:'24'
targetSdkVersion:'28'
uses-permission: name='android.permission.INTERNET'
application: label='DSH Native' icon=''
launchable-activity: name='dev.dsh.native.MainActivity'

$ apksigner verify --print-certs DSHNative-poc.apk
V3.0 Signer: certificate DN: CN=DSH Native, OU=POC, O=DSH, L=NA, ST=NA, C=CN
```

---

## 局限（如实说明）

1. **尚未在真实 App 沙箱中验证 exec**
   PoC 的自检是在容器内以相同方式（`LD_LIBRARY_PATH` + 私有目录）跑的。
   `run-as`/容器环境使用 SELinux 的 `runas_app` 域，**该域豁免 exec 限制**——
   这是已知的测试陷阱。必须在 UI 里点开 App 才能确认。**这就是需要你验证的核心一项。**

2. **只捆绑了 Node，没有捆绑工具链**
   shell 工具（bash）、`git`、`ripgrep` 尚未打包。它们都是 glibc 链接的，
   在 Android（bionic）上不能直接用，必须用 NDK 重新编译。

3. **运行时无法更新工具**
   冻结式分发：想升级 Node 或工具，只能重新打包 APK。

4. **APK 34MB，首次启动解压 93MB**
   主要体积来自 `libicudata.so.78`（32MB，ICU 时区/编码数据）与 `node`（47MB）。

5. **`targetSdk 28` 是过渡方案**
   Android 已在收紧对低 targetSdk 的支持。长期应迁移到 `nativeLibraryDir` 方案。

---

## 后续计划

- [ ] **你在手机上验证 exec 是否被 SELinux 拦截**（决定架构走向的关键一步）
- [ ] 若被拦截 → 迁移到 `nativeLibraryDir` + `lib*.so` 方案（Android 官方认可路径）
- [ ] 用 NDK 编译 `git` / `ripgrep` / `bash`，扁平化后放入 `jniLibs`
- [ ] 打包完整 DSH（当前 `node_modules` 约 500MB，需裁剪）
- [ ] 处理 `node-pty`（DSH 唯一的强原生依赖，无 Android 预编译包）
- [ ] 补 `srv.js` 之外的真实 agent 前端

---

## 参考

- Termux（Node 二进制来源、`nativeLibraryDir` 方案先例）：https://github.com/termux/termux-app
- `oonid/pr`（在 Android 16 上验证 `lib*.so` exec 路径）：https://github.com/oonid/pr
- Android 10 行为变更（`execve()` 限制）：https://developer.android.com/about/versions/10/behavior-changes-10
- Node.js 不支持 Android：https://github.com/nodejs/node/blob/main/BUILDING.md

## 许可

仅供学习与个人验证使用。内置的 Node.js 运行时来自 Termux 发行版，遵循其原有许可（Node.js MIT + 各依赖库许可）。
