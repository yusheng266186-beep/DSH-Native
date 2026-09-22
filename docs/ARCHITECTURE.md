# 架构与设计

## 1. 整体形状：为什么是两段式

要在 Android 上跑 DSH，直觉做法是把所有东西塞进 APK。但完整的工具链
（git、python、curl、npm…）解压后是 134MB，加上 DSH 本体 182MB ——
安装包会超过 300MB，且**任何一处改动都要重下整个包**。

所以拆成两段：

```
┌─────────────────────────────────────────────────────────────┐
│ 第一段：APK（34MB，装机即用）                                  │
│                                                              │
│   assets/payload/                                            │
│     node                      47MB   Node 运行时 v26.4.0      │
│     lib/*.so（10 个）         15MB   它的依赖                  │
│     lib/libicudata.so.78      31MB   ICU 数据（Intl 依赖）     │
│     unpack.js                 3.5KB  解压器（自己写的）         │
│     preflight.js                    环境自检                   │
│     sharp-android.js                图片处理（Pillow 实现）     │
│     pillow_shim.py                   ↑ 的 Python 侧            │
│                                                              │
│   启动时解压到 <root>/，node 由此可用                          │
└─────────────────────────────────────────────────────────────┘
                            ↓ 首次启动
┌─────────────────────────────────────────────────────────────┐
│ 第二段：运行包（54MB，分 5 片，从 GitHub Releases 下载）        │
│                                                              │
│   dsh.tar.zst          19.5MB   DSH 本体 → <root>/dsh         │
│   tools-base.tar.zst   13.7MB   工具链基础 → <root>/tools      │
│   tools-libs.tar.zst   11.9MB   .so 库                       │
│   tools-python.tar.zst  6.5MB   Python 3.14 + site-packages  │
│   tools-npm.tar.zst     2.2MB   npm                          │
│                                                              │
│   解压后：dsh 182MB + tools 134MB = 316MB                     │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ 用户数据（永不随更新动）                                       │
│   <root>/.dsh/          配置、凭据、会话、profiles             │
│   <root>/.dsh/profiles/web/   用户层（插件装在这里）           │
└─────────────────────────────────────────────────────────────┘
```

**关键设计**：运行包与用户数据**分开存放**。任何更新都不会碰到
`.dsh/`，所以配置和会话不会因为升级而丢失。这一条在
`PayloadUpdate` 的路径校验里还有第二道保险（禁止删除 `.dsh`）。

---

## 2. 启动流程

`MainActivity.onCreate` 之后跑在一条后台线程上（`runStartup`）：

```
1. 解压 APK 内置负载 → extractAssets(root)
   └─ 按 APK 版本号判断是否刷新；版本变了也只重写**内容真的变了**的文件
      （脚本 204KB 总是刷新；node/ICU 等 92.8MB 比对大小+摘要，一致则跳过）
   └─ 这一步决定了「升级后首次启动」是几十秒还是瞬间

2. 自检 Node 可执行性 → probeNodeExec(node)
   └─ 前提是整条架构成立：Android 10+ 对 targetSdk>=29 禁止 exec 私有目录文件
      本项目 targetSdk=28 正是为了绕开这一条

3. 准备运行包 → 见第 3 节

4. 应用 Android 专项补丁 → applyAndroidPatches()
   ├─ patchFrontendViewport()    改前端 index.html 的 viewport
   ├─ patchAttachmentDurability() 让附件落在可用位置
   └─ 替换 sharp 为 Pillow 实现

5. 环境自检 → runPreflight()     纯本地检查，无网络

6. 准备配置 → prepareConfig()    首次写入默认 settings

7. 找空闲端口 → findFreePort(3080, 3280)

8. 启动 dsh web
   node --expose-internals --no-warnings lib/bin.js
        --patch <生成的插件覆盖层>
        --profile web --no-open --port <端口>

9. 载入 http://127.0.0.1:<端口>/?token=…
```

---

## 3. 运行包更新机制

这是整个项目最需要小心的地方。演进过三代，每一代都是被真实问题逼出来的。

### 第一代：整体版本号

```
清单里一个 version → 版本不同就重下整个包
```
**问题**：只给工具链加个 npm，也要重下 31MB。

### 第二代：哨兵文件

```
每个分片带一个「哨兵」文件（路径 + 大小 + sha256）
启动时比对哨兵 → 不一致才下载该分片
```
**问题**：**只删文件的更新永远检测不到**。
删掉一批文件后，其余文件的哨兵全部不变 → 判定「已是最新」→
那些文件永远留在设备上。

> 实例：运行包里有 27MB 在 Android 上根本加载不了的原生库
> （sharp 的各平台实现，而 App 用 Pillow 整体替换了 sharp）。
> 想删掉它们，靠哨兵是发现不了的。

### 第三代：分片修订号 + 删除清单（当前）

