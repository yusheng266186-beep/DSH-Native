# 文档中心

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.0**；源码：**0.33.1**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

先看 [项目状态与验证边界](STATUS.md)，再选择用户指南或技术文档。各文件顶部自动显示稳定发布、源码、运行包和固定内核；当前说明与历史记录使用不同标记。

## 使用与开发入口

| 文档 | 读者与内容 |
|---|---|
| [中文 README](../README.md) / [English README](../README.en.md) | 下载、初始化、工具入口、模型、文件、更新与权限边界 |
| [STATUS](STATUS.md) | 唯一当前进度入口，发布与源码事实、完成范围、验证证据与未验证事项 |
| [AGENTS](../AGENTS.md) | 开发前必读，数据保护、签名、UI、配置与发布红线 |
| [HANDOVER](HANDOVER.md) | 接手步骤、关键类、当前行为与事故防护 |
| [ARCHITECTURE](ARCHITECTURE.md) | 分发、执行、配置、模型目录、探针、恢复与安全边界 |
| [BUILD](BUILD.md) | Linux / CI 构建、测试、签名、正式发布与问题定位 |
| [DESIGN](DESIGN.md) | DshUi、主题、布局、动效、异步生命周期和返回导航规范 |
| [MODEL_REASONING](MODEL_REASONING.md) | 逐模型官方快照与 App 档位、Max 请求、优先级与回归 |
| [CORE_UPGRADE](CORE_UPGRADE.md) | 固定 DSH、payload-v10、构建发布和 Android 兼容差异 |
| [GOTCHAS](GOTCHAS.md) | 历史事故及现行防护，避免静默失败和重复踩坑 |
| [scripts/README](../scripts/README.md) | 脚本接口、用途、环境和文档同步机制 |
| [发布检查清单](../scripts/release_checklist.md) | 正式/测试通道、签名、资产与清单顺序 |
| [tools/README](../tools/README.md) | 旧设备调试工具的适用范围与非破坏性调试方式 |

## Android 兼容层

| 文档 | 当前定位 |
|---|---|
| [Node 内部模块垫片](../patch/01-require-builtin-shim/README.md) | 仍需要；当前运行包构建保留，启动带 `--expose-internals` |
| [旧会话 rename 补丁](../patch/02-session-link-to-rename/README.md) | 已归档；当前硬链接优先、COPYFILE_EXCL 降级，禁止套用旧补丁 |
| [Android flock](../patch/03-flock-android/README.md) | Android 降级与桌面真实锁分开处理，不能声称降级提供互斥 |

## 阶段历史

以下记录保留当时计划、测试和验收步骤。相应实现已进入主分支；当时“不发布”或“候选”的文字不是新的发布指令。当前行为有变化时以现行文档为准。

| 阶段 | 记录 |
|---|---|
| 一：加固 | [PHASE1-HARDENING](PHASE1-HARDENING.md) |
| 二：体验与状态 | [PHASE2-EXPERIENCE](PHASE2-EXPERIENCE.md)、[PHASE2-STATUS](PHASE2-STATUS.md) |
| 三：产品化 | [PHASE3-PRODUCT](PHASE3-PRODUCT.md) |
| 四 A：工具入口 | [PHASE4A-WEBUI-TOOLS](PHASE4A-WEBUI-TOOLS.md) |
| 四 B：体验 | [PHASE4B-EXPERIENCE](PHASE4B-EXPERIENCE.md)；用户于 2026-09-27 确认该清单验收 |
| 四 C：交互 | [PHASE4C-INTERACTIONS](PHASE4C-INTERACTIONS.md) |
| 四 D：发布 | [PHASE4D-RELEASE](PHASE4D-RELEASE.md) |
| 四 E：状态与设置修复 | [PHASE4E-STATUS-SETTINGS-HOTFIX](PHASE4E-STATUS-SETTINGS-HOTFIX.md) |
| 四 F：动效 | [PHASE4F-MOTION-INTERACTION](PHASE4F-MOTION-INTERACTION.md) |
| 四 G：发布加固 | [PHASE4G-RELEASE-HARDENING](PHASE4G-RELEASE-HARDENING.md) |
| 五 A：任务恢复 | [PHASE5A-TASK-RECOVERY](PHASE5A-TASK-RECOVERY.md) |
| 五 B：文件与会话 | [PHASE5B-FILES-SESSIONS](PHASE5B-FILES-SESSIONS.md) |
| 五 C：模型与引导 | [PHASE5C-MODEL-ONBOARDING](PHASE5C-MODEL-ONBOARDING.md) |
| 五 D：回滚与诊断 | [PHASE5D-ROLLBACK-DIAGNOSTICS](PHASE5D-ROLLBACK-DIAGNOSTICS.md) |
| 五 E：目录与英文 | [PHASE5E-MODEL-CATALOG-I18N](PHASE5E-MODEL-CATALOG-I18N.md) |
| 五 F：目录写入运行时 | [PHASE5F-MODEL-RUNTIME-SYNC](PHASE5F-MODEL-RUNTIME-SYNC.md) |
| 五 G：刷新与运行时重载 | [PHASE5G-MODEL-WEBUI-REFRESH](PHASE5G-MODEL-WEBUI-REFRESH.md) |

## 研究、上游反馈与发布记录

- [文件浏览器研究](FILE-BROWSER-RESEARCH.md)：2026-09-22 的候选方案、许可证与活跃度快照，不能当作今天的重新核查。
- [上游问题英文记录](UPSTREAM-ISSUE.md) / [中文记录](UPSTREAM-ISSUE.zh.md)：历史复现、适配建议与讨论；不代表上游已合并全部修复。
- [版本说明目录](../release-notes/)：各版变更保持独立，payload 发布也独立记录。

## 文档如何保持同步

```bash
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

脚本同步全部项目 Markdown 的状态块、STATUS 发布事实表和模型固定快照表，并校验本地文件链接。正式发布还同步两份 README 的版本、下载地址、体积与 SHA-256。外部 URL 和历史研究结论不做自动刷新；需要更新研究时应重新核查并注明日期。

`<!-- dsh-doc-status:start -->` 等标记之间属于生成区。修改功能正文仍需人工核对源码、更新中英文和验证边界；自动同步不会替代技术审阅。
