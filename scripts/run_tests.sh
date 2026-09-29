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

# 仓库内脚本位于 <root>/payload；CI 的隔离构建工作区则把仓库源码
# 放在 <root>/bootstrap 下。测试必须和真正进入 APK 的那份脚本使用
# 同一路径，避免本地通过、CI 却在测试阶段找不到模块。
PAYLOAD_DIR="$ROOT/payload"
if [ ! -d "$PAYLOAD_DIR" ]; then
    PAYLOAD_DIR="$ROOT/bootstrap/payload"
fi
[ -d "$PAYLOAD_DIR" ] || { echo "找不到 payload 目录" >&2; exit 1; }

SRC="$JAVA_DIR/FileListing.java
     $JAVA_DIR/TextCodec.java
     $JAVA_DIR/Version.java
     $JAVA_DIR/CommandCodeUsage.java
     $JAVA_DIR/TaskNotifier.java
     $JAVA_DIR/TaskTimeline.java
     $JAVA_DIR/ConnectionRecovery.java
     $JAVA_DIR/DraftRecovery.java
     $JAVA_DIR/FileBatch.java
     $JAVA_DIR/FileTrash.java
     $JAVA_DIR/FilePreview.java
     $JAVA_DIR/FileOps.java
     $JAVA_DIR/ConfigBackup.java
     $JAVA_DIR/ShareTargets.java
     $JAVA_DIR/PluginSpecs.java
     $JAVA_DIR/PayloadUpdate.java
     $JAVA_DIR/SessionStatus.java
     $JAVA_DIR/SessionProbe.java
     $JAVA_DIR/SessionRecovery.java
     $JAVA_DIR/SessionOrganizer.java
     $JAVA_DIR/ModelConfig.java
     $JAVA_DIR/ModelCatalogSync.java
     $JAVA_DIR/ProviderCheck.java
     $JAVA_DIR/LiveModelCatalog.java
     $JAVA_DIR/ProjectModelSettings.java
     $JAVA_DIR/ProcessSupervisor.java
     $JAVA_DIR/TransferState.java
     $JAVA_DIR/SecretMasker.java
     $JAVA_DIR/DiagnosticReport.java
     $JAVA_DIR/UiText.java
     $JAVA_DIR/MobileLayout.java
     $JAVA_DIR/DeviceLayout.java
     $JAVA_DIR/PayloadRollback.java
     $JAVA_DIR/WorkspaceProjects.java
     $JAVA_DIR/ShareTask.java
     $JAVA_DIR/PluginPermissions.java
     $JAVA_DIR/ReleaseChannel.java
     $JAVA_DIR/WebToolsEntry.java
     $JAVA_DIR/UiPolicy.java
     $JAVA_DIR/OperationGate.java
     $JAVA_DIR/InteractionFeedback.java
     $JAVA_DIR/CrashReporter.java
     $JAVA_DIR/WorkerRegistry.java
     $JAVA_DIR/ProviderRoute.java"
