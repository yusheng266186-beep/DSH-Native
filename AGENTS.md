# 给接手的 Agent：先读这一份

本项目是 **DeepSeek Harness（DSH）的 Android 原生客户端**。
它把 Node.js + DSH 完整地跑在 Android 上，**不依赖 Termux、不依赖 proot**。

如果你是被派来接手的 AI agent，这份文件是入口。读完它再动代码。

> **本文件的版本号、数字、结论可能已经过期。**
> **当前真实状态以 `docs/HANDOVER.md` 第八节为准**（2026-09 迭代记录：
> 构建与发布已迁到 GitHub Actions、签名事故与防护、新增红线、已知陷阱、未验证事项）。

---

## 一、这个项目在做什么

DSH 本身是一个 Node.js 写的 CLI/Web 应用。要在 Android 上跑它，
常规做法是装 Termux 再装 Node —— 但那样用户得先装一个终端模拟器，
体验割裂。

本项目换了一条路：**自己带一个 Node 运行时，直接以 Android 应用的形式跑起来**。

```
用户安装的 APK（34MB）
  ├── Node 运行时（47MB 解压后）+ 它需要的 10 个 .so
  ├── ICU 数据（31MB，Node 的 Intl 依赖它，删不掉）
  └── 一堆引导脚本（unpack.js / preflight.js / sharp-android.js ...）

首次启动时下载「运行包」（54MB，分 5 片）
  ├── dsh.tar.zst         DSH 本体（node_modules）
  └── tools-*.tar.zst     工具链（git / python / npm / curl ...）

解压后 ≈ 316MB，用户配置在 <root>/.dsh，与运行包分开存放
```

**为什么要分两段？** APK 里塞不下完整的工具链（会变成 300MB+ 的安装包）。
分两段后，APK 保持 34MB，工具链按需下载，且能增量更新。

---

## 二、红线：这些事不能做

以下每一条都是踩过坑之后定下来的，改动前先读 `docs/GOTCHAS.md` 里的原因。

| 红线 | 原因 |
|---|---|
| **源码、脚本、注释里不能出现 emoji** | 用户明确要求过。构建脚本里有检查，违反了会构建失败。 |
| **纯逻辑层不能 import `android.` / `androidx.`** | `FileListing` `TextCodec` `Version` `CommandCodeUsage` `TaskNotifier` `FileOps` `ConfigBackup` `ShareTargets` `PluginSpecs` `PayloadUpdate` `SessionStatus` 要在普通 JVM 上跑测试。构建脚本第 3.45 步会检查。 |
| **新功能必须用原生 UI，不能用 `AlertDialog.Builder`** | 构建脚本第 3.5 步会检查。统一走 `DshUi`。 |
| **不能提交 APK 到仓库** | 仓库历史已经 2.5GB（88 次提交各带一个 34MB 的 APK）。APK 由 GitHub Releases 提供，App 也从 Releases 下载。`.gitignore` 已加。 |
| **发布必须用 `scripts/release.sh`，不能手工写 latest.json** | 曾经因为脚本语法错误跳过了 `gh release create` 却写了清单，导致所有客户端更新失败。`release.sh` 会先验证 release 资产与两条下载路径，**最后**才写清单。 |
| **`--patch` 必须写在 `--profile` 之前** | DSH 的命令行解析要求。 |
| **不能启用 `dsh-mcp-client`** | 实测：没有配置任何 server 时它会让 DSH 整个启动失败。代码里有注释说明。 |
| **改动后必须跑 `scripts/run_tests.sh`** | 纯逻辑断言，构建期强制执行。 |
| **不要换签名密钥** | 换签名 = 所有已安装用户必须卸载重装 = 丢会话与 API 密钥。仓库里那把曾长期是 PoC 时代的旧密钥（`OU=POC`），而实际发布用的是另一把 —— 结果用户更新时被系统拒绝「安装失败(-7)」。现在 `release.yml` 发版前会强制核对，`build_bootstrap.sh` 也不会再默默生成新密钥。详见 HANDOVER 8.2。 |
| **任何会触发 `recreate()` / Activity 生命周期的逻辑，必须带防振荡三件套**（去抖复核 + 跨重建限流（计数存 prefs）+ 超限硬停止） | 深色模式曾按"每收到一次上报就重建"实现 → 无限重建，用户侧"深浅反复横跳、不停重启"，**卸载重装也无效**。当时加的限流器还是实例字段，每次重建都归零。详见 HANDOVER 8.3。 |
| **XML 注释里不能出现连续两个减号** | 写 `--dsw-alias-...` 进注释 → aapt2 报 not well-formed，构建失败。 |
| **颜色必须走 `DshUi` 的方法，不能是常量** | `public static final int` 会被 javac **内联**到每处调用点，运行时改它对已编译代码无效。 |
| **主题跟随 DSH 网页，不是 Android 系统** | DSH 有自己的 `ui-theme.preference`，与系统深色相互独立；只跟系统会"网页黑、原生白"。 |
| **注入脚本必须每次页面加载都执行** | 任何 reload 都会清空页面脚本；"只注入一次"会让状态看板、任务通知、接口诊断静默失效。 |
| **多会话/多 agent 并行改这个仓库时，动手前先 `git fetch`** | 曾因本地克隆落后 5 个提交而误读状态。 |
| **发布前必须拿到子任务的明确交回** | 不能拿"文件 N 秒没变"当完成信号 —— 曾据此发布，而任务仍在写。 |

---

## 三、常用命令

**注意：本机（Android App 沙箱）没有 JDK/aapt2/d8/apksigner，编译只能在 CI 上做。**
推送后 `build.yml` 会自动构建；发版手动跑 `release.yml`（先跑一次 `dry_run` 更稳）。

