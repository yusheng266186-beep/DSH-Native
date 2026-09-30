# 模型思考强度与 Max 请求

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.32.3**；源码：**0.32.3**；源码运行包：`payload-v10`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 当前规则

所有模型都提供字面量 `max` 请求选项。原生模型中心与聊天框使用同一生成规则，聊天框显示 **Max（请求）/ Max (request)**。这项用户要求的扩展自 App 0.32.1 起实现。

“官方声明支持哪些档位”和“App 可以提交哪些选项”分别记录。`ModelReasoning.supportedDeclaration` 保留能力来源；`declaration` 在原声明之外加 `max: max`，即使原 max 映射为其他别名，也按字面量 max 请求。

没有可调声明、非推理或未知新模型，提供 `off: null` 的服务商默认路径与 max。默认路径不发送 reasoning 参数；不能把它解释为该模型保证可以关闭自动推理。

服务商可能执行、忽略或拒绝 max。客户端不承诺更大的预算、不偷偷降级为 high，也不因 max 可选就标注“官方已支持”。

## 模型来源与能力优先级

1. 可见模型 ID 仅来自本次成功上游目录响应，本地表不添加或删除 ID。
2. 能力以明确上游声明优先，再采用服务商固定官方客户端快照，最后参考已有自定义声明。
3. 官方支持声明完成后，再追加全模型 max 请求扩展。
4. 未知新 ID 允许选择；未知视觉能力不猜测为图片支持。

识别 `effort.supported_levels`、`reasoningEfforts` 列表/映射及禁用声明，保留来源注释、视觉、上下文/输出上限和兼容参数。刷新、离线目录修复与静态预设使用相同规则。

## Command Code 固定能力快照

以下表按仓库 fixture 自动生成，来源为 `command-code@1.72.4` 的 Provider API effort map 与 known-ID set，核对记录日期 2026-09-30。它不是直连 OpenAI/Anthropic 或 OAuth 路由的能力表，也不是对未来目录的实时保证。

