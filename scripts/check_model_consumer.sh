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
gh release download "$TAG" --repo yusheng266186-beep/DSH-Native \
    --pattern dsh.tar.zst --pattern SHA256SUMS.txt --dir "$PROBE"
(cd "$PROBE"; awk '$2 ~ /(^|\/)dsh\.tar\.zst$/ {print $1 "  dsh.tar.zst"}' SHA256SUMS.txt > dsh.sha256;
    test -s dsh.sha256; sha256sum -c dsh.sha256)
mkdir "$PROBE/runtime" "$PROBE/classes"
tar --zstd --no-same-owner -xf "$PROBE/dsh.tar.zst" -C "$PROBE/runtime"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac);
else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$PROBE/classes" \
    "$ROOT"/src/dev/dsh/nativeapp/{ModelConfig,ModelReasoning,ModelEffortUi,ModelCatalogSync,LiveModelCatalog,ProviderCheck,MobileLayout,SessionProbe,ConnectionRecovery,DraftRecovery,LayoutProbe}.java \
    "$ROOT"/tests/{ModelCatalogSyncTest,ModelReasoningTest,ModelEffortUiTest,MobileLayoutTest,LayoutProbeTest}.java
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelCatalogSyncTest --dump-config > "$PROBE/models.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/models.yaml"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelReasoningTest \
    --dump-config "$ROOT/tests/fixtures/reasoning-models-response.json" > "$PROBE/reasoning.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/reasoning.yaml" \
    "$ROOT/tests/fixtures/command-code-reasoning-1.72.4.json" "$PROBE/catalog.json"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelEffortUiTest --dump-client \
    "$PROBE/runtime/node_modules/@deepseek-ai/dsh-client-ui-model-selection/lib/client.js" > "$PROBE/client.js"
npm ci --prefix "$ROOT/tests/js" --no-audit --no-fund
node "$ROOT/tests/js/composer-effort-consumer.mjs" "$PROBE/runtime" "$PROBE/client.js" "$PROBE/catalog.json"
node --expose-internals "$ROOT/tests/js/core-runtime-consumer.mjs" "$PROBE/runtime"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.MobileLayoutTest --dump-html \
    "$PROBE/runtime/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html" 480 > "$PROBE/mobile.html"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.LayoutProbeTest --dump-script > "$PROBE/layout-probe.js"
"$ROOT/tests/js/node_modules/.bin/playwright" install --with-deps chromium
node "$ROOT/tests/js/mobile-layout-consumer.mjs" "$PROBE/runtime" "$PROBE/mobile.html" "$PROBE/layout-probe.js"
