# 隐私与设备权限

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**1.1.2**；源码：**1.1.3**；源码运行包：`payload-v14`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

DSH Native 在手机私有目录运行 DSH 和工具。使用模型时，你发送的文字、所选图片/文件、对话历史和工具结果会交给所选服务商；CodeBuddy 接入通过内置官方 CLI/SDK。服务商、系统语音引擎、外部应用各自处理收到的数据，本应用不能替它们承诺数据保留规则。

| 数据/能力 | 实际处理方式 |
|---|---|
| API Key、账号授权 | 保存在 App 私有 DSH 凭据中；连接导入和配置备份使用口令加密。密钥不放进命令行、普通日志或公开设置描述 |
| 会话、附件、项目 | 保存在私有 DSH 数据或所选共享工作区。归档/回收站可恢复，不等于释放空间；卸载会影响私有数据，升级请覆盖安装 |
| 语音输入 | 由用户点击启动并申请麦克风权限，使用 Android 已启用的识别引擎；引擎可能联网。转写先进入草稿，不自动发送；App 不保存录音或将转写写进诊断 |
| 剪贴板 | 仅在明确操作或 DSH 工具调用时读写，受前台与 Android 限制；不是持续监听剪贴板 |
| 截图 | 每次由系统授权，只取一帧，取消/完成释放投影；不会隐式持续录屏 |
| 文件打开、编辑、分享 | 显式调用 Android 处理应用。默认只授予读取；明确外部编辑才授权单文件写入。选择目标应用后，它可能取得文件内容 |
| 通知和保活 | 通知显示任务及连接状态。运行任务使用有期限的唤醒锁，仍受 ROM 后台政策影响；拒绝权限不会被标成正常 |
| 更新 | 从 GitHub 或配置的下载镜像取得 APK/运行包，校验大小、摘要、原签名。下载服务器会看到正常网络连接信息 |
| 诊断与性能 | 用户主动导出 ZIP；摘要含设备/系统/WebView、布局尺寸、连接、环境、权限、回滚和脱敏日志。阶段耗时仅固定标签/数字，不含提示词、密钥、工具参数或录音 |
| 中断恢复 | 配置恢复的私有撤销记录可能包含原凭据，完成后删除，失败时保留用于恢复；不得分享或当作缓存清理 |

诊断导出不打包配置、凭据、会话正文、附件、项目文件、CodeBuddy 私有状态或恢复撤销记录。分享诊断前仍可自行检查 ZIP 内容。应用不会自动替你上传诊断包。

清除可重新生成的编译缓存不会删除会话；配置备份只备份配置，不包含完整历史和项目。迁移设备前请另外保存共享工作区、所需历史及附件，并保管好备份口令。

Privacy summary: model requests include the conversation and selected attachments/tool results and go to your chosen provider. Voice is handled by your selected Android engine. Native clipboard, capture, file export and sharing require explicit operations and applicable permissions. Credentials and recovery records remain private; encrypted configuration backups do not contain full projects or conversation history. Diagnostics are exported only on request, redact logs, and exclude credentials, configuration, conversation content, attachments and projects. Other apps/providers apply their own data policies.
