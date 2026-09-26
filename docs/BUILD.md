# 构建

## 为什么不用 Gradle

Gradle 需要完整的 Android SDK + JDK + 一套下载依赖的构建环境。
本项目的开发环境是**设备上的 proot Debian 容器**，装不下这些。

所以整条构建链是手写的：`scripts/build_bootstrap.sh`，
依赖只有 java/javac、python3、aapt2、d8.jar、apksigner.jar。

**代价**：没有 Android Studio 的调试能力，没有模拟器。
**收益**：整个项目在一个手机容器里就能构建和发布。

---

## 需要的部件

| 部件 | 位置 | 说明 |
|---|---|---|
| `android.jar` | `sdk/android.jar` | 框架资源表，aapt2 link 用 |
| `android-modern.jar` | `sdk/android-modern.jar` | 编译用（含 API21+ 属性，如 `statusBarColor`） |
| `aapt2` | Termux 环境 | **bionic 二进制**，见下方陷阱 |
| `d8.jar` | `tools/` | Java 字节码 → DEX |
| `apksigner.jar` | `tools/` | 签名 |
| `node` | Termux 环境 | 构建期辅助（运行运行包打包脚本） |

**关于两个 android.jar**：编译用 `android-modern.jar`（有完整的 API 定义），
aapt2 link 用 `android.jar`（有框架资源表）。两者职责不同，不能混用。

---

## 陷阱：aapt2 必须用 Termux 的 linker

`aapt2` 是 **bionic 二进制**（Android 的 libc），而容器是 glibc 的 Debian。
直接跑会报：

```
CANNOT LINK EXECUTABLE "aapt2": library "libc++_shared.so" not found
```

正确做法：

```bash
env LD_LIBRARY_PATH="$TERMUX_LIB" "$AAPT2" compile --dir "$RESDIR" -o out/res.zip
env LD_LIBRARY_PATH="$TERMUX_LIB" "$AAPT2" link ...
```

`d8.jar` / `apksigner.jar` 是普通 JVM 工具，直接用 `java -cp` 跑，不需要这一层。

---

## 构建步骤

`bash scripts/build_bootstrap.sh` 依次做：

```
0.   检查工具链
1.   收集 APK 内置负载（node + lib/*.so + 引导脚本）
2.   aapt2 compile/link 编译资源，导出资源 id
3.   mkmanifest.py 手写二进制 AndroidManifest.xml（注入资源 id）
3.4  run_tests.sh —— 580 项纯逻辑测试           ← 失败则中止
3.45 架构约束检查：纯逻辑层不得 import android.  ← 失败则中止
3.5  UI 规范检查：不得使用 AlertDialog.Builder   ← 失败则中止
4.   javac --release 8 编译
5.   d8 --min-api 24 → classes.dex
6.   mkzip.py 组装 APK
7.   apksigner 签名
```

产物：`bootstrap/out/DSHNative-bootstrap.apk`（以及同目录的 unsigned.apk）

### 为什么清单要手写

`aapt2 link` 需要资源文件，而本项目的图标是 **Canvas 画的**（零资源），
主题也只有最小定义。手写二进制 AXML 反而更可控，
且能精确注入 aapt2 生成的资源 id（通过环境变量传递，保证两侧一致）。

`mkmanifest.py` 就是那个手写 AXML 的生成器 —— 496 行，
里面有 AXML 的字符串池、属性编码等细节。

### 三个构建期闸门

这三个检查是**强制的**，违反直接失败。它们保护的约定见 `AGENTS.md`：

| 步骤 | 检查什么 | 为什么 |
|---|---|---|
| 3.4 | 580 项测试 | 没有真机调试回路，测试是唯一验证手段 |
| 3.45 | 纯逻辑层无 Android 依赖 | 否则测试跑不起来，「纯逻辑层」会慢慢失效 |
| 3.5 | 不用系统 AlertDialog | 保证 UI 风格统一（走 `DshUi`） |

---

## 版本号要同步改三处

```python
# 1. scripts/mkmanifest.py —— 清单里的 versionName / versionCode
(A, "versionName", s("0.23.3")), (A, "versionCode", integer(2008)),

# 2. MainActivity.java —— 日志头部显示的版本
w.write("APK 版本: 0.23.3\n");
```

第三处是运行包标签（`payload-v<N>`），App 里硬编码了 URL；
`release.sh` 会从源码推导并写入 `latest.json`，避免两者不一致。

---

## 重建运行包分片

改动了工具链内容（`tools/` 下的文件）时：

```bash
DSH_TOOLS_DIR=<工具链目录> \
DSH_PAYLOAD_OUT=<输出目录> \
python3 scripts/make_payload_parts.py
```

脚本会：

1. 按分类规则把工具链切成 5 个分片
2. 重新打包 `dsh.tar.zst`（排除 `DSH_EXCLUDE` 里列的内容）
3. 为每片计算哨兵文件的大小与 sha256
4. 写入 `revision`（`PART_REVISION`）与 `remove`（`DSH_REMOVE`）
5. 生成 `manifest.json`

**注意**：`dsh.tar.zst` 的重新打包是「解压到临时目录 → 用 `--exclude` 重新打包」，
不是流式过滤（tar 无法同时从 stdin 读、向 stdout 写并过滤条目）。
这需要约 210MB 的临时空间。

### 改了运行包内容之后

- **只增改文件**：哨兵会自然发现，不需要改 `revision`
- **删了文件**：必须递增该分片的 `revision`，并把路径加进 `remove`
  —— 否则老用户那边删不掉（哨兵发现不了删除）

---

## 发布

```bash
bash scripts/release.sh <版本号> <构建目录> <发布说明.md>
```

顺序（**不要改**）：

```
1. gh release create         上传 APK + 清单
2. 验证 release 资产存在
3. 轮询两条下载路径          直连 + 镜像，都是 206/200 才算通过
4. 最后才写 latest.json
```

出过一次事故：脚本语法错误跳过了 `gh release create`，但清单被写了
→ 所有客户端更新失败。所以顺序不能反。

### 运行包发布

```bash
gh release create payload-v<N> \
  --repo <owner>/<repo> \
  --title "..." --notes "..." \
  dsh.tar.zst tools-*.tar.zst
# 分片全部就绪后再单独上传 manifest.json
gh release upload payload-v<N> manifest.json
```

**先传分片、最后传清单** —— 否则客户端可能拿到清单却下不到分片。

---

## 完整重建（换机器 / 接手）

```bash
# 1. 克隆
git clone https://github.com/yusheng266186-beep/DSH-Native.git
cd DSH-Native

# 2. 跑测试，确认基线是绿的
bash scripts/run_tests.sh

# 3. 准备构建部件（不在仓库里的大文件）
#    - sdk/android.jar、sdk/android-modern.jar
#    - tools/d8.jar、tools/apksigner.jar
#    - Termux 环境的 aapt2、node
#    这些需要从 Android SDK / Termux 包里取

# 4. 构建
#    先确认 build_bootstrap.sh 里的路径指向你的环境
bash scripts/build_bootstrap.sh
```

**`.git` 是 2.5GB** —— 历史里有 88 个 33.5MB 的 APK 副本。
如果克隆太慢，可以用 `--depth 1` 只取最新一次提交。

---

## 签名

`scripts/release.keystore` 在仓库里（公开项目，本来就没有保密的必要）。
口令也在 `build_bootstrap.sh` 里。

**不要换签名** —— 换了之后用户无法覆盖安装，必须卸载重装（会丢配置）。
