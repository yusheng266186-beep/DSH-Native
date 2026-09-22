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
[ -f "$SDK/android.jar" ] || die "缺少 android.jar（aapt2 解析框架属性用）"
# 编译用现代 jar：旧 jar 是 API 16，缺少 evaluateJavascript(API19)、
# EXTRA_ALLOW_MULTIPLE(API18) 等在文件上传里必需的接口。
ANDROID_JAR_COMPILE="$SDK/android-modern.jar"
[ -f "$ANDROID_JAR_COMPILE" ] || ANDROID_JAR_COMPILE="$SDK/android.jar"
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
cp "$BOOT/payload/openssl.cnf" "$P/openssl.cnf"
cp "$BOOT/payload/preflight.js" "$P/preflight.js"
cp "$BOOT/payload/sharpstub.js" "$P/sharpstub.js"
cp "$BOOT/payload/settings-preset.yaml" "$P/settings-preset.yaml"
echo "  引导脚本: unpack.js, openssl.cnf, preflight.js, sharpstub.js, settings-preset.yaml"

say "3. 编译 Java"
find "$BOOT/src" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -nowarn -proc:none \
      -classpath "$ANDROID_JAR_COMPILE" \
      -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v 'deprecat' | head -10 || true
CLASS_N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CLASS_N" -gt 0 ] || die "编译未产出 class"
echo "  class: $CLASS_N 个"

say "4. d8 转 dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --min-api 24 --release --lib "$ANDROID_JAR_COMPILE" \
     --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') 2>&1 | grep -v '^Warning' | tail -3
[ -f "$OUT/dex/classes.dex" ] || die "未生成 classes.dex"
echo "  classes.dex: $(du -h "$OUT/dex/classes.dex" | cut -f1)"

# ---------------------------------------------------------------- 4.5 资源（图标）
say "4.5 编译资源（应用图标）"
AAPT2="$STAGING/data/data/com.termux/files/usr/bin/aapt2"
AAPT2_LIB="$BUILD/staging/data/data/com.termux/files/usr/lib"
RESDIR="$BUILD/icon/res"

if [ -x "$AAPT2" ] && [ -d "$RESDIR" ]; then
  env LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" compile --dir "$RESDIR" -o "$OUT/res.zip" \
      || die "aapt2 compile 失败"
  echo "  资源编译: $(stat -c%s "$OUT/res.zip") 字节"

  # 用最小源清单 link —— 只含 package 与 icon，
  # 这样旧版 android.jar 也能通过（新属性由手写二进制清单负责）。
  cat > "$OUT/res_manifest.xml" <<'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.dsh.native">
    <application android:icon="@mipmap/ic_launcher"/>
</manifest>
XEOF
  env LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" link \
      -o "$OUT/resources.apk" -I "$SDK/android.jar" \
      --manifest "$OUT/res_manifest.xml" "$OUT/res.zip" \
      || die "aapt2 link 失败"

  # 核对资源 id —— 必须与 mkmanifest.py 的 ICON_RES_ID 一致，否则图标不显示
  ICON_ID=$(env LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" dump resources "$OUT/resources.apk" 2>/dev/null \
            | grep -oE '0x[0-9a-f]+ mipmap/ic_launcher' | head -1 | awk '{print $1}')
  MANIFEST_ID=$(grep -oE 'ICON_RES_ID = 0x[0-9A-Fa-f]+' "$BUILD/mkmanifest.py" | grep -oE '0x[0-9A-Fa-f]+')
  echo "  ic_launcher: aapt2=$ICON_ID  清单=$MANIFEST_ID"
  if [ -n "$ICON_ID" ] && [ "$(echo "$ICON_ID" | tr 'A-F' 'a-f')" != "$(echo "$MANIFEST_ID" | tr 'A-F' 'a-f')" ]; then
    die "图标资源 id 不一致：请把 mkmanifest.py 的 ICON_RES_ID 改为 $ICON_ID"
  fi

  # 解出 resources.arsc 与 res/ 到 APK 暂存区
  python3 - "$OUT/resources.apk" "$OUT/apk" <<'PYEOF'
import sys, zipfile, os
src, dst = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
n = 0
for name in z.namelist():
    if name == 'AndroidManifest.xml':
        continue                      # 用手写的二进制清单，不用 aapt2 的
    if name == 'resources.arsc' or name.startswith('res/'):
        out = os.path.join(dst, name)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with z.open(name) as f, open(out, 'wb') as g:
            g.write(f.read())
        n += 1
print(f"  已注入 {n} 个资源条目（resources.arsc + res/）")
PYEOF
else
  echo "  ⚠️ 跳过（aapt2 或图标目录不可用）"
fi

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
