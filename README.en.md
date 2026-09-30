# DeepSeek Harness Native for Android

[中文](README.md) | [English](README.en.md)

Run Node.js, DeepSeek Harness (DSH), and a practical development toolchain directly on Android. No Termux, no proot, and no remote execution server are required.

**Current stable release: 0.31.5** (payload-v10)


Reasoning effort follows the selected provider and model, and survives catalog refreshes and upgrades. See the [full model capability table](docs/MODEL_REASONING.md).

## Download and install

Stable APK:

**[Download DSHNative-bootstrap.apk](https://github.com/yusheng266186-beep/DSH-Native/releases/download/v0.31.5-bootstrap/DSHNative-bootstrap.apk)** (33.7 MiB)

SHA-256: `5f80e2ec4dfaa2cf1a330158b00a13c8d161e491ee4abdceb00ae31b3447175c`

Requirements:

- Android 7.0 or newer (minSdk 24)
- An ARM64 device
- An internet connection during first launch
- At least 550 MiB of free storage is recommended

First launch:

1. The app runs a Node architecture check, normally within about three seconds.
2. It downloads the roughly 117.2 MiB runtime in verified chunks, with resume and per-chunk retries.
3. It extracts DSH and the toolchain, then validates the runtime.
4. Open Model center and enter a Command Code or DeepSeek API key.
5. Fetch the provider's live model catalog, select a model, and start a new session.

Installing a newer APK over an existing build preserves sessions, keys, projects, task history, and settings when the signing certificate is unchanged. Do not uninstall the old build first; uninstalling clears the app's private data.

## Highlights

### Full DSH experience

- Runs an Android/bionic Node.js build and the DSH WebUI from app-private storage.
- Gives the agent access to bundled `git`, `rg`, `fd`, `jq`, `bash`, and Python tools.
- Uses a foreground service so work can continue in the background or while the screen is locked.
- Combines WebSocket and page probes to distinguish idle, running, awaiting approval, recovering, and finished states.
- The notification shows start time, elapsed time, and connection state, and alerts when approval is required or background work finishes.

### Model center

Two provider routes are available:

| Provider | Catalog source | Read-only endpoint |
|---|---|---|
| Command Code | Fetched from Command Code whenever the picker opens or refreshes | `GET https://api.commandcode.ai/provider/v1/models` |
| DeepSeek direct | Fetched directly from DeepSeek whenever the picker opens or refreshes | `GET https://api.deepseek.com/models` |

The picker no longer presents a bundled preset as the provider's model list. It displays only model IDs returned by the current upstream response. Discovery reads the catalog only: it sends no prompt and makes no billed generation request.

The upstream catalog is authoritative for model IDs: every model returned by the provider's current `/models` response can be selected and saved, even when the bundled capability hints have not caught up yet. Vision metadata is read from the upstream response first, then from existing configuration hints. Image, text-only, and unknown capabilities are labeled separately; unknown capability never blocks selection. An unchanged saved model remains editable during a temporary outage; first-time setup and every new model choice require a successful live fetch.

Click Refresh models to write the complete successful catalogs for saved providers directly into DSH, without changing the default model or requiring another save: Command Code goes to `llm-pi-ai.providers.commandcode.models`, while DeepSeek direct goes to `llm-deepseek-api-key.models`. DSH and the native model center use the same catalog. The update applies automatically when idle; active or unknown status defers it until idle is confirmed. Choose and save separately to change the default model. A small internal marker lets future app upgrades merge transport settings without restoring the old bundled model list.

Model center also supports:

- A global default plus per-project overrides
- `off / low / medium / high / xhigh / max` reasoning effort
- Command Code usage details
- Clear handling of rejected keys, rate limits, moved endpoints, service errors, and invalid responses
- Search across live upstream model IDs
- A visible Back to tools & settings action and Android back-gesture support

Model changes apply to new sessions. A session that has already sent a request keeps the model recorded in its own log.

### Projects, files, and sessions

- Default shared workspace: `/sdcard/DSHNative/workspace`
- Isolated named projects with optional per-project model settings
- File browsing, text editing, image preview, batch copy/move, and a recoverable trash folder
- Share files or text from another Android app into the active project and optionally create a task
- Entry points to DSH's official session search, archive, and restore UI, without copying private RPCs
- Draft recovery after a page reload or short disconnect, without automatic submission

### Updates, rollback, and diagnostics

Tools & settings → Updates & maintenance provides:

| Action | Behavior |
|---|---|
| Update runtime | Downloads only changed DSH/toolchain chunks, verifies them, and restarts the agent |
| Restore previous runtime | Restores the pre-update snapshot without changing sessions, keys, projects, or the APK |
| Check app update | Verifies version, package name, signer, and SHA-256 before opening Android's installer |
| Stable / Test channel | Stable reads `latest.json`; Test compares both `latest-test.json` and the stable manifest |

Runtime updates are transactional: snapshot first, replace second, and automatically restore after a failed validation. The diagnostics center exports a redacted ZIP containing device, layout, network, notification, runtime, rollback, and recent-log details. Credentials, session content, attachments, and project files are excluded.

### Chinese and English

Choose System, Chinese, or English under Tools & settings → Display & language. The native settings hub, Model center, Task center, project manager, update and diagnostics pages, and launcher shortcuts support English. The DSH WebUI has its own language setting.

New installations receive a language and environment guide. Upgrades with an existing `.dsh` directory are never forced through first-run onboarding.

## Opening native App tools

Recommended path: expand the DSH sidebar and select App tools at the bottom.

When the sidebar is collapsed, the visible gear belongs to DSH's own web settings and is not intercepted by the Android shell. Backup entry points remain available:

- Long-press the top of the page
- Use Settings in the persistent notification
- Long-press the launcher icon for Settings, Runtime log, or Check updates

Every native settings child page has a visible back action. The Android back key and edge gesture follow the same parent navigation instead of silently dismissing a page or opening the app-exit confirmation.

## Files, privacy, and security boundaries

- API keys are stored only in `.credentials.yaml` under app-private storage.
- Model discovery uses the selected provider's official HTTPS endpoint. Keys, request headers, and response bodies are never written to logs.
- No privileged `JavascriptInterface` is added; the WebUI helper entry uses constrained script injection.
- Native file writes are restricted to app-private storage and the `/sdcard/DSHNative` allowlist.
- Encrypted configuration backups contain keys and therefore require a password of at least eight characters.
- App updates must keep the package name and signing certificate unchanged or Android will reject an in-place install.

## Architecture

```text
Android Activity / WebView
        |
        +-- Native tools and settings
        +-- Foreground task service and notifications
        +-- Update, rollback, backup, and diagnostics
        |
        +-- Node.js (Android/bionic, arm64)
                |
                +-- DeepSeek Harness WebUI
                +-- git / rg / fd / jq / bash / Python
                +-- Shared workspace
```

The extracted runtime is several hundred MiB, which is unsuitable for a normal single-file GitHub distribution. The APK carries Node, bootstrap logic, and manifests; the first launch downloads hash-verified runtime chunks. Later updates replace changed chunks only.

Starting with Android 10, ordinary apps targeting SDK 29 or newer cannot execute files directly from private data storage. This project currently fixes `targetSdkVersion` at 28 to preserve its native execution architecture. It is an explicit architectural constraint and must not be changed in a routine feature pull request.

## Build and test

The build requires JDK 17/21, Android SDK build-tools, `d8`, `aapt2`, and `apksigner`.

```bash
bash scripts/run_tests.sh
bash scripts/build_bootstrap.sh
```

The current regression suite includes:

- 1,049 pure-logic assertions
- WebUI App tools DOM simulation
- WebSocket recovery simulation
- Draft recovery simulation
- Session-status fetch and DOM simulation
- Runtime snapshot/restore simulation
- Java architecture, resource XML, no-emoji, no-system-AlertDialog, and no-privileged-bridge gates
- Android CI validation of javac, DEX, aapt2, signing, manifest, and APK output

Releases must go through `.github/workflows/release.yml` and `scripts/release.sh`. Do not upload an APK manually, edit update manifests by hand, or migrate the signing key.

## Repository layout

```text
src/dev/dsh/nativeapp/   Android shell, Model center, task, and utility panels
payload/                 Bootstrap, extraction, snapshot, and runtime helpers
patch/                   Android/bionic compatibility patches for DSH
tests/                   Pure-logic tests and JavaScript simulations
icon/                    Icons, themes, motion, and localized shortcut resources
scripts/                 Build, CI, signer verification, and release scripts
docs/                    Architecture, gotchas, handover, and acceptance notes
release-notes/           Published release notes
```

## Known limitations

- Only ARM64 builds are currently provided.
- First installation requires a network connection to download the runtime.
- `targetSdk 28` is required by the current private-directory execution design; a long-term migration needs `nativeLibraryDir` or another supported architecture.
- Upstream `/models` responses usually expose IDs, not full image, context, and reasoning capabilities. Every ID returned by the live response is selectable; missing local capability metadata only hides the corresponding image/reasoning hint and never blocks use.
- A model whose upstream response has no capability fields is written with safe text-only baseline metadata. It remains selectable and usable for new sessions; image and reasoning hints appear only when the app has reliable metadata.
- The large Office-to-PDF conversion engine is not bundled to keep runtime size manageable.
- Runtime restore rolls back DSH and its tools, not the Android APK.

## Documentation

| Document | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Development rules, red lines, layout, and commands |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Two-stage runtime, patches, and component boundaries |
| [docs/GOTCHAS.md](docs/GOTCHAS.md) | Android, networking, layout, update, and release pitfalls |
| [docs/HANDOVER.md](docs/HANDOVER.md) | Current branch facts, signer rules, and handover state |
| [docs/BUILD.md](docs/BUILD.md) | Local/CI build, validation, and release flow |
| [docs/PHASE5A-TASK-RECOVERY.md](docs/PHASE5A-TASK-RECOVERY.md) | Task state, reconnect recovery, and draft protection |
| [docs/PHASE5B-FILES-SESSIONS.md](docs/PHASE5B-FILES-SESSIONS.md) | File workflows, trash, and session management |
| [docs/PHASE5C-MODEL-ONBOARDING.md](docs/PHASE5C-MODEL-ONBOARDING.md) | Model center, project overrides, and first-run setup |
| [docs/PHASE5D-ROLLBACK-DIAGNOSTICS.md](docs/PHASE5D-ROLLBACK-DIAGNOSTICS.md) | Rollback, diagnostics, accessibility, and device adaptation |
| [docs/PHASE5E-MODEL-CATALOG-I18N.md](docs/PHASE5E-MODEL-CATALOG-I18N.md) | Live provider catalogs, back navigation, and English completion |
| [docs/PHASE5F-MODEL-RUNTIME-SYNC.md](docs/PHASE5F-MODEL-RUNTIME-SYNC.md) | Persisting live catalogs into DSH and WebUI synchronization |

## License and upstream projects

This repository is licensed under the MIT License. The Android Node.js build comes from the Termux distribution and retains all upstream licenses.

- DeepSeek Harness: <https://github.com/deepseek-ai/deepseek-harness>
- Termux: <https://github.com/termux/termux-app>
- Android 10 behavior changes: <https://developer.android.com/about/versions/10/behavior-changes-10>

