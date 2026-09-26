#!/usr/bin/env bash
# 纯逻辑测试。
#
# FileListing / TextCodec 都不含 Android 依赖，可在普通 JVM 上直接跑。
# 它们承载的是最容易出错、又最难靠肉眼发现的部分 ——
# 排序顺序、边界判断、编码与换行判定（错了会静默损坏用户文件）。
# 放进构建流程后，改坏立刻失败，不必等装到手机上靠截图发现。
set -euo pipefail
# 定位仓库根与源码目录。
#
# 脚本在两处运行，布局不同：
#   * 仓库克隆    <root>/scripts/run_tests.sh  → 源码在 <root>/src/…
#   * 构建工作区  <root>/run_tests.sh          → 源码在 <root>/bootstrap/src/…
# 所以从脚本所在目录**逐级向上找**，认准「同时有 tests/ 和源码目录」的那一层。
here="$(cd "$(dirname "$0")" && pwd)"
ROOT=""
JAVA_DIR=""
for up in "$here" "$here/.." "$here/../.."; do
    if [ -d "$up/tests" ] && [ -d "$up/src/dev/dsh/nativeapp" ]; then
        ROOT="$up"; JAVA_DIR="src/dev/dsh/nativeapp"; break
    fi
    if [ -d "$up/tests" ] && [ -d "$up/bootstrap/src/dev/dsh/nativeapp" ]; then
        ROOT="$up"; JAVA_DIR="bootstrap/src/dev/dsh/nativeapp"; break
    fi
done
if [ -z "$ROOT" ]; then
    echo "找不到项目根（向上找过：$here、$here/..、$here/../..）" >&2
    echo "期望某一层同时有 tests/ 和 src/dev/dsh/nativeapp（或 bootstrap/src/…）" >&2
    exit 1
fi
cd "$ROOT"
echo "项目根: $ROOT"
echo "源码目录: $JAVA_DIR"

SRC="$JAVA_DIR/FileListing.java
     $JAVA_DIR/TextCodec.java
     $JAVA_DIR/Version.java
     $JAVA_DIR/CommandCodeUsage.java
     $JAVA_DIR/TaskNotifier.java
     $JAVA_DIR/FileOps.java
     $JAVA_DIR/ConfigBackup.java
     $JAVA_DIR/ShareTargets.java
     $JAVA_DIR/PluginSpecs.java
     $JAVA_DIR/PayloadUpdate.java
     $JAVA_DIR/SessionStatus.java
     $JAVA_DIR/SessionRecovery.java
     $JAVA_DIR/ProcessSupervisor.java
     $JAVA_DIR/TransferState.java
     $JAVA_DIR/SecretMasker.java"
TESTS="tests/FileListingTest.java
       tests/TextCodecTest.java
       tests/VersionTest.java
       tests/CommandCodeUsageTest.java
       tests/TaskNotifierTest.java
       tests/FileOpsTest.java
       tests/ConfigBackupTest.java
       tests/ShareTargetsTest.java
       tests/PluginSpecsTest.java
       tests/PayloadUpdateTest.java
       tests/SessionStatusTest.java
       tests/SessionRecoveryTest.java
       tests/ProcessSupervisorTest.java
       tests/TransferStateTest.java
       tests/SecretMaskerTest.java"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -nowarn -d "$OUT" $SRC $TESTS

rc=0
for t in dev.dsh.nativeapp.FileListingTest dev.dsh.nativeapp.TextCodecTest dev.dsh.nativeapp.VersionTest dev.dsh.nativeapp.CommandCodeUsageTest dev.dsh.nativeapp.TaskNotifierTest dev.dsh.nativeapp.FileOpsTest dev.dsh.nativeapp.ConfigBackupTest dev.dsh.nativeapp.ShareTargetsTest dev.dsh.nativeapp.PluginSpecsTest dev.dsh.nativeapp.PayloadUpdateTest dev.dsh.nativeapp.SessionStatusTest dev.dsh.nativeapp.SessionRecoveryTest dev.dsh.nativeapp.ProcessSupervisorTest dev.dsh.nativeapp.TransferStateTest dev.dsh.nativeapp.SecretMaskerTest; do
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
