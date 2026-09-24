#!/usr/bin/env bash
# 校验「仓库里的签名密钥」与「某个已发布 APK 的签名」是不是同一把。
#
# 为什么需要它（真实事故）：
#   仓库里的 scripts/release.keystore 是第一版 PoC 提交进来的（证书 OU=POC），
#   而项目改用引导式架构后，构建用的其实是另一把（OU=Bootstrap）。
#   两者一直并存、没人核对过 —— 直到用户点更新时被系统拒绝：
#   「安装失败(-7) 与已安装应用签名不同」，只能卸载重装，
#   而卸载会清掉应用私有目录里的全部会话与密钥。
#
#   签名密钥是应用身份，不是构建参数。任何一次发版前都该先核对它与
#   已发布版本一致 —— 这个脚本就是把那次核对固化下来。
#
# 用法：check_signer.sh <APK 路径或下载 URL> [keystore]
set -euo pipefail

TARGET="${1:?用法: check_signer.sh <APK 路径或下载 URL> [keystore]}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KS="${2:-$ROOT/scripts/release.keystore}"
[ -f "$KS" ] || { echo "[FAIL] 找不到密钥 $KS"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if printf '%s' "$TARGET" | grep -qE '^https?://'; then
    echo "  参考 APK：$TARGET"
    curl -sSL --retry 3 --max-time 600 -o "$TMP/ref.apk" "$TARGET"
else
    cp "$TARGET" "$TMP/ref.apk"
fi

if [ -n "${APKSIGNER_JAR:-}" ]; then
    apksigner() { java -jar "$APKSIGNER_JAR" "$@"; }
fi

norm() { tr 'A-Z' 'a-z' | tr -d ' :'; }

APK_CERT="$(apksigner verify --print-certs "$TMP/ref.apk" 2>/dev/null \
            | grep -i 'certificate SHA-256 digest' | head -1 \
            | awk -F': *' '{print $2}' | norm)"
KS_CERT="$(keytool -list -v -keystore "$KS" -storepass dshnative 2>/dev/null \
            | grep -iE '^[[:space:]]*SHA256:' | head -1 \
            | awk -F': *' '{print $2}' | norm)"

echo "  已发布 APK 的签名证书 : $APK_CERT"
echo "  仓库密钥的证书        : $KS_CERT"
[ -n "$APK_CERT" ] || { echo "[FAIL] 读不到 APK 的签名证书"; exit 1; }
[ -n "$KS_CERT" ]  || { echo "[FAIL] 读不到密钥的证书（口令不是 dshnative？）"; exit 1; }

if [ "$APK_CERT" = "$KS_CERT" ]; then
    echo "  [OK] 同一把密钥 —— 用它签出的包可以覆盖安装"
else
    echo "[FAIL] 不是同一把密钥！用它签名，已安装的用户全都装不上。"
    echo "       必须使用与已发布版本一致的密钥，不要生成新的。"
    exit 1
fi
