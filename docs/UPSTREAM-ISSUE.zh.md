# 上游 issue：Android 上附件落盘失败

> **已发布：** <https://github.com/deepseek-ai/deepseek-harness/discussions/7507>

> 仓库：<https://github.com/deepseek-ai/deepseek-harness>
> 包：`packages/attachment/attachment-local`（`@deepseek-ai/dsh-attachment-local@0.1.6-alpha.2`）、
> `packages/api/session-controller`
> 平台：Android（应用私有存储，普通应用 UID，非 root）

---

## 摘要

在 Android 上，**任何带图片/附件的提示都会失败**。附件落盘路径中有两处平台假设在
Android 上不成立，而失败又被兜底错误包装**掩盖**，用户只能看到
`prompt rejected (session/agent-busy)`，没有任何可行动的信息。

---

## 现象

在 `dsh web` 中发送图片，返回：

```json
{
  "code": "session/agent-busy",
  "message": "prompt rejected",
  "details": { "reason": "Error: EACCES: permission denied, open '/data/user/0'" }
}
```

界面渲染为 `prompt rejected (session/agent-busy)`。纯文字提示一切正常。

---

## 背景——设计意图本身是合理的，但在 Android 上无法满足

`dsh-attachment-local/README.md` 明确写明了设计目标：

> 首次写入前，进程会**把 home 的每一级祖先目录一路同步到文件系统根**，
> 以免把一个由其他进程创建、但尚未同步的目录误当作安全边界。
> ……在 Windows 上，由文件系统元数据日志负责条目持久性。

这在桌面端是合理的保证。但在 Android 上它**根本无法完成**：
应用被限制在 `/data/user/0/<包名>`，连**打开**其上的任何祖先目录都不被允许。
代码其实已经承认了这类平台差异（为 Windows 开了豁免），Android 需要同等对待。

---

## 根因 1 —— 持久化遍历越过了应用的数据目录

```js
async function ensureDurableHome(path) {
    const home = resolve(path);
    if (!durableHomes.has(home)) {
        await ensureDurableDirectory(home, parse(home).root);   // ← 边界是 "/"
        durableHomes.add(home);
    }
    return home;
}

async function ensureDurableDirectory(path, boundary) {
    const target = resolve(path);
    const stop = resolve(boundary);
    await mkdir(target, { recursive: true, mode: 448 });
    await chmod(target, 448);
    let level = target;
    while (level !== stop) {                    // 一路走到 "/"
        const parent = dirname(level);
        await syncDirectory(parent);            // 打开每一级祖先
        if (parent === level) return;
        level = parent;
    }
}

async function syncDirectory(path) {
    if (process.platform === "win32") return;   // Windows 有豁免……
    const handle = await open(path, constants.O_RDONLY);   // ……Android 没有
    try { await handle.sync(); } finally { await handle.close(); }
}
```

调用点：

```js
// stageImmutableObject
const boundary = await ensureDurableHome(dirname(dirname(resolve(root))));
// publishImmutableAlias
await ensureDurableDirectory(parent, await ensureDurableHome(dirname(dirname(resolve(root)))));
```

当 `root = <DSH_HOME>/attachments/v1` 时，边界变成 `<DSH_HOME>`，随后遍历
**一路向上直到文件系统根**：

| 层级 | Android 上是否可访问 |
|---|---|
| `/data/user/0/dev.dsh.native/files/dsh/.dsh` | ✅ 应用家目录 |
| `/data/user/0/dev.dsh.native/files` | ✅ |
| `/data/user/0/dev.dsh.native` | ✅ |
| **`/data/user/0`** | ❌ **EACCES** |
| `/data/user`、`/data`、`/` | ❌ EACCES |

Android 应用只能访问自己的 `/data/user/0/<包名>` 子树，因此打开其上任何祖先
目录都会抛 `EACCES`。

设备上实测：

```
[dsh-attach] syncDirectory skipped /data/user/0: EACCES
[dsh-attach] syncDirectory skipped /data/user: EACCES
[dsh-attach] syncDirectory skipped /data: EACCES
[dsh-attach] syncDirectory skipped /: EACCES
```

### 建议修法

边界不应假定为 `/`。二选一：

