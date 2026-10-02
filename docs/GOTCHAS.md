# 踩过的坑

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.10**；源码：**0.33.10**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](STATUS.md)。
<!-- dsh-doc-status:end -->

这份文件记录**看起来对但实际错**的情况。每一条都是真实发生过的，
不是理论风险。接手时先读一遍，能省下大量重复踩坑的时间。

---

## 一、静默失败：本项目最大的一类 bug

不崩溃、不报错、功能悄悄不工作。修过的 bug 大多是这一类。
**审计代码时，重点看每个 `catch` 吞掉了什么。**

### 1. 通知渠道不存在 → 通知静默丢失

通知渠道原本只在前台服务里创建，而任务完成通知用同一个渠道。
服务若没起来（权限被拒、被系统拒绝启动），`notify()` 会因渠道不存在抛异常，
又被外层 `catch (Throwable)` 吞掉 —— 用户什么都看不到，也不会知道功能坏了。

**修法**：通知前先 `DshUi.ensureChannel(...)`，不依赖「服务一定启动过」。

### 2. 只想删文件的更新永远检测不到

运行包更新原来只看哨兵文件：

```java
if (missing.isEmpty()) return true;   // ← 判定「已是最新」
```

删掉文件后，其余哨兵全部不变 → 永远判定「已是最新」→ 那些文件永远留在设备上。

