#!/usr/bin/env bash
# CI 上的构建入口：装 SDK 部件 → 准备构建工作区 → 跑 build_bootstrap.sh。
#
# 由 build.yml（每次提交验证）与 release.yml（发版）共用 ——
# 两处各写一份构建步骤必然会漂移，而构建步骤一旦漂移，
# 「CI 绿了」就不再代表「发出去的包是这样构建的」。
#
# 用法：bash scripts/ci_build.sh <构建目录>
set -euo pipefail

BUILD="${1:?用法: ci_build.sh <构建目录>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"

say() { printf '\n== %s ==\n' "$*"; }

say "安装 SDK 部件"
SM="$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1)"
[ -n "$SM" ] || { echo "[FAIL] 找不到 sdkmanager（SDK=$SDK）"; exit 1; }
yes | "$SM" --sdk_root="$SDK" --install \
    "platforms;android-28" "platforms;android-34" "build-tools;34.0.0" >/dev/null 2>&1 || true

# 固定 build-tools 版本：runner 预装的更新版本会被 sort -V 选中，
# 固定它才能让 CI 的构建行为可复现。
BT="$SDK/build-tools/34.0.0"
[ -d "$BT" ] || BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
for j in "$SDK/platforms/android-28/android.jar" \
         "$SDK/platforms/android-34/android.jar" \
         "$BT/lib/d8.jar" "$BT/lib/apksigner.jar" "$BT/aapt2"; do
    [ -e "$j" ] || { echo "[FAIL] 缺少 $j"; exit 1; }
    echo "  [OK] $j"
done

say "准备构建工作区"
BT="$BT" bash "$ROOT/scripts/ci_stage.sh" "$BUILD"

say "构建（断言 + 三道闸门 + 编译 + 打包 + 签名）"
DSH_BUILD_DIR="$BUILD" DSH_AAPT2_LIB="$BT/lib64" \
    bash "$ROOT/scripts/build_bootstrap.sh"

APK="$BUILD/bootstrap/DSHNative-bootstrap.apk"
[ -f "$APK" ] || { echo "[FAIL] 未生成 APK"; exit 1; }
say "产物"
ls -l "$APK"
sha256sum "$APK"
