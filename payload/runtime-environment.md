<!-- DSH-NATIVE-ENVIRONMENT-v1 -->
# DSH Native Android 运行环境

这里运行的是 Android 应用私有目录中的 ARM64/bionic Node 与工具，不是 Termux、Debian 或远程服务器。

- `node`、`npm`、`npx`、`python3`、`pip3`、`git`、`rg`、`bash` 等通过进程 PATH 使用。先运行 `command -v` / `--version` 确认具体工具。
- 临时文件放在 `$TMPDIR`，不要假定 `/tmp` 可写。当前 App 将临时目录放在私有运行目录下的 `tmp`。
- App 启动时修复托管工具脚本的 Termux/Unix 解释器路径；保留正文和全局包。安装新命令后若报解释器路径不存在，可重新启动 App 进行修复，或显式用 `node` / `python3` 执行对应脚本。
- `npm` 全局前缀和 Python 前缀由 App 设置。优先使用纯 JS / Python 包或明确提供 Android ARM64 构建的包，Linux ARM64/glibc 二进制不能直接用于本环境。
- 编译器、ffmpeg、完整科学计算栈等不保证预装；需要时先检查目标平台、安装体积和依赖。Pillow 是否可用以运行环境自检和实际导入为准，不能用“PyPI 没有 wheel”推断预装包不存在。
- 新建文件使用保留同名文件保护的 Android 兼容路径；原始 `ln` 失败不能证明文件工具不可用。可运行 `node "$HOME/runtime-checks.js" "$HOME/dsh" "$PWD"` 复查当前目录的新建、读取、同名保护和按版本更新；只写自己创建的临时目录。不要预删目标或先 touch 来绕过保护。
- 启动日志列出可选 Python 库的实际 import 结果；pip 包数量不等于可用能力。docx/pptx 需要 lxml 等兼容依赖，不保证纯 Python 安装；缺可选包不是启动错误。
- Node 支持可擦除类型的 TypeScript 脚本；完整类型检查和项目构建仍按项目依赖与锁文件处理。
- 手机原生操作使用已注册的 `android` 工具，或 `dsh-android METHOD [JSON]`；先调用 capabilities。支持 clipboard.get/set、notification.post、share.text/file、url.open、file.open、app.open、keepalive.status、screenshot。`am`/`cmd` 不因此获得 shell/root 身份。
- 剪贴板/分享/打开需要 DSH 窗口在前台。分享只打开系统选择器，不代表已经发送。文件仅当前工作区或 native-captures 的常规产物，最多32 MiB；凭据、私钥、配置目录等不导出。
- screenshot 每次弹出 Android 系统授权，成功返回单张私有 PNG 路径后停止，不重复使用令牌、不连续监控。只有拿到实际图片才能描述屏幕内容。不要轮询剪贴板或把其正文加入普通日志。
- dsh-android 参数可用 `-` 从 stdin 读 JSON；私有桥令牌由客户端读取，不要查看、复制或输出 `.native-bridge.json`。
- 通知栏任务状态由原生服务通过认证本机 RPC 获取。查询失败显示未知/同步中，不能当成任务已完成。
- 语音输入默认在 DSH 页面内连接手机公开识别服务，临时文字只预览、最终结果填草稿。插件详情可检测/选择引擎；“使用系统语音”才会打开外部窗口。识别服务可能联网，语言与离线能力取决于已安装服务，仅提供外部窗口的服务不能在 DSH 内换肤。
- 凭据、会话历史和用户配置在私有 `.dsh` 下；不应把它们加入代码仓库、普通日志或公开诊断正文。
- MCP、额外插件、模型服务、审批和沙箱由用户配置；不要为“自检通过”写入示例令牌、启用无 server 的 MCP、替换用户的审批策略或初始化/重置已有 Git 仓库。

项目自己的 AGENTS.md、构建命令和技能仍以实际项目为准。本说明只描述运行条件。