**修法**：分片修订号 + `remove` 清单。详见 [两段式分发与空间](ARCHITECTURE.md#3-两段式分发与空间)。

### 3. `catch (Throwable)` 把 NPE 变成「安装失败」

插件面板直接用宿主引用：

```java
@Override public File node() { return nodeRef; }   // 启动未完成时为 null
```

NPE 在后台线程发生 → 被吞掉 → 只显示「安装失败」，看不到原因。

**修法**：调用处加非空检查；深层函数自己也检查并抛**可读错误**
（NPE 的信息对用户毫无意义）。


**红线：`catch (Throwable ignored)` 的总数不得超过 `check_java.py` 里的
`SILENT_CATCH_BASELINE`（现为 121）。** 这道闸门由 `build_bootstrap.sh` 的
3.43 步在每次构建时执行。

为什么不要求清零：现存 121 处里绝大多数是合理的 —— 关流、取消开屏动画、
销毁旧 WebView、探测下一个端口、资源清理。逐个改写只会制造无谓 diff 和
回归风险。真正的问题是**它还在增长**（0.26.3 时 100 处 → 0.32.3 时 121 处），
所以锁住上限比强行清零更实际。想降低就改小基线值，并确认每一处减少都对应
一处真正的修复。

判断标准很简单：**这里如果真抛异常，用户会知道吗？** 会，就该 `log()`；
不会（清理、装饰性 UI、已有兜底），可以静默但要就近写清理由。

### 3.1 测了 mock，没测现实

「后台任务完成通知」曾经用一段注入脚本轮询 `/api/session/list`，
我还写了个模拟脚本去"验证"它 —— 脚本逻辑确实对，
**但那个接口根本不存在**，mock 的 `fetch` 当然不会报错。

实测 DSH 的服务端 API 走的是自定义 RPC（WebSocket + `/api/remote.mux`），
不是 REST；`/api/session/list`、`/api/sessions`、`/api/approval/list`
全都是 `not found`。于是那个功能**从未生效过**，而测试是绿的。

**教训**：验证外部依赖时，**先确认那个依赖真的存在** ——
起一个真实实例探测一次，比写十个 mock 都管用。

### 3.1.1 只给 fetch 加日志，会漏掉 WebSocket

排查「点归档没反应」时，我反复看 App 的 `[dsh-api]` 日志，
一直找不到归档请求 —— 后来才明白：**日志只包了 `fetch`，
而归档走的是 DSH 的 Remote RPC（WebSocket）**。
断连的证据其实一直在日志里（`[connection] connection lost, retry #1`），
只是我没往那儿看。

**教训**：给网络层加可观测性时，先弄清楚**有几种通道**。
只覆盖一种，另一种的故障就是黑盒。

### 3.1.2 「点了没反应」：先确认请求有没有发出去

排查「归档 / 重命名点不动」时，我连续四轮在**没有证据**的情况下改东西
（加 JS 弹窗回调、加连接恢复、改通知逻辑），全都不对症。

真正的突破口是给 **WebSocket 加探针**：记录所有 RPC 收发。
装上后一眼就看到 —— 所有 RPC 里**没有任何一条 `request`**，
只有订阅用的 `open`。于是范围立刻缩小到「点击根本没触发处理函数」，
而不是网络、服务端或归档逻辑。

**根因**：DSH 的会话操作菜单是悬停卡片（`closeOnPointerLeave: true`）。
鼠标上合理，但**手指抬起同样产生 `pointerleave`** ——
菜单在点中的瞬间被关掉，用户点的是一片空白。
归档、重命名、分叉都在同一个菜单里，所以一起失灵。

**修法**：注入触摸适配 —— 记录最近触摸时间，在**捕获阶段**拦掉
紧随其后的 `pointerleave` / `mouseleave` / `pointerout`。
React 事件委托到根节点，捕获阶段停掉传播它就收不到。
（已实测有效；临时探针在定位后已删除。）

**排查顺序（值得固化成反射）**：

1. 请求**发出去了吗**？（给对应通道加可观测性）
2. 有回应吗？成功还是失败？
3. 成功了但界面没变？→ 前端状态问题
4. 没发出去？→ 交互层问题（点击没到处理函数）

**别跳过第 1 步去猜。**

### 3.1.3 读错误码要读到点上：ERR_NAME_NOT_RESOLVED ≠ ERR_CONNECTION_CLOSED

用户发来一张浏览器报错截图，我扫了一眼就下结论「这个地址是畸形的」，
并据此加了一条「主机名必须有点、必须有合法顶级域」的拦截规则。

**判断错了。** 截图里的错误是：

```
net::ERR_CONNECTION_CLOSED
```

| 错误码 | 含义 |
|---|---|
| `ERR_NAME_NOT_RESOLVED` | **DNS 解析失败** —— 域名不存在 |
| `ERR_CONNECTION_CLOSED` | **连接建立后被对端/代理关闭** —— DNS 是成功的 |

`ERR_CONNECTION_CLOSED` 说明地址**能解析**，问题在连接层
（截图里开着 VPN，多半是代理路由）。我加的那条规则会**误伤正常地址**
（内网、代理、VPN 的 DNS 下单段主机名本来就能解析），已撤回。

**教训**：
- 看报错要看到**具体错误码**，不要停在标题行
- 加限制性规则前先确认「不满足这条规则的输入」**真的都是坏的** ——
  收紧容易，误伤之后要很久才发现

### 3.1.4 判断文件是否存在，路径必须核对过

启动快路径里有一句：

```java
if (!new File(toolsDir, "bin/node").isFile()) return false;
```

`tools/bin/node` **永远不存在** —— node 来自 APK 内置资源，解压到
`<root>/node`；工具链载荷里根本没有它（四个分片里 `bin/node` 数量都是 0）。

于是这个函数**永远返回 false**，每次启动都走阻塞的网络检查。
网络好时看不出来；国内网络下拉清单会挂住，表现为
「一直卡在正在准备运行环境」。**这个 bug 活了三个版本才被用户遇到。**

**教训**：
- 写「某文件是否存在」的判断时，**用真实产物核对一遍那个路径**
  （`tar -tf` 列一下、`ls` 看一眼），不要凭印象写
- 这类「恒假/恒真」的条件不会报错，只会让某条分支永远不被走到
- 网络好的时候，错误的慢路径和正确的快路径**看起来一样**

### 3.2 权限没声明 → 拿到的是"空"而不是报错

通知看板显示「网络不可用（未连接）」，但手机网络正常。

原因：清单里**没有 `ACCESS_NETWORK_STATE`**。没有这个权限时
`getActiveNetwork()` 返回 `null`，代码把它当成"没网"。
Android 的权限缺失往往不抛异常，只是让 API 返回空值。

**教训**：用系统 API 前先确认权限声明了；
并且把"API 返回空"和"业务上确实没有"区分开。

### 3.3 文案可能只在属性里

状态看板原本只查 `document.body.textContent` 找「停止生成」「发送消息」——
但按钮显示的是**图标**，文案在 `aria-label` / `title` 里，
于是三个判据一个都命中不了，状态永远停在「未知」。

**教训**：按文案定位元素时，可见文字、`aria-label`、`title`、
`placeholder`、`data-tooltip` 都要查。

### 4. 补丁前提失效 → 补丁静默跳过

运行时补丁都是文本匹配的。前提一旦变化（上游改了代码），
补丁就静默不生效，而 App 照常启动。

**排查方法**：从线上运行包里提取目标文件，逐个 grep 匹配标记：

```bash
tar --use-compress-program=unzstd -xf dsh.tar.zst \
  ./node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js
grep -c 'await syncDirectory(' <文件>
```

---

## 二、代码改动过程中的自伤

这几条我在同一个下午连续犯了三次，值得单独列出。

### 5. 正则替换代码时匹配错位置

用 Python 正则改 Java 代码，连续三次插错地方：

- 把日志行插进了 `if` 块**内部**
- 把方法调用插到了**作用域外**（`remoteRev` 在该方法里不存在）
- **吃掉了异常抛出的括号**（导致离线检查失去保护）

前两处靠编译报错发现。**第三处编译通过** —— 只有读代码才能发现。

**正确做法**：
- 用**方法签名**作锚点，不要用注释（注释会被自己改掉）
- **逐次改动、逐次编译**，不要一次改多处再看结果
- 改完把关键代码段 `sed -n` 出来看一眼

### 6. Python heredoc 吃掉 Java 转义

```python
# heredoc 里写 '\\s' 到 Java 源码会变成 '\s'，Java 编译报
# "text blocks are not supported" / "invalid escape"
```

**做法**：用 `python3 - <<'PY'`（带引号，阻止 shell 展开），
并在写完后用 `grep -nP '(?<!\\)\\[s{]'` 检查漏网的单反斜杠。

### 7. Anchors 依赖会被自己改掉的注释

改了 `DshUi` 里某个方法的 javadoc，另一处脚本还在用那句注释当锚点 → 锚点找不到。

**做法**：锚点用 `public static void toast(Context c, CharSequence msg) {` 这样的签名。

### 7.1 用 cp 同步脚本时方向反了（犯过两次）

改动脚本后要在「仓库副本」与「构建工作区副本」之间同步。
方向必须是 **仓库 → 工作区**。

我两次写成反的，把工作区的旧版本覆盖了刚修好的仓库版本 ——
第二次甚至是在这条教训已经写进文档之后。

**做法**：同步后**两个副本都跑一遍**验证（例如 `bash -n` 加实际执行）。

### 8. `pkill -f 'lib/bin.js'` 杀掉了自己

模式匹配到了执行这条命令的 shell 自己的命令行 —— 经典 pkill 自匹配。

**做法**：用 `ps -eo pid,args | grep -F ... | grep -v grep`，
或先 `pgrep` 拿到 PID 再排除自己的 `$$`。

---

## 三、历史设备构建环境陷阱（旧 proot Debian）

本节记录旧设备环境，当前 Actions 使用 Linux 官方 Android build-tools；不代表 App 依赖 proot，也不是当前 Linux 构建操作指南。

| 现象 | 原因 / 做法 |
|---|---|
| `aapt2` 报 `CANNOT LINK EXECUTABLE` | 它是 **bionic 二进制**，必须 `env LD_LIBRARY_PATH="$TERMUX_LIB" aapt2 ...` |
| 找不到 `/usr/bin/time` | 容器里没有。用 `python3 -c "import time; ..."` 计时 |
| 找不到 `bc` | 同上，用 `python3 -c "print(1/1024)"` |
| `import yaml` 失败 | 没装 pyyaml。验证 YAML 改用**真实消费者**（拿生成的清单真的启动一次 DSH） |
| tar 解压报 3000+ 个 `Cannot change mode` | proot 的权限怪癖，**不是归档有问题**。用同样方式解压旧归档对比错误数即可确认 |
| Python `os.walk` + `open()` 数出 645MB 重复 | 它**跟随了符号链接**，把同一个文件数了 150 次。改用 `du`（只算一次）或 `lstat` |
| tar 里的路径带 `./` 前缀 | `tar -tf` 输出是 `./node_modules/...`，`tar -x` 也要用同样的路径 |

---

## 四、Android 特有

### 9. targetSdk 必须是 28

Android 10（API 29）起禁止 exec 私有目录文件。本项目整个架构就是
「解压 node 到私有目录然后执行」—— 升到 29 直接失效。**这是架构前提，不是将就。**

### 10. `file://` URI 会抛异常

API 24 起把 `file://` 交给别的应用会抛 `FileUriExposedException`。
必须用 `content://`（本项目手写了 `UpdateProvider`，不依赖 androidx）。

### 11. 分享 URI 里的路径必须转义，且解码后要再校验

文件名含空格、中文、`#`、`?`、`%` 时直接拼进 URI 会被截断或解析错。
**更重要的是**：URI 是外部应用传进来的，对方能自己构造路径读任意文件。
转义只解决格式问题，**权限判断必须独立做一遍**。

### 12. 清单里的 provider 需要 `grantUriPermissions=true`

否则一次性读授权不生效，接收方打不开文件。`exported` 保持 `false`。

### 13. bionic 加载不了 glibc 的 `.so`

`@img/sharp-linux-arm64` 那类原生库在 Android 上永远加载不了
（除非专门为 Android 交叉编译）。判断依据：`file` 看到的 ELF 不代表能加载。

**架构正确 ≠ 能加载。** 这是本项目真实踩过的坑：`node-pty` 的 `linux-arm64`
预编译是 **Termux** 编译的 —— 同样是 AArch64 ELF，`EM_AARCH64` 断言全部通过，
但它链接 `libutil.so.1`、`libstdc++.so.6`、`ld-linux-aarch64.so.1`，
普通 Android 应用里一个都没有（`libutil` 在 Android 12 起已从公共 bionic 移除），
`dlopen` 必然失败。正确做法是用 `@mmmbuto/node-pty-android-arm64`
（只依赖 `libc.so`/`liblog.so`/`libm.so`/`libdl.so`）。

判据不是架构，而是 **`DT_NEEDED`**：`scripts/prepare_core_payload.py` 的
`assert_android_loadable()` 会解析 ELF 并拒绝任何链接 `libutil.so` 的二进制。

**更隐蔽的一点**：node-pty 的加载器依次尝试多个路径，只把**最后一个**错误抛出。
所以日志里写的是 `Cannot find module './prebuilds/android-arm64/pty.node'`，
而那个文件明明就躺在那儿 —— 真实原因（依赖缺失）被完全掩盖了。
排查「明明有文件却说找不到」时，要直接 `dlopen` 那个文件看真实报错。

### 14. `getCanonicalPath()` 才能防符号链接逃逸

白名单判断只看字面路径的话，一个指向 `/system` 的软链就能绕过去。
另外比较时要要求「根 + 分隔符」开头，
否则 `/data/app` 会被 `/data/ap` 误判为子路径。

---

## 五、布局（真机上很容易挤爆）

### 15. `UNSPECIFIED` 测量会把权重子视图压成 0

自测布局时用 `UNSPECIFIED` 测量，带 `weight` 的子视图高度会算成 0，
列表看起来是空的。改用 `AT_MOST(maxH)`。

### 16. `dialogFill` 的 body 必须 `0dp + weight=1`

否则底部按钮会被内容顶出屏幕。反过来，`dialog`（贴合内容）
**不能**用来装带权重的列表 —— 会被压扁。

判断方法：文件里如果有 `LayoutParams(x, 0, 1f)` 这种权重填充区，
就必须用 `dialogFill`。

### 17. 等权重按钮也会溢出

4 个按钮，每个标签 2 字（32dp）+ 默认内边距 32dp = 64dp，
而等权重只分到 63dp —— 差 1dp 就会挤压。

**做法**：底部按钮统一用紧凑内边距（`dp(6)`），2 字只需 44dp。
加第 5 个按钮时复核一遍预算（376dp 对话框 - 2×14dp 边距 = 348dp ÷ 5 = 63dp，
极窄屏 320dp 下 52dp，仍大于 44dp）。

### 18. `setBackgroundTintList(null)` 否则按钮颜色不生效

某些 ROM 上 `setBackgroundColor` 会被主题的 tint 覆盖。

### 19. 密码框的 `EditText` 在某些输入法下会自动填充

处理方式是显式设置 `setImportantForAutofill`，
并注意 `inputType` 组合（`TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD`）。

---

## 六、发布与网络

### 20. raw.githubusercontent.com 有约 5 分钟 CDN 缓存

刚推送的 `latest.json` 不会立刻生效。`release.sh` 会轮询到生效为止。

### 21. jsDelivr 的 `@main` 缓存更久

曾经卡在旧版本很久。因此 App 取「多个来源里的最高版本」，
只要有一个源更新了就能检测到。

### 21.1 草稿状态的 release 对公众不可下载

发布脚本在「等待下载路径生效」那一步超时被中断后，release 已经创建、
但仍是**草稿**，资产也还没传完。手工收尾时我补传了资产并写了清单 ——
于是两个下载源都是 404：客户端会检测到更新却下不下来。

**教训**：手工收尾发布时，也要走一遍验证（现在有 `scripts/verify_release.sh`
可以单独跑）。「验证通过才写清单」这条护栏，绕过它就会出事 ——
而人在急着收尾时最容易绕过。

### 21.2 版本号替换失配 → 客户端无限提示更新

提升版本号时我硬编码了旧值做替换：

```
源码里已经是 0.22.5  →  我却写 "0.22.4" 替换成 "0.22.6"
→ 替换静默失配，版本号没变
→ 构建出来的 APK 仍是 0.22.5，而 latest.json 写成了 0.22.6
→ 客户端看到「有新版」，下载下来却是旧版，无限提示更新
```

**这类错误没有任何报错** —— 替换失败不会让构建失败，清单也照写。

**两道防线**：

1. `release.sh` 发布前用 `aapt2 dump badging` 读出 APK 里的实际版本，
   与本次要发布的版本比对，不一致**直接中止、不写清单**
2. `scripts/bump_version.sh` 从源码里**读当前值再递增**，不硬编码旧值

### 22. 写清单前必须验证 release 真的创建成功

出过一次事故：脚本语法错误跳过了 `gh release create`，
但 `latest.json` 被写了 → 所有客户端更新失败。
`release.sh` 现在的顺序是：
**上传 → 验证资产存在 → 轮询两条下载路径（206/200）→ 最后才写清单**。

### 23. 运行包分片的上传顺序

先传分片、**最后传 `manifest.json`** ——
否则客户端可能拿到清单却下不到分片。

### 24. 容器访问不了 `raw.githubusercontent.com`（工具层面的限制）

`web_fetch` 会报「non-public IP」。用 `bash` + `curl` 可以。

---

## 七、验证方法本身也会错

### 25. 测试预期写错（发生过多次）

例子：
- `new byte[9000]` 全是零，却以为是随机内容
- 断链被算作文件还是目录
- `percentText(2.921)` 的档位记错
- 长度上限是 300，测试却用 300 字符（不是「超过」）
- `/x` 是合法的绝对路径，测试却期望被拒

**做法**：测试失败时**先怀疑测试**，再怀疑实现。

### 26. 锚点在改动后失效，grep 读到旧日志

改了注释导致脚本锚点找不到；`grep` 读到上一次运行留下的日志行。

**做法**：每次验证前清干净中间产物；grep 时带上文件与行号。

### 27. 「编译通过」不等于「功能有效」

插件管理曾经有 64 项测试全通过，但用**真实插件**一试就发现
两种类型的插件都装不成/启不了。

**教训**：涉及外部生态的功能，必须拿真实的东西试一遍。
本项目因此建立了这些验证手段：

| 验证什么 | 怎么验证 |
|---|---|
| 运行包完整性 | 线上清单与本地逐分片比对 sha256 |
| 哨兵是否真实存在 | 从分片归档里逐个列出 |
| 补丁前提是否成立 | 从线上运行包提取目标文件，grep 匹配标记 |
| 生成的 YAML 是否合法 | **拿它真的启动一次 DSH**，看插件是否被加载 |
| 归档能否正确解压 | 用 App 自己的 `unpack.js` 解压，与直接 tar 比对文件数 |
| 恶意备份包 | 构造带 `../` 的 zip，确认磁盘上**真的没有**多出文件 |
| 注入防护 | 21 种 shell 注入尝试 |

### 28. 依赖被当成插件列出来

装 `dsh-about` 会连带装上 react、js-tokens、loose-envify，
它们和插件在同一个 `node_modules` 里 —— 不区分的话插件列表会被淹没。

### 29. 本地扫描出的「645MB 重复」是假的

`os.walk` + `open()` 会跟随符号链接。用 `du` 或 `lstat` 才能看到真实占用。

---

## 八、协作约定

| 约定 | 原因 |
|---|---|
| 源码/脚本/注释里不能有 emoji | 用户明确要求。构建脚本有检查 |
| 易错判断进入纯逻辑层并离线验证 | 可重复回归与真机验收分别报告，不能把测试当作唯一证据 |
| 新 UI 必须走 `DshUi`，不能用 `AlertDialog.Builder` | 构建脚本第 3.5 步会检查 |
| App 版本同步使用 bump_version.sh | versionName/versionCode 与日志一致；payload 独立版本，不是每轮必须变化 |
| 发布只用 `scripts/release.sh` | 手工写清单出过事故 |
| 发布失败保留旧清单，通过正式流程完成核验 | verify_release 只是 bootstrap 辅助检查，不能替代 release.yml/release.sh |
| 改动后必须跑 `scripts/run_tests.sh` | 数量随版本变化，以本次日志为准 |
## 0.30.0 后维护补充

## 模型目录：上游目录决定模型可选性，本地目录只补充提示

`GET /models` 常常只返回模型 ID。它能证明服务商当前列出了某个模型；本地目录可以补充图片输入、上下文窗口、最大输出或 reasoning effort 映射，但不能因为这些提示暂时缺失而阻止模型选择。

正确分层是：

- 上游响应决定选择器显示哪些 ID，并决定这些 ID 是否可选；
- 本地/运行时能力目录只给这些上游 ID 补充能力提示；
- 上游新增但能力未知的模型仍可选择，缺失的提示不显示即可；
- 网络失败不得回退到本地预设并把它伪装成实时结果；
- 不发送测试提示词来“探测”能力；未知视觉不猜测为支持。0.32.1 起 max 是用户要求的请求扩展，必须与官方支持声明分开标注。

这样既不会让静态列表冒充在线目录，也不会因本地静态目录滞后而阻止服务商刚发布的模型。

## Dialog 返回：按钮与系统手势必须共用父级导航

Android 的 Dialog 默认返回行为只是取消当前窗口。设置首页用换页方式打开子页时，这会让系统返回手势关掉子页，却不会重建父页；随后返回事件落到 Activity，又可能出现退出确认。

设置流程应使用 `DshUi.onBack(dialog, previous)`：

- 可见返回按钮调用 `DshUi.swapDialog(dialog, true, previous)`；
- Android 返回键/边缘手势调用同一个 `previous`；
- 禁止点击卡片外侧静默取消；
- 列表内部导航（文件目录）和未保存编辑器继续使用各自更具体的返回守卫。


## 0.32.x 当前维护防护

### 刷新页面不能重建服务商目录

原生配置写入成功不等于 DSH Host 已经采用。单独 WebView reload 仍复用旧 topology；应写完整 provider catalog，确认空闲后受控重载。任务运行或状态未知时延后，持久化 pending，避免打断任务。排查还要检查 legacy settings 导入与 active profile patch。

### 所有模型 max 可选，不等于上游全部保证支持

保留固定官方声明，另外增加字面量 max 请求。未知/非可调模型保留不传参数的默认路径。UI 显示 Max（请求），上游拒绝/忽略不隐藏为降级 high。回归必须到真实 Host、调用配置、SDK 请求体和聊天框组件/RPC，不能只看原生选项。

### 当前会话发布不用旧 rename 补丁

先检查再 rename 有覆盖竞争窗口。v10 硬链接优先，兼容失败用 COPYFILE_EXCL 拒绝覆盖；磁盘满继续报错。Android flock no-op 没有跨进程锁，桌面则必须保留真实锁及错误传播。旧 patch 明确归档。

### 压缩体积不是安装与更新空间

v10 DSH 展开分配约 491MiB，全量压缩约 118.5MiB；工具、内置 Node、下载缓存和安全快照还需额外空间。使用 manifest unpacked_size 和实际预检，不沿用旧三倍估算或 550MiB 安装建议。

### 文档漂移也必须有检查

交接页曾落后到 0.25.5，README 保留旧测试数量与空间估算，阶段候选被误当当前进度。现在生成全部文档状态、STATUS 事实与固定模型表，核对本地链接；发布同步双 README 下载字段。历史记录保留当时事实，不能统一把标题版本替换为最新号。

```bash
python3 scripts/sync_project_metadata.py --docs-only
python3 scripts/sync_project_metadata.py --check --allow-unpublished-source
```

所有维护结论的证据层级见 [STATUS](STATUS.md)，持续红线见 [HANDOVER 第 8 节](HANDOVER.md#8-持续有效的事故防护与红线)。

## 触屏图标拉伸：先检查全局最小高度

0.33.8 的 `@media(pointer:coarse)` 给普通按钮统一设置 `min-height:44px`。
真实页面中发送变为 34×44、附件取消变为 18×44、文件标签关闭变为 20×44，
叉号落到标题行下方。移除该下限后恢复上游尺寸；无需改小 480px 视口或为每个按钮
叠加覆盖。CSS Module 不保证语义命名，稳定 ARIA / data 锚点必须来自实际 DOM。

原体检只在加载后扫描一次、截断整段 JSON，会漏掉后来的附件。现改为交互后去抖测量、
固定动作名脱敏、独立有界记录，并加入真实运行包的触屏浏览器回归。
详见 [布局交接](HANDOVER-LAYOUT.md)；浏览器验证与新版真机反馈分别记录。

## 0.33.10：账号授权与已导入配置

### legacy 文件不等于当前配置

DSH 0.2 导入 `settings.yaml` 后改为 `settings.yaml.imported`，实际配置位于 Web profile。
原生读取若只看旧路径，模型目录会退回预设，项目切换也可能跳过覆盖。现在有待导入文件时读取它，
否则通过内核 `settings/describe` 投影公开模型配置；项目切换使用同一快照。
加密备份也必须包含 active profile，不能只备份已被迁移的路径。

### 不用旧凭据全文覆盖内核 grant

账号授权会向 `.credentials.yaml` 的 `records` 写入 grant，API Key 在 `refs`。
对话框打开时缓存的全文可能早于浏览器登录，保存整份旧文件会丢失授权。
API Key 修改使用内核 `credentials/set` / `unset`，真实账号消费回归验证授权前后修改均保留 grant 和密钥。

### Desktop 前端检查不是后端缺功能

上游账号页有 `dshDesktop` 条件，但固定 Web profile 已加载账号 RPC。
原生面板消费现有后端，不制造假的 Desktop 桥；授权 URL、PKCE、state 与回调各有不同边界。
网络错误和日志不得包含 token、Cookie、authorization code 或 PKCE verifier。
`127.0.0.1` 子串或 `localhost` 前缀也不能作为本机 origin 判定；host、port、scheme 必须准确匹配。

### 服务地址早于模型导入

内核在异步 legacy 导入结束前就打印 Web 地址。CI 曾读到默认模型已经更新，但 Command Code 目录尚未出现的中间状态。
完成日志默认不输出到终端，不能通过固定 sleep 或猜测日志来判断。启动前保留预期模型配置，
通过真实 `settings/describe` 核对默认、目录 ID 与能力；超时明确失败。合成平台账号回归使用同一生产判断。
