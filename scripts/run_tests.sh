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
     $JAVA_DIR/SecretMasker.java
     $JAVA_DIR/UiText.java
     $JAVA_DIR/MobileLayout.java
     $JAVA_DIR/WorkspaceProjects.java
     $JAVA_DIR/ShareTask.java
     $JAVA_DIR/PluginPermissions.java
     $JAVA_DIR/ReleaseChannel.java
     $JAVA_DIR/WebToolsEntry.java
     $JAVA_DIR/UiPolicy.java
     $JAVA_DIR/OperationGate.java
     $JAVA_DIR/InteractionFeedback.java"
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
       tests/SecretMaskerTest.java
       tests/UiTextTest.java
       tests/MobileLayoutTest.java
       tests/WorkspaceProjectsTest.java
       tests/ShareTaskTest.java
       tests/PluginPermissionsTest.java
       tests/ReleaseChannelTest.java
       tests/WebToolsEntryTest.java
       tests/UiPolicyTest.java
       tests/OperationGateTest.java
       tests/InteractionFeedbackTest.java"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

# Some slim JDK images expose the compiler module but omit the javac launcher.
# Use the module directly in that environment so local verification does not
# become weaker than CI merely because one small binary is absent.
if command -v javac >/dev/null 2>&1; then
    JAVAC=(javac)
else
    java --list-modules 2>/dev/null | grep -q '^jdk.compiler@' \
        || { echo "找不到 javac 或 jdk.compiler 模块" >&2; exit 1; }
    JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${JAVAC[@]}" -encoding UTF-8 -nowarn -d "$OUT" $SRC $TESTS

rc=0
for t in dev.dsh.nativeapp.FileListingTest dev.dsh.nativeapp.TextCodecTest dev.dsh.nativeapp.VersionTest dev.dsh.nativeapp.CommandCodeUsageTest dev.dsh.nativeapp.TaskNotifierTest dev.dsh.nativeapp.FileOpsTest dev.dsh.nativeapp.ConfigBackupTest dev.dsh.nativeapp.ShareTargetsTest dev.dsh.nativeapp.PluginSpecsTest dev.dsh.nativeapp.PayloadUpdateTest dev.dsh.nativeapp.SessionStatusTest dev.dsh.nativeapp.SessionRecoveryTest dev.dsh.nativeapp.ProcessSupervisorTest dev.dsh.nativeapp.TransferStateTest dev.dsh.nativeapp.SecretMaskerTest dev.dsh.nativeapp.UiTextTest dev.dsh.nativeapp.MobileLayoutTest dev.dsh.nativeapp.WorkspaceProjectsTest dev.dsh.nativeapp.ShareTaskTest dev.dsh.nativeapp.PluginPermissionsTest dev.dsh.nativeapp.ReleaseChannelTest dev.dsh.nativeapp.WebToolsEntryTest dev.dsh.nativeapp.UiPolicyTest dev.dsh.nativeapp.OperationGateTest dev.dsh.nativeapp.InteractionFeedbackTest; do
    name="${t##*.}"
    if ! out=$(java -Dfile.encoding=UTF-8 -cp "$OUT" "$t" 2>&1); then
        echo "$out" | grep -aE 'FAIL|Error|Exception' | head -10
        echo "  [FAIL] $name 失败"
        rc=1
    else
        echo "  $name: $(echo "$out" | grep -a 'TOTAL' | tail -1)"
    fi
done

# WebUI 注入不仅要有字符串断言，还要真正经过 JavaScript 语法检查与最小 DOM 模拟。
# GitHub runner 自带 Node；本地缺少 Node 时明确失败，避免这条验证静默跳过。
if ! command -v node >/dev/null 2>&1; then
    echo "  [FAIL] 找不到 node，无法验证 WebUI 工具入口脚本" >&2
    rc=1
else
    WEB_TOOLS_JS="$OUT/web-tools-entry.js"
    java -Dfile.encoding=UTF-8 -cp "$OUT" \
        dev.dsh.nativeapp.WebToolsEntryTest --dump-script > "$WEB_TOOLS_JS"
    if ! node --check "$WEB_TOOLS_JS" >/dev/null 2>&1; then
        echo "  [FAIL] WebToolsEntry JavaScript 语法错误" >&2
        rc=1
    elif ! out=$(node tests/js/web-tools-entry-simulation.js "$WEB_TOOLS_JS" 2>&1); then
        echo "$out"
        echo "  [FAIL] WebToolsEntry DOM 模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

# 原生接线回归：入口必须随页面加载注入，旧的 WebView 覆盖按钮不得回流，
# 同时禁止为了打开设置而新增高权限 JavaScriptInterface。
MAIN_ACTIVITY="$JAVA_DIR/MainActivity.java"
if grep -q 'quickToolsButton' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 旧的悬浮工具按钮重新出现在 MainActivity" >&2
    rc=1
elif grep -q 'addJavascriptInterface' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] MainActivity 不得向 WebUI 暴露 JavascriptInterface" >&2
    rc=1
elif ! grep -q 'installWebToolsEntry();' "$MAIN_ACTIVITY" \
        || ! grep -q 'WebToolsEntry.isReady' "$MAIN_ACTIVITY" \
        || ! grep -q 'WebToolsEntry.isMissing' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] WebUI 工具入口的页面加载或控制台接线不完整" >&2
    rc=1
else
    echo "  WebToolsEntryWiring: overlay removed / injection wired / no JavascriptInterface"
fi

# 阶段四 B 接线回归：维护任务必须互斥，两条键盘路径必须合并并
# 保留安全区，快速连按返回键不得绕过确认直接销毁 Activity。
if grep -q 'super.onBackPressed();' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 返回确认仍可被连按绕过" >&2
    rc=1
elif ! grep -q 'maintenanceGate.tryStart' "$MAIN_ACTIVITY" \
        || ! grep -q 'UiPolicy.mergedIme' "$MAIN_ACTIVITY" \
        || ! grep -q 'stopSplashAnimation();' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 阶段四 B 交互/生命周期接线不完整" >&2
    rc=1
else
    echo "  Phase4BExperienceWiring: maintenance gated / insets merged / splash stopped / back guarded"
fi

# 阶段四 C 接线回归：长任务必须有持续反馈，项目切换必须先收起
# 原面板再展示精确的重启状态，按钮动效必须共用统一策略。
if ! grep -q 'DshUi.taskProgress' "$MAIN_ACTIVITY" \
        || ! grep -q 'finishShareTaskSubmission' "$MAIN_ACTIVITY" \
        || ! grep -q 'origin.dismiss();' "$MAIN_ACTIVITY" \
        || ! grep -q 'InteractionFeedback.PRESSED_SCALE' "$JAVA_DIR/DshUi.java"; then
    echo "  [FAIL] 阶段四 C 动效/持续反馈/项目切换接线不完整" >&2
    rc=1
else
    echo "  Phase4CInteractionWiring: motion unified / progress persistent / switch visible"
fi
exit $rc
