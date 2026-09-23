#!/usr/bin/env bash
# 验证某个版本的发布是否真的可用。
#
# 为什么需要单独一个脚本
# ----------------------
# release.sh 里的顺序是「上传 → 验证 → 最后写清单」，这条护栏挡住过一次事故。
# 但当发布中途失败、需要手工收尾时，人很容易跳过验证直接写清单 ——
# 2026-09-23 就这么发生过一次：
#
#   1. release.sh 在「等待下载路径生效」那一步超时被中断
#   2. 此时 release 是**草稿**状态（还没 publish），资产也还没传完
#   3. 手工补传资产、并写了 latest.json
#   4. 但草稿对公众不可下载 → 两个下载源都是 404
#      → 客户端会检测到更新却下不下来
#
# 所以把验证独立出来：无论发布是脚本跑的还是手工收尾的，
# 写清单之前都跑一遍这个。
#
# 用法: bash scripts/verify_release.sh <版本号>
set -uo pipefail

VER="${1:?用法: verify_release.sh <版本号>（不带 v 前缀）}"
REPO="yusheng266186-beep/DSH-Native"
TAG="v${VER}-bootstrap"
APK="DSHNative-bootstrap.apk"

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
ok()  { printf '  [OK] %s\n' "$*"; }
bad() { printf '  [FAIL] %s\n' "$*"; FAILED=1; }
FAILED=0

say "1. release 存在且不是草稿"
INFO=$(gh release view "$TAG" --repo "$REPO" --json isDraft,isPrerelease,assets 2>/dev/null)
if [ -z "$INFO" ]; then
    bad "release $TAG 不存在"
else
    DRAFT=$(printf '%s' "$INFO" | python3 -c 'import json,sys;print(json.load(sys.stdin)["isDraft"])')
    # 草稿对公众不可下载 —— 这是最容易漏掉的一种失败
    [ "$DRAFT" = "False" ] && ok "已发布（非草稿）" || bad "还是草稿状态，公众无法下载"
    printf '%s' "$INFO" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for a in d.get('assets',[]):
    print(f\"  [OK] 资产 {a['name']}  {a['size']/1048576:.2f}MB\")
if not d.get('assets'): print('  [--] 资产列表为空（多为 API 限流），以下载路径为准')
"
fi

say "2. 两条下载路径都能取到（直连 + 镜像）"
for url in \
    "https://github.com/${REPO}/releases/download/${TAG}/${APK}" \
    "https://gh-proxy.com/https://github.com/${REPO}/releases/download/${TAG}/${APK}"; do
    HOST=$(printf '%s' "$url" | cut -d/ -f3)
    CODE=$(curl -sSL -o /dev/null -w '%{http_code}' -r 0-1023 --max-time 60 "$url" 2>/dev/null)
    if [ "$CODE" = "206" ] || [ "$CODE" = "200" ]; then
        ok "$HOST → HTTP $CODE"
    else
        bad "$HOST → HTTP $CODE（应为 206/200）"
    fi
done

say "3. 本地清单是否指向这个版本"
if [ -f latest.json ]; then
    MANIFEST_VER=$(python3 -c 'import json;print(json.load(open("latest.json"))["version"])' 2>/dev/null)
    if [ "$MANIFEST_VER" = "$VER" ]; then
        ok "latest.json → $MANIFEST_VER"
    else
        printf '  [--] latest.json → %s（与本次 %s 不同，收尾时再写即可）\n' "$MANIFEST_VER" "$VER"
    fi
fi

say "结论"
if [ "$FAILED" = "0" ]; then
    echo "  发布可用。可以写 latest.json 了。"
    exit 0
else
    echo "  发布**不可用** —— 先修好再写 latest.json。"
    echo "  最常见的原因：release 还是草稿（gh release edit $TAG --draft=false），"
    echo "  或者资产没传完（gh release upload $TAG $APK）。"
    exit 1
fi
