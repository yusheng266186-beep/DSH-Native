# 阶段五 F：上游模型目录与 DSH WebUI 同步

<!-- dsh-doc-status:start -->
> 历史记录：下文日期、测试数字、候选状态与计划保留当时语境，不代表当前待办。 已发布 stable：**0.33.9**；源码：**0.33.10**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

## 归档状态与后续变更

本阶段对应实现已进入主分支。以下验收步骤、测试数量和发布限制保留当时版本语境；当前完成范围与未验证项目统一见 [STATUS](STATUS.md)。后续刷新按钮直接写入完整目录，空闲应用改为 runtime 重载；运行或未知延后。0.32.1 已追加全模型 max 请求。

## 原始阶段记录

本轮修复“原生模型中心选中了模型，但 WebUI 里没有对应模型或仍显示旧列表”的数据源分裂。目标版本为 `0.31.2`，仍沿用现有包名、签名、用户数据目录、payload-v9、minSdk 24、targetSdk 28 和覆盖升级路径。

## 根因

此前保存动作只改写 `agent-default-model` 和 `.native-project-models`：

- 原生模型中心的实时目录来自服务商 `/models`；
- Command Code 的 DSH WebUI 选择器读取 `llm-pi-ai.providers.commandcode.models`；
- DeepSeek 官方直连的 DSH provider 读取 `llm-deepseek-api-key.models`；
- 保存动作没有把上游返回的模型 ID写进 provider catalog；
- 下一次启动又会用 `settings-preset.yaml` 的静态 Command Code 列表覆盖运行时配置。

所以“默认模型字段已变化”和“WebUI 能否列出/解析该模型”会出现不一致。

## 实现

### 1. 保存时同步完整上游目录

`ModelCatalogSync.writeLiveCatalog(...)` 只接受当前成功读取的 `LiveModelCatalog.Entry`：

- Command Code 替换 `llm-pi-ai.providers.commandcode.models`；
- DeepSeek 官方直连创建或更新 `llm-deepseek-api-key.models`（兼容已有的 `llm-deepseek` 块）；
- 去重并过滤空/非法 ID；
- 保留本地已知的名称、图片和推理提示；上游只返回 ID 时写入安全的文本基础能力；
- 不写入密钥、Authorization、响应正文或任意用户输入的 Base URL；
- 使用 `# dsh-native-live-catalog: <provider>` 标记，便于升级时识别动态目录。

模型中心只有在实时目录成功且用户点击保存时才写入，空响应或失败不会清空上一份可用目录。项目覆盖和全局默认选择仍按原有规则写入；目录同步与选择写入在同一次保存流程中生成，避免“选择字段更新、目录没更新”的半完成状态。

### 2. 升级时保留动态目录

启动配置合并仍从预设读取 provider 的传输字段，但 `MainActivity.syncProviderConfig(...)` 会保留已标记的 live `models` 列表，不再把旧静态模型恢复到 WebUI。这样覆盖安装新 APK 不会把用户最近一次上游目录覆盖掉；没有 live 标记的旧配置仍按原流程补齐预设。

### 3. 保存后刷新 WebUI

保存完成后宿主刷新 WebView 模型选择器，使 DSH WebUI 重新读取目录。若当前有运行中/等待批准任务，则只写入 DSH 配置并延后刷新，避免丢失滚动位置和输入草稿；DSH 设置 seam 仍让后续请求使用新路由。

## 测试

`ModelCatalogSyncTest` 覆盖：

- Command Code 新模型写入、旧静态模型移除、图片/推理提示保留；
- DeepSeek provider block 创建、`inputModalities` 格式和目录解析；
- 重复上游 ID 去重与重复保存幂等；
- 静态预设合并时保留 live 标记和动态模型，不恢复 bundled 模型；
- `ModelConfig.modelsForProvider(...)` 能读取同步后的官方直连目录。

完整验证命令：

```bash
bash scripts/run_tests.sh
python3 scripts/check_java.py
```

Android CI 还必须完成 javac、DEX、aapt2、APK 清单、签名和 APK 产物校验；正式发布只通过 `.github/workflows/release.yml`，不手工改 `latest.json`。

## 真机验收

1. 从 `0.31.1` 正式版覆盖安装 `0.31.2`，确认会话、密钥、项目和任务历史仍在。
2. 打开“App 工具 → 模型中心”，填写有效的 Command Code key，刷新模型列表并选择一个当前静态预设没有的模型。
3. 点击保存后进入 DSH WebUI 的模型选择器，确认新模型出现在对应 provider 下，且默认选择显示同一个 ID；新建会话发送一条最小文本消息。
4. 返回模型中心切换到 DeepSeek direct，用有效 key 刷新并选择上游返回的新模型；重复第 3 步。
5. 覆盖安装/重启 App，再次打开 WebUI 模型选择器，确认动态目录没有被静态预设恢复覆盖。
6. 断网打开模型中心，确认失败时不会清空已保存目录；已保存模型可查看，切换到新模型仍要求成功实时读取。
7. 任务运行期间保存模型，确认任务不中断；任务结束后重新打开 WebUI，确认新目录可见。
8. 检查运行日志和诊断导出，不得出现 API key、Authorization 或模型目录响应正文。

## 风险与边界

- `/models` 通常只提供 ID，未知能力按文本基础能力处理；这不会阻止模型选择，但不能凭空宣称图片能力。
- 上游若返回服务商暂时不能生成的模型，DSH 仍会返回服务商自己的错误；App 不会为绕过该错误而发送测试提示词或修改任意端点。
- 目录写入只覆盖受支持的两个官方 provider，保留 API key 的环境变量引用；不新增高风险 `JavascriptInterface`。
- 不直接合并 main、不迁移签名、不改变 targetSdk、不清理用户数据；通过 CI 和验收后才由 release workflow 发布正式版。
