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
        "FileOps", "ConfigBackup", "ShareTargets", "PluginSpecs", "PayloadUpdate",
        "SessionStatus"]
EMOJI = re.compile('[\U0001F300-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\uFE0F]')
SOURCES = os.path.join(ROOT, "src", "dev", "dsh", "nativeapp")

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

    section(f"1/3 语法解析（{len(files)} 个文件）")
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

    section("2/3 跨类方法引用")
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

    section("3/3 构建期闸门")
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

    print()
    if failed:
        print("[FAIL] " + "；".join(failed))
        return 1
    print("[OK] 全部本地核验通过（注意：这只覆盖语法与引用，"
          "类型层面的错误仍需 CI 的 javac）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
