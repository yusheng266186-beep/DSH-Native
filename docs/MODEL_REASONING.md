# 模型思考强度

Command Code 通道以官方 npm `command-code@1.72.4` 的 Provider API effort map 和 known-ID set 为来源，核对日期 2026-09-30。不是 OpenAI/Anthropic 直连或 OAuth 通道的等级。官方包：https://www.npmjs.com/package/command-code/v/1.72.4 。官方客户端 `dist/cli.mjs` SHA-256：`d5f8f072276f5afea8dbe198be4f64963578f0134556cf36bb59f4bcd4bb99bc`。

能力优先级：上游明确声明 > 该服务商官方能力表 > 用户现有自定义声明。能力表不增加或删除上游模型 ID。未知新模型仍可用，采用服务商默认；未公布可调等级的自动思考模型不添加假档位。Command Code 表中没有 Off 的模型，不显示关闭按钮。

| Command Code 模型 ID | 可选择思考强度 |
|---|---|
| `claude-sonnet-5-5` | low / medium / high / xhigh / max |
| `claude-sonnet-5` | low / medium / high / xhigh / max |
| `claude-sonnet-4-6` | low / medium / high / xhigh / max |
| `claude-fable-5-1` | low / medium / high / xhigh / max |
| `claude-fable-5` | low / medium / high / xhigh / max |
| `claude-opus-5-5` | low / medium / high / xhigh / max |
| `claude-opus-5` | low / medium / high / xhigh / max |
| `claude-opus-4-8` | low / medium / high / xhigh / max |
| `claude-opus-4-7` | low / medium / high / xhigh / max |
| `claude-haiku-4-5-20251001` | 服务商默认（没有可调档位） |
| `gpt-6-astra` | low / medium / high / xhigh / max |
| `gpt-6.1-sol` | low / medium / high / xhigh / max |
| `gpt-6-sol` | low / medium / high / xhigh / max |
| `gpt-6-luna` | low / medium / high / xhigh / max |
| `gpt-5.6-sol` | low / medium / high / xhigh / max |
| `gpt-5.6-terra` | low / medium / high / xhigh / max |
| `gpt-5.6-luna` | low / medium / high / xhigh / max |
| `gpt-5.5` | low / medium / high / xhigh |
| `gpt-5.4` | low / medium / high / xhigh |
| `gpt-5.3-codex` | low / medium / high / xhigh |
| `gpt-5.4-mini` | low / medium / high |
| `MiniMaxAI/MiniMax-M3-Free` | low / medium / high |
| `moonshotai/Kimi-K3` | low / high / max |
| `thinkingmachines/inkling` | 服务商默认（没有可调档位） |
| `thinkingmachines/inkling-small` | 服务商默认（没有可调档位） |
| `deepseek/deepseek-v4-pro` | high / max |
| `deepseek/deepseek-v4-flash` | high / max |
| `deepseek/deepseek-v4-flash-vision-exp` | high / max |
| `deepseek/deepseek-v4-flash-fast` | low / high / max |
| `deepseek/deepseek-v4.1-flash` | low / high / max |
| `deepseek/deepseek-v4.1-flash-fast` | low / high / max |
| `moonshotai/Kimi-K2.7-Code` | 服务商默认（没有可调档位） |
| `moonshotai/Kimi-K2.7-Code-Highspeed` | 服务商默认（没有可调档位） |
| `moonshotai/Kimi-K2.6` | 服务商默认（没有可调档位） |
| `moonshotai/Kimi-K2.5` | 服务商默认（没有可调档位） |
| `zai-org/GLM-5.3` | low / high / max |
| `z-ai/glm-5.3-flash` | low / high / max |
| `z-ai/glm-5.3-flashx` | low / high / max |
| `zai-org/GLM-5.2` | high / max |
| `zai-org/GLM-5.2-Fast` | 服务商默认（没有可调档位） |
| `zai-org/GLM-5.1` | 服务商默认（没有可调档位） |
| `zai-org/GLM-5` | 服务商默认（没有可调档位） |
| `MiniMaxAI/MiniMax-M3` | low / medium / high |
| `MiniMaxAI/MiniMax-M2.7` | 服务商默认（没有可调档位） |
| `minimax/minimax-m3-free` | low / medium / high |
| `minimax/minimax-m2.7-free` | 服务商默认（没有可调档位） |
| `MiniMaxAI/MiniMax-M2.5` | 服务商默认（没有可调档位） |
| `xiaomi/mimo-v2.6-pro` | 服务商默认（没有可调档位） |
| `xiaomi/mimo-v2.6-pro-ultraspeed` | 服务商默认（没有可调档位） |
| `xiaomi/mimo-v2.6-flash` | 服务商默认（没有可调档位） |
| `xiaomi/mimo-v2.5-pro` | 服务商默认（没有可调档位） |
| `xiaomi/mimo-v2.5` | 服务商默认（没有可调档位） |
| `Qwen/Qwen3.6-Max-Preview` | 服务商默认（没有可调档位） |
| `Qwen/Qwen3.6-Plus` | 服务商默认（没有可调档位） |
| `Qwen/Qwen3.7-Max` | 服务商默认（没有可调档位） |
| `Qwen/Qwen3.7-Plus` | 服务商默认（没有可调档位） |
| `Qwen/Qwen3.8-Omni-Flash` | low / medium / xhigh |
| `Qwen/Qwen3.8-Max-0902` | low / medium / xhigh |
| `Qwen/Qwen3.8-Max` | low / medium / xhigh |
| `Qwen/Qwen3.8-27B` | low / medium / xhigh |
| `Qwen/Qwen3.8-Flash` | low / medium / xhigh |
| `Qwen/Qwen3.7-Flash` | 服务商默认（没有可调档位） |
| `meituan/LongCat-2.0` | 服务商默认（没有可调档位） |
| `meituan/LongCat-2.0:free` | 服务商默认（没有可调档位） |
| `stepfun/Step-5-Preview` | low / medium / high |
| `stepfun/Step-3.7-Flash` | 服务商默认（没有可调档位） |
| `stepfun/Step-3.5-Flash` | 服务商默认（没有可调档位） |
| `tencent/hy4-preview` | low / medium / high |
| `tencent/hy3-paid` | 服务商默认（没有可调档位） |
| `tencent/Hy3` | 服务商默认（没有可调档位） |
| `google/gemini-3.8-flash` | low / medium / high |
| `google/gemini-3.7-flash` | low / medium / high |
| `google/gemini-3.6-flash` | low / medium / high |
| `google/gemini-3.5-flash` | low / medium / high |
| `google/gemini-3.5-flash-lite` | low / medium / high |
| `google/gemini-3.1-flash-lite` | low / medium / high |
| `sakana/fugu-ultra` | high / xhigh |
| `xai/grok-4.5` | low / medium / high |
| `xai/grok-4.6` | low / medium / high / xhigh |
| `xai/grok-4.7` | low / medium / high / xhigh |
| `meta/muse-spark-1.1` | low / medium / high / xhigh |
| `meta/muse-spark-1.2` | low / medium / high / xhigh |
| `meta/muse-spark-1.2-contributor` | low / medium / high / xhigh |
| `meta/muse-spark-1.3` | low / medium / high / xhigh / max |
| `meta/muse-spark-1.3-contributor` | low / medium / high / xhigh |
| `nvidia/nemotron-3-ultra-550b-a55b` | 服务商默认（没有可调档位） |
| `poolside/laguna-s-2.1-free` | 服务商默认（没有可调档位） |
| `inclusionai/ling-3.0-flash-free` | 服务商默认（没有可调档位） |
| `inclusionai/ling-3.0-flash-sante:free` | 服务商默认（没有可调档位） |
| `inclusionai/ling-3.1-flash:free` | low / medium / high |
| `stealth/space-bunny-alpha` | low / medium / high |
| `stealth/pixel-canary` | low / medium / xhigh |

DeepSeek 官方通道：`deepseek-flash`、`deepseek-v4-pro` 都提供 `off / low / high / max`。官方 `/models` 的 `effort.supported_levels` 只列思考开启后的档位；Off 由当前 DSH 官方适配器的关闭思考模式提供。来源：https://api-docs.deepseek.com/zh-cn/api/list-models/ ，以及 payload-v9 的 `dsh-llm-deepseek` 适配器。

升级会修复已保存的实时目录，保留模型 ID、视觉声明、上下文/输出上限和兼容参数；刷新重复执行不会删掉思考等级。旧默认等级不受模型支持时，使用受支持的 high，其次 medium，再其次第一档；没有档位时使用服务商默认。已有会话中保存的历史等级不重写，升级后应在会话模型选择器重新选择一个有效档位。
