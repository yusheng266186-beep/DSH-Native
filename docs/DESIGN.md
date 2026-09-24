# UI 设计规范

> **强制约定：所有原生界面（对话框、设置页、提示条等）必须使用 `DshUi` 组件层构建。
> 禁止直接使用 Android 系统默认控件样式。**
>
> 原因：DSH 的 Web 界面是浅色卡片风格，而系统默认的 Material 风格
> （下划线输入框、水波纹按钮、灰色对话框、系统字号）与之放在一起会明显割裂。
> 用户明确要求「原生界面与 DSH 视觉统一和谐」，此要求对**后续所有新增功能**持续有效。

---

## 一、设计变量（取自 DSH 前端实际使用的值）

深浅两套取值都来自 DSH 前端自己的设计令牌，**不要自己配近似色**：

| 用途 | 浅色 | 深色（`--dsw-alias-*`） | 说明 |
|---|---|---|---|
| 页面底色 | `#F7F8FA` | `#151517`（`bg-base`） | 卡片外的背景 |
| 卡片 | `#FFFFFF` | `#232324`（`bg-layer-1`） | 主容器 |
| 描边 | `#00000014` | `#ffffff0f`（`border-l1`） | **1dp 极细** |
| 输入框底 | `#F5F6F8` | `#2C2C2E`（`bg-layer-2`） | 聚焦时 `#EDEFF3` / `#353638`（`bg-layer-3`） |
| 次按钮底 | `#F3F4F6` | `#2C2C2E`（`bg-layer-2`） | 按下时 `#E8EAEE` / `#353638`（`bg-layer-3`） |
| 品牌蓝 | `#4D6BFE` | `#6B85FF` | 主按钮、强调（深色下提亮保证对比度） |
| 主文字 | `#1F2329` | `#F9FAFB`（`label-primary`） | 标题、正文 |
| 次文字 | `#6B7280` | `#CFD3D6`（`label-secondary`） | 字段标签、状态 |
| 弱文字 | `#9CA3AF` | `#ADB2B8`（`label-tertiary`） | 说明、路径 |

圆角：卡片 **16dp**、输入框与按钮 **10dp**。
留白：卡片内边距 **20dp**，控件间距 **6–14dp**。

> 这些值集中在 `DshUi.java` 的颜色方法区（`DshUi.BG()` / `DshUi.TEXT()` 等）。
> 需要调整时**改那里**，不要在业务代码里写死颜色。
>
> **颜色是方法而不是常量**：`public static final int` 属于编译期常量，
> javac 会把值内联进调用点，运行时改字段对已编译代码无效 —— 那样主题切换
> 就只能靠重启进程。改成方法后，**重建界面即可换主题**。
> 宿主必须在建任何视图之前调用 `DshUi.applyTheme(context)`（见 `MainActivity.onCreate`）。

---

## 二、常用组件

```java
// 对话框：透明窗口 + 自绘圆角卡片（不是 AlertDialog）
Dialog dlg = DshUi.dialog(this,
        DshUi.scroll(this, body),          // 内容超出时滚动
        DshUi.footer(this, cancel, save),  // 底部按钮行（右对齐）
        660);                              // 最大高度 dp
dlg.show();

// 组件
DshUi.title(this, "设置")             // 对话框标题 17sp 加粗
DshUi.sectionLabel(this, "更新")      // 区块小标题 13sp 加粗
DshUi.label(this, "API Key")          // 字段标签 12.5sp 次要色
DshUi.hint(this, "仅保存在本地")       // 说明文字 11.5sp 弱化色
DshUi.status(this, "当前版本 1.0")     // 可异步更新的状态文字
DshUi.input(this, value, secret)      // 输入框（浅灰圆角、无下划线）
DshUi.button(this, "保存并重启", true) // 主按钮（品牌蓝）
DshUi.button(this, "取消", false)      // 次按钮（浅灰）

// 布局
DshUi.paddedBody(this)                // 卡片正文容器（20dp 内边距）
DshUi.fullWidth(this, 12)             // 撑满宽度 + 上边距 12dp
DshUi.column(this)                    // 纵向容器
```

---

## 三、新增功能时的检查清单

- [ ] 对话框用 `DshUi.dialog()`，**不用** `AlertDialog.Builder`
- [ ] 按钮用 `DshUi.button()`，**不用**系统 `Button` 默认样式
- [ ] 输入框用 `DshUi.input()`，**不用**带下划线的 `EditText`
- [ ] 颜色引用 `DshUi` 常量，**不写死**十六进制值
- [ ] 圆角/间距符合上面的取值（卡片 16dp、控件 10dp、内边距 20dp）
- [ ] 文字用三档层级（`TEXT` / `TEXT_2` / `TEXT_3`），不随意取灰色
- [ ] 内容可能超出屏幕时用 `DshUi.scroll()` 包裹
- [ ] 构建后确认 APK 内 `AlertDialog` 出现次数为 0

---

## 四、验证方式

构建后可静态检查是否仍在使用系统样式：

```bash
# 应输出 0
grep -c 'AlertDialog' src/dev/dsh/nativeapp/MainActivity.java
```

或在 APK 的 dex 中确认已无 `AlertDialog$Builder` 符号。

---

## 五、为什么不用 androidx / Material Components

本项目构建链是手写清单 + aapt2 + d8，**不引入 androidx 依赖**。
因此无法使用 `MaterialAlertDialogBuilder`、`Theme.Material3` 等。
`DshUi` 用 `GradientDrawable` + `StateListDrawable` 自绘，
既满足视觉统一，也不增加依赖体积。
