#!/bin/bash
# 提升版本号：从源码里读当前值再递增，**不硬编码旧值**。
# 硬编码旧值是出过事故的：源码已经前进了一版，替换却没匹配上，
# 结果版本没变、清单却写了新号 —— 客户端无限提示更新。
set -euo pipefail
NEW="${1:?用法: bump_version.sh <新版本号，如 0.22.7>}"
M=/root/build/mkmanifest.py
A=/root/build/bootstrap/src/dev/dsh/nativeapp/MainActivity.java

CUR=$(grep -oE 's\("[0-9]+\.[0-9]+\.[0-9]+"\)' "$M" | head -1 | sed 's/s("//;s/")//')
[ -n "$CUR" ] || { echo "读不到当前版本" >&2; exit 1; }
echo "  当前版本: $CUR → $NEW"

python3 - "$M" "$A" "$CUR" "$NEW" <<'PY'
import sys, re
m, a, cur, new = sys.argv[1:5]
s = open(m, encoding='utf-8').read()
assert f's("{cur}")' in s, "mkmanifest.py 里未找到当前版本"
s = s.replace(f's("{cur}")', f's("{new}")', 1)
# versionCode：主*10000 + 次*100 + 修订
p = [int(x) for x in new.split('.')]
code = p[0] * 10000 + p[1] * 100 + (p[2] if len(p) > 2 else 0)
s = re.sub(r'integer\(\d+\)\),\s*#?\s*versionCode|integer\((\d+)\)', f'integer({code})', s, count=1)
open(m, 'w', encoding='utf-8').write(s)

t = open(a, encoding='utf-8').read()
assert f'APK 版本: {cur}' in t, "MainActivity.java 里未找到当前版本"
t = t.replace(f'APK 版本: {cur}', f'APK 版本: {new}', 1)
open(a, 'w', encoding='utf-8').write(t)
print(f"  ✅ 已写入 {new}（versionCode {code}）")
PY
grep -oE 's\("[0-9.]+"\)|integer\([0-9]+\)' "$M" | head -2 | sed 's/^/  /'
grep -oE 'APK 版本: [0-9.]+' "$A" | head -1 | sed 's/^/  /'
