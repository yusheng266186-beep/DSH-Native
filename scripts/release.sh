#!/usr/bin/env bash
#
# 发布一个版本：构建 → 上传 → **验证可下载** → 最后才更新版本清单。
#
# 为什么顺序不能颠倒：
#   latest.json 是 App 判断「有没有新版本」的唯一依据。一旦它指向一个
#   尚未创建或上传失败、或 CDN 还没生效的 release，**所有用户点更新都会失败**。
#   实测发生过一次：shell 报错导致 gh release create 没执行，
#   而版本清单已经写成了新版本号，App 去下载一个不存在的 APK。
#
# 因此本脚本把「更新清单」放在最后，并且在此之前必须验证：
#   1) release 存在
#   2) APK 资产已上传
#   3) 直连与镜像两条下载路径都返回成功
#
# 用法：
#   scripts/release.sh <版本号> <构建目录> [发布说明.md]
# 例：
#   scripts/release.sh 0.19.3 /root/build /tmp/notes.md
set -euo pipefail

# 同步 README 里的下载链接与 SHA-256。
#
# 手工维护出过问题：README 里的下载链接曾长期指向 v0.9.0，
# 而 SHA-256 因为一次批量替换被拼成了两个哈希。
# 这两个数字每次发版都会变，交给脚本最可靠。
update_readme() {
    local ver="$1" sha="$2" readme="$3"
    [ -f "$readme" ] || return 0
    python3 - "$ver" "$sha" "$readme" <<'PYEOF'
import re, sys
ver, sha, path = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding='utf-8').read()
before = s
# 下载链接与 release 标签统一指向本次版本
s = re.sub(r'releases/download/v[0-9.]+-bootstrap/DSHNative-bootstrap\.apk',
           f'releases/download/v{ver}-bootstrap/DSHNative-bootstrap.apk', s)
s = re.sub(r'releases/tag/v[0-9.]+-bootstrap', f'releases/tag/v{ver}-bootstrap', s)
s = re.sub(r'\[v[0-9.]+ 引导式（推荐）\]',
           f'[v{ver} 引导式（推荐）]', s)
# SHA-256：旧值可能是 64 位，也可能是被拼坏的超长串
# （曾经因为一次批量替换变成两个哈希连在一个反引号里），两种都要能吃下
s = re.sub(r'SHA-256：`[0-9a-f]{64,}`', f'SHA-256：`{sha}`', s)
if s != before:
    open(path, 'w', encoding='utf-8').write(s)
    print(f"  [OK] README 已同步到 v{ver}")
else:
    print("  [--] README 无需改动")
PYEOF
}


# 从 App 源码里取实际使用的运行包标签 —— 避免 latest.json 与代码不一致
# （曾出现过：App 已切到 payload-v7，latest.json 里还写着 payload-v6）
currentPayloadTag() {
    grep -oE 'releases/download/payload-v[0-9]+/' \
        "$ROOT/bootstrap/src/dev/dsh/nativeapp/MainActivity.java" 2>/dev/null \
        | head -1 | sed -E 's|releases/download/([^/]+)/|\1|'
}


VER="${1:?用法: release.sh <版本号> <构建目录> [发布说明.md]}"
BUILD_DIR="${2:?用法: release.sh <版本号> <构建目录> [发布说明.md]}"
NOTES="${3:-}"
TAG="v${VER}-bootstrap"
REPO="yusheng266186-beep/DSH-Native"
APK_NAME="DSHNative-bootstrap.apk"
PRIMARY="https://github.com/${REPO}/releases/download/${TAG}/${APK_NAME}"
MIRROR="https://gh-proxy.com/https://github.com/${REPO}/releases/download/${TAG}/${APK_NAME}"

cd "$(dirname "$0")/.."
APK="${BUILD_DIR}/bootstrap/DSHNative-bootstrap.apk"
[ -f "$APK" ] || { echo "[FAIL] 找不到 APK: $APK"; exit 1; }

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c%s "$APK")
echo "  版本:   $VER  ($TAG)"
echo "  APK:    $SIZE 字节"
echo "  SHA256: $SHA"

