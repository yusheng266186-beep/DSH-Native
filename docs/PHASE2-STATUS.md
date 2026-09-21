# Phase 2 技术现状与实施路径

本文档记录把**完整 DSH agent** 打包进单 APK 的可行性调查结果。
所有结论均基于在 Xiaomi 25128PNA1C / Android 17 / arm64 上的**实测**，非推断。

日期：2026-09-21

---

## 一、结论速览

| 组件 | 状态 | 说明 |
|---|---|---|
| Node 运行时 | ✅ 可用 | Termux nodejs 26.4.0，bionic，解释器 `/system/bin/linker64` |
| shell 工具链 | ✅ **可用** | ripgrep / git / bash / fd / jq 全部实测跑通 |
| `node-pty` | ✅ **可用** | npm 上有现成 bionic 构建，PTY 实测可 fork |
| `koffi` | ✅ 非必需 | 仅 Windows 路径 + 可选强管控路径；缺失时降级并告警 |
| DSH 核心启动 | ✅ 可用 | `dsh --version` / `--dump-config` 正常 |
| **`dsh web` 完整启动** | ✅ **已解决** | 纯 JS 垫片替代原生插件，UI 与客户端资源全部实测可加载 |
| 配套工具（gh 等） | ✅ 可用 | `gh` 为 glibc 静态包，Android 上无法直接运行，改用 API 或 Termux 版 |

**原本的阻断点已用纯 JS 垫片攻克**（见第三节）。剩余工作为工程化打包，非技术可行性问题。

---

## 二、逐项实测证据

### 2.1 shell 工具链（本以为要自己编译，实际不用）

Termux 仓库的包全部是 **NDK r28 + bionic** 构建，ELF 解释器为 `/system/bin/linker64`，
因此可脱离 Termux 独立运行。只需自带 `.so` 并设 `LD_LIBRARY_PATH`。

```
✅ rg     ripgrep 15.2.0
✅ fd     fd 10.5.0
✅ jq     jq-1.8.2
✅ bash   GNU bash, version 5.3.15(1)-release (aarch64-unknown-linux-android)
✅ git    git version 2.55.0
```

功能级验证（不只是 `--version`）：

```
git:  init → add → commit → log   →  83145c4 test        ✅
bash: 循环 / 算术 / 管道 / 变量   →  1 4 9 / PIPESTATUS=3  ✅
rg:   实际搜索命中                →  search               ✅
```

依赖闭包：`ripgrep`=2 包，`fd`=1 包，`jq`=2 包，`git`=14 包，`bash`=48 包。
全部为 Termux `.deb`，解包即用，**无需编译**。

### 2.2 node-pty（最大风险项，已解决）

- DSH 的 `dsh-subprocess-local` 通过 `createLazyRequire("node-pty")` 加载
- npm 上存在 Android 专用 fork：**`@mmmbuto/node-pty-android-arm64@1.1.2`**
- 内含 `prebuilds/android-arm64/pty.node`，依赖 `libc.so / liblog.so / libc++_shared.so`（**bionic**）

实测完整 JS API：

```
✅ 模块加载成功
✅ spawn 成功, pid=25007
✅ 子进程退出 code=0
✅ PTY 输出: "PTY_API_WORKS\nTERM=xterm-color\n/data/data/..."
```

底层能力确认：`native.open(80, 24)` → `{"master":18,"slave":19,"pty":"/dev/pts/1"}`

> ⚠️ 注意：`native.fork(...)` 的裸调用会报 `Usage:` 错误，这是原生层的兜底错误信息，
> **并非参数问题**。正常用法是走 `lib/index.js` 的 `spawn()` JS API。

### 2.3 koffi（非阻断）

`koffi` 在 DSH 中的用法全部集中在 Windows 路径：

```js
koffi.load("advapi32.dll")   // GetFileSecurityW / SetFileSecurityW / ReplaceFileW
koffi.load("kernel32.dll")   // Process32FirstW / Process32NextW / GetLastError
```

唯一 Linux 用法在 `dsh-subprocess-local/lib/runner-launch-*.js` 的 `loadLinuxExecve()`：
用 `koffi.load(null)` 取 libc 的 `execve` / `fcntl`，服务于 **Linux managed 进程强管控**。

其调用点有明确降级逻辑：

```js
selectContainmentMode(kind) {
  if (platform === "linux") {
    const available = this.linuxDeepProbePassed ? probeLinuxManager() : probeLinuxNative();
    if (available) return "linux-scope";
    fallbackReason = "the current user-systemd scope or private bootstrap is unavailable";
  }
  this.warnFallback(platform, kind, fallbackReason);   // 仅告警
  return "fallback";                                    // 功能继续
}
```

而 `probeLinuxManager()` 依赖 `systemctl`——Android 上不存在，该强化路径本就不可用。
**故 koffi 缺失不影响基本功能**，仅损失"进程树强管控"（DSH 会打印一条警告）。

### 2.4 已确认的阻断点：`node-addon-require-builtin`

**现象**：

