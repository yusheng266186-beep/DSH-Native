# CodeBuddy CLI 接入

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.38.0**；源码：**0.39.0**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

0.39.0 在 APK 内置官方 `@tencent-ai/agent-sdk` 0.3.291 和它随附的 CodeBuddy CLI 2.176.0。手机直接运行，无需电脑或另外安装 Termux。现有 `CODEBUDDY_API_KEY` 继续从 DSH 凭据解析，不需要重新导入。

## 为什么改用 CLI

用户在 0.38.0 遇到服务端 400 / 11128。核对 beichen 固定提交 `b99ed431aa50cf9eea6a86fa4b3a188388a495ff` 后确认，它是 `.cn/v2` 的 Bearer + stream 调用，没有特殊渠道认证。相同密钥的简单请求正常，直接发送完整 DSH 请求仍被拒绝；把 DSH 系统提示词作为官方 SDK 的替代系统提示词也被拒绝。因此删去旧的额外产品/IDE 头并不足以解决问题。

当前使用官方 SDK 的标准 CLI 代理模式，不伪造客户端标识、不修改文本来规避渠道检查。CLI 使用自身的代理行为；DSH 负责界面、会话记录和工具权限。DSH 的组合系统人格不作为 CLI 的替代人格，普通对话上下文、工作区信息、文件引用、图片和历史工具结果交给 CLI。辅助标题/压缩任务保留任务指令。该模式与直接 LLM API 的系统提示词语义不同。

SDK 的内置工具全部禁用，个人/项目设置源、额外 MCP 配置和 CLI 会话持久化也关闭。只通过 SDK MCP 公共接口公布当前 DSH 工具及原始 JSON Schema。完整工具请求返回 DSH 循环，由 DSH 执行并将结果带入下一次请求；CLI 不直接执行模型请求的文件修改或命令。目录仍是固定 CLI 元数据，不能宣称所有模型已获账号授权。max 是传给 SDK 的请求选项，实际接受方式由官方 CLI 与模型服务决定。

## 使用与检查

安装新版覆盖现有 App，然后在更新与维护重启内核。从新会话选择 CodeBuddy；原有项目覆盖会保留，必要时在项目模型设置里切换。现有 Key 可直接使用，也可在模型设置填写 Key。

“检测连接与模型”通过内置官方 CLI 发送一个短请求，可能使用少量额度；只证明所选模型的这次检查。模型目录刷新不发送生成提示词，界面明确标注其来源；导入成功也不冒充对话检查成功。

首次使用自动校验和展开内置 SDK，展开约 192 MiB，首次准备建议至少保留约 300 MiB 空间供运行与 CLI 私有缓存使用。SDK 归档进入已签名 APK，构建使用固定 lockfile；App 核对大小、SHA-256 和 SDK/CLI 版本，使用既有受限 tar 解包器。后续直接复用已准备版本。取消、关闭和空闲超时会撤销 SDK 子进程；错误不包含密钥或请求正文。

SDK 私有状态位于 App 私有 `.codebuddy-home`，不能通过文件分享或外部编辑导出，也不进入诊断 ZIP。验证中未发现 Key 写入该目录；Key 通过私有 stdin 或 SDK 子进程环境传入，不进入命令行、普通日志、Git 或 Release。

## 私人加密导入

1. 电脑执行 `node tools/export_codebuddy_connection.cjs /absolute/private/output`，默认只读取 `~/.codebuddy/settings.json` 的 `env.CODEBUDDY_API_KEY`。
2. 把私人 `CodeBuddy.dshconnection` 送到手机，另行保管 `连接口令.txt`。
3. 设置 → 模型 → 导入连接，选择文件并输入口令。

格式仍为 `DSH-CODEBUDDY-1`，使用随机 salt/IV、PBKDF2-SHA256 200000 次和 AES-256-GCM。网页在本机解密，经 `credentials/set` 保存密钥；公开配置只保存引用。导入使用真实内核 RPC，全局默认与项目默认保持一致，不清空其他服务商或已有项目覆盖。

## 本次验证边界

- 在独立临时工作区，用本机真实账号完成实际 DSH → 内置 CLI → DSH write → DSH read → 回复保存；普通 Node 和 Android 平台选择边界均通过，没有 11128。不是手机实测，也不代表全部模型、图片与长期会话均已实测。
- 自动测试使用合成凭据和 SDK 协议替身，覆盖流式去重、原工具 Schema、工具返回、取消、期限、错误脱敏、归档拒绝和官方 CLI 的 Android 平台启动；不发送生成提示词。
- 按用户要求不做真机验证，由用户安装新版后验证小米手机环境。

官方接口与使用语义见 [Agent SDK](https://www.codebuddy.cn/docs/cli/sdk)、[自定义工具](https://www.codebuddy.cn/docs/cli/sdk-custom-tools)、[身份与认证](https://www.codebuddy.cn/docs/cli/iam)。官方 SDK/CLI 的 MIT 许可随归档保留。

## 旧服务商清理

CommandCode 面板、默认配置和旧凭据引用已移除。启动迁移仅处理 DSH 自有配置，保留其他服务商、账号授权、项目和历史；不重新导入共享原始文件里的旧密钥。托管 CodeBuddy 显示名更新为 CodeBuddy CLI，自定义服务地址保留。