- [固定 npm 版本](https://www.npmjs.com/package/command-code/v/1.72.4)
- [仓库快照](../tests/fixtures/command-code-reasoning-1.72.4.json)
- 官方客户端 `dist/cli.mjs` SHA-256：`d5f8f072276f5afea8dbe198be4f64963578f0134556cf36bb59f4bcd4bb99bc`

表中的 App 档位基于该固定快照；若实时 API 明确返回新的声明，按实时优先级处理，再追加 max。

<!-- dsh-model-table:start -->
| Command Code 模型 ID | 固定官方快照声明 | App 提供的选项 |
|---|---|---|
| `claude-sonnet-5-5` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-sonnet-5` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-sonnet-4-6` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-fable-5-1` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-fable-5` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-opus-5-5` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-opus-5` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-opus-4-8` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-opus-4-7` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `claude-haiku-4-5-20251001` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `gpt-6-astra` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-6.1-sol` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-6-sol` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-6-luna` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.6-sol` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.6-terra` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.6-luna` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.5` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `gpt-5.4` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `gpt-5.3-codex` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `gpt-5.4-mini` | low / medium / high | low / medium / high / max（请求） |
| `MiniMaxAI/MiniMax-M3-Free` | low / medium / high | low / medium / high / max（请求） |
| `moonshotai/Kimi-K3` | low / high / max | low / high / max（请求） |
| `thinkingmachines/inkling` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `thinkingmachines/inkling-small` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `deepseek/deepseek-v4-pro` | high / max | high / max（请求） |
| `deepseek/deepseek-v4-flash` | high / max | high / max（请求） |
| `deepseek/deepseek-v4-flash-vision-exp` | high / max | high / max（请求） |
| `deepseek/deepseek-v4-flash-fast` | low / high / max | low / high / max（请求） |
| `deepseek/deepseek-v4.1-flash` | low / high / max | low / high / max（请求） |
| `deepseek/deepseek-v4.1-flash-fast` | low / high / max | low / high / max（请求） |
| `moonshotai/Kimi-K2.7-Code` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `moonshotai/Kimi-K2.7-Code-Highspeed` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `moonshotai/Kimi-K2.6` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `moonshotai/Kimi-K2.5` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `zai-org/GLM-5.3` | low / high / max | low / high / max（请求） |
| `z-ai/glm-5.3-flash` | low / high / max | low / high / max（请求） |
| `z-ai/glm-5.3-flashx` | low / high / max | low / high / max（请求） |
| `zai-org/GLM-5.2` | high / max | high / max（请求） |
| `zai-org/GLM-5.2-Fast` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `zai-org/GLM-5.1` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `zai-org/GLM-5` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `MiniMaxAI/MiniMax-M3` | low / medium / high | low / medium / high / max（请求） |
| `MiniMaxAI/MiniMax-M2.7` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `minimax/minimax-m3-free` | low / medium / high | low / medium / high / max（请求） |
| `minimax/minimax-m2.7-free` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `MiniMaxAI/MiniMax-M2.5` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `xiaomi/mimo-v2.6-pro` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `xiaomi/mimo-v2.6-pro-ultraspeed` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `xiaomi/mimo-v2.6-flash` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `xiaomi/mimo-v2.5-pro` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `xiaomi/mimo-v2.5` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `Qwen/Qwen3.6-Max-Preview` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `Qwen/Qwen3.6-Plus` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `Qwen/Qwen3.7-Max` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `Qwen/Qwen3.7-Plus` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `Qwen/Qwen3.8-Omni-Flash` | low / medium / xhigh | low / medium / xhigh / max（请求） |
| `Qwen/Qwen3.8-Max-0902` | low / medium / xhigh | low / medium / xhigh / max（请求） |
| `Qwen/Qwen3.8-Max` | low / medium / xhigh | low / medium / xhigh / max（请求） |
| `Qwen/Qwen3.8-27B` | low / medium / xhigh | low / medium / xhigh / max（请求） |
| `Qwen/Qwen3.8-Flash` | low / medium / xhigh | low / medium / xhigh / max（请求） |
| `Qwen/Qwen3.7-Flash` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `meituan/LongCat-2.0` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `meituan/LongCat-2.0:free` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `stepfun/Step-5-Preview` | low / medium / high | low / medium / high / max（请求） |
| `stepfun/Step-3.7-Flash` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `stepfun/Step-3.5-Flash` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `tencent/hy4-preview` | low / medium / high | low / medium / high / max（请求） |
| `tencent/hy3-paid` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `tencent/Hy3` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `google/gemini-3.8-flash` | low / medium / high | low / medium / high / max（请求） |
| `google/gemini-3.7-flash` | low / medium / high | low / medium / high / max（请求） |
| `google/gemini-3.6-flash` | low / medium / high | low / medium / high / max（请求） |
| `google/gemini-3.5-flash` | low / medium / high | low / medium / high / max（请求） |
| `google/gemini-3.5-flash-lite` | low / medium / high | low / medium / high / max（请求） |
| `google/gemini-3.1-flash-lite` | low / medium / high | low / medium / high / max（请求） |
| `sakana/fugu-ultra` | high / xhigh | high / xhigh / max（请求） |
| `xai/grok-4.5` | low / medium / high | low / medium / high / max（请求） |
| `xai/grok-4.6` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `xai/grok-4.7` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `meta/muse-spark-1.1` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `meta/muse-spark-1.2` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `meta/muse-spark-1.2-contributor` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `meta/muse-spark-1.3` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `meta/muse-spark-1.3-contributor` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `nvidia/nemotron-3-ultra-550b-a55b` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `poolside/laguna-s-2.1-free` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `inclusionai/ling-3.0-flash-free` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `inclusionai/ling-3.0-flash-sante:free` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `inclusionai/ling-3.1-flash:free` | low / medium / high | low / medium / high / max（请求） |
| `stealth/space-bunny-alpha` | low / medium / high | low / medium / high / max（请求） |
| `stealth/pixel-canary` | low / medium / xhigh | low / medium / xhigh / max（请求） |
<!-- dsh-model-table:end -->

### 太空兔子示例

`stealth/space-bunny-alpha` 的固定官方快照是 low / medium / high。App 当前保留这三档，并增加 max（请求）；上游是否实际接受 max 尚未通过付费在线请求验证。免费标识、配额和模型可见性以账户目录与服务商规则为准。

## DeepSeek 官方直连

`deepseek-flash` 与 `deepseek-v4-pro` 的当前原生适配配置提供 `off / low / high / max`。官方 `/models` 的 `effort.supported_levels` 描述开启思考后的等级；off 由实际 `dsh-llm-deepseek` 适配器路径处理。

来源为固定 payload 中的适配器与 [DeepSeek 模型目录文档](https://api-docs.deepseek.com/zh-cn/api/list-models/)。不要将这两项直连声明套用到 Command Code 下的同名或前缀模型。

## 升级、保存与会话

- 升级修复已保存 live catalog，保留 ID、视觉、token 与兼容字段，不恢复旧预设覆盖它。
- 全局/项目默认等级重新核对有效选择；旧值无效时按现有归一化顺序选择 high、medium 或可用首档。
- 无声明模型保留服务商默认；新增 max 不改写已有历史。
- 默认配置用于新会话；已有会话可在聊天框显式更改后续请求强度，已发送请求不受影响。
- `ModelEffortUi` 修改实际运行包 client 模块，有锚点且幂等；结构不匹配时不写入并留诊断。
- 模型目录写入后，空闲才重载 runtime 应用；运行或未知时延后，不中断任务。

## 回归与证据边界

`check_model_consumer.sh` 消费真实 payload，验证 Host `buildModelCatalog`、目录投影、LLM `resolveCallConfig` 与离线 SDK 请求体。固定快照的 92 个 Command Code ID 加两个 DeepSeek 直连项用于聊天框组件回归，两种语言共 188 组选择，确认原模型/会话与字面量 max 进入真实选择 RPC。

默认路径验证省略参数，强制 max 验证请求字面量；其他无效等级仍按配置拒绝。真实 React 组件测试不同于只检查适配器元数据或原生按钮。

没有使用付费模型提示词。这些结果不证明服务商的真实思考预算、未来新增模型档位，也不代替 Android 触摸布局验收。统一验证矩阵见 [STATUS](STATUS.md)。
