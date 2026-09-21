#!/bin/bash
# DSH Native POC —— 手工组装 APK
# 不依赖 aapt2（本机无法运行），改为：
#   手写二进制 AndroidManifest.xml (mkmanifest.py)
#   + javac 编译 → d8 转 dex → zip 打包 → apksigner 签名
set -euo pipefail

BUILD=/root/build
POC=$BUILD/poc
STAGING=$BUILD/staging
OUT=$POC/out
SDK=$BUILD/sdk
TOOLS=$BUILD/tools

TERMUX_NODE="$STAGING/data/data/com.termux/files/usr/bin/node"
TERMUX_LIB="$STAGING/data/data/com.termux/files/usr/lib"

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 0. 前置检查
say "0. 检查工具链"
for t in java javac keytool; do
  command -v $t >/dev/null 2>&1 || die "缺少 $t（JDK 未安装完成）"
done
D8_JAR=$(find "$TOOLS" -name 'd8.jar' | head -1)
APKSIGNER_JAR=$(find "$TOOLS" -name 'apksigner.jar' | head -1)
[ -n "$D8_JAR" ] || die "找不到 d8.jar"
[ -n "$APKSIGNER_JAR" ] || die "找不到 apksigner.jar"
[ -f "$SDK/android.jar" ] || die "找不到 android.jar"
[ -f "$TERMUX_NODE" ] || die "找不到 node 二进制"
echo "  java     : $(java -version 2>&1 | head -1)"
echo "  d8       : $D8_JAR"
echo "  apksigner: $APKSIGNER_JAR"
echo "  android  : $SDK/android.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$OUT/classes" "$OUT/dex" "$OUT/apk"

# ---------------------------------------------------------------- 1. 清单文件
say "1. 生成二进制 AndroidManifest.xml"
if [ -f "$BUILD/mkmanifest.py" ]; then
  python3 "$BUILD/mkmanifest.py" "$OUT/AndroidManifest.xml" || die "清单生成失败"
else
  die "缺少 $BUILD/mkmanifest.py"
fi
SZ=$(stat -c%s "$OUT/AndroidManifest.xml")
echo "  生成成功: $SZ 字节"
[ "$SZ" -lt 100 ] && die "清单文件过小，格式可能有问题"

# ---------------------------------------------------------------- 2. 准备 payload
say "2. 准备内置运行时 (payload)"
P=$POC/payload
rm -rf "$P"
mkdir -p "$P/lib"

cp "$TERMUX_NODE" "$P/node"
chmod 755 "$P/node"
echo "  node          : $(du -h "$P/node" | cut -f1)  ($(readelf -l "$P/node" 2>/dev/null | grep -oE 'interpreter: [^]]*' | head -1))"

# 把 node 真正需要、且系统不提供的库挑出来，按 soname 命名
REQUIRED="libz.so.1 libcares.so libsqlite3.so libffi.so libcrypto.so.3 libssl.so.3 \
          libicui18n.so.78 libicuuc.so.78 libicudata.so.78 libc++_shared.so"
for soname in $REQUIRED; do
  src=""
  # 优先精确匹配，其次前缀匹配（处理带完整版本号的库文件名）
  for cand in "$TERMUX_LIB/$soname" "$STAGING/lib/$soname"; do
    [ -e "$cand" ] && { src="$cand"; break; }
  done
  if [ -z "$src" ]; then
    src=$(find "$STAGING/lib" -maxdepth 1 -name "${soname}*" -type f 2>/dev/null | head -1)
  fi
  if [ -n "$src" ] && [ -e "$src" ]; then
    cp -L "$src" "$P/lib/$soname"      # -L 跟随符号链接，落地为真实文件
    printf '  %-22s ← %s\n' "$soname" "$(basename "$src")"
  else
    die "缺少必需库: $soname"
  fi
done

# 一并带上 node 的 JS 服务脚本（源文件在 src/，避免被上面的 rm -rf 清掉）
cp "$BUILD/src/srv.js" "$P/srv.js"
echo "  srv.js        : $(du -h "$P/srv.js" | cut -f1)"
echo "  payload 总计  : $(du -sh "$P" | cut -f1)"

# ---------------------------------------------------------------- 3. 编译 Java
say "3. 编译 Java 源码"
find "$POC/src" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs echo "  源文件数:"
javac --release 8 -nowarn -proc:none \
      -classpath "$SDK/android.jar" \
      -d "$OUT/classes" \
      @"$OUT/sources.txt" 2>&1 | head -10 || true
CLASS_N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CLASS_N" -gt 0 ] || die "编译未产出 class 文件"
echo "  产出 class: $CLASS_N 个"

# ---------------------------------------------------------------- 4. 转 dex
say "4. d8 转换 dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --min-api 24 --release \
     --lib "$SDK/android.jar" \
     --output "$OUT/dex" \
     $(find "$OUT/classes" -name '*.class') 2>&1 | tail -5
[ -f "$OUT/dex/classes.dex" ] || die "未生成 classes.dex"
echo "  classes.dex: $(du -h "$OUT/dex/classes.dex" | cut -f1)"

# ---------------------------------------------------------------- 5. 组装 APK
say "5. 组装 APK"
cd "$OUT/apk"
cp "$OUT/AndroidManifest.xml" .
cp "$OUT/dex/classes.dex" .
mkdir -p assets
cp -r "$P" assets/payload
find . -type f | sed 's|^\./||' | sort | head -20
echo "  …"
# 注意：不压缩 node/so，避免解压开销并保证可执行位语义
zip -q -r -X ../unsigned.apk . -x '.*' || die "zip 打包失败"
cd "$BUILD"
echo "  未签名 APK: $(du -h "$OUT/unsigned.apk" | cut -f1)"

# ---------------------------------------------------------------- 6. 签名
say "6. 生成签名密钥并签名"
KS=$POC/release.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v \
    -keystore "$KS" -storepass dshnative -keypass dshnative \
    -alias dshnative -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DSH Native, OU=POC, O=DSH, L=NA, ST=NA, C=CN" 2>&1 | tail -2
  echo "  已生成密钥库: $KS"
fi

java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:dshnative --key-pass pass:dshnative \
  --ks-key-alias dshnative \
  --min-sdk-version 24 \
  --out "$POC/DSHNative-poc.apk" \
  "$OUT/unsigned.apk" 2>&1 | tail -5

[ -f "$POC/DSHNative-poc.apk" ] || die "签名失败"
say "完成"
ls -lh "$POC/DSHNative-poc.apk"
echo
echo "验证签名:"
java -jar "$APKSIGNER_JAR" verify --min-sdk-version 24 --print-certs \
     "$POC/DSHNative-poc.apk" 2>&1 | head -11
