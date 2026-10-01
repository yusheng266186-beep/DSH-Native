#!/usr/bin/env python3
"""源码卫生检查：只抓**机器可判定**的问题，用来省掉 CI 往返。

为什么需要它
------------
本机没有 JDK，javac 与单元测试只能在 CI 跑。于是「一个低级错误」的成本
不是本地报错，而是**推送 → 等 CI → 读日志 → 修 → 再推**，每轮几分钟。
这些错误本身毫无价值，却反复消耗时间。

反复踩到过的三类（都已真实发生过）：
  1. `File.createTempFile` 前缀不足 3 个字符 —— 抛 IllegalArgumentException，
     测试第一行就死，后面所有断言都没跑到；
  2. 裸控制字符（DEL 等）写进源码；
  3. U+FFFD 替换字符（heredoc 写中文时的产物）——会原样发到公开 Release 页面。

另加一条很窄的反向断言检查：用例名说「找到/接受/存在」而表达式却取了反，
这种自相矛盾几乎都是笔误。仅覆盖少数正向词，避免误报。

只报**确定**的问题。宁可漏报，不可误报挡住别人干活。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCAN_DIRS = ("src", "tests", "scripts", "payload", "docs")
SCAN_EXT = (".java", ".sh", ".py", ".md")

TEMP_PREFIX = re.compile(r'createTempFile\(\s*"([^"]*)"')
# 用例名里的正向词：出现这些词而表达式取了反，通常是笔误。
POSITIVE_WORDS = ("still found", "must be found", "is present",
                  "should be present", "is found", "must be allowed")
# 只取 check(... 的第一个参数，即用例名
NAME_RE = re.compile(r'\b(?:check|assert(?:True|False)?)\(\s*"([^"]*)"')


def java_sources():
    for base in SCAN_DIRS:
        top = os.path.join(ROOT, base)
        for dirpath, _dirnames, filenames in os.walk(top):
            for name in filenames:
                if name.endswith(SCAN_EXT):
                    yield os.path.join(dirpath, name)


def main():
    findings = []
    for path in java_sources():
        rel = os.path.relpath(path, ROOT)
        try:
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
        except UnicodeDecodeError as exc:
            # 读取失败本身就是要报的问题。**不能抛出去** —— 一个会崩的检查器
            # 等于没有检查器：它既报不出问题，也会让人误以为「跑过了就是没问题」。
            findings.append(f"{rel} 不是合法 UTF-8（{exc.reason}，位置 {exc.start}）")
            continue

        # 1) 替换字符：heredoc 写中文的产物，绝不能进仓库
        for i, line in enumerate(text.splitlines(), 1):
            if "\ufffd" in line:
                findings.append(f"{rel}:{i} 出现 U+FFFD 替换字符（heredoc 写入时损坏）")

        if not path.endswith(".java"):
            continue

        # 2) createTempFile 前缀长度
        for lineno, line in enumerate(text.splitlines(), 1):
            for prefix in TEMP_PREFIX.findall(line):
                if 0 < len(prefix) < 3:
                    findings.append(
                        f"{rel}:{lineno} createTempFile 前缀 {prefix!r} 不足 3 字符，"
                        "运行时会抛 IllegalArgumentException")

        # 3) 裸控制字符（制表与换行除外；换行已被 splitlines 吃掉）
        for lineno, line in enumerate(text.splitlines(), 1):
            for ch in line:
                if (ord(ch) < 0x20 and ch != "\t") or ord(ch) == 0x7F:
                    findings.append(
                        f"{rel}:{lineno} 出现裸控制字符 U+{ord(ch):04X}，"
                        "请改用 \\\\uXXXX 转义")
                    break

        # 4) 反向断言：用例名说正向，表达式却取反
        #
        # 只看**第一个参数（用例名）**。早期版本匹配整行，结果把 detail 里的
        # "UNSAFE ACCEPTED" 也算进去了，几十条误报 —— 误报的闸门等于没有闸门，
        # 因为没人愿意绕过去。
        for lineno, line in enumerate(text.splitlines(), 1):
            match = NAME_RE.search(line)
            if not match:
                continue
            name_text = match.group(1).lower()
            if not any(word in name_text for word in POSITIVE_WORDS):
                continue
            rest = line[match.end():].lstrip()
            if rest.startswith(","):
                rest = rest[1:].lstrip()
            if rest.startswith("!"):
                findings.append(
                    f"{rel}:{lineno} 用例名是正向表述但断言取了反（! 开头），"
                    "确认是不是写反了")

    if findings:
        print("[FAIL] 源码卫生检查未通过：")
        for item in findings:
            print(f"  - {item}")
        return 1
    print("[OK] 源码卫生检查通过（替换字符 / 裸控制字符 / 临时文件前缀 / 反向断言）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
