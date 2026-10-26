# CodeBuddy CLI 接入

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.38.0**；源码：**0.38.0**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

DSH 使用本机官方 CodeBuddy CLI 的 API 配置。当前接入核对为 CLI 2.174.0、`https://www.codebuddy.cn/v2`、`deepseek-v4.1-flash`；默认强度保持用户要求的 max。地区入口与账号权限可能不同，不能把 `.ai` 和 `.cn` 当作可互换地址。

## 导入到手机

1. 在电脑执行 `node tools/export_codebuddy_connection.cjs /absolute/private/output`。默认只读取 `~/.codebuddy/settings.json` 的 `env.CODEBUDDY_API_KEY`，也可用第二参数指定配置文件。
2. 输出私人 `CodeBuddy.dshconnection` 和独立的 `连接口令.txt`。将加密连接文件送到手机。
3. 在 DSH 设置 → 模型 → 导入 CodeBuddy CLI 连接，选择文件并输入口令。成功后从新会话调用 CodeBuddy；既有项目覆盖保留，可在项目模型设置里单独修改。

连接文件使用随机 salt/IV、PBKDF2-SHA256 200000 次和 AES-256-GCM，格式为 `DSH-CODEBUDDY-1`。网页只在本机解密，通过当前已认证的 DSH `credentials/set` 存入 `CODEBUDDY_API_KEY`，公开模型配置只保存引用。API key 不进入源码、APK、GitHub Release 或普通日志；口令不放在 Release，不能与连接文件一起公开分享。

导入写入真实 `settings/mutate` 和 `settings/update`，全局默认选择与原生项目配置保持一致；已有项目覆盖不清空。导入时收起旧项目模型面板，避免它持有导入前的旧配置。

## 接口和模型目录

CodeBuddy 当前使用 OpenAI chat-completions 流式接口，还要求 `X-API-Key`。App 在真正发送请求时从内核凭据解析认证头，同时设置 CLI 所需产品标识；密钥不写入 provider 的 `headers`。普通查看/编辑公开模型配置看不到密钥。

该入口没有可用的通用 `GET /models`。App 使用空消息参数探测检查接口响应，返回目录明确标注来自固定 CLI 元数据，不能称为账号授权列表。本机真实凭据曾核对最小流式响应；自动回归只用合成凭据和离线 SDK 边界，不发送付费生成提示词。模型与思考强度的实际接受情况以服务端为准。

模型资料见 [public metadata](../runtime/codebuddy-catalog.json) 与 [参数说明](MODEL_REASONING.md)。官方配置入口见 [环境变量](https://www.codebuddy.ai/docs/cli/env-vars) 和 [配置说明](https://www.codebuddy.ai/docs/cli/settings)。

## 移除旧服务商

本版移除 CommandCode 的独立面板、用量查询、默认配置与能力快照。每次内核启动前，对 DSH 自有 active profile、legacy 导入快照、凭据和全局/项目模型选择做幂等迁移，移除旧路由及旧密钥引用，将仍指向旧路由的默认项改为 CodeBuddy。其他服务商、DeepSeek 账号、项目内容和历史会话保留；共享目录的原始用户文件不修改，旧密钥也不再从共享文件重新导入。
