# 调试工具（设备内 adb）

本项目长期受限于「无法在真实 App 沙箱里验证」（容器与 `run-as` 使用豁免的
`runas_app` 域，测不出真实行为）。

解决方案：Termux 仓库的 `android-tools` 提供 bionic 版 `adb`，
可脱离 Termux 直接在本机运行。开启手机**无线调试**后，
即可从设备内部（`127.0.0.1`）配对连接 adbd，获得 `shell` 权限通道。

## 获取 adb

```bash
# 从 Termux 仓库下载并解包（注意文件名中的 '+' 需编码为 %2b）
curl -O https://packages-cf.termux.dev/apt/termux-main/pool/main/a/android-tools/android-tools_37.0.0_aarch64.deb
# 连同依赖（abseil-cpp brotli fmt libc++ liblz4 libprotobuf pcre2 zlib zstd liblzma）
dpkg-deb -x android-tools_37.0.0_aarch64.deb x/
```

## 脚本

| 脚本 | 用途 |
|---|---|
| `adb.sh` | 封装 `LD_LIBRARY_PATH` 与可写 `HOME`，其余参数透传给 adb |
| `pair.sh <配对码> <配对端口> <连接端口> [IP]` | 无线调试配对 + 连接 |
| `install_and_log.sh [APK]` | 卸载 → 安装 → 清日志 → 启动 → 抓关键日志 |

## 用法示例

```bash
./pair.sh 123456 37123 40001 127.0.0.1
./adb.sh devices -l
./install_and_log.sh /path/to/DSHNative-bootstrap.apk
```

## 能验证的关键项

- App 是否正常启动（闪退时直接看到 `AndroidRuntime` 堆栈）
- **`targetSdk 28` 下能否执行私有目录中的 Node**（SELinux `execute_no_trans`）
- 首启下载与解压全流程