TESTS="tests/FileListingTest.java
       tests/TextCodecTest.java
       tests/VersionTest.java
       tests/CommandCodeUsageTest.java
       tests/TaskNotifierTest.java
       tests/TaskTimelineTest.java
       tests/ConnectionRecoveryTest.java
       tests/DraftRecoveryTest.java
       tests/FileBatchTest.java
       tests/FileTrashTest.java
       tests/FilePreviewTest.java
       tests/FileOpsTest.java
       tests/ConfigBackupTest.java
       tests/ShareTargetsTest.java
       tests/PluginSpecsTest.java
       tests/PayloadUpdateTest.java
       tests/SessionStatusTest.java
       tests/SessionProbeTest.java
       tests/SessionRecoveryTest.java
       tests/SessionOrganizerTest.java
       tests/ModelConfigTest.java
       tests/ModelCatalogSyncTest.java
       tests/ProviderCheckTest.java
       tests/LiveModelCatalogTest.java
       tests/ProjectModelSettingsTest.java
       tests/ProcessSupervisorTest.java
       tests/TransferStateTest.java
       tests/SecretMaskerTest.java
       tests/DiagnosticReportTest.java
       tests/UiTextTest.java
       tests/MobileLayoutTest.java
       tests/DeviceLayoutTest.java
       tests/PayloadRollbackTest.java
       tests/WorkspaceProjectsTest.java
       tests/ShareTaskTest.java
       tests/PluginPermissionsTest.java
       tests/ReleaseChannelTest.java
       tests/WebToolsEntryTest.java
       tests/UiPolicyTest.java
       tests/OperationGateTest.java
       tests/InteractionFeedbackTest.java
       tests/CrashReporterTest.java
       tests/WorkerRegistryTest.java
       tests/ProviderRouteTest.java"
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
seen_tests=" "
for t in dev.dsh.nativeapp.FileListingTest dev.dsh.nativeapp.TextCodecTest dev.dsh.nativeapp.VersionTest dev.dsh.nativeapp.CommandCodeUsageTest dev.dsh.nativeapp.TaskNotifierTest dev.dsh.nativeapp.TaskTimelineTest dev.dsh.nativeapp.ConnectionRecoveryTest dev.dsh.nativeapp.DraftRecoveryTest dev.dsh.nativeapp.FileBatchTest dev.dsh.nativeapp.FileTrashTest dev.dsh.nativeapp.FilePreviewTest dev.dsh.nativeapp.FileOpsTest dev.dsh.nativeapp.ConfigBackupTest dev.dsh.nativeapp.ShareTargetsTest dev.dsh.nativeapp.PluginSpecsTest dev.dsh.nativeapp.PayloadUpdateTest dev.dsh.nativeapp.PayloadRollbackTest dev.dsh.nativeapp.SessionStatusTest dev.dsh.nativeapp.SessionProbeTest dev.dsh.nativeapp.SessionRecoveryTest dev.dsh.nativeapp.SessionOrganizerTest dev.dsh.nativeapp.ModelConfigTest dev.dsh.nativeapp.ModelCatalogSyncTest dev.dsh.nativeapp.ProviderCheckTest dev.dsh.nativeapp.LiveModelCatalogTest dev.dsh.nativeapp.ProjectModelSettingsTest dev.dsh.nativeapp.ProcessSupervisorTest dev.dsh.nativeapp.TransferStateTest dev.dsh.nativeapp.SecretMaskerTest dev.dsh.nativeapp.DiagnosticReportTest dev.dsh.nativeapp.UiTextTest dev.dsh.nativeapp.MobileLayoutTest dev.dsh.nativeapp.DeviceLayoutTest dev.dsh.nativeapp.WorkspaceProjectsTest dev.dsh.nativeapp.ShareTaskTest dev.dsh.nativeapp.PluginPermissionsTest dev.dsh.nativeapp.ReleaseChannelTest dev.dsh.nativeapp.WebToolsEntryTest dev.dsh.nativeapp.UiPolicyTest dev.dsh.nativeapp.OperationGateTest dev.dsh.nativeapp.InteractionFeedbackTest dev.dsh.nativeapp.CrashReporterTest dev.dsh.nativeapp.WorkerRegistryTest dev.dsh.nativeapp.ProviderRouteTest; do
    case "$seen_tests" in *" $t "*) continue ;; esac
    seen_tests="$seen_tests$t "
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

# 运行环境快照必须能由 App 自带脚本创建，并由同一 unpack.js 完整恢复。
# 测真实目录、可执行位、长文件名和符号链接，不只做字符串断言。
if command -v node >/dev/null 2>&1; then
    if ! out=$(node tests/js/payload-rollback-simulation.js \
            "$PAYLOAD_DIR/snapshot.js" "$PAYLOAD_DIR/unpack.js" 2>&1); then
        echo "$out"
        echo "  [FAIL] 运行环境快照与恢复模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

