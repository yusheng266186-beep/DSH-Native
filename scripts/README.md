# 脚本说明

## 哪些能在克隆里直接跑

| 脚本 | 克隆里能跑吗 | 说明 |
|---|---|---|
| `run_tests.sh` | **能** | 423 项纯逻辑断言，只依赖 java/javac。接手第一步就跑这个 |
| `build_bootstrap.sh` | 需要构建工作区 | 见下方「构建工作区」 |
| `release.sh` | 需要构建工作区 + gh 已登录 | 发布用 |
| `mkmanifest.py` | 需要构建工作区 | 手写二进制 AndroidManifest.xml |
| `mkzip.py` | 需要构建工作区的产物 | 组装 APK |
| `make_payload_parts.py` | 需要工具链目录 | 重建运行包分片 |

`run_tests.sh` 会自动识别两种目录布局：
构建工作区的 `bootstrap/src/…` 与克隆里的 `src/…`。

---

## 构建工作区

仓库里**只有源码、脚本和文档**。构建还需要一个工作区，
里面放的是不适合进 git 的大文件：

```
<工作区>/
├── sdk/
│   ├── android.jar           框架资源表（aapt2 link 用）
│   └── android-modern.jar    编译用（含 API21+ 属性，如 statusBarColor）
├── tools/
│   ├── d8.jar                Java 字节码 → DEX
│   └── apksigner.jar         签名
├── staging/data/data/com.termux/files/usr/
│   ├── bin/aapt2             Termux 的 aapt2（bionic 二进制）
│   ├── bin/node              构建期辅助
│   └── lib/                  aapt2 依赖的库
└── bootstrap/                源码的副本（构建时用）
```

默认路径是 `/root/build`（开发时的位置），
用 `DSH_BUILD_DIR` 指向你自己的：

```bash
DSH_BUILD_DIR=~/dsh-build bash scripts/build_bootstrap.sh
```

`aapt2` 的来源要特别注意 —— 它是 **bionic 二进制**，
必须用 Termux 的 linker 跑，详见 `docs/BUILD.md`。

---

## 各脚本做什么

### `run_tests.sh`

编译并运行 10 个纯逻辑测试类。这些类不依赖 Android，
所以能在普通 JVM 上跑。构建流程的第 3.4 步会调用它，**失败即中止构建**。

### `build_bootstrap.sh`

完整构建。步骤：

```
0.   检查工具链
1.   组装 APK 内置负载（node + lib/*.so + 引导脚本）
2.   aapt2 compile/link 编译资源
3.   mkmanifest.py 生成二进制清单（注入 aapt2 给的资源 id）
3.4  run_tests.sh                              ← 闸门
3.45 纯逻辑层不得 import android.                ← 闸门
3.5  UI 不得用 AlertDialog.Builder              ← 闸门
4-7. javac → d8 → mkzip → apksigner
```

产物：`bootstrap/out/DSHNative-bootstrap.apk`

### `release.sh`

```bash
bash scripts/release.sh <版本号> <构建目录> <发布说明.md>
```

顺序**不能改**：

```
1. gh release create              上传 APK + 清单
2. 验证 release 资产存在
3. 轮询两条下载路径（直连 + 镜像）都是 206/200
4. 最后才写 latest.json
5. 同步 README 的下载链接与 SHA-256
```

出过一次事故：脚本语法错误跳过了 `gh release create`，但清单被写了
→ 所有客户端更新失败。所以「验证通过才写清单」这条是硬要求。

README 同步是后加的 —— 那两个数字（链接里的版本号、SHA-256）
每次发版都变，手工维护出过两次错（链接长期指向 v0.9.0、
SHA 被拼成两个哈希连在一起）。

### `make_payload_parts.py`

把工具链切成 5 个运行包分片并生成 `manifest.json`。

```bash
DSH_TOOLS_DIR=<工具链目录> DSH_PAYLOAD_OUT=<输出目录> \
  python3 scripts/make_payload_parts.py
```

改运行包内容时注意：

- **只增改文件**：哨兵会自然发现，不需要改 `revision`
- **删了文件**：必须递增该分片的 `revision` 并把路径加进 `remove`
  —— 否则老用户那边删不掉（哨兵发现不了删除）

`dsh.tar.zst` 的重新打包是「解压到临时目录 → 用 `--exclude` 重新打包」，
需要约 210MB 临时空间。

### `mkmanifest.py`

手写二进制 AXML 生成器（496 行）。为什么要手写：
本项目的图标是 Canvas 画的（零资源），`aapt2 link` 没有资源可链，
手写反而更可控，且能精确注入 aapt2 生成的资源 id。

---

## 相关文档

- `docs/BUILD.md` —— 构建环境与步骤
- `docs/HANDOVER.md` —— 当前状态与待办
- `docs/GOTCHAS.md` —— 踩过的坑（跑脚本前值得一看）
