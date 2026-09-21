#!/bin/bash
# 构建「引导式」DSH Native APK
# APK 只内置 Node + 引导脚本（~35MB）；运行包首次启动时下载。
set -euo pipefail

BUILD=/root/build
BOOT=$BUILD/bootstrap
STAGING=$BUILD/staging
OUT=$BOOT/out
SDK=$BUILD/sdk
TOOLS=$BUILD/tools

TERMUX_NODE="$STAGING/data/data/com.termux/files/usr/bin/node"
TERMUX_LIB="$STAGING/data/data/com.termux/files/usr/lib"

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

say "0. 检查工具链"
for t in java javac keytool; do
  command -v $t >/dev/null 2>&1 || die "缺少 $t"
done
D8_JAR=$(find "$TOOLS" -name 'd8.jar' | head -1)
APKSIGNER_JAR=$(find "$TOOLS" -name 'apksigner.jar' | head -1)
[ -f "$SDK/android.jar" ] || die "缺少 android.jar"
[ -f "$TERMUX_NODE" ] || die "缺少 node"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk/assets/payload/lib"

say "1. 生成 AndroidManifest.xml"
python3 "$BUILD/mkmanifest.py" "$OUT/AndroidManifest.xml" || die "清单生成失败"
echo "  $(stat -c%s "$OUT/AndroidManifest.xml") 字节"

say "2. 组装内置负载（Node + 引导脚本）"
P="$OUT/apk/assets/payload"
cp "$TERMUX_NODE" "$P/node"
chmod 755 "$P/node"
echo "  node: $(du -h "$P/node" | cut -f1)"

REQUIRED="libz.so.1 libcares.so libsqlite3.so libffi.so libcrypto.so.3 libssl.so.3 \
          libicui18n.so.78 libicuuc.so.78 libicudata.so.78 libc++_shared.so"
for soname in $REQUIRED; do
  src=""
  for cand in "$TERMUX_LIB/$soname" "$STAGING/lib/$soname"; do
    [ -e "$cand" ] && { src="$cand"; break; }
  done
  [ -n "$src" ] || src=$(find "$STAGING/lib" -maxdepth 1 -name "${soname}*" -type f 2>/dev/null | head -1)
  [ -n "$src" ] || die "缺少必需库: $soname"
  cp -L "$src" "$P/lib/$soname"
done
echo "  共享库: $(ls "$P/lib" | wc -l) 个"

cp "$BOOT/payload/unpack.js" "$P/unpack.js"
echo "  引导脚本: unpack.js"

say "3. 编译 Java"
find "$BOOT/src" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -nowarn -proc:none \
      -classpath "$SDK/android.jar" \
      -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | head -10 || true
CLASS_N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CLASS_N" -gt 0 ] || die "编译未产出 class"
echo "  class: $CLASS_N 个"

say "4. d8 转 dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --min-api 24 --release --lib "$SDK/android.jar" \
     --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') 2>&1 | grep -v '^Warning' | tail -3
[ -f "$OUT/dex/classes.dex" ] || die "未生成 classes.dex"
echo "  classes.dex: $(du -h "$OUT/dex/classes.dex" | cut -f1)"

say "5. 打包 APK"
cd "$OUT/apk"
cp "$OUT/AndroidManifest.xml" .
cp "$OUT/dex/classes.dex" .
python3 "$BUILD/mkzip.py" . ../unsigned.apk
cd "$BUILD"

say "6. 签名"
KS=$BOOT/release.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass dshnative -keypass dshnative \
    -alias dshnative -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DSH Native, OU=Bootstrap, O=DSH, L=NA, ST=NA, C=CN" 2>&1 | tail -1
fi
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:dshnative --key-pass pass:dshnative \
  --ks-key-alias dshnative --min-sdk-version 24 \
  --out "$BOOT/DSHNative-bootstrap.apk" "$OUT/unsigned.apk" 2>&1 | tail -3
[ -f "$BOOT/DSHNative-bootstrap.apk" ] || die "签名失败"

say "完成"
ls -lh "$BOOT/DSHNative-bootstrap.apk"
java -jar "$APKSIGNER_JAR" verify --min-sdk-version 24 --print-certs \
     "$BOOT/DSHNative-bootstrap.apk" 2>&1 | head -4
