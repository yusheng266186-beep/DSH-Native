# 开源组件与来源

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**1.1.2**；源码：**1.1.2**；源码运行包：`payload-v14`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

本仓库的 [MIT 许可](../LICENSE) 只覆盖相应原创代码。下列运行组件保持原许可；不能把整个 APK 和下载工具统一标成 MIT。

| 组件 | 固定来源/许可与说明 |
|---|---|
| DeepSeek Harness | `runtime/core-source.json` 固定 0.2.1-rc.3 与完整性值；MIT，Copyright 2026 DeepSeek。运行包保留其依赖原文件及许可；此版本仍是上游候选版 |
| CodeBuddy SDK/CLI | SDK 0.3.291 / CLI 2.176.0，按 `runtime/codebuddy/package-lock.json` 安装；包元数据标 MIT，CLI 原许可 Copyright 2023 Tencent Cloud 随包保留。只裁剪未使用文件，不改官方运行代码 |
| Node.js | APK 内 ARM64 Node 的嵌入版本字符串为 v26.4.0；实际二进制由 0.40.2 APK 继承并在 `runtime/compatibility.json` 固定大小/SHA。随 APK 增加 [Node v26.4.0 原许可及第三方声明](https://github.com/nodejs/node/blob/v26.4.0/LICENSE) |
| 引导共享库 | zlib、c-ares、SQLite、libffi、OpenSSL、ICU 和 libc++；实际 10 个文件同样固定摘要，分别保留相应上游许可，不能以 Node 主许可覆盖其全部条款 |
| 工具链 | payload-v13 的工具片继承已发布分片；git、bash、Python、curl、npm、Pillow 等保留各自许可。Termux 构建来源见 [termux-packages](https://github.com/termux/termux-packages)，不能把下载后工具当成本仓库原创 |
| 测试依赖 | Playwright、React、jsdom 等只用于构建/测试，不因出现在测试 lockfile 就被宣称进入 APK |

APK 的 `assets/payload/licenses/` 包含本仓库、DSH、CodeBuddy CLI 和 Node 原许可文本；SDK 归档还保留依赖自身的 LICENSE/NOTICE。`dependency-notices.json` 从两个实际 lockfile 生成，列明版本、包完整性、来源和已声明的许可；没有声明时保留 unknown，不伪造许可证。

Node 与共享库的核验绑定实际文件，而不只看版本文字。更新内核、SDK、引导二进制或 Android 目标版本时，必须重新核对来源和许可、更新兼容记录并跑消费回归，不能仅为通过检查改一处摘要。

这份清单记录可追溯来源和随包声明，不将元数据清单等同于对所有第三方源代码的独立法律审计。历史工具片未在本轮重新编译；其现有源包/构建来源与 GPL 等组件的对应源代码要求仍随组件许可适用。