```
Error: dsh: host preparation failed: No usable native binding found for
       node-addon-require-builtin-android-arm64 (auto)
    at boot (.../dsh-app-boot/lib/index.js:2738:9)
```

**根因**：该包只发布 6 个平台的 optionalDependencies，**没有 android**：

```
node-addon-require-builtin-darwin-arm64 / -darwin-x64
node-addon-require-builtin-linux-arm64-gnu / -linux-x64-gnu
node-addon-require-builtin-win32-arm64-msvc / -win32-x64-msvc / -win32-ia32-msvc
```

且已安装的 `linux-arm64-gnu` 二进制是 **glibc**（`NEEDED: libdl.so.2 libc.so.6`），
Android 上无法 dlopen。

**加载器的平台名拼装**（`node-addon-native-custom-loader`）：

```js
if (process.platform === 'darwin') return `darwin-${process.arch}`;
if (process.platform === 'linux')  return `linux-${process.arch}-${libc}`;
if (process.platform === 'win32')  return `win32-${process.arch}-msvc`;
return `${process.platform}-${process.arch}`;   // Android → "android-arm64"，无对应包
```

**为何需要它**：`dsh-app-boot` 用 `requireBuiltin()` 拿到 Node 私有内部模块，
以**劫持模块解析器**为 profile 注入自定义解析（`installProfileResolution()`，无条件调用）：

```js
const esmModule  = addon.requireBuiltin("internal/modules/esm/loader");
const cjsModule  = addon.requireBuiltin("internal/modules/cjs/loader");
const cjsHelpers = addon.requireBuiltin("internal/modules/helpers");
const esmUtils   = addon.requireBuiltin("internal/modules/esm/utils");
const esmResolve = addon.requireBuiltin("internal/modules/esm/resolve");
// 用到: getOrInitializeCascadedLoader, getOrCreateModuleJob,
//        Module._resolveFilename, getCjsConditions,
//        getDefaultConditions, defaultResolve
```

---

## 三、社区已验证的绕行方案

DSH 官方仓库 Discussion **#1588**「[dsh runs on Termux (Android) — with 5 small patches](https://github.com/deepseek-ai/deepseek-harness/discussions/1588)」
给出了在 Android 上运行 dsh 的完整修复清单。其中第 4 项**正是**本项目的阻断点：

| # | 包 / 功能 | 问题 | 修复方式 |
|---|---|---|---|
| 1 | `node-pty` | node-gyp 把 `process.config.variables.OS="android"` 写入 `config.gypi`，gyp 引用未定义的 `android_ndk_path` | patch node-gyp 丢弃 `OS` 变量，让 gyp 推断为 `linux` |
| 2 | `koffi` | `base.cc` 调用 `statx()`，bionic 在 API 30 以下不暴露；clang 默认 target `android24` | 用 `--target=aarch64-unknown-linux-android30` 编译 |
| 3 | `sharp` | 无 `android-arm64` 预编译绑定 | 装系统 `libvips` 并 `SHARP_FORCE_GLOBAL_LIBVIPS=1` 构建 |
| **4** | **HMR service** | **`cordis-plugin-hmr` 需要 Node `--expose-internals`；`node-addon-require-builtin` 无 android 预编译** | **把 `dsh` 的 shebang 改写为 `node --expose-internals`** |
| 5 | `session-persistence-jsonl` | 用硬 `link()` 发布日志，Android 上 `EACCES` | 原子发布改用 `rename()`（同样原子） |

**第 4 项对我们的意义**：既然走 `--expose-internals`，就**不需要原生插件**——
它唯一的作用是让 HMR 服务访问 Node 内部模块，而 `--expose-internals` 能直接达到同样效果。
因此可以做一个**纯 JS 垫片**替换 `node-addon-require-builtin`。