```json
{
  "name": "dsh.tar.zst",
  "revision": 2,                    // 内容有实质变化（含只删）时递增
  "remove": [                       // 处理前要删掉的相对路径
    "node_modules/@img/sharp-linux-arm64",
    "node_modules/@img/sharp-libvips-linux-arm64",
    "node_modules/@img/sharp-wasm32"
  ],
  "sentinel": { "path": "lib/bin.js", "size": 10974, "sha256": "..." }
}
```

判定规则（`PayloadUpdate`，37 项测试）：

| 哨兵 | 修订号 | 动作 |
|---|---|---|
| 匹配 | 相同 | 不动 |
| 不匹配 | — | 下载 + 解压该分片 |
| 匹配 | **变高** | 下载 + 执行 `remove` + 解压 |

**为什么是「按分片」而不是「整包重来」**：整包重来虽然正确，
但代价是删 27MB 却要用户重下 60MB。按分片后，
这次只重下了 dsh 那一片（19.5MB），另外 4 片完全不动。

**安全**：`remove` 里的路径来自网络上的清单，必须当作不可信输入。
`isSafeRelativePath()` 拒绝绝对路径、`..`、反斜杠、`.dsh`、`cache`。
17 种非法路径有测试覆盖。理由很直接：一个被篡改的清单，
靠一个 `../../.dsh` 就能删掉用户的配置和密钥。

### 修订号只在全部成功后记录

如果下载中途失败（校验不过、解压出错）却已经记下了修订号，
下次启动会误判为「已应用」，那些该删的文件就永远补不回来了。

---

## 4. 纯逻辑层：为什么值得单独抽出来

### 问题

Android UI 代码没法在容器里跑测试。整个开发过程是在一台设备上的
proot Debian 里完成的，没有模拟器、没有真机调试回路。
如果所有逻辑都写在 Activity 里，就只能靠「编译通过」来判断对错 ——
而编译通过和逻辑正确是两件事。

### 做法

把**易错的判断**抽成不依赖 Android 的类，用普通 JVM 测试：

| 类 | 抽出来的判断 | 测试数 |
|---|---|---|
| `FileListing` | 目录列举、排序、断链判定 | 42 |
| `TextCodec` | 编码探测、换行符、二进制判定 | 31 |
| `Version` | 版本号比较（含溢出饱和） | 23 |
| `CommandCodeUsage` | 余额解析与格式化 | 38 |
| `TaskNotifier` | 何时该发完成通知 | 33 |
| `FileOps` | 写入白名单、符号链接逃逸、名称校验 | 47 |
| `ConfigBackup` | zip-slip 防护、白名单进出 | 29 |
| `ShareTargets` | 路径编解码往返、MIME 映射 | 45 |
| `PluginSpecs` | 命令注入防护、YAML 生成 | 98 |
| `PayloadUpdate` | 分片更新决策、删除路径安全 | 37 |
| | **合计** | **423** |

### 强制手段

`build_bootstrap.sh` 第 3.45 步会检查这些文件里**没有** `import android.`。
违反直接构建失败 —— 否则「纯逻辑层」会慢慢被污染，测试也就跑不起来了。

### 这个做法救过什么

- `Version.digit` 溢出返回 0，导致 `99999999999999.0.0` 被判为比 `1.0.0` 旧
  —— 测试发现，改成饱和到 `Long.MAX_VALUE`
- `ConfigBackup` 的 zip-slip：构造带 `../escaped.txt` 的恶意 zip，
  测试确认磁盘上真的**没有**多出文件，而不只是「校验函数返回了错误」
- `PayloadUpdate`：「哨兵 0 处不匹配但修订号变了」这个用例
  —— 正是第二代机制漏掉的那一类

---

## 5. 运行时补丁

App 会在 DSH 启动前修改运行包里的文件。这些补丁都是**文本匹配**的，
所以每一条都有前提 —— 前提一旦变化就会静默失效。

| 补丁 | 改什么 | 前提 |
|---|---|---|
| **viewport** | 前端 `index.html` 的 viewport 标签 | 存在 `content="width=device-width, initial-scale=1"` |
| **附件落盘** | `dsh-attachment-local/lib/index.js` | 存在 `await syncDirectory(`、`await link(staged.path, target);` |
| **sharp 替换** | `node_modules/sharp/index.js` | 用 `sharp-android.js` + `pillow_shim.py` 整份覆盖 |

补丁是**幂等**的：原始文件会先备份成 `<name>.dshorig`，并带版本标记
（如 `DSH-ANDROID-ATTACH-PATCH-v3`），所以可以重复应用、也可以升级补丁。

**升级补丁时注意**：不能把 `if (src.contains("标记")) return;` 当作幂等判断 ——
那样旧版补丁永远升不到新版。必须从**原始备份**重新打补丁。

### 为什么需要附件补丁

图片附件在 Android 上失败，真实原因是两层叠加：

