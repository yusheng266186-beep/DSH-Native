#!/usr/bin/env bash
# Validate configuration and touch layouts against the payload installed by the App.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBE="$(mktemp -d)"
trap 'rm -rf "$PROBE"' EXIT
TAG="$(python3 - "$ROOT/src/dev/dsh/nativeapp/MainActivity.java" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'releases/download/(payload-v[0-9]+)/', s)
if not m:
    raise SystemExit('payload tag missing')
print(m.group(1))
PY
)"
if [ -n "${DSH_CONSUMER_PAYLOAD_DIR:-}" ]; then
    # Pre-publication local run: the same APK-pinned manifest and full archive digests still apply.
    for asset in dsh.tar.zst SHA256SUMS.txt manifest.json; do
        cp "$DSH_CONSUMER_PAYLOAD_DIR/$asset" "$PROBE/$asset"
    done
else
if ! gh release download "$TAG" --repo yusheng266186-beep/DSH-Native \
    --pattern dsh.tar.zst --pattern SHA256SUMS.txt --pattern manifest.json --dir "$PROBE" 2>"$PROBE/download.log"; then
    echo "Release API download failed; retrying the same official public assets over HTTPS."
    for asset in dsh.tar.zst SHA256SUMS.txt manifest.json; do
        curl -fL --retry 3 --connect-timeout 20 --max-time 300 \
            -o "$PROBE/$asset" "https://github.com/yusheng266186-beep/DSH-Native/releases/download/$TAG/$asset"
    done
fi
fi
bash "$ROOT/scripts/check_payload_manifest.sh" "$PROBE/manifest.json"
(cd "$PROBE"; awk '$2 ~ /(^|\/)dsh\.tar\.zst$/ {print $1 "  dsh.tar.zst"}' SHA256SUMS.txt > dsh.sha256;
    test -s dsh.sha256; sha256sum -c dsh.sha256)
mkdir "$PROBE/runtime" "$PROBE/classes"
node "$ROOT/payload/unpack.js" "$PROBE/dsh.tar.zst" "$PROBE/runtime"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac);
else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$PROBE/classes" \
    "$ROOT"/src/dev/dsh/nativeapp/{CodeBuddyCatalog,ModelConfig,ModelReasoning,ModelEffortUi,ModelCatalogSync,LiveModelCatalog,ProviderCheck,JsonValue,CoreRpcClient,CoreReadiness,ModelSettingsSnapshot,YamlBlocks,WebToolsEntry,MobileLayout,SessionProbe,SessionStatus,ConnectionRecovery,DraftRecovery,LayoutProbe,SessionPersistencePatch,AccountUi,AppSettingsUi,FileActionsUi}.java \
    "$ROOT"/src/dev/dsh/nativeapp/{StartupState,StartupReuse,CoreUpgrade,CoreTaskState,VoiceUi,FsWritePatch,NativeBridgePolicy,NativeBridgeServer,FileOps,ShareTargets}.java \
    "$ROOT"/tests/{ModelCatalogSyncTest,ModelReasoningTest,ModelEffortUiTest,MobileLayoutTest,LayoutProbeTest,WebToolsEntryTest,CoreRpcConsumer,SessionPersistencePatchTest,AccountUiTest,AppSettingsUiTest,FileActionsUiTest,SessionStatusTest,ConnectionRecoveryTest,StartupStateTest,CoreUpgradeTest,VoiceUiTest,FsWritePatchTest,NativeBridgeTest}.java