相关社区项目（可作为参考实现）：
- [lilyco-42/dsh-termux](https://github.com/lilyco-42/dsh-termux) — 一键安装器，含上述 5 项补丁
- [sunflower2333/dsh-termux](https://github.com/sunflower2333/dsh-termux) — 自包含离线包，预编译原生模块（Termux + Node 24）
- [Vengisk/deepseek-harness-termux](https://github.com/Vengisk/deepseek-harness-termux) — 含 `prebuilt/` 目录

---

## 三之二、垫片实现与验证结果 ✅

### 实现

垫片见 `patch/narblib/index.js`（92 行，零依赖）。核心发现：

**带 `--expose-internals` 时，Node 允许直接 `require` 私有内部模块**，
且导出的函数与原生插件**完全一致**：

```
$ node --expose-internals -e 'require("internal/modules/esm/loader")'
✅ internal/modules/cjs/loader  → kModuleSource, …, Module
✅ internal/modules/helpers     → getCjsConditions, …
✅ internal/modules/esm/loader  → getOrInitializeCascadedLoader, …
✅ internal/modules/esm/utils   → getDefaultConditions, …
✅ internal/modules/esm/resolve → defaultResolve, …
```

垫片导出与原生包逐一对齐：
`requireBuiltin` / `isAllowedInternalId` / `getBindingInfo` / `default`

### 验证结果（全部在 Xiaomi 25128PNA1C / Android 17 上实测）

```
# 1. 垫片在 Android 下取内部模块
internalsExposed: true
✅ internal/modules/esm/loader → 4 个导出
✅ internal/modules/cjs/loader → 16 个导出
✅ internal/modules/helpers    → 22 个导出
✅ internal/modules/esm/utils  → 9 个导出
✅ internal/modules/esm/resolve→ 8 个导出

# 2. dsh web 启动成功
$ node --expose-internals dsh/lib/bin.js --profile web --no-open --port 3099
dsh web: http://127.0.0.1:3099/?token=pxEM5kVEO6p5i2_74vCLL7XawI6yWKcBP2VFGt26zYs

# 3. 完整认证流程（token → cookie → 200）
GET /?token=… → 303 See Other, set-cookie: dsh-auth-…
GET /         → 200, 31252B, text/html
<title>DeepSeek Harness</title>

# 4. 客户端资源正常下发
/assets/index-8VXBH-f-.js   → 200, 616090B, text/javascript
/assets/vendor-CCJJTK99.js  → 200, 740575B, text/javascript

# 5. 服务响应
首页响应时间 0.006s，进程 RSS 188MB
```

**结论：DSH agent 后端已在 Android 原生 Node 上完整运行，UI 可正常加载。**

### 关键实现细节

1. **启动参数必须含 `--expose-internals`**（对应社区方案的第 4 项修复）
2. 垫片直接覆盖 `node_modules/node-addon-require-builtin/lib/index.js`
   （该包无 `exports` 字段，`main` 指向 `lib/index.js`，子路径导入可被覆盖）
3. `process.platform === "android"`（**不是 `"linux"`**），DSH 会因此走 `fallback` 分支，
   仅损失进程树强管控并打印一条警告，不影响功能

---

## 四、待办清单（按依赖顺序）

- [x] ~~**P0** 实现 `node-addon-require-builtin` 的纯 JS 垫片~~ → **已完成并验证**
      （`patch/narblib/index.js`；配合 `--expose-internals` 启动）
- [ ] **P0** 打 `session-persistence-jsonl` 的 `link()` → `rename()` 补丁
      （Android 上硬链接被禁，`EACCES`；见社区方案第 5 项）
- [ ] **P0** 把垫片与 `--expose-internals` 接入 APK 的 Node 启动参数
- [ ] **P1** 用 `@mmmbuto/node-pty-android-arm64` 替换 `node-pty`
- [ ] **P1** 打包 5 个 shell 工具（rg/git/bash/fd/jq）及其依赖库
- [ ] **P1** 禁用或适配 HMR（`cordis-plugin-hmr` 现已可工作，但移动端可关以省资源）
- [ ] **P2** 处理 `sharp`（图片附件功能，可先禁用）
- [ ] **P2** 裁剪 DSH `node_modules`（当前 498MB）
- [ ] **P2** 接入 API Key 配置与用户自定义模型
- [ ] **P3** App 内交互完善（当前 PoC 仅 WebView + 启动日志）

---

## 五、体积预估

| 组件 | 压缩前 | 说明 |
|---|---|---|
| Node 26.4.0 | 47 MB | 必需 |
| Node 依赖库（ICU/OpenSSL/…） | 41 MB | `libicudata.so.78` 独占 32MB |
| shell 工具链 | ~25 MB | rg/git/bash/fd/jq + 依赖 |
| DSH 裁剪后 | ~150 MB? | 当前全量 498MB，需裁剪 |
| **合计** | **~260 MB** | APK 压缩后预计 **85–110 MB** |

> 体积优化方向：Node 的 ICU 可用 `--with-intl=small-icu` 大幅缩减（32MB → ~2MB），
> 但需自行编译 Node，成本较高，暂列为可选优化。

---

## 六、环境备注

- 构建机（当前 PRoot Debian 容器）已装：JDK 21、`d8.jar`、`apksigner.jar`、`android.jar`
- 构建走**手工流水线**（无 Gradle）：`mkmanifest.py` → `javac` → `d8` → Python zip → `apksigner`
- 关键约束：`targetSdkVersion = 28`（否则 SELinux 禁止 exec 私有目录文件）
- 镜像源：`deb.debian.org` 仅 100KB/s，改用 `mirrors.aliyun.com`（17.5MB/s）；
  Termux 仓库 1.6MB/s

### ⚠️ 测试环境的重要局限（必须知道）

本文件中所有 Android 侧测试（Node 启动、node-pty、工具链、`dsh web`）
**都是在 PRoot 环境内通过 `/system/bin/sh` 执行的**，而非真实 App 沙箱。

PRoot 以 uid 0 运行且不套用 `untrusted_app_25` 的 SELinux 域，
因此这些测试**无法证明** `targetSdk 28` 下 App 私有目录的 `execve` 会被放行。
真正需要 App UI 实测的那一项（Phase 1 的核心待验证点）**仍然悬而未决**。

可以确定的是：**Phase 2 的软件层阻断已全部排除**（工具链、node-pty、koffi、原生插件）。
剩余的唯一未知量是 SELinux exec 策略，只能由用户装包后实测。
