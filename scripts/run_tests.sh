#!/usr/bin/env bash
# 纯逻辑测试。
#
# FileListing / TextCodec 都不含 Android 依赖，可在普通 JVM 上直接跑。
# 它们承载的是最容易出错、又最难靠肉眼发现的部分 ——
# 排序顺序、边界判断、编码与换行判定（错了会静默损坏用户文件）。
# 放进构建流程后，改坏立刻失败，不必等装到手机上靠截图发现。
set -euo pipefail
cd "$(dirname "$0")"

SRC="bootstrap/src/dev/dsh/nativeapp/FileListing.java
     bootstrap/src/dev/dsh/nativeapp/TextCodec.java
     bootstrap/src/dev/dsh/nativeapp/Version.java
     bootstrap/src/dev/dsh/nativeapp/CommandCodeUsage.java
     bootstrap/src/dev/dsh/nativeapp/TaskNotifier.java"
TESTS="tests/FileListingTest.java
       tests/TextCodecTest.java
       tests/VersionTest.java
       tests/CommandCodeUsageTest.java
       tests/TaskNotifierTest.java"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -nowarn -d "$OUT" $SRC $TESTS

rc=0
for t in dev.dsh.nativeapp.FileListingTest dev.dsh.nativeapp.TextCodecTest dev.dsh.nativeapp.VersionTest dev.dsh.nativeapp.CommandCodeUsageTest dev.dsh.nativeapp.TaskNotifierTest; do
    name="${t##*.}"
    if ! out=$(java -Dfile.encoding=UTF-8 -cp "$OUT" "$t" 2>&1); then
        echo "$out" | grep -aE 'FAIL|Error|Exception' | head -10
        echo "  [FAIL] $name 失败"
        rc=1
    else
        echo "  $name: $(echo "$out" | grep -a 'TOTAL' | tail -1)"
    fi
done
exit $rc
