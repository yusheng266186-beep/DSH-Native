# 调研：Android 内嵌文件浏览器 / 文本编辑器 / 日志查看器

> 调研日期 2026-09-22。许可取自各仓库 LICENSE 原文，活动时间取自 commit atom feed
> 与 Maven Central metadata，**非记忆**。
>
> 背景：本项目为纯 Java、零第三方依赖、minSdk 24 / targetSdk 28。
> 已有自建 UI 组件库（DshUi），**样式无需借鉴**，只关注交互语义、
> 边界处理与可移植代码。

---

## 一、候选项目评估

| 项目 | 许可（已核验） | 技术栈 | 最近活动 | 结论 |
|---|---|---|---|---|
| [sora-editor](https://github.com/Rosemoe/sora-editor) | **LGPL-2.1** | Kotlin/AAR | 提交 2026-09-17；[Maven 0.23.6](https://repo1.maven.org/maven2/io/github/Rosemoe/sora-editor/editor/maven-metadata.xml) 停于 2025-06 | **✗ 不适合** |
| [Amaze File Manager](https://github.com/TeamAmaze/AmazeFileManager) | **GPL-3.0** | **Java** | master 冻结 2024-02，开发转 `release/4.0`（2026-08-20）；[v3.11.3](https://github.com/TeamAmaze/AmazeFileManager/releases) 2025-12-28 | 只读参考 |
| [Material Files](https://github.com/zhanghai/MaterialFiles) | **GPL-3.0** | Kotlin | 2026-09-20 活跃 | 只读参考（最佳交互范本） |
| [Simple File Manager](https://github.com/SimpleMobileTools/Simple-File-Manager) | GPL-3.0 | Kotlin | 2024-06 起停滞 | ✗ |
| [Fossify File Manager](https://github.com/FossifyOrg/File-Manager) | GPL-3.0 | Kotlin | 2026-09-11 | ✗ |
| [Squircle CE](https://github.com/massivemadness/Squircle-CE) | **Apache-2.0** | Kotlin 多模块 | 提交 2026-01-05；[v2025.1.3](https://github.com/massivemadness/Squircle-CE/releases) | 许可 OK，架构不可搬 |
| [Acode](https://github.com/Acode-Foundation/Acode) | **MIT** | Cordova/WebView | 2026-09-22 活跃 | **✗ 全 JS，无 Java 可移植物** |
| [kilo](https://github.com/antirez/kilo) | **BSD-2-Clause** | C，1308 行 | 2025-01 | **✓✓ 算法金矿** |
| [micro](https://github.com/zyedidia/micro) MIT / [nano](https://git.savannah.gnu.org/cgit/nano.git/) GPL-3.0 | — | Go / C | micro 2026-09-21 | 仅思路 / ✗ |
| **[AmrDeveloper/CodeView](https://github.com/AmrDeveloper/CodeView)** | **MIT** | **纯 Java，9 文件 ≈1480 行** | 1.3.9（2023-12-30）休眠 | **✓✓ 唯一推荐** |

### sora-editor 的排除理由（最容易被误选）

- **LGPL-2.1**：闭源分发需保留可重链接能力，实际等同于不可修改
- 其 `editor-0.23.6.pom` 强制传递 `kotlin-stdlib 2.1.21` +
  `androidx.annotation 1.9.1` + `androidx.collection 1.5.0`，
  与「纯 Java 零依赖」直接冲突
- editor AAR 实测 665 KB（classes.jar 700 KB），含依赖增量 2.5 MB+
- 主干已升到 AGP 9.3.1 / JVM 17，与 targetSdk 28 的旧工具链不兼容

### GPL 项目的使用边界

Material Files / Amaze / Fossify 均为 **GPL-3.0，代码不可进闭源工程** ——
但可以**读其设计**。Material Files 的 README 明确指出：它不用
`java.io.File` 而走 Linux syscall，因为 `java.io.File` **无法正确处理符号链接**。

---

## 二、可直接使用的轻量实现

**唯一推荐：AmrDeveloper/CodeView 1.3.9**

- 坐标 `io.github.amrdeveloper:codeview:1.3.9`，MIT，2023-12-30 发布
- **AAR 仅 21 KB**，唯一依赖 `androidx.appcompat:1.4.0`
- 自带：可选行号 / 当前行高亮 / 相对行号（vim 风）、正则语法高亮、
  查找替换、自动缩进、自动补全、代码片段、错误标记
- 全部 9 个 Java 文件（`CodeView.java` 927 行），全文仅 **2 处** AppCompat 引用
  → **可直接 vendoring 源码并把 appcompat 换成普通 `EditText`**，即得零依赖实现
- 缺陷：2023 年后休眠；继承 `EditText`，**>1 MB 文件会卡顿/OOM**，
  只适合配置文件级文本

不推荐：[tiagohm/CodeView](https://github.com/tiagohm/CodeView)（2017 后无提交）、
[kbiakov/CodeView-Android](https://github.com/kbiakov/CodeView-Android)（2019 后无提交）。

**Google 官方**：只有 [androidx.documentfile 1.1.0](https://developer.android.com/jetpack/androidx/releases/documentfile)
（Apache-2.0）+ [SAF](https://developer.android.com/guide/topics/providers/document-provider)。
它是 SAF 的薄封装、逐节点 IPC，**遍历大目录极慢**，不适合做文件浏览器主数据源；
targetSdk 28 可直接走 `java.io.File` 路径。

---

## 三、实现建议清单

### ① 文件浏览器

- **符号链接**：用 `android.system.Os.lstat()` / `Os.readlink()` /
  `OsConstants.S_ISLNK`，官方标注 **API 21+**
  （[Os 参考](https://developer.android.com/reference/android/system/Os)），
  minSdk 24 零依赖可用。
  **注意 `java.nio.file.Files` 是 API 26+**，minSdk 24 用不了
  → Material Files 的 NIO2 路线对本项目作废。
- **权限**：targetSdk 28 在 Android 11 上仍保留 legacy 外部存储
  （官方原文：只有 target 到 API 30 后系统才忽略 `requestLegacyExternalStorage`，
  见 [Android 11 存储变更](https://developer.android.com/about/versions/11/privacy/storage)）；
  但 Android 11+ 写任意目录需
  [`MANAGE_EXTERNAL_STORAGE`](https://developer.android.com/training/data-storage/manage-all-files)
  （manifest 声明 + `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 引导 +
  `Environment.isExternalStorageManager()` 检测），且 Google Play 仅允许文件管理器等类目。
- **边界情况**：`listFiles()` 返回 `null`（EACCES）与返回空数组必须分别提示；
  `File.length()` 对目录无意义；`lastModified()` 不可读时返回 0；
  列目录必须后台线程 + 可取消令牌，大目录显示进度。
- **排序规则**（照 [FileSortOptions.kt](https://github.com/zhanghai/MaterialFiles/blob/master/app/src/main/java/me/zhanghai/android/files/filelist/FileSortOptions.kt)）：
  目录永远置顶；按类型/大小/时间排序时**始终以文件名 collation 作末级 tiebreaker**；
  降序在「目录置顶」之前应用，保证文件夹仍在最前；
  `.` 与 `#` 前缀同组（Nautilus 行为）；隐藏文件默认不显示且开关持久化。
- **面包屑 / 返回键**：参考 [BreadcrumbLayout.kt](https://github.com/zhanghai/MaterialFiles/blob/master/app/src/main/java/me/zhanghai/android/files/filelist/BreadcrumbLayout.kt)
  （横向滚动 + 中间段省略）；返回键顺序为
  「先退搜索/多选 → 再逐级退目录 → 最后才退出」。

### ② 文本编辑器

- 编码：前 3 字节判 BOM（UTF-8/UTF-16LE/BE）；无 BOM 则严格 UTF-8 解码，
  失败回退 `Charset.defaultCharset()`/GBK；**保存时保持原编码与 BOM**。
- 换行符：统计 CRLF/LF/CR 哪个是主流并**原样写回**，不要统一转换。
- 大文件：>2 MB 不整读；先扫描建**行长索引**（每 N 行记录一个 offset 检查点），
  只把可视窗口解码进内存；超限文件降级为只读查看。
- 未保存保护：脏标记 + 与「原文件 mtime/size」比对，
  `onBackPressed` 弹三选一（保存/丢弃/取消）。
- 撤销：存 `(offset, 删除文本, 插入文本)` 差量操作栈，上限约 200 步，
  合并连续输入。
- 等宽与行号：`Typeface.MONOSPACE` + 自绘 gutter，
  `onDraw` 只画 `firstVisibleLine..lastVisibleLine`，
  宽度 = `measureText(String.valueOf(lineCount))`。

### ③ 日志查看器

- **必须自建日志文件**：官方明确「自 Android 4.1（API 16）起，
  只有特权系统应用才能被授予 `READ_LOGS`」，并建议
  「需要更详细日志就写到内部存储自行管理」
  （[Log Info Disclosure](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)）。
  → 包一层 `Log` 门面，同时写 logcat 与 `getFilesDir()/logs/`，按天或按大小分片。
  （本项目已采用共享日志文件方案，方向一致。）
- 级别过滤与搜索：**预解析行索引** `(level, timestamp, offset, length)`
  后在内存里过滤，不要每次全文正则扫描。

---

## 四、值得移植的算法

### (1) 增量语法高亮失效传播（kilo，BSD-2）

每行维护 `chars`（逻辑文本）/ `render`（Tab 展开后）/
`hl[]`（**每字节一个高亮类型**）/ `hl_open_comment`（行尾多行注释状态）。
单遍状态机靠 `prev_sep` / `in_string` / `in_comment` 判定，
关键字需 `prevSep` 前置校验以避免匹配到标识符中部：

```
rehighlight(row):
    old = row.hl_open_comment
    row.hl = 扫描 row.chars 的单遍状态机      // 单行注释/多行注释/字符串/数字/关键字
    row.hl_open_comment = 扫描结束时的 in_comment 状态
    // 关键：只有「行尾跨行状态发生翻转」才向下级联
    if old != row.hl_open_comment and row+1 存在:
        rehighlight(row + 1)
```

编辑一行只需重扫该行，把每次击键从 O(全文) 降到 O(单行 + 受影响行)。
Android 侧 `hl[]` 用 `byte[]` 存类型，绘制时**合并同色连续区间**为一次
`drawText`（kilo 用 `current_color` 去重，同理）。

### (2) 文件名自然排序（glib）

`g_utf8_collate_key_for_filename()`，Material Files 的
[CollatorFileNameExtensions.kt](https://github.com/zhanghai/MaterialFiles/blob/master/app/src/main/java/me/zhanghai/android/files/filelist/CollatorFileNameExtensions.kt)
即其 Kotlin 移植（注释直接标注了 glib 出处）。
把文件名切成「非数字段 / `.` / 数字段」，拼成可直接 `memcmp` 的 key：

```
key(name):
    for token in split(name):
        '.'     -> append [1,1,1,1]                       // 分隔哨兵，保证点号优先
        digits  -> append [1,1,1,2]                       // 数字哨兵
                   + 前导零个数 + 有效位数 + 数值本身
        other   -> append Collator.getCollationKey(token) 的字节
```

效果：`file2 < file10`（按数值而非字典序）、前导零独立参与比较、点号分段稳定。
Android 上只用 `java.text.Collator`，零第三方依赖。

> 本项目采用等价的**直接比较器**实现（逐段比较、数字按有效位数再按值），
> 效果相同而代码更短。

---

## 五、结论

| 功能 | 建议 |
|---|---|
| 文件浏览器 | **自研**：`java.io.File` + `android.system.Os`（零依赖）；交互与边界照 Material Files；需要 Java 写法时读 Amaze 的 [HybridFile.java](https://github.com/TeamAmaze/AmazeFileManager/blob/master/app/src/main/java/com/amaze/filemanager/filesystem/files/HybridFile.java) / [FileUtils.java](https://github.com/TeamAmaze/AmazeFileManager/blob/master/app/src/main/java/com/amaze/filemanager/filesystem/files/FileUtils.java)（GPL，**只读不抄**） |
| 文本编辑器 | **vendoring AmrDeveloper/CodeView 的 9 个 MIT Java 文件**（去掉 appcompat）负责 ≤1 MB 编辑；**大文件另写只读分页查看器**（行号 gutter + 行长索引） |
| 日志查看器 | **自研**，数据源必须是自己写的日志文件（logcat 读不到） |
| 明确不要 | sora-editor（LGPL + Kotlin 依赖链 + JVM 17）、Acode（WebView）、任何 GPL 代码、androidx.documentfile 作主数据源 |

**一句话**：可借鉴的是 **Material Files 的交互与排序语义**、
**Amaze 的 Java 目录遍历写法**、**kilo 的增量高亮模型**；
可移植的代码只有 **MIT 的 AmrDeveloper/CodeView**；其余一律因许可或技术栈排除。

---

## 六、本项目已据此落地的改动

| 改动 | 依据 |
|---|---|
| 符号链接识别改用 `Os.lstat`（显示 `` 与目标路径） | 「`java.io.File` 无法正确处理符号链接」；运行包内 `tools/bin/*` 大量软链，不区分会误编辑到目标文件 |
| 文件名自然排序（`file2` 排在 `file10` 前） | 第四节 (2) |
| 返回键逐级退目录，退到顶层才关闭 | 第三节 ① 返回键顺序 |

### 暂不采纳

- **引入 CodeView 做语法高亮**：AAR 虽仅 21 KB，但需 vendoring 1480 行并改动
  appcompat 依赖，且 **>1 MB 文件会卡**；本项目编辑器面向配置文件级文本，
  收益与维护成本不成比例。若后续确实需要高亮，按第四节 (1) 的 kilo 模型
  自己实现更可控。
- **编辑器 BOM / 换行符保持**：当前已保持原编码（UTF-8 严格解码失败回退
  GB18030），但未显式处理 BOM 与 CRLF —— 列为后续可选改进。
