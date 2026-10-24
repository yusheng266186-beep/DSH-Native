# Android 环境升级指南复核与落地

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.34.2**；源码：**0.35.0**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

2026-10-24 复核用户提供的《DSH Native 环境升级指南》。原报告基于 0.33.13 / payload-v12，当前源码基于 payload-v13 / DSH 0.2.1-rc.3。报告是问题线索与方案参考，其附录脚本未作为指令执行；本轮修复随 0.35.0 APK 启动流程自动应用。

## 已落地的修复

| 问题 | 0.35.0 的处理 |
|---|---|
| Node 不在 PATH、npm/npx 依赖旧 Termux 解释器 | App 专用 `.native-bin` 提供 node/npm/npx 包装，放在工具和系统 PATH 前面；传递原参数，不覆盖用户自定义入口 |
| 托管脚本有 Termux/Unix shebang | 每次启动只在 tools 内修复已有解释器的脚本，保留正文、可执行位、npm 链接和全局包；不跟随 tools 外的链接 |
| `/tmp` 不可写 | 创建私有 tmp，内核及自检统一设置 TMPDIR/TMP/TEMP；硬编码 `/tmp` 的外部脚本仍需改为读环境变量 |
| 文件工具新建时报 link EACCES/EPERM | 修复真实 `dsh-fs-local` 新建路径：兼容性错误才使用 COPYFILE_EXCL 并同步文件，保留同名文件与并发创建保护；其他磁盘错误继续显示 |
| 通知栏读不到任务、后台失去网页状态 | 原生认证查询真实 session/list，未知不冒充完成；运行时限时保活，结束或停止释放 |
| 语音插件依赖桌面模型 | 输入栏和插件详情接入系统识别；提供服务/权限检测、错误和草稿插入，见[语音与状态排查](VOICE_INPUT.md) |
| 自检不够具体、失败也缓存通过 | 增加 node/npm/npx/pip3、临时目录和 Pillow PNG 生成/读取检查；失败不记录通过缓存，下一次启动复查 |
| agent 不清楚 Android 环境边界 | APK 更新私有 runtime-environment.md，工作区说明提供路径；不自动覆盖项目 AGENTS.md |

修复在每次启动重放，APK 更新或运行包恢复后仍可应用；旧 payload-v12 跳过不兼容的新内核补丁。无需执行附件中会覆盖 npm 符号链接目标的包装脚本。

## 对报告结论的更正

- **Pillow 已在运行包中。** 已校验 payload-v13 的 tools-python 归档，包含 PIL 及 Android 原生扩展，包括 WebP。PyPI 没有通用 Android wheel，不能推断预装 Pillow 不存在；手机实际导入与图像读写仍以新增自检为准。
- **python-docx / python-pptx 不能一概说无需原生依赖。** 两者依赖 lxml，python-pptx 还依赖 Pillow，图表依赖 XlsxWriter；在 Android 上需先确认依赖是否有兼容构建，不能直接照搬桌面 pip 安装承诺。依据：[python-docx 安装说明](https://python-docx.readthedocs.io/en/latest/user/install.html)、[python-pptx 安装说明](https://python-pptx.readthedocs.io/en/latest/user/install.html)。
- **新建文件失败点已定位到实际后端。** 当前 `dsh-atomic-write` 已走 rename，直接修改它无法修复文件工具的 guarded-create 硬链接。新的回归调用实际文件系统后端，并模拟 Android 拒绝链接与磁盘失败。
- **系统命令受 App 身份限制。** 修复 am/termux-open 的 shebang 不会获得 shell/root 权限；“只有一处前台服务引用”也不能证明没有通知或后台托管。
- **报告的版本、网络和包数量是当时快照。** 不把一次网络 200、可用空间或 scope 描述当成后续所有环境的保证，也不在项目文档复制凭据信息。

## 需要按实际项目配置的部分

编译器、科学计算栈、ffmpeg、PDF 渲染器和其他大依赖按任务安装。先检查 ARM64/Android/bionic、依赖和体积；不自动安装 Linux/glibc 二进制，不把“能下载”当成“能运行”。保留已有 Android 原生工具、Python/Pillow 兼容层。

Skills、MCP、终端和工作流插件应有明确用途与真实配置。没有 server 时不启用 MCP；不填写示例密钥，不改用户审批、沙箱或已有 profile，也不为了通过自检启用一批插件。项目约定由项目自己的 AGENTS.md 维护。

剪贴板、屏幕捕获和任意 Intent 是独立原生能力，涉及用户触发、权限、前台状态和目标范围。本轮实现用户要求的语音和任务状态；不增加无范围的 agent 手机控制接口。

原报告建议批量清理 dsh-spill / subprocess / workspace-changes 目录，可能删除仍被任务使用的内容。本轮只清理自己创建的自检临时目录，不做通配删除；也不自动初始化或重置用户工作区 Git。

## 安装后的检查

覆盖安装同签名 APK，并从更新与维护重启内核。启动日志可查看工具修复数量，以及工具版本、TMPDIR 和 Pillow 自检结果；未通过项保留可见错误并允许继续启动，便于导出现场。

遇到包安装或执行问题，先提供失败命令、错误原文与诊断 ZIP；无需安装 Termux、清数据或按旧指南批量执行修复脚本。设备自检与真实系统语音由用户验证，仓库自动回归不代表这些真机操作已验收。
