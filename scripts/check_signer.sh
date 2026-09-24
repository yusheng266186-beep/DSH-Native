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

# 提取指纹时**不要**用 awk 按 ": " 切分字段：
# -F': *' 里的 " *" 是"零个或多个空格"，于是它会按**每个冒号**切分，
# 指纹 "9B:F1:9E:…" 会被切成 "9B"，取到的是垃圾（实测踩过）。
# 直接按前缀定位、去掉冒号、统一小写，最稳。
APK_RAW="$(apksigner verify --print-certs "$TMP/ref.apk" 2>/dev/null \
           | grep -i 'certificate SHA-256 digest' | head -1)"
KS_RAW="$(keytool -list -v -keystore "$KS" -storepass dshnative 2>/dev/null \
           | grep -i 'SHA256:' | head -1)"
APK_CERT="$(printf '%s' "$APK_RAW" | sed -E 's/.*[Dd]igest:[[:space:]]*//' \
            | tr -d ':' | tr 'A-Z' 'a-z' | tr -d '[:space:]')"
KS_CERT="$(printf '%s' "$KS_RAW" | sed -E 's/.*SHA256:[[:space:]]*//' \
            | tr -d ':' | tr 'A-Z' 'a-z' | tr -d '[:space:]')"

echo "  已发布 APK 的签名证书 : $APK_CERT"
echo "  仓库密钥的证书        : $KS_CERT"
for v in "$APK_CERT" "$KS_CERT"; do
    printf '%s' "$v" | grep -qE '^[0-9a-f]{64}$' || {
        echo "[FAIL] 指纹格式不对（应为 64 位十六进制）；原始行："
        echo "       APK: $APK_RAW"
        echo "       KEY: $KS_RAW"
        exit 1
    }
done

if [ "$APK_CERT" = "$KS_CERT" ]; then
    echo "  [OK] 同一把密钥 —— 用它签出的包可以覆盖安装"
else
    echo "[FAIL] 不是同一把密钥！用它签名，已安装的用户全都装不上。"
    echo "       必须使用与已发布版本一致的密钥，不要生成新的。"
    exit 1
fi