# WebSocket 探针必须保持原型/常量，不得把 URL 或消息内容写进日志。
if command -v node >/dev/null 2>&1; then
    CONNECTION_JS="$OUT/connection-recovery.js"
    java -Dfile.encoding=UTF-8 -cp "$OUT" \
        dev.dsh.nativeapp.ConnectionRecoveryTest --dump-script > "$CONNECTION_JS"
    if ! node --check "$CONNECTION_JS" >/dev/null 2>&1; then
        echo "  [FAIL] ConnectionRecovery JavaScript 语法错误" >&2
        rc=1
    elif ! out=$(node tests/js/connection-recovery-simulation.js "$CONNECTION_JS" 2>&1); then
        echo "$out"
        echo "  [FAIL] ConnectionRecovery WebSocket 模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

# 草稿恢复必须真实覆盖保存、重载恢复与清空，且永远不自动发送。
if command -v node >/dev/null 2>&1; then
    DRAFT_JS="$OUT/draft-recovery.js"
    java -Dfile.encoding=UTF-8 -cp "$OUT" \
        dev.dsh.nativeapp.DraftRecoveryTest --dump-script > "$DRAFT_JS"
    if ! node --check "$DRAFT_JS" >/dev/null 2>&1; then
        echo "  [FAIL] DraftRecovery JavaScript 语法错误" >&2
        rc=1
    elif ! out=$(node tests/js/draft-recovery-simulation.js "$DRAFT_JS" 2>&1); then
        echo "$out"
        echo "  [FAIL] DraftRecovery DOM 模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

# 会话状态探针必须在真实 JavaScript 引擎里覆盖首次请求、重放、计数和幂等。
if command -v node >/dev/null 2>&1; then
    SESSION_PROBE_JS="$OUT/session-probe.js"
    java -Dfile.encoding=UTF-8 -cp "$OUT" \
        dev.dsh.nativeapp.SessionProbeTest --dump-script > "$SESSION_PROBE_JS"
    if ! node --check "$SESSION_PROBE_JS" >/dev/null 2>&1; then
        echo "  [FAIL] SessionProbe JavaScript 语法错误" >&2
        rc=1
    elif ! out=$(node tests/js/session-probe-simulation.js "$SESSION_PROBE_JS" 2>&1); then
        echo "$out"
        echo "  [FAIL] SessionProbe fetch 模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

# DOM 状态兜底必须真实覆盖空闲、运行、待批准、无依据与重复注入。
if command -v node >/dev/null 2>&1; then
    SESSION_STATUS_JS="$OUT/session-status.js"
    java -Dfile.encoding=UTF-8 -cp "$OUT" \
        dev.dsh.nativeapp.SessionStatusTest --dump-script > "$SESSION_STATUS_JS"
    if ! node --check "$SESSION_STATUS_JS" >/dev/null 2>&1; then
        echo "  [FAIL] SessionStatus JavaScript 语法错误" >&2
        rc=1
    elif ! out=$(node tests/js/session-status-simulation.js "$SESSION_STATUS_JS" 2>&1); then
        echo "$out"
        echo "  [FAIL] SessionStatus DOM 模拟失败" >&2
        rc=1
    else
        echo "  $out"
    fi
fi

if command -v node >/dev/null 2>&1; then
    for mode in search archive; do
        script="$OUT/session-organizer-$mode.js"
        java -Dfile.encoding=UTF-8 -cp "$OUT" \
            dev.dsh.nativeapp.SessionOrganizerTest "--dump-$mode" > "$script"
        if ! node --check "$script" >/dev/null 2>&1; then
            echo "  [FAIL] SessionOrganizer $mode JavaScript 语法错误" >&2
            rc=1
        fi
    done
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

# 状态探针必须在页面开始阶段安装，避免 onPageFinished 之后才接管而错过首次会话请求。
if ! grep -q 'installSessionProbe(view);' "$MAIN_ACTIVITY" \
        || ! grep -q 'HarnessService.TASK_CHANNEL_ID' "$MAIN_ACTIVITY" \
        || ! grep -q 'taskNotifier.startedAt()' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 状态计时或任务完成提醒接线不完整" >&2
    rc=1
