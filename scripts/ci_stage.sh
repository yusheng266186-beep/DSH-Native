#!/usr/bin/env bash
# 在 CI（Linux glibc）上准备构建工作区。
#
# 与设备上的构建只差两处：
#   1. aapt2 / d8 / apksigner / android.jar 用**官方 Linux build-tools**
#      （设备上用的是 Termux 的 bionic aapt2，两者不通用）
#   2. node 与 10 个 .so 从**上一个已发布的 APK** 里取
#      —— 它们与 APK 版本无关、体积 93MB，不该进 git 历史
#
# 用法：ANDROID_HOME=... bash scripts/ci_stage.sh <构建目录>
set -euo pipefail

BUILD="${1:?用法: ci_stage.sh <构建目录>}"
PREV_TAG="${PREV_TAG:-v0.23.3-bootstrap}"
REPO="${REPO:-yusheng266186-beep/DSH-Native}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || { echo "[FAIL] 缺少 ANDROID_HOME"; exit 1; }

say() { printf '\n== %s ==\n' "$*"; }

say "1. 目录骨架"
rm -rf "$BUILD"
mkdir -p "$BUILD/bootstrap" "$BUILD/sdk" "$BUILD/tools" "$BUILD/icon" \
         "$BUILD/staging/data/data/com.termux/files/usr/bin" "$BUILD/staging/lib"

say "2. 仓库源码 → 构建工作区"
cp -r "$ROOT/src" "$BUILD/bootstrap/src"
cp -r "$ROOT/payload" "$BUILD/bootstrap/payload"
cp "$ROOT/scripts/release.keystore" "$BUILD/bootstrap/release.keystore"
cp -r "$ROOT/tests" "$BUILD/tests"
cp "$ROOT/scripts/mkmanifest.py" "$ROOT/scripts/mkzip.py" "$ROOT/scripts/run_tests.sh" "$BUILD/"
cp -r "$ROOT/icon/res" "$BUILD/icon/res"
echo "  源码 $(find "$BUILD/bootstrap/src" -name '*.java' | wc -l) 个 java 文件"

say "3. Android SDK 部件（官方 Linux 版）"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "[FAIL] 找不到 build-tools"; exit 1; }
echo "  build-tools: $BT"
[ -f "$ANDROID_HOME/platforms/android-28/android.jar" ] || { echo "[FAIL] 缺 platforms;android-28"; exit 1; }
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || { echo "[FAIL] 缺 platforms;android-34"; exit 1; }
cp "$ANDROID_HOME/platforms/android-28/android.jar" "$BUILD/sdk/android.jar"
cp "$ANDROID_HOME/platforms/android-34/android.jar" "$BUILD/sdk/android-modern.jar"
cp "$BT/lib/d8.jar" "$BUILD/tools/d8.jar"
cp "$BT/lib/apksigner.jar" "$BUILD/tools/apksigner.jar"
cp "$BT/aapt2" "$BUILD/staging/data/data/com.termux/files/usr/bin/aapt2"
chmod +x "$BUILD/staging/data/data/com.termux/files/usr/bin/aapt2"
echo "  aapt2: $("$BUILD/staging/data/data/com.termux/files/usr/bin/aapt2" version 2>&1 | head -1)"

say "4. 从上一个 APK 取内置负载（node + 10 个 .so）"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
URL="https://github.com/${REPO}/releases/download/${PREV_TAG}/DSHNative-bootstrap.apk"
echo "  下载 $URL"
curl -sSL --retry 3 -o "$TMP/prev.apk" "$URL"
[ -s "$TMP/prev.apk" ] || { echo "[FAIL] 上一个 APK 下载失败"; exit 1; }
unzip -q -o "$TMP/prev.apk" 'assets/payload/*' -d "$TMP/x"
[ -f "$TMP/x/assets/payload/node" ] || { echo "[FAIL] APK 里没有 assets/payload/node"; exit 1; }
cp "$TMP/x/assets/payload/node" "$BUILD/staging/data/data/com.termux/files/usr/bin/node"
chmod +x "$BUILD/staging/data/data/com.termux/files/usr/bin/node"
cp "$TMP/x/assets/payload/lib/"*.so "$BUILD/staging/lib/"
echo "  node: $(du -h "$BUILD/staging/data/data/com.termux/files/usr/bin/node" | cut -f1)"
echo "  共享库: $(ls "$BUILD/staging/lib" | wc -l) 个"

say "5. 工作区就绪"
echo "  BUILD=$BUILD"
echo "  构建时请设：DSH_AAPT2_LIB=$BT/lib64"
