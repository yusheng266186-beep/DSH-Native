# 设备调试工具说明

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.0**；源码：**0.33.1**；源码运行包：`payload-v11`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

本目录保留早期在 Android/bionic 环境进行无线 ADB 调试的脚本。它们不是 App 的用户依赖，也不是当前 Linux CI 构建入口；当前开发路径见 [BUILD](../docs/BUILD.md)。

## 现有脚本的适用范围

| 文件 | 原用途 | 当前边界 |
|---|---|---|
| `adb.sh` | 封装旧设备侧 Android ADB 和动态库路径 | 依赖 `x/data/data/com.termux/files/usr` 中未随仓库提供的二进制，并使用旧环境路径，不能当作通用桌面 ADB |
| `pair.sh` | 旧无线调试配对 | 包含历史绝对路径，须在隔离开发环境人工核对后使用 |
| `install_and_log.sh` | 早期实验安装与启动日志 | **会先卸载 App**；不得用于现有用户升级、发布验收或含真实数据的设备 |

这些旧脚本保留用于追溯，不在本轮文档修改中改变执行行为。不要照旧 README 运行其一键安装命令。

## 非破坏性覆盖安装与日志

在桌面安装官方 Android platform-tools，并完成设备调试授权。保持与已安装版相同的包名和签名：

```bash
adb devices
adb install -r /path/to/DSHNative-bootstrap.apk
adb shell am start -n dev.dsh.native/dev.dsh.nativeapp.MainActivity
adb logcat -v time -s DSHNative AndroidRuntime
```

签名不一致应停止安装并核对产物，不能通过卸载或 `pm clear` 处理。日志分享前脱敏，优先使用 App 的诊断导出，避免传输密钥或会话内容。

## 真机记录格式

注明版本、Android/ROM、设备、方向/字体/主题、操作步骤、预期、实际结果和脱敏日志。优先覆盖新内核 PTY/图片、目录更新与聊天框 max、运行/未知时的延后重载、后台通知、更新失败恢复和已有数据保留。

既有验收与剩余事项统一记录在 [STATUS](../docs/STATUS.md)，不要把一次安装成功写成所有功能通过。
