#!/usr/bin/env bash
# 纯逻辑测试：FileListing 不含 Android 依赖，可在普通 JVM 上直接跑。
#
# 为什么要有这一步：文件浏览的排序、边界判断、格式化最容易出错，
# 而它们完全不需要设备就能验证。构建期跑一遍，
# 改坏立刻失败 —— 而不是等装到手机上靠截图发现。
set -euo pipefail
cd "$(dirname "$0")"

SRC=bootstrap/src/dev/dsh/nativeapp/FileListing.java
TEST=tests/FileListingTest.java
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -nowarn -d "$OUT" "$SRC" "$TEST"
RESULT=$(java -cp "$OUT" dev.dsh.nativeapp.FileListingTest 2>&1) || {
    echo "$RESULT" | tail -30
    echo "✗ 纯逻辑测试失败"
    exit 1
}
TAIL=$(echo "$RESULT" | tail -1)
echo "  $TAIL"
if echo "$TAIL" | grep -qE '/ [1-9][0-9]* 失败'; then
    echo "$RESULT" | tail -30
    exit 1
fi