else
    echo "  SessionStatusWiring: early probe / stable timer / completion channel"
fi

# 阶段五 A：任务历史必须持久化，连接与草稿探针必须随每次页面加载恢复，
# 任务中心需要从设置首页可达；仍禁止高权限 JavascriptInterface。
if ! grep -q 'showTaskCenter();' "$MAIN_ACTIVITY" \
        || ! grep -q 'PREF_TASK_TIMELINE' "$MAIN_ACTIVITY" \
        || ! grep -q 'installConnectionWatcher(view);' "$MAIN_ACTIVITY" \
        || ! grep -q 'installDraftRecovery(view);' "$MAIN_ACTIVITY" \
        || ! grep -q 'EXTRA_CONNECTION_STATE' "$JAVA_DIR/HarnessService.java"; then
    echo "  [FAIL] 阶段五 A 任务中心、恢复持久化或页面探针接线不完整" >&2
    rc=1
else
    echo "  Phase5ATaskRecoveryWiring: timeline persisted / connection visible / draft restored"
fi

# 阶段五 B：文件删除默认进入回收站，批量复制/移动与图片预览必须接线；
# 会话管理只调用 DSH 官方界面，不得复制私有 RPC 或新增原生桥。
if ! grep -q 'FileTrash.move' "$JAVA_DIR/FileBrowser.java" \
        || ! grep -q 'FileBatch.transfer' "$JAVA_DIR/FileBrowser.java" \
        || ! grep -q 'FilePreview.kind' "$JAVA_DIR/FileBrowser.java" \
        || ! grep -q 'showSessionManager();' "$MAIN_ACTIVITY" \
        || ! grep -q 'SessionOrganizer.showArchivedScript' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 阶段五 B 文件工作流或会话管理接线不完整" >&2
    rc=1
elif grep -q 'workspace.archiveSession' "$JAVA_DIR/SessionOrganizer.java" \
        || grep -q 'fetch(' "$JAVA_DIR/SessionOrganizer.java"; then
    echo "  [FAIL] 会话管理不得直接调用未公开的 DSH RPC" >&2
    rc=1
else
    echo "  Phase5BFileSessionWiring: batch / preview / trash / official session UI"
fi

# 阶段五 C：模型选择必须结构化写入 agent-default-model，服务商检测只读模型列表；
# 项目覆盖随切换应用，首次配置标记只能由新安装引导创建。
if ! grep -q 'ModelCenterPanel.show' "$MAIN_ACTIVITY" \
        || ! grep -q 'applyProjectModelConfig(project)' "$MAIN_ACTIVITY" \
        || ! grep -q 'modelOnboardingPending' "$MAIN_ACTIVITY" \
        || ! grep -q 'ProviderCheck.endpoint' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'ProjectModelSettings.FILE_NAME' "$JAVA_DIR/ConfigBackup.java" \
        || ! grep -q 'ModelCatalogSync.writeLiveCatalog' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'ModelCatalogSync.mergePresetProviderBlock' "$MAIN_ACTIVITY" \
        || ! grep -q 'refreshModelCatalog' "$JAVA_DIR/ModelCenterPanel.java"; then
    echo "  [FAIL] 阶段五 C 模型中心、项目覆盖、首次向导或备份接线不完整" >&2
    rc=1
elif grep -q 'chat/completions' "$JAVA_DIR/ModelCenterPanel.java" \
        || grep -q 'addJavascriptInterface' "$JAVA_DIR/ModelCenterPanel.java"; then
    echo "  [FAIL] 服务商检测不得产生模型调用或新增 WebView 权限桥" >&2
    rc=1
else
    echo "  Phase5CModelOnboardingWiring: read-only check / project override / resumable onboarding"
fi

