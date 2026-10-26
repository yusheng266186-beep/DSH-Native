# 模型思考强度与 Max 请求

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.40.0**；源码：**0.40.0**；源码运行包：`payload-v13`；固定 DSH：`0.2.1-rc.3`（上游候选版）。[统一进度与验证边界](STATUS.md)。
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

## CodeBuddy CLI 能力快照

0.39.0 的实际传输为官方 SDK 0.3.291 / CLI 2.176.0 的代理模式；下面保留的 2.174.0 资料只补充已知能力，不冒充当前账号的授权目录。max 通过 SDK effort 选项请求；CLI 与服务端的最终处理不由 App 保证。

模型和能力来自本机安装的官方 `@tencent-ai/codebuddy-code@2.174.0` 的 CLI 元数据，核对日期 2026-10-26。该服务没有可直接读取的通用 `/models` 路由；连接检查通过不等于账号有权调用表中每个模型。

- [公开元数据](../runtime/codebuddy-catalog.json) / [回归快照](../tests/fixtures/codebuddy-cli-catalog.json)
- [CodeBuddy 接入与加密导入](CODEBUDDY.md)
- [官方环境变量](https://www.codebuddy.ai/docs/cli/env-vars)

App 保留 CLI 明确声明的等级，并按用户既有要求追加字面量 max；没有公布可调等级时仍保留不传参数的默认选项。

<!-- dsh-model-table:start -->
| CodeBuddy 模型 ID | CLI 元数据声明 | App 提供的选项 |
|---|---|---|
| `default-model` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `fast-model` | medium | medium / max（请求） |
| `balanced-model` | medium | medium / max（请求） |
| `primary-model` | high | high / max（请求） |
| `deep-model` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |
| `deepseek-v4.1-flash` | high | high / max（请求） |
| `gpt-6-astra` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `hy4-preview` | high | high / max（请求） |
| `hy3` | low / high | low / high / max（请求） |
| `kimi-k2.8-preview` | low / high / max | low / high / max（请求） |
| `gpt-5.6-sol` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.6-terra` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.6-luna` | low / medium / high / xhigh / max | low / medium / high / xhigh / max（请求） |
| `gpt-5.5` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `gpt-5.4` | low / medium / high / xhigh | low / medium / high / xhigh / max（请求） |
| `gemini-3.5-flash` | medium | medium / max（请求） |
| `glm-5.3-flash` | low / high / max | low / high / max（请求） |
| `glm-5.3` | low / high / max | low / high / max（请求） |
| `glm-5.2` | high / xhigh | high / xhigh / max（请求） |
| `kimi-k3` | medium | medium / max（请求） |
| `kimi-k2.6` | medium | medium / max（请求） |
<!-- dsh-model-table:end -->

## DeepSeek 官方直连

`deepseek-flash` 与 `deepseek-v4-pro` 的当前原生适配配置提供 `off / low / high / max`。官方 `/models` 的 `effort.supported_levels` 描述开启思考后的等级；off 由实际 `dsh-llm-deepseek` 适配器路径处理。

来源为固定 payload 中的适配器与 [DeepSeek 模型目录文档](https://api-docs.deepseek.com/zh-cn/api/list-models/)。不要将这两项直连声明套用到 CodeBuddy 下的同名模型。

## 升级、保存与会话

- 升级修复已保存 live catalog，保留 ID、视觉、token 与兼容字段，不恢复旧预设覆盖它。
- 全局/项目默认等级重新核对有效选择；旧值无效时按现有归一化顺序选择 high、medium 或可用首档。
- 无声明模型保留服务商默认；新增 max 不改写已有历史。
- 默认配置用于新会话；已有会话可在聊天框显式更改后续请求强度，已发送请求不受影响。
- `ModelEffortUi` 修改实际运行包 client 模块，有锚点且幂等；结构不匹配时不写入并留诊断。
- 模型目录写入后，空闲才重载 runtime 应用；运行或未知时延后，不中断任务。

## 回归与证据边界

`check_model_consumer.sh` 消费真实 payload，验证 Host `buildModelCatalog`、目录投影、LLM `resolveCallConfig` 与离线 SDK 请求体。固定快照的 21 个 CodeBuddy ID 加两个 DeepSeek 直连项用于聊天框组件回归，两种语言共 46 组选择，确认原模型/会话与字面量 max 进入真实选择 RPC。

默认路径验证省略参数，强制 max 验证请求字面量；其他无效等级仍按配置拒绝。真实 React 组件测试不同于只检查适配器元数据或原生按钮。

自动回归只使用合成凭据和离线网络边界。CodeBuddy 的本机凭据另做过一次最小流式接入核对；它不证明所有模型或档位的实际权限。这些结果不证明服务商的真实思考预算、未来新增模型档位，也不代替 Android 触摸布局验收。统一验证矩阵见 [STATUS](STATUS.md)。
