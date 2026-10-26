# Android 环境升级指南复核与落地

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.39.0**；源码：**0.39.1**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

2026-10-24 再次复核用户更新的《DSH Native 环境升级指南》（21:45 设备复核、21:51 文件修订，当前 App 0.36.1）。报告已确认 node/npm/npx、私有 tmp 和大部分解释器修复生效；其早期 0.33.13 / payload-v12 基线保留为历史。附件是问题线索与方案参考，未直接执行附录脚本。本次 0.36.2 补齐已确认的空白 shebang 漏洞，并完善真实文件工具和 Python 能力自检。

## 21:45 更新内容的处理

| 新版报告的事项 | 核对结果与本轮处理 |
|---|---|
| py3clean / py3compile 仍指向 Termux | 确认 `#!` 后的空格/制表符未被旧规则识别；修复解析，保留参数、正文、可执行状态与重复启动幂等性；解释器须实际存在且位于 tools 内 |
| git-cvsserver 依赖未安装 Perl | 保留原文；重写到不存在的解释器不会使它可用，不为了消除计数假报修复 |
| hardlink 被拒，推断 write 无法新建 | 原始 `ln` 失败是 Android 文件系统现象，不能单独证明已打补丁的工具失败；原 FsWritePatch 保留。本版自检直接调用真实 LocalFileSystem 的新建、读取、同名拒绝与按版本更新，分别检查私有 tmp 和所选工作区 |
| Python 包数少、Pillow/文档库不可用 | Pillow 归档仍在；PNG 往返检查继续保留。增加 requests/bs4/docx/pptx/lxml/numpy/pandas/matplotlib 实际 import 能力清单，区分可用、缺包/依赖缺失和导入失败；可选包缺失为信息，不伪装为强制启动故障 |
| 更新后环境又被覆盖 | shebang 修复每次启动重放；工作区路径加入自检缓存条件，切换文件系统后重新检查。APK 内置探针随版本更新，另提供手动 CLI，缓存不是实时包安装清单 |
| 原生桥、MCP、skills、审批、批量清理 | 属于方案建议与项目配置，不能靠一次检测结果决定自动启用。保持既有用户配置和任务数据；以下列出当前边界与更正 |

探针检查文件工具的本地后端，不替代完整会话的权限策略或模型调用；若后端通过而 write 工具仍失败，应提供实际工具错误与内核是否已重启的信息。

文件工具探针只在新建的唯一临时目录内写入固定测试文字，清理自己创建的目录；不使用固定的 `.preflight` 文件覆盖既有内容。错误只输出阶段与错误码，不包含文件正文。旧 payload-v12 明确提示未执行新版探针，保留恢复启动。


## 0.37.0 原生桥接补充

用户要求将第 4 节原生能力落实到 agent，而不是只维护环境检查。0.37.0 已新增 android 工具和 dsh-android 命令：剪贴板读写、通知、分享、打开链接/文件/App、保活状态和单次系统授权截图，详见[原生能力说明](ANDROID_BRIDGE.md)。桥由托管服务持有，窗口操作需要前台，截图每次请求系统批准；不改变用户的 MCP、插件选择、审批或工作区约定。

此前将这部分视为后续建议，范围收窄不符合用户意图；当前实现和验证边界以上述专页为准。

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
- **没有全局 TypeScript 包不等于不能运行所有 TS。** 当前 Node 26 可执行只含可擦除类型语法的 `.ts`，但不做类型检查，也不能替代完整项目构建和 `tsconfig` 工具链；依赖仍应按项目锁文件配置。依据：[Node TypeScript 文档](https://nodejs.org/api/typescript.html)。
- **没有用户 skills 目录不等于所有技能都为空。** 固定 rc.3 的 filesystem provider 默认扫描项目 `.dsh/skills`、`.agents/skills`、用户 DSH/agents 技能目录，并支持 custom/bundled provider；普通项目 `skills/` 需明确配置，不是当前源码的默认根。以实际技能列表为准。不要盲目挂载 Windows 或需要 LibreOffice CLI 的技能到 Android。
- **无 `.dsh/logs` 不能证明 App 无落盘日志。** App 的脱敏原生日志/诊断和内核会话日志是两层；后者需实际配置，不能为排障默认公开完整会话正文。
- **系统命令受 App 身份限制。** 修复 am/termux-open 的 shebang 不会获得 shell/root 权限；“只有一处前台服务引用”也不能证明没有通知或后台托管。
- **报告的版本、网络和包数量是当时快照。** 不把一次网络 200、可用空间或 scope 描述当成后续所有环境的保证，也不在项目文档复制凭据信息。

## 需要按实际项目配置的部分

编译器、科学计算栈、ffmpeg、PDF 渲染器和其他大依赖按任务安装。先检查 ARM64/Android/bionic、依赖和体积；不自动安装 Linux/glibc 二进制，不把“能下载”当成“能运行”。保留已有 Android 原生工具、Python/Pillow 兼容层。

Skills、MCP、终端和工作流插件应有明确用途与真实配置。没有 server 时不启用 MCP；不填写示例密钥，不改用户审批、沙箱或已有 profile，也不为了通过自检启用一批插件。项目约定由项目自己的 AGENTS.md 维护。

剪贴板、屏幕捕获和任意 Intent 是独立原生能力，涉及用户触发、权限、前台状态和目标范围。0.35/0.36 当时只实现语音与任务状态；0.37.0 已补齐封闭方法与文件范围的 agent 原生接口，系统权限仍由 Android 决定。

原报告建议批量清理 dsh-spill / subprocess / workspace-changes 目录，可能删除仍被任务使用的内容。本轮只清理自己创建的自检临时目录，不做通配删除；也不自动初始化或重置用户工作区 Git。

## 安装后的检查

覆盖安装同签名 APK，并从更新与维护重启内核。启动日志可查看工具修复数量、工具版本、TMPDIR、Pillow PNG 往返、私有目录/工作区文件工具与可选 Python 库结果。文件工具失败会保留阶段（import/create/read/collision-guard/replace 等）和错误码，并阻止缓存“全部通过”，但仍允许启动以收集现场。

agent 也可按实际私有运行目录手动复查，无需 `touch` 绕过新建保护：

```bash
node "$HOME/runtime-checks.js" "$HOME/dsh" "$PWD"
```

这条命令只检查真实后端，不修改项目约定、凭据、插件或安装包。输出 `FILE_TOOL|PASS` 后仍不能据此宣称已经验证某个旧会话进程；APK 安装后先重启内核。若失败，提供阶段和错误码即可。

自动回归包含 26 项托管工具检查、39 项实际运行包/生产自检检查，其中 Android 链接拒绝、磁盘错误与系统命令部分为合成边界；不代表已执行手机自检。按用户要求不做真机验证。

遇到包安装或执行问题，先提供失败命令、错误原文与诊断 ZIP；无需安装 Termux、清数据或按旧指南批量执行修复脚本。设备自检与真实系统语音由用户验证，仓库自动回归不代表这些真机操作已验收。

附录 `00-check.sh` 虽标注只读，仍包含 `am start`，若以后具备权限会打开外部页面；`ln` 和包数量也不能替代真实功能探针。批量 `rm -rf`、git init、挂载技能及安装包不能仅因出现在附件中就自动执行。
