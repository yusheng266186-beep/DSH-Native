#!/usr/bin/env bash
# Build using a local official SDK and a checksum/signer-verified released APK.
# No GitHub API, upload, new signing key or published manifest mutation.
set -euo pipefail
BUILD="${1:?用法: local_build.sh <隔离构建目录> <已发布APK>}"
REFERENCE="${2:?用法: local_build.sh <隔离构建目录> <已发布APK>}"
BUILD="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$BUILD")"
REFERENCE="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$REFERENCE")"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[ -n "$SDK" ] || { echo '[FAIL] 指定 ANDROID_SDK_ROOT（需要 android-28、android-34、build-tools 34.0.0）'; exit 1; }
BT="$SDK/build-tools/34.0.0"
for file in "$SDK/platforms/android-28/android.jar" "$SDK/platforms/android-34/android.jar" \
    "$BT/aapt2" "$BT/lib/d8.jar" "$BT/lib/apksigner.jar"; do
    [ -f "$file" ] || { echo "[FAIL] 缺少 $file"; exit 1; }
done
# Stage removes its output directory; refuse paths that could erase the checkout or reference.
python3 - "$BUILD" "$ROOT" "$REFERENCE" <<'PY'
import pathlib, sys
build, root, reference = (pathlib.Path(p).resolve() for p in sys.argv[1:])
if build == pathlib.Path('/') or build == pathlib.Path.home() or root == build or build in root.parents or reference == build or build in reference.parents:
    raise SystemExit('[FAIL] 构建目录必须是独立临时目录，不能包含源码或参考 APK')
PY
ANDROID_HOME="$SDK" BT="$BT" DSH_PREVIOUS_APK="$REFERENCE" \
    bash "$ROOT/scripts/ci_stage.sh" "$BUILD"
DSH_BUILD_DIR="$BUILD" DSH_AAPT2_LIB="$BT/lib64" \
    bash "$ROOT/scripts/build_bootstrap.sh"
APK="$BUILD/bootstrap/DSHNative-bootstrap.apk"
APKSIGNER_JAR="$BT/lib/apksigner.jar" bash "$ROOT/scripts/check_signer.sh" "$APK"
"$BT/aapt2" dump badging "$APK" | head -3
python3 - "$APK" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
with p.open('rb') as f:
    for block in iter(lambda: f.read(1024 * 1024), b''): digest.update(block)
digest = digest.hexdigest()
print(f'APK: {p}\nBytes: {p.stat().st_size}\nSHA-256: {digest}')
PY