# 阶段五 E：模型选择器的可见列表和可选性必须来自本次上游响应；本地目录只能做
# 能力提示。模型中心和设置子页的 Android 返回手势必须复用父级导航。
if ! grep -q 'requestCatalog(act, provider, key' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'LiveModelCatalog.reconcile' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'true, known != null && known.image' "$JAVA_DIR/LiveModelCatalog.java" \
        || grep -q 'setEnabled(item.selectable)' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'DshUi.onBack(dialog, returnToParent)' "$JAVA_DIR/ModelCenterPanel.java" \
        || ! grep -q 'public static void onBack' "$JAVA_DIR/DshUi.java" \
        || ! grep -q 'DshUi.onBack(dialog' "$MAIN_ACTIVITY" \
        || { [ -f "$ROOT/README.md" ] && [ ! -f "$ROOT/README.en.md" ]; }; then
    echo "  [FAIL] 阶段五 E 实时模型目录、返回导航或英文 README 接线不完整" >&2
    rc=1
elif grep -q 'final List<ModelConfig.Model> models = ModelConfig.modelsForProvider' \
        "$JAVA_DIR/ModelCenterPanel.java"; then
    echo "  [FAIL] 模型选择器不得重新使用本地预设作为可见列表" >&2
    rc=1
else
    echo "  Phase5ELiveCatalogWiring: upstream-only visibility / all upstream selectable / back navigation / English README"
fi

# 阶段五 D：更新前快照、失败自动回滚、诊断包分享和多设备布局必须接线。
if ! grep -q 'createPayloadRollback' "$MAIN_ACTIVITY" \
        || ! grep -q 'restorePayloadRollback' "$MAIN_ACTIVITY" \
        || ! grep -q 'DiagnosticReport.Builder' "$MAIN_ACTIVITY" \
        || ! grep -q 'ACTION_SEND' "$MAIN_ACTIVITY" \
        || ! grep -q 'DeviceLayout.stackFooter' "$JAVA_DIR/DshUi.java"; then
    echo "  [FAIL] 阶段五 D 回滚、诊断或多设备布局接线不完整" >&2
    rc=1
else
    echo "  Phase5DRollbackDiagnosticsWiring: snapshot / automatic restore / share / adaptive footer"
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

# 阶段四 F：动效必须真正在公共组件和设置导航中接线，不能只改一份未使用的资源。
if ! grep -q 'class MotionCard' "$JAVA_DIR/DshUi.java" \
        || ! grep -q 'RippleDrawable' "$JAVA_DIR/DshUi.java" \
        || ! grep -q 'DshUi.swapDialog(dialog, false' "$MAIN_ACTIVITY" \
        || ! grep -q 'DshUi.animateChoiceChange(row)' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 阶段四 F 分层进入/触摸反馈/面板换页接线不完整" >&2
    rc=1
else
    echo "  Phase4FMotionWiring: layered reveal / bounded ripple / page swap / choice feedback"
fi

# 发布前缺陷回归：密钥不能进入 Autofill；Activity 重建必须解除静态接线、
# 停止长期线程并销毁 WebView；安装器未真正启动时不得显示成功。
if ! grep -q 'IMPORTANT_FOR_AUTOFILL_NO' "$JAVA_DIR/DshUi.java" \
        || ! grep -q 'DshUi.clearLogSink(dshUiLogSink)' "$MAIN_ACTIVITY" \
        || ! grep -q 'activityWorkers.stop();' "$MAIN_ACTIVITY" \
        || ! grep -q 'oldWebView.destroy();' "$MAIN_ACTIVITY" \
        || ! grep -q 'CrashReporter.install(crashFile)' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 发布前隐私或 Activity 生命周期修复接线不完整" >&2
    rc=1
elif ! grep -q 'success = install == INSTALL_LAUNCHED' "$MAIN_ACTIVITY" \
        || grep -q 'private void installApk' "$MAIN_ACTIVITY"; then
    echo "  [FAIL] 安装器未启动时仍可能误报成功" >&2
    rc=1
else
    echo "  ReleaseHardeningWiring: autofill blocked / workers stopped / WebView destroyed / install result truthful"
fi
exit $rc
