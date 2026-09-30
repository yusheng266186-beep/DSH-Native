#!/usr/bin/env bash
# Validate generated configuration against the payload actually installed by the App.
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
    "$ROOT"/src/dev/dsh/nativeapp/{ModelConfig,ModelReasoning,ModelCatalogSync,LiveModelCatalog,ProviderCheck}.java \
    "$ROOT/tests/ModelCatalogSyncTest.java" "$ROOT/tests/ModelReasoningTest.java"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelCatalogSyncTest --dump-config > "$PROBE/models.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/models.yaml"
java -Dfile.encoding=UTF-8 -cp "$PROBE/classes" dev.dsh.nativeapp.ModelReasoningTest \
    --dump-config "$ROOT/tests/fixtures/reasoning-models-response.json" > "$PROBE/reasoning.yaml"
node --expose-internals "$ROOT/tests/js/model-catalog-consumer.mjs" "$PROBE/runtime" "$PROBE/reasoning.yaml" \
    "$ROOT/tests/fixtures/command-code-reasoning-1.72.4.json"
node --expose-internals "$ROOT/tests/js/core-runtime-consumer.mjs" "$PROBE/runtime"
