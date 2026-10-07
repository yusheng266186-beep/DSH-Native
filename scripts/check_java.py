#!/usr/bin/env python3
"""本地 Java 静态核验（无需 JDK）。

设备上（Android App 沙箱）没有 javac，而 CI 是唯一真编译器 ——
但把没核验过的代码推上去再等 CI 报错，代价是每一轮好几分钟，
而且「CI 绿了」也说不清是本来就对还是碰巧。

这个脚本把能在本地做的检查一次做完，推之前先跑：

  1. 整文件语法解析（javalang；它是真正的 Java 解析器，
     比数括号强得多，能抓到漏分号、括号错位、修饰符错乱这类问题）
  2. 跨类方法引用核验：Xxx.method() 的方法必须在 Xxx 里真的存在
     （抓拼写错误、以及"我以为有这个方法"）
  3. 构建期三道闸门：纯逻辑层无 android 依赖、无 AlertDialog、无 emoji

没有 javalang 时第 1、2 步会跳过并提示（它只是本地辅助，不是构建依赖）。

用法：
    python3 scripts/check_java.py
或指定文件：
    python3 scripts/check_java.py src/dev/dsh/nativeapp/FileBrowser.java
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PURE = ["FileListing", "TextCodec", "Version", "CommandCodeUsage", "TaskNotifier",
        "TaskTimeline", "ConnectionRecovery", "DraftRecovery",
        "FileOps", "ConfigBackup", "ShareTargets", "PluginSpecs", "PayloadUpdate",
        "SessionStatus", "SessionProbe", "SessionRecovery", "ProcessSupervisor", "TransferState",
        "SecretMasker", "DiagnosticReport", "UiText", "MobileLayout", "DeviceLayout",
        "PayloadRollback", "WorkspaceProjects", "ShareTask",
        "PluginPermissions", "ReleaseChannel", "WebToolsEntry", "UiPolicy",
        "OperationGate", "InteractionFeedback", "LiveModelCatalog", "ModelCatalogSync", "ModelCatalogRefresh",
        "PayloadManifest", "CredentialMerge", "WebUrl", "YamlBlocks", "RuntimeDir", "ModelImageSupport", "ModelCatalogPersistence", "LayoutProbe", "JsonValue", "CoreRpcClient", "CoreReadiness", "SessionPersistencePatch", "AccountUi", "ModelSettingsSnapshot"]
EMOJI = re.compile('[\U0001F300-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]')

# 静默捕获（catch (Throwable ignored)）的允许上限。只允许降低，不允许增长。
# 降低时请一并改小本值，并确认每一处减少都对应一处真正的修复。
SILENT_CATCH_BASELINE = 120
# 仓库克隆与 CI 构建工作区的源码位置不同（run_tests.sh 同理）：
#   仓库克隆      <root>/src/dev/dsh/nativeapp
#   构建工作区    <root>/bootstrap/src/dev/dsh/nativeapp
# 认不出来就直接报错 —— 静默扫到 0 个文件会让这道闸门形同虚设。
def _locate_sources():
    here = os.path.dirname(os.path.abspath(__file__))
    bases = [ROOT, here, os.path.dirname(here)]
    rels = (("src", "dev", "dsh", "nativeapp"),
            ("bootstrap", "src", "dev", "dsh", "nativeapp"))
    for base in bases:
        for rel in rels:
            candidate = os.path.join(base, *rel)
            if os.path.isdir(candidate):
                return candidate
    raise SystemExit("[FAIL] 找不到源码目录（找过各处的 src/… 与 bootstrap/src/…）")


SOURCES = _locate_sources()

failed = []


def section(title):
    print(f"\n== {title} ==")


def main(argv):
    files = argv[1:] or sorted(glob.glob(os.path.join(SOURCES, "*.java")))
    files = [f if os.path.isabs(f) else os.path.join(ROOT, f) for f in files]
    files = [f for f in files if os.path.isfile(f)]
    if not files:
        print("[FAIL] 找不到要检查的 java 文件")
        return 1

    section(f"1/4 语法解析（{len(files)} 个文件）")
    try:
        import javalang
    except ImportError:
        javalang = None
        print("  [--] 未安装 javalang，跳过（pip install javalang 可启用）")
    if javalang:
        bad = 0
        for f in files:
            try:
                javalang.parse.parse(open(f, encoding="utf-8").read())
            except Exception as e:
                bad += 1
                print(f"  [FAIL] {os.path.relpath(f, ROOT)}: {str(e)[:150]}")
        if bad:
            failed.append(f"{bad} 个文件语法解析失败")
        else:
            print(f"  [OK] {len(files)} 个文件解析通过")

    section("2/4 跨类方法引用")
    if not javalang:
        print("  [--] 需要 javalang，跳过")
    else:
        srcs = {}
        for f in glob.glob(os.path.join(SOURCES, "*.java")):
            srcs[os.path.basename(f)[:-5]] = open(f, encoding="utf-8").read()
        missing = set()
        for name, s in srcs.items():
            for m in re.finditer(r"\b([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\s*\(", s):
                cls, meth = m.group(1), m.group(2)
                if cls not in srcs or cls == name:
                    continue
                if not re.search(r"\b" + re.escape(meth) + r"\s*\(", srcs[cls]):
                    missing.add(f"{name} -> {cls}.{meth}()")
        if missing:
            for x in sorted(missing):
                print(f"  [FAIL] {x}")
            failed.append(f"{len(missing)} 处跨类引用找不到定义")
        else:
            print("  [OK] 全部跨类引用都能找到定义")

    section("3/4 资源 XML")
    if not check_resources():
        failed.append("资源 XML 不合法")

    section("4/4 构建期闸门")
    hits = []
    for name in PURE:
        p = os.path.join(SOURCES, name + ".java")
        if not os.path.isfile(p):
            hits.append(f"缺少纯逻辑文件 {name}.java")
            continue
        for i, line in enumerate(open(p, encoding="utf-8"), 1):
            if re.match(r"^import +(android|androidx)\.", line):
                hits.append(f"{name}.java:{i} 引入了 Android 依赖")
    for f in files:
        s = open(f, encoding="utf-8").read()
        for i, line in enumerate(s.splitlines(), 1):
            if re.search(r"AlertDialog\.Builder|new +AlertDialog", line):
                hits.append(f"{os.path.relpath(f, ROOT)}:{i} 使用了系统 AlertDialog")
            if EMOJI.search(line):
                hits.append(f"{os.path.relpath(f, ROOT)}:{i} 出现 emoji")
    if hits:
        for h in hits:
            print(f"  [FAIL] {h}")
        failed.append(f"{len(hits)} 处闸门违规")
    else:
        print("  [OK] 纯逻辑层无 Android 依赖 / 无 AlertDialog / 无 emoji")

    # 静默捕获只减不增。
    #
    # `catch (Throwable ignored)` 会把 NPE 变成「什么都没发生」，而历史上三个 bug
    # 都出自这里（docs/GOTCHAS 第 3 条）。但现存 121 处里**绝大多数是合理的**：
    # 关流、取消动画、清 WebView、资源清理、端口探测下一个 —— 逐个改写只会制造
    # 无谓的 diff 和回归风险。
    #
    # 所以这里不追求「清零」，而是**锁住上限**：新增一处就构建失败，逼着作者
    # 当场判断这是不是又一处该记日志的吞异常。想降低基线就改 SILENT_CATCH_BASELINE。
    global SILENT_CATCH_BASELINE
    total = 0
    per_file = []
    for f in files:
        s = open(f, encoding="utf-8").read()
        n = s.count("catch (Throwable ignored)")
        if n:
            total += n
            per_file.append((n, os.path.relpath(f, ROOT)))
    per_file.sort(reverse=True)
    print(f"  静默捕获 {total} 处（上限 {SILENT_CATCH_BASELINE}）"
          + (": " + ", ".join(f"{n}×{os.path.basename(p)}" for n, p in per_file[:4])
             if per_file else ""))
    if total > SILENT_CATCH_BASELINE:
        for n, p in per_file[:8]:
            print(f"  [FAIL] {p}: {n} 处静默捕获")
        failed.append(
            f"静默捕获 {total} 处超过上限 {SILENT_CATCH_BASELINE}："
            "新增的吞异常必须先判断要不要 log()，不要直接加进基线")

    print()
    if failed:
        print("[FAIL] " + "；".join(failed))
        return 1
    print("[OK] 全部本地核验通过（注意：这只覆盖语法与引用，"
          "类型层面的错误仍需 CI 的 javac）")
    return 0


def check_resources():
    """资源 XML 合法性。

    加这一步的直接原因：values-night/colors.xml 的注释里写了 `--dsw-alias-...`，
    而 XML 注释**不允许出现连续两个减号** —— aapt2 报 "not well-formed"，
    构建直接失败。Java 检查完全覆盖不到资源，于是这个错误只能等 CI 发现。
    """
    import xml.dom.minidom
    bad = 0
    files = []
    for root, _dirs, names in os.walk(os.path.join(ROOT, "icon", "res")):
        files += [os.path.join(root, n) for n in names if n.endswith(".xml")]
    for f in sorted(files):
        try:
            xml.dom.minidom.parse(f)
        except Exception as e:
            bad += 1
            print(f"  [FAIL] {os.path.relpath(f, ROOT)}: {e}")
    if bad:
        return False
    print(f"  [OK] {len(files)} 个资源 XML 合法")
    return True


if __name__ == "__main__":
    sys.exit(main(sys.argv))