1. **把 `EACCES`/`EPERM` 视为「此处无需同步」**——该目录不在我们的控制范围内，
   其持久性不该由我们负责；或
2. 遍历遇到第一个**无法打开**的祖先时即停止，而不是固定用 `parse(home).root`。

Windows 豁免已经承认「目录 fsync 并非处处可用」；Android 应用沙箱属于同一类约束。
在这类平台上，诚实的边界应是「我们实际被允许打开的最高祖先」，
保证相应降级为「该层以下的每一级」。

---

## 根因 2 —— `link()` 在 Android 应用私有存储上不被允许

```js
async function publishStagedObject(root, target, staged) {
    const parent = dirname(target);
    try {
        await ensureDurableDirectory(parent, staged.boundary);
        try {
            await link(staged.path, target);       // ← Android 上 EACCES
        } catch (error) {
            if (!(error.code === "EEXIST")) throw error;
            ...
        }
        ...
    } catch (error) {
        await removeTemporary(staged.path);
        if (error instanceof AttachmentError) throw error;
        throw new AttachmentError("Unable to persist attachment.", "ATTACHMENT_WRITE_FAILED", { cause: error });
    }
}
```

设备上实测：

```
[dsh-attach] link unsupported (EACCES), copy instead
```

本平台上应用私有存储**拒绝硬链接**（SELinux 策略），即使两个路径位于同一文件系统。

### 建议修法

当 `link` 因「该平台不支持链接」类错误失败时退化为复制，而不是当作意外错误：

```js
const LINK_UNSUPPORTED = new Set(["EPERM", "EXDEV", "ENOSYS", "EACCES", "EMLINK", "EOPNOTSUPP"]);
```

暂存文件此时已完整写入并校验过摘要，读取 + 写入的复制可保持同样的保证。

---

## 根因 3 —— 错误被兜底包装掩盖

```js
// dsh-api-session-controller
} catch (error) {
    if (remoteErrorOf(error) !== void 0) throw error;
    if (error instanceof AttachmentError) throw new RemoteError("session/attachment-invalid", error.message, { reason: error.code });
    throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) });   // ← 兜底
}
```

任何非 `RemoteError`、非 `AttachmentError` 的值都会变成
**`session/agent-busy` / `prompt rejected`**，这是**误导性**的：agent 并没有忙。
真正的原因只保留在 `details.reason` 里，而界面不会显示它。

再加上 `AttachmentError(..., { cause: error })` 的 `cause` 同样不被呈现，
那个真实的 `EACCES` 一共穿过了**三层掩盖**才被观测到——
最终只能通过给客户端的 `fetch` 打桩才拿到。

### 建议修法（可诊断性）

- 把 `error.cause` 并入 `ATTACHMENT_WRITE_FAILED` 的消息；或
- 重新抛出前先把被包装的错误写入 stderr；或
- 给兜底分支一个独立错误码（例如 `session/prompt-failed`），
  避免与真实的 busy 状态混淆。

---

## 环境

| | |
|---|---|
| 主机 | Android 17（SDK 37），arm64，非 root 应用 UID |
| Node | v26.4.0 |
| DSH | `dsh-attachment-local@0.1.6-alpha.2` |
| 路径 | `DSH_HOME=/data/user/0/<包名>/files/dsh/.dsh` |
| 工作区 | `/sdcard/<app>/workspace` |

桌面端 / root 容器**复现不了**——那里 `DSH_HOME` 的各级祖先都可读，
这大概也是它一直没有在上游暴露出来的原因。

---

## 打补丁后的验证

同时应用两处修复（容忍 EACCES 的遍历 + 复制退化）后，
图片提示成功，流水线正常完成（`1200×2608`、331442 字节、`attachmentId` 正确），
文字行为不受影响。

---

## 最小复现（Android）

1. 安装一个以自己的 UID 运行 `dsh web`、且 `DSH_HOME` 位于应用私有存储的 Android 应用。
2. 发送任意带图片附件的提示。
3. 观察到提示被拒，错误为 `session/agent-busy`。
4. 包装 WebView 的 `fetch`（或查看 `details.reason`），即可看到底层的
   `EACCES: permission denied, open '/data/user/0'`。