"${JAVAC[@]}" -encoding UTF-8 --release 8 -cp "$PROBE/classes" -d "$PROBE/classes" "$ROOT"/tools/java/dev/dsh/nativeapp/*.java
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.AndroidRuntimePort "$PROBE/runtime" "$ROOT/payload" --verify
cmp "$PROBE/runtime/android-runtime-files.tsv" "$ROOT/payload/android-port-files.tsv"
node "$ROOT/tests/js/connection-consumer.mjs" "$PROBE/runtime"
node "$ROOT/tests/js/upgrade-runtime-consumer.mjs" "$PROBE/runtime" "$PROBE/manifest.json" "$PROBE/classes"
node "$ROOT/tests/js/provider-cleanup-consumer.mjs" "$PROBE/runtime"
node --expose-internals "$ROOT/tests/js/fs-write-consumer.mjs" "$PROBE/runtime"
node --expose-internals "$ROOT/tests/js/runtime-environment-consumer.mjs" "$PROBE/runtime"
node --expose-internals "$ROOT/tests/js/android-bridge-consumer.mjs" "$PROBE/runtime" "$PROBE/classes"
node --expose-internals "$ROOT/tests/js/session-publish-consumer.mjs" "$PROBE/runtime"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelCatalogSyncTest --dump-config > "$PROBE/models.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/models.yaml"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelReasoningTest \
    --dump-config "$ROOT/tests/fixtures/reasoning-models-response.json" > "$PROBE/reasoning.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/reasoning.yaml" \
    "$ROOT/tests/fixtures/codebuddy-cli-catalog.json" "$PROBE/catalog.json"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelEffortUiTest --dump-client \
    "$PROBE/runtime/node_modules/@deepseek-ai/dsh-client-ui-model-selection/lib/client.js" > "$PROBE/client.js"
npm ci --prefix "$ROOT/tests/js" --no-audit --no-fund
"$ROOT/tests/js/node_modules/.bin/playwright" install --with-deps chromium
node "$ROOT/tests/js/android-voice-simulation.mjs"
node "$ROOT/tests/js/composer-effort-consumer.mjs" "$PROBE/runtime" "$PROBE/client.js" "$PROBE/catalog.json"
node --expose-internals "$ROOT/tests/js/core-runtime-consumer.mjs" "$PROBE/runtime"
node --expose-internals "$ROOT/tests/js/cache-meter-consumer.mjs" "$PROBE/runtime"
SDK_ASSETS="${DSH_CODEBUDDY_ASSETS:-${RUNNER_TEMP:-$PROBE}/dsh-build/codebuddy-check}"
if [ ! -f "$SDK_ASSETS/codebuddy-sdk-manifest.json" ]; then
    SDK_ASSETS="$PROBE/codebuddy-check"
    python3 "$ROOT/scripts/prepare_codebuddy_sdk.py" "$SDK_ASSETS"
fi
node --expose-internals "$ROOT/tests/js/codebuddy-conversation-consumer.mjs" "$PROBE/runtime" "$SDK_ASSETS"
java -cp "$PROBE/classes" dev.dsh.nativeapp.StartupStateTest --dump-script > "$PROBE/startup.js"
node --expose-internals "$ROOT/tests/js/startup-consumer.mjs" "$PROBE/runtime" "$PROBE/startup.js"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.MobileLayoutTest --dump-html \
    "$PROBE/runtime/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html" 480 > "$PROBE/mobile.html"
node --expose-internals "$ROOT/tests/js/deepseek-account-consumer.mjs" "$PROBE/runtime" "$PROBE/classes" "$PROBE/mobile.html"
node --expose-internals "$ROOT/tests/js/app-settings-consumer.mjs" "$PROBE/runtime" "$PROBE/mobile.html" "$PROBE/classes"
python3 "$ROOT/tests/check_native_theme.py" --consumer "$PROBE/runtime" "$PROBE/mobile.html"
python3 "$ROOT/tests/check_web_settings.py" --consumer "$PROBE/runtime" "$PROBE/mobile.html"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.LayoutProbeTest --dump-script > "$PROBE/layout-probe.js"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.WebToolsEntryTest --dump-script > "$PROBE/web-tools.js"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.SessionStatusTest --dump-touch-script > "$PROBE/touch-menu.js"
node "$ROOT/tests/js/mobile-layout-consumer.mjs" "$PROBE/runtime" "$PROBE/mobile.html" "$PROBE/layout-probe.js" "$PROBE/web-tools.js" "$PROBE/touch-menu.js"
