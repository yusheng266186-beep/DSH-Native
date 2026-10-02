# DeepSeek 账号登录与原生模型设置

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.9**；源码：**0.33.10**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 用户路径

在侧栏打开 **App 工具 → 模型中心**。模型列表更新入口位于这里，侧栏只保留 App 工具。
服务商选择按整行纵向排列，提供 Command Code、DeepSeek API Key 和 DeepSeek 账号。

点击 **DeepSeek 账号登录与管理 → 登录 DeepSeek 账号**，在系统浏览器中登录并授权。
授权回调到本机内核，完成后返回 App。选择 DeepSeek 账号，读取模型目录，选择模型与强度并保存。
账号授权不会自动更改已保存的全局/项目默认或历史会话。

账号页可以刷新状态、重新打开授权页、取消当前授权和退出账号。后台暂停轮询，回到前台重新读取状态。
关闭面板不取消授权；重建 Activity 后可以继续读取内核保存的尝试。退出前提示是否有账号任务，内核停止
使用该账号的任务并移除授权，API Key、会话与项目保留。

## 实际内核接口

固定运行包仍是 `payload-v12` / DSH `0.2.0-rc.2`。2026-10-02 核对上游 Release 后，它仍是最新候选版。
上游 Web 账号页面要求 `dshDesktop` 桥，Android 不伪造该桥；原生面板直接消费已随 Web profile 加载的账号 RPC。

| 组件 | 职责 |
|---|---|
| `CoreRpcClient` | 只连接本 App 的 127.0.0.1 端口，交换启动 token 为私有 Cookie，限制 RPC 方法、响应大小、超时与重定向 |
| `NativeCoreApi` | JSON 边界、单线程有界后台队列、取消与 Activity 回调；读取当前公开模型配置 |
| `DeepSeekAccountPanel` / `DeepSeekAccount` | 原生账号操作、状态显示、前后台轮询、授权 URL 白名单 |
| `ModelSettingsSnapshot` | 将 `settings/describe` 的公开模型 namespace 投影到现有纯 Java 配置读写器 |
| `ModelCenterPanel` | 三种 provider、账号模型目录、全局/项目选择、异步保存与受控应用 |
| DSH 账号模块 | PKCE、state、过期、授权交换、回调、任务停止与账号 grant 存储 |

RPC 使用真实 `client-request` / `server-response` 信封和独立 `rpcId`。
主要调用 `account/getState`、`startSignIn`、`cancelSignIn`、`hasRunningAccountTasks`、`signOut`，
账号目录由 `session/modelCatalog` 的 `deepseek-account` 分组提供。
浏览器仅接受 `https://platform.deepseek.com/dsh/authorize`，禁止 userinfo、其他 host 和路径。
登录元数据传入当前语言、时区和固定内核版本；grant 与 PKCE verifier 不投影到原生状态页。

## 配置迁移与凭据保护

内核会将 `.dsh/settings.yaml` 导入 active Web profile，原文件改为 `settings.yaml.imported`。
没有待导入文件时，原生模型读取和项目切换通过 `settings/describe` 获取当前配置；不能把 legacy 文件缺失
当作没有模型设置。写入新默认或完整目录后沿用待导入文件路径，确认任务空闲再受控重启内核；未知状态延后。

API Key 修改通过内核 `credentials/set` / `credentials/unset`。不要用对话框打开时的凭据全文覆盖
`.credentials.yaml`：内核可能在浏览器授权期间写入新的账号 `records`，旧全文会丢失 grant。
账号 provider 的模型目录和 live 标记独立于 API Key provider，不能互相覆盖。

加密配置备份白名单包含 `.credentials.yaml`、`settings.yaml`、`settings.yaml.imported`、
`profiles/web/cordis.patch.yml` 和 `.native-project-models`。恢复支持精确的嵌套 profile 路径，
校验全部目标及父目录后才替换文件；拒绝通过符号链接逃出配置根目录。仍不包含项目、附件或会话历史。

## 验证与边界

`bash scripts/run_tests.sh` 包含 RPC 传输、授权 URL/状态、公开配置投影、凭据脱敏和配置备份的纯 Java 测试。
`bash scripts/check_model_consumer.sh` 包含实际运行包与同一 Java 传输客户端的账号消费回归。

账号消费回归共 45 项检查：用本机模拟平台验证 PKCE/state、重复发起、错误回调、授权交换、目录、
profile/余额接口、重启持久化、原生生成的账号默认与 max 被真实内核采用、取消、过期、网络失败、退出
以及 API Key 与账号授权互相保留。授权 code / token 使用合成值，不调用真实账号或付费模型。

真实前端触屏回归共六组、294 项检查，包含 App 工具的实际点击、幂等注入、侧栏刷新入口移除，
以及发送、附件、文件关闭、设置、中英文和明暗主题。
这些检查不代表新的原生账号页已在用户手机验收，也不代表真实 DeepSeek 平台登录已用用户账号完成。
完整 Android 编译和既有签名由 CI 验证；真实平台授权及手机上的浏览器往返仍需覆盖安装后确认。
