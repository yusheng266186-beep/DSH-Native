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
#   scripts/release.sh <版本号> <构建目录>
# 例：
#   scripts/release.sh 0.19.3 /root/build
set -euo pipefail

VER="${1:?用法: release.sh <版本号> <构建目录>}"
BUILD_DIR="${2:?用法: release.sh <版本号> <构建目录>}"
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
gh release create "$TAG" --repo "$REPO" \
    --title "DeepSeek Harness v${VER}" \
    --notes "见仓库 README 与 git log。" \
    "$APK"

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
python3 - "$VER" "$TAG" "$SHA" <<'PY'
import json, sys, pathlib
ver, tag, sha = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path('latest.json')
old = json.loads(p.read_text(encoding='utf-8')) if p.exists() else {}
old.update({"version": ver, "tag": tag, "apk": "DSHNative-bootstrap.apk",
            "payload": old.get("payload", "payload-v6")})
p.write_text(json.dumps(old, ensure_ascii=False, indent=2) + "\n", encoding='utf-8')
print(f"  [OK] latest.json → {ver}")
PY

echo
echo "  发布完成。清单已更新，App 现在可以检测到 v${VER}。"
echo "  别忘了同步 README 里的下载链接与 SHA-256。"
