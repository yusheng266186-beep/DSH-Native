# DSH 0.2.0-rc.2 / payload-v10

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.13**；源码：**0.33.13**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

App 0.32.0 起使用 payload-v10，将 DSH CLI 及全部 277 个 `dsh-*` 模块从 0.1.7-rc.2 升到固定的 0.2.0-rc.2。这是 2026-09-30 核对并锁定的上游候选版，不是对未来 npm latest 的动态声明。当前来源与完整性以 `runtime/core-source.json` / 锁文件为准；App stable 与上游候选级别分别记录。

来源：https://github.com/deepseek-ai/deepseek-harness/releases/tag/dsh-v0.2.0-rc.2 。上游带来模型搜索、计划审阅和 Agent 预设修复，以及 pi-ai 0.87.1 兼容更新。原生实时模型目录和思考等级配置继续保留；App 0.32.1 又按用户要求对所有模型追加 max 请求，详见 [模型思考说明](MODEL_REASONING.md)。不因 pi-ai 内置旧 ID 移除而覆盖用户的 Provider API 目录。

## 构建与增量更新

`runtime/core-source.json` 固定官方 CLI tarball 和 SHA-512；`runtime/core-package-lock.json` 固定实际依赖。`scripts/prepare_core_payload.py` 校验官方归档、以 `npm ci --omit=dev --ignore-scripts` 安装，保留 payload-v9 的 Node 内部模块垫片和已发布 Android bionic PTY 二进制。两个 PTY 平台入口均使用同一个已验证 ELF，不引入新的 Node 或 Android 动态库。

构建命令：

```bash
python3 scripts/prepare_core_payload.py --base /path/to/verified-v9-runtime \
  --previous-manifest /path/to/verified-v9-manifest.json --out /path/to/payload-v10
```

工具链四片从 v9 原样复用，字节、摘要、哨兵均不变。DSH 修订号 4 -> 5；只有 DSH 片需要下载。`remove` 自动列举上一归档有、新归档没有的文件，并删除旧附件备份，避免旧 hash chunk、旧模块和 `.dshorig` 混入新内核。删除范围只有运行目录，用户 `.dsh`、凭据、历史、项目工作区不在其中。App 保留更新前快照和更新失败自动恢复。

发布先校验所有本地分片和 SHA256SUMS，创建 draft Release，逐资产上传并校验 GitHub 返回的 SHA256，最后上传 manifest，再公开 Release并验证其可下载。通过 `scripts/release_payload.py` 完成；它不触碰 App 的 `latest*.json`。APK 继续通过原有 release.yml 签名、双下载路径验证、最后更新清单。

## 本轮修复

- DSH 首页认证使用 token -> 303 -> Cookie。原生存活探测现在在同一 localhost origin 内交换 Cookie，避免将可用服务误判成死亡而重复启动。跳转次数、正文大小、超时均有限制，不向其他主机/端口发送凭据。
- APK 内置运行包摘要改变时，首次启动立即进入更新流程，避免先运行旧内核、再等下次启动。手动回滚暂缓仍优先保留。
- 新 manifest 使用实际展开占用 `unpacked_size`（含 4KiB 文件块和目录开销）。DSH 片压缩约 84MiB、展开分配约 491MiB，全量五片压缩约 118.5MiB；原来的三倍估算明显不足。空间预检还包含下载和回滚快照开销；旧清单兼容保留三倍估算。
- payload-v9 的 flock 垫片会在任意平台原生模块加载失败时静默取消锁。v10 只在 Android 使用已有降级；Linux/桌面保留真实文件锁和错误传播。Android no-op 本身不提供跨进程互斥，不得把受控启动描述为文件锁已经有效。
- 会话发布保留硬链接优先的独占语义；无硬链接文件系统使用 COPYFILE_EXCL，拒绝覆盖已有历史，磁盘满等非兼容性错误继续上报。不沿用旧版的非独占 rename 降级。
- CI 允许源码领先已发布版本，发布后的严格校验仍存在。旧测试通道清单保持其自身 APK 对应的 payload，不被新 stable 源码误判。
- 签名参考自动筛选 bootstrap APK tag，避免新 payload Release 抢占最新发布记录导致签名核对失败。

## 验证及边界

纯 Java/JS 全部测试通过；新增本地 HTTP 认证探测和发布元数据回归。使用 App 自带 unpack.js 成功展开真实 v10 归档，再由其实际适配器验证模型目录、视觉能力、固定快照 92 个 Command Code 模型与两个 DeepSeek 直连项、离线 SDK 请求体及其他无效档位拒绝。新增字面量 max 属于请求扩展；真实聊天框组件验证两种语言共 188 组选择，并不证明服务商实际增加预算。

`core-runtime-consumer.mjs` 验证实际会话创建、刷盘、关闭、重开与恢复写入，独占发布/FUSE 降级/磁盘错误，Linux 真实锁竞争，模拟 Android 平台锁入口，以及无凭据的完整 Web profile 启动、token/Cookie 交换和前端响应。所有模型请求只离线捕获，不使用付费服务。

容器回归不能证明手机端真实终端 ELF 加载、布局和图片传输。安装后重点检查：首次更新只有 DSH 一片；已有会话可打开；选择模型后档位正确；发图/终端可用；关闭再打开不重复启动；更新失败时旧环境仍可启动。

## 现行导航

- [架构与兼容层](ARCHITECTURE.md)
- [构建与发布](BUILD.md)
- [逐模型声明与全模型 max](MODEL_REASONING.md)
- [当前进度与验证矩阵](STATUS.md)
