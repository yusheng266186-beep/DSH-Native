#!/usr/bin/env bash
# Exercise the App's admission rules against its pinned published manifest.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CHECK="$(mktemp -d)"
trap 'rm -rf "$CHECK"' EXIT
MANIFEST="${1:-}"
if [ -z "$MANIFEST" ]; then
    TAG="$(python3 - "$ROOT/src/dev/dsh/nativeapp/MainActivity.java" <<'PY'
import pathlib, re, sys
source = pathlib.Path(sys.argv[1]).read_text()
tag = re.search(r'releases/download/(payload-v[0-9]+)/', source)
if not tag:
    raise SystemExit('payload tag missing')
print(tag.group(1))
PY
)"
    gh release download "$TAG" --repo yusheng266186-beep/DSH-Native \
        --pattern manifest.json --dir "$CHECK"
    MANIFEST="$CHECK/manifest.json"
fi
mkdir "$CHECK/classes"
if command -v javac >/dev/null 2>&1; then JAVAC=(javac);
else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$CHECK/classes" \
    "$ROOT"/src/dev/dsh/nativeapp/{PayloadManifest,PayloadUpdate,JsonValue}.java \
    "$ROOT/tests/PayloadManifestTest.java"
java -Dfile.encoding=UTF-8 -cp "$CHECK/classes" dev.dsh.nativeapp.PayloadManifestTest \
    --check-file "$MANIFEST" "$ROOT/src/dev/dsh/nativeapp/MainActivity.java"