# 1) 先确认这个 tag 没发布过，避免重复上传造成版本混乱
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "[FAIL] $TAG 已存在。若需重发请先删除：gh release delete $TAG --repo $REPO"
    exit 1
fi

# 2) 上传
echo "  上传中 …"
if [ -n "$NOTES" ] && [ -f "$NOTES" ]; then
    gh release create "$TAG" --repo "$REPO" \
        --title "DeepSeek Harness v${VER}" --notes-file "$NOTES" "$APK"
else
    gh release create "$TAG" --repo "$REPO" \
        --title "DeepSeek Harness v${VER}" \
        --notes "见仓库 README 与 git log。" "$APK"
fi

# 3) 验证资产确实存在
ASSETS=$(gh release view "$TAG" --repo "$REPO" --json assets \
         --jq '.assets[] | select(.name=="'"$APK_NAME"'") | .size' 2>/dev/null || true)
[ -n "$ASSETS" ] || { echo "[FAIL] release 已建但资产未上传，**不要更新 latest.json**"; exit 1; }
echo "  [OK] 资产已上传: $ASSETS 字节"

# 4) 验证两条下载路径（CDN 需要时间，轮询）
echo "  验证下载路径（最多等 8 分钟）…"
for path in "$PRIMARY" "$MIRROR"; do
    host=$(echo "$path" | cut -d/ -f3)
    ok=0
    for i in $(seq 1 24); do
        code=$(curl -sSL -o /dev/null -w '%{http_code}' -r 0-1023 --max-time 30 "$path" 2>/dev/null || echo 000)
        if [ "$code" = "206" ] || [ "$code" = "200" ]; then ok=1; break; fi
        sleep 20
    done
    if [ "$ok" = "1" ]; then
        echo "  [OK] $host 可下载"
    else
        echo "[FAIL] $host 仍不可下载 —— **不要更新 latest.json**"
        exit 1
    fi
done

# 5) 全部通过后才更新版本清单
# 运行包标签在 bash 里算好再传进去 —— 之前直接写在 Python 里调用 bash 函数，
# 那次发布就断在这里（好在清单是最后一步，没有写坏线上状态）。
PAYLOAD_TAG="$(currentPayloadTag)"
python3 - "$VER" "$TAG" "$SHA" "$NOTES" "$PAYLOAD_TAG" <<'PY'
import json, sys, pathlib
ver, tag, sha, notes_file, payload_tag = (sys.argv[1], sys.argv[2], sys.argv[3],
                                          sys.argv[4], sys.argv[5])
p = pathlib.Path('latest.json')
old = json.loads(p.read_text(encoding='utf-8')) if p.exists() else {}

# notes 取发布说明里第一个有内容的行（去掉 markdown 标题符号）。
# 这个字段 App 不读，但公开仓库里会被人看到 —— 曾经长期停留在
# 十几版之前的旧文案，因为没人更新它。
summary = ""
try:
    for line in pathlib.Path(notes_file).read_text(encoding='utf-8').splitlines():
        t = line.strip().lstrip('#').strip()
        if t:
            summary = t[:120]
            break
except Exception:
    pass

old.update({"version": ver, "tag": tag, "apk": "DSHNative-bootstrap.apk",
            "payload": payload_tag})
if summary:
    old["notes"] = summary
p.write_text(json.dumps(old, ensure_ascii=False, indent=2) + "\n", encoding='utf-8')
print(f"  [OK] latest.json → {ver}")
PY

# 6) 同步 README 的下载链接与 SHA-256。
#    这两个数字每次发版都会变，手工维护出过两次错：
#    下载链接曾长期指向 v0.9.0；SHA-256 因为一次批量替换被拼成了两个哈希。
update_readme "$VER" "$(sha256sum "$APK_OUT" | cut -d' ' -f1)" \
              "${REPO_DIR:-/root/dsh-native}/README.md"

echo
echo "  发布完成。清单已更新，App 现在可以检测到 v${VER}。"
