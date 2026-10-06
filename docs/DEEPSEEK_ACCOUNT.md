# DSH 账号与余额、Android 会话保存

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.12**；源码：**0.33.12**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 用户路径

打开 **App 工具 → DeepSeek 账号**，进入 DSH 自带的 **账号与余额** 设置页。
**模型中心 → DeepSeek 账号与余额** 打开同一页面。侧栏的上游账号菜单也保留设置、登录和退出入口。
更新模型列表继续位于 **App 工具 → 模型中心**。

原始账号组件负责登录、取消、状态更新和退出，显示账户资料、充值余额及赠金余额，保留查询用量和充值链接。
余额由内核平台接口读取，失败显示不可用及开放平台入口，不显示伪造的零余额。
点击登录后在系统浏览器授权，回调到本机内核，再返回 App；等待界面可再次打开浏览器或复制授权链接。
Android 使用上游的 web 登录来源，不使用 Electron 内嵌平台或 Desktop 首次引导。

授权保存后，模型中心可以选择 DeepSeek 账号模型并保存全局/项目默认；API Key 路线独立保留。
退出由上游提示运行任务影响，停止使用账号的任务，保留 API Key、会话和项目。
不重写历史会话的模型。首次账号登录采用上游 initializeDefaultModel 行为；显式配置继续通过模型中心保存。

## 上游界面的平台适配

固定运行包仍为 payload-v12 / DSH 0.2.0-rc.2；2026-10-02 核对上游发布列表，它仍是最新候选版。
0.33.10 的独立原生账号面板只实现了部分账号功能，0.33.11 删除它并保留实际的上游 React 组件。

AccountUi 只对两个已核对的真实模块做有限、幂等的修改：

- 启用 dsh-client-ui-settings-account，保留 Desktop 专用首次引导的原条件，不制造 dshDesktop / dshPlatform。
- 登录使用真实 account/startSignIn、PKCE、state 和流式状态；授权页由核心校验。
- 授权等待时在前台打开系统浏览器，保留显式重新打开与复制；不会读取 grant 或 PKCE verifier。
- 签出状态也在设置中注册账号页，方便从 App 工具直接进入。
- dsh-client-ui-settings-general 接收页面内无权限导航事件，用原 actions.openSection("account") 打开账号页。
- 自动模型引导不再盖住打开的设置，也不会将用户主动打开的账号页关闭。

结构变化或重复锚点导致补丁返回失败；不猜测新结构，不写半份模块。
App 升级后已运行的旧内核不会被宣称采用新补丁：旧进程版本不符时要求点击状态页「重试启动」或从更新与维护重启，避免隐式中断任务。
系统浏览器外链通过现有 WebView 导航处理，DSH 页面保留在 App 中。
账号及凭据 RPC 正文不进入网页接口诊断日志。

## 会话首次保存 EACCES

用户在 0.33.10 反馈账号登录后首次运行失败，路径为压缩 session.v4.jsonl.zstd.<id>.tmp 到正式日志的 link()。
该错误来自本机会话发布，账号授权已完成；不能归因于余额或账号接口。

旧运行包只给 publishCurrentExclusive（历史格式迁移）添加了部分硬链接降级，遗漏新会话的
materializePosix，且兼容错误表没有 EACCES。0.33.11 的 SessionPersistencePatch 在每次启动处理两个路径，
APK 携带共用的 payload/session-publish.js；新运行包构建也使用同一辅助函数。

硬链接成功时保留原路径；EACCES、EPERM、EXDEV 等链接兼容错误才使用 COPYFILE_EXCL，随后同步复制文件。
同名目标拒绝覆盖；复制本身的权限错误、磁盘满和 I/O 错误继续传播。成功或失败均沿用原临时文件清理。
排他复制保留拒绝覆盖的语义，但不是硬链接的原子可见性，不使用“先检查再 rename”覆盖历史的旧实验补丁。
不删除 .dsh、既有会话、授权、附件或工作区，不要求清空数据。

## 配置与凭据

内核将 settings.yaml 导入当前 Web profile 后改为 settings.yaml.imported。
原生模型设置与项目切换在没有待导入文件时通过 settings/describe 读取当前公开配置。
启动前保留预期模型配置，CoreReadiness 核对实际默认、目录和能力，避免导入竞争。

API Key 使用 credentials/set / unset，避免用过时的凭据全文覆盖账号 grant。
加密备份包含 .credentials.yaml、settings.yaml、settings.yaml.imported、
profiles/web/cordis.patch.yml 和 .native-project-models；全部目标预检并拒绝越界符号链接。

## 回归与手机验证

scripts/check_model_consumer.sh 在经 SHA 校验的真实运行包上应用生产 Java 补丁后测试：

- session-publish-consumer.mjs 强制 link 返回 EACCES，实际保存压缩/普通新会话、重开与继续写；检查同名拒绝覆盖、写权限拒绝、磁盘错误和临时文件清理。
- deepseek-account-consumer.mjs 使用真实内核、上游账号组件和本机合成平台，检查浏览器授权、两类余额、失败余额、三组触屏/主题、PKCE/state、凭据保留、重启、默认模型、取消、过期和退出。
- mobile-layout-consumer.mjs 六组触屏场景检查发送、附件、文件关闭、设置和实际 App 工具点击；启用账号菜单后也必须通过。
- core-runtime-consumer.mjs 保留 Linux 原生锁、迁移独占发布和真实 profile 验证，不用 Android 降级掩盖桌面错误。

合成平台使用测试 code/grant，不发送付费提示词。测试数量看当次日志，不累计历史数字。
用户已反馈 0.33.10 可完成账号登录，但首次任务保存失败；本版 Android 修复及上游账号页的实际手机体验仍需覆盖安装验收。