```bash
# 本地静态核验（无 JDK 时能做的全部检查：语法、跨类引用、资源 XML、三道闸门）
python3 scripts/check_java.py

# 核对签名密钥与已发布版本是否一致（换密钥会让所有用户装不上）
bash scripts/check_signer.sh <APK 路径或下载 URL>

# 跑测试（纯逻辑层，普通 JVM）
bash scripts/run_tests.sh

# 完整构建（测试 → 架构约束 → UI 规范 → 编译 → d8 → 打包 → 签名）
bash scripts/build_bootstrap.sh

# 发布（上传 → 验证资产 → 轮询两条下载路径 → 最后写 latest.json）
bash scripts/release.sh <版本号> <构建目录> <发布说明.md>

# 重建运行包分片（改动了 tools 内容时）
DSH_TOOLS_DIR=<工具链目录> DSH_PAYLOAD_OUT=<输出目录> python3 scripts/make_payload_parts.py
```

**构建环境的依赖**（见 `docs/BUILD.md`）：aapt2、d8、apksigner 需要从 Termux 环境取，
且 aapt2 是 bionic 二进制，必须用 Termux 的 linker。`build_bootstrap.sh` 里已经处理好。

---

## 四、代码结构

```
bootstrap/src/dev/dsh/nativeapp/
├── MainActivity.java      3823 行 —— 启动流程、补丁、更新、设置页
│
│  ── 纯逻辑层（无 Android 依赖，有测试）──
├── FileListing.java       目录列举、排序、图标类型判定
├── TextCodec.java         编码探测（BOM / UTF-8 / GB18030）、换行符
├── Version.java           版本号比较（带溢出保护）
├── CommandCodeUsage.java  Command Code 订阅余额的解析与格式化
├── TaskNotifier.java      后台任务完成通知的判定
├── FileOps.java           文件增删改（写入白名单 + 符号链接防护）
├── ConfigBackup.java      配置备份（zip 打包 + zip-slip 防护）
├── ShareTargets.java      分享路径编解码 + MIME 映射
├── PluginSpecs.java       插件规格校验（命令注入防护）+ patch YAML 生成
├── PayloadUpdate.java     运行包更新决策（分片修订号 + 删除清单）
├── SessionStatus.java     通知栏状态看板的判定（状态优先级、文案、渠道）
│
│  ── UI 层 ──
├── DshUi.java             设计系统：颜色、卡片、按钮、对话框、通知渠道
├── FileBrowser.java       文件浏览（含文件管理、分享）
├── TextEditor.java        文本编辑（编码探测、原子保存）
├── LogViewer.java         日志查看（会话分段、级别着色、搜索）
├── CommandCodePanel.java  订阅余额面板
├── NetworkDiag.java       网络诊断
├── ConfigBackupPanel.java 配置备份面板
├── PluginPanel.java       插件管理面板
├── FileIconView.java      Canvas 绘制的文件图标（零资源）
├── UpdateProvider.java    ContentProvider（安装包 + 文件分享）
└── HarnessService.java    前台服务（常驻通知）
```

**为什么要有纯逻辑层？** 见 `docs/ARCHITECTURE.md` 第 4 节。
一句话：Android UI 没法在容器里跑测试，但判断逻辑可以。
把易错的判断（路径校验、版本比较、状态机）抽出来单独测，
是这个项目能在没有真机的情况下迭代 20 多个版本的前提。

---

## 五、收尾工作与已知问题

**详见 `docs/HANDOVER.md`**，这里只列最要紧的三条：

1. **真机布局自检数据从未采集过** —— 代码里有一套布局自检
   （FileBrowser 的 `reportLayout`），但需要用户在手机上操作才能触发。
   到目前为止没有任何一次真机验证记录。

2. **运行包里的 `@img/colour` 还在**（96KB）—— 它和已删除的那 27MB 是同一批
   （sharp 的依赖），但体积小，保守起见留着了。如果要清，
   走 `DSH_REMOVE` 机制，不要直接删。

3. **状态看板依赖 DSH 的界面文案** —— 判据取自 DSH 客户端插件的 locale
   字典（停止生成 / 发送消息 / 等待审批）。上游改了这几个词，状态会退化成
   「未知」（而不是报错的状态）。排查时看日志里的「状态看板」相关行。

4. **`latest.json` 的 `payload` 字段是信息性的** —— App 实际使用的运行包标签
   硬编码在 `MainActivity.java` 里，`release.sh` 现在会从源码推导，
   避免两者不一致（曾经不一致过）。

---

## 六、给接手者的建议

这个项目的难点**不在写代码**，而在两件事：

1. **验证**。Android 应用没法在容器里跑起来，很多改动只能靠
   「纯逻辑层的测试 + 对真实文件的检查 + 对真实服务的请求」来间接验证。
   本仓库的 `docs/GOTCHAS.md` 记录了大量「看起来对但实际错」的例子。

2. **静默失败**。项目里修过的 bug 大多是同一类：不崩溃、不报错、
   功能悄悄不工作（通知丢失、更新检测不到、只想删文件的更新永远不生效）。
   审计代码时，**重点看每个 `catch` 吞掉了什么**。

改动前先看 `docs/GOTCHAS.md`，能省下大量重复踩坑的时间。

**另外强烈建议先读 `docs/HANDOVER.md` 第八节**——那里记着这几轮迭代新增的
红线、已知陷阱与**未验证事项清单**。最后一项尤其重要：有几批改动只验证到
"能编译"，没有做过真机行为验证，别把它们当成已经可用。
