# Upstream issue draft — attachment persistence fails on Android

> **Posted:** <https://github.com/deepseek-ai/deepseek-harness/discussions/7507>

> Repo: <https://github.com/deepseek-ai/deepseek-harness>
> Packages: `packages/attachment/attachment-local` (`@deepseek-ai/dsh-attachment-local@0.1.6-alpha.2`),
> `packages/api/session-controller`
> Platform: Android (app-private storage, non-root app UID)

---

## Summary

On Android, **every image / attachment prompt fails**. Two independent
platform assumptions in the attachment persistence path break, and the failure
is then **masked by a catch-all error wrapper**, so the user sees only
`prompt rejected (session/agent-busy)` with no actionable detail.

---

## Symptom

Sending an image in `dsh web` returns:

```json
{
  "code": "session/agent-busy",
  "message": "prompt rejected",
  "details": { "reason": "Error: EACCES: permission denied, open '/data/user/0'" }
}
```

The client UI renders this as `prompt rejected (session/agent-busy)`.
Text-only prompts work normally.

---

## Context — the documented intent is sound, but unsatisfiable on Android

`dsh-attachment-local/README.md` states the design goal explicitly:

> Before the first write, the process **syncs every ancestor directory of the home
> down to the filesystem root once**, so a directory another process created but
> has not yet synced is never mistaken for a safe boundary.
> … on Windows, filesystem metadata journaling owns entry durability.

That is a reasonable desktop guarantee. On Android it **cannot be satisfied at
all**: an app is confined to `/data/user/0/<package>` and cannot even *open*
any ancestor above it. The code already concedes this class of platform
difference for Windows; Android needs the same treatment.

---

## Root cause 1 — the durability walk traverses above the app's data dir

```js
async function ensureDurableHome(path) {
    const home = resolve(path);
    if (!durableHomes.has(home)) {
        await ensureDurableDirectory(home, parse(home).root);   // ← boundary = "/"
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
    while (level !== stop) {                    // walks all the way to "/"
        const parent = dirname(level);
        await syncDirectory(parent);            // opens every ancestor
        if (parent === level) return;
        level = parent;
    }
}

async function syncDirectory(path) {
    if (process.platform === "win32") return;   // Windows is exempt…
    const handle = await open(path, constants.O_RDONLY);   // …Android is not
    try { await handle.sync(); } finally { await handle.close(); }
}
```

Call sites:

```js
// stageImmutableObject
const boundary = await ensureDurableHome(dirname(dirname(resolve(root))));
// publishImmutableAlias
await ensureDurableDirectory(parent, await ensureDurableHome(dirname(dirname(resolve(root)))));
```

With `root = <DSH_HOME>/attachments/v1`, the boundary becomes `<DSH_HOME>`,
and the walk then ascends **to the filesystem root**:

| level | Android reachability |
|---|---|
| `/data/user/0/dev.dsh.native/files/dsh/.dsh` | app home |
| `/data/user/0/dev.dsh.native/files` | |
| `/data/user/0/dev.dsh.native` | |
| **`/data/user/0`** | **EACCES** |
| `/data/user`, `/data`, `/` | EACCES |

An Android app may only access its own `/data/user/0/<package>` subtree, so
opening any ancestor above it raises `EACCES`.

Observed on device:

```
[dsh-attach] syncDirectory skipped /data/user/0: EACCES
[dsh-attach] syncDirectory skipped /data/user: EACCES
[dsh-attach] syncDirectory skipped /data: EACCES
[dsh-attach] syncDirectory skipped /: EACCES
```

### Suggested fix

Boundary should not be assumed to be `/`. Either:

1. **Treat `EACCES`/`EPERM` as “nothing to sync here”** — the directory is
   outside our control, so its durability is not ours to enforce; or
2. Stop the walk at the first ancestor that cannot be opened, instead of
   using `parse(home).root`.

The Windows exemption already acknowledges that directory fsync is not
universally available; an Android app sandbox is the same class of constraint.
On such platforms the honest boundary is "the highest ancestor we are actually
allowed to open", and the guarantee degrades to "every directory below that".

---

## Root cause 2 — `link()` is not permitted on Android app-private storage

```js
async function publishStagedObject(root, target, staged) {
    const parent = dirname(target);
    try {
        await ensureDurableDirectory(parent, staged.boundary);
        try {
            await link(staged.path, target);       // ← EACCES on Android
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

Observed on device:

```
[dsh-attach] link unsupported (EACCES), copy instead
```

Hard links are refused for app-private storage on this platform (SELinux
policy), even though both paths live on the same filesystem.

### Suggested fix

Fall back to a copy when `link` fails with a link-capability error rather than
an unexpected one:

```js
const LINK_UNSUPPORTED = new Set(["EPERM", "EXDEV", "ENOSYS", "EACCES", "EMLINK", "EOPNOTSUPP"]);
```

The staging file is already fully written and digest-verified, so a
read+write copy preserves the same guarantees.

---

## Root cause 3 — the error is masked by a catch-all wrapper

```js
// dsh-api-session-controller
} catch (error) {
    if (remoteErrorOf(error) !== void 0) throw error;
    if (error instanceof AttachmentError) throw new RemoteError("session/attachment-invalid", error.message, { reason: error.code });
    throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) });   // ← catch-all
}
```

Any non-`RemoteError`, non-`AttachmentError` value becomes
**`session/agent-busy` / `prompt rejected`**, which is misleading: the agent
is not busy. The genuine reason survives only inside `details.reason`, which
the UI does not surface.

Combined with `AttachmentError(..., { cause: error })` also not being surfaced,
the real `EACCES` went through **three layers of masking** before it could be
observed — obtained only by instrumenting the client's `fetch`.

### Suggested fix (diagnostics)

- Include `error.cause` in the `ATTACHMENT_WRITE_FAILED` message, or
- Log the wrapped error to stderr before rethrowing, and/or
- Give the catch-all a distinct code (e.g. `session/prompt-failed`) so it is
  not confused with a genuine busy state.

---

## Environment

| | |
|---|---|
| Host | Android 17 (SDK 37), arm64, non-root app UID |
| Node | v26.4.0 |
| DSH | `dsh-attachment-local@0.1.6-alpha.2` |
| Paths | `DSH_HOME=/data/user/0/<pkg>/files/dsh/.dsh` |
| Workspace | `/sdcard/<app>/workspace` |

Not reproducible on desktop/root containers, where the ancestors of `DSH_HOME`
are readable — which is likely why it has not surfaced upstream.

---

## Verification after patching

With both fixes applied locally (EACCES-tolerant walk + copy fallback),
image prompts succeed and the pipeline completes normally
(`1200×2608`, 331442 bytes, correct `attachmentId`). Text behaviour is
unaffected.

---

## Minimal reproduction (Android)

1. Install an Android app that runs `dsh web` under its own UID, with
   `DSH_HOME` inside app-private storage.
2. Send any prompt with an image attachment.
3. Observe the prompt rejected with `session/agent-busy`.
4. Wrap the WebView's `fetch` (or inspect `details.reason`) to see the
   underlying `EACCES: permission denied, open '/data/user/0'`.