1. `ensureDurableHome` 一路向上找到 `/`，尝试打开 `/data/user/0` → `EACCES`
2. Android 的 `link()` 在应用私有存储上返回 `EACCES`

修法是让 `syncDirectory` 容忍 `EACCES`，并给 `link` 加拷贝回退。

---

## 6. 插件机制

DSH 有活跃的插件生态，但 App 里的 DSH 是隔离环境，原本没有任何安装入口。

### 安装

```
npm install --prefix <DSH_HOME>/profiles/web <规格>
  → profiles/web/node_modules/<包名>     ← DSH 正是从这里解析插件
```

**为什么不用 `dsh plugin add`**：它依赖 **pnpm**，而运行包里没有；
装一个 pnpm 要 46MB，不值得。npm 已在运行包里，
且 profile 用的是 `nodeLinker: hoisted` —— 与 npm 的默认行为一致，落点相同。

规格支持 npm 包名、GitHub 简写（`owner/repo`）、`github:` / `gitlab:` 前缀、
完整 URL、绝对路径。**命令注入防护没有放松**：分号、管道、反引号、
`$()`、重定向、引号、通配符、空格一律拒绝（21 种注入尝试有测试）。

### 启用：两种类型，方式不同

这是踩过的坑：**bundle 类插件用 `--patch insert` 是不生效的**。

| 类型 | 判定 | 启用方式 |
|---|---|---|
| 普通插件 | package.json 无 `dsh.bundle` | 写进 `--patch` 的 insert 列表 |
| **bundle 插件** | package.json 有 `dsh.bundle` | 追加到 profile 的 `dsh.profile.bundles` |

类型**读插件自己的 package.json**，不按名字猜。
改 profile 的 `package.json` 是 JSON 编辑，不能靠字符串拼接 ——
拼坏了 profile 就起不来。测试验证了追加后 JSON **仍能被真实解析器解析**。

### 依赖过滤

装 `dsh-about` 会连带装上 react、js-tokens、loose-envify ——
它们和插件躺在同一个 `node_modules` 里。不区分的话插件列表会被依赖淹没。
判据：package.json 有 `dsh` 字段，**或**包名以 `dsh-` 开头。

### 自我修复

如果带插件启动失败，App 会写一个 `.plugins-disabled` 标记，
下次启动跳过所有插件 —— 保证 App 一定能起来。
这是为什么「插件把 DSH 弄挂了」不会变成「App 打不开」。

---

## 7. 构建链

没有用 Gradle（那需要完整的 Android SDK + JDK，容器里放不下）。
全部手写：

```
mkmanifest.py          手写二进制 AXML（AndroidManifest.xml）
  └─ 为什么要手写：aapt2 的 link 需要资源，而本项目零资源（图标是 Canvas 画的）
     手写 AXML 反而更可控，且能精确注入图标/主题的资源 id

aapt2 compile/link     编译 res/（图标、主题、快捷方式）
javac --release 8      编译 Java（targetSdk 28 时代的字节码）
d8 --min-api 24        Java 字节码 → DEX
mkzip.py               组装 APK（store 模式放 assets，deflate 放其它）
apksigner              签名（keystore 在 scripts/release.keystore）
```

**环境陷阱**：`aapt2` 是 **bionic 二进制**（Android 的 libc），
必须用 Termux 的 linker 跑：

```bash
env LD_LIBRARY_PATH="$TERMUX_LIB" "$AAPT2" compile ...
```

`d8.jar` / `apksigner.jar` 是普通 JVM 工具，直接用 `java -cp` 跑。

### 为什么 targetSdk=28

Android 10（API 29）起禁止 App exec 自己私有目录里的文件
（`execute_no_trans`）。而本项目的整个架构就是
「解压一个 node 到私有目录然后执行它」—— 升到 29 会直接失效。

这不是将就，是**架构前提**。若将来必须升级 targetSdk，
需要重新设计 node 的存放位置（应用可执行目录已不可写）。

---

## 8. 数据流：一次用户提问

```
用户在 WebView 里输入
  ↓
DSH 前端 → /api/… → DSH 服务端（本地 127.0.0.1:<端口>）
  ↓
模型调用（走用户配置的 provider / API key）
  ↓
工具调用（读文件、执行命令…）
  ↓ 执行在 <root>/tools 的沙箱里
结果回到前端
  ↓
注入的脚本轮询 /api/session/list，观察 running 由有到无
  ↓ console.log('[dsh-task] done …')
onConsoleMessage 捕获 → TaskNotifier 判定 → 是否需要发通知
```

**为什么用注入而不是原生轮询**：网页**已经完成认证**（会话 Cookie），
同源 `fetch` 直接可用，不必把 token 取出来在原生侧另开一条请求。
回报走 `console.log`，**不引入 JS 桥** —— 那会增加网页侧的安全面，
只为传一个布尔值不值得。
