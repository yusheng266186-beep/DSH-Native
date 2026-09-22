#!/bin/bash
# 构建「引导式」DeepSeek Harness APK
#
# APK 内置 Node 运行时与引导脚本（约 34MB）；运行包首次启动时下载。
#
# 资源处理：
#   aapt2 负责编译 res/ 并分配资源 id；清单仍由 mkmanifest.py 手写二进制生成
#   （它支持任意属性，不受 android.jar 版本限制）。
#   aapt2 使用现代 android.jar，以支持 statusBarColor 等 API21+ 属性。
#   资源 id 由 aapt2 导出后经环境变量传给 mkmanifest.py，确保两侧永远一致。
set -euo pipefail

BUILD=/root/build
BOOT=$BUILD/bootstrap
STAGING=$BUILD/staging
OUT=$BOOT/out
SDK=$BUILD/sdk
TOOLS=$BUILD/tools

TERMUX_NODE="$STAGING/data/data/com.termux/files/usr/bin/node"
TERMUX_LIB="$STAGING/data/data/com.termux/files/usr/lib"
AAPT2="$STAGING/data/data/com.termux/files/usr/bin/aapt2"
AAPT2_LIB="$TERMUX_LIB"
RESDIR="$BUILD/icon/res"

say() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[FAIL] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 0. 工具链
say "0. 检查工具链"
for t in java javac keytool python3; do
  command -v $t >/dev/null 2>&1 || die "缺少 $t"
done
D8_JAR=$(find "$TOOLS" -name 'd8.jar' | head -1)
APKSIGNER_JAR=$(find "$TOOLS" -name 'apksigner.jar' | head -1)
[ -n "$D8_JAR" ] || die "找不到 d8.jar"
[ -n "$APKSIGNER_JAR" ] || die "找不到 apksigner.jar"
[ -f "$SDK/android.jar" ] || die "缺少 android.jar（框架资源表）"
[ -x "$AAPT2" ] || die "找不到 aapt2"
[ -f "$TERMUX_NODE" ] || die "找不到 node"

ANDROID_JAR_COMPILE="$SDK/android-modern.jar"
[ -f "$ANDROID_JAR_COMPILE" ] || ANDROID_JAR_COMPILE="$SDK/android.jar"
echo "  编译用 JAR: $ANDROID_JAR_COMPILE"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/apk/assets/payload/lib"

# ---------------------------------------------------------------- 1. 内置负载
say "1. 组装内置负载（Node + 引导脚本）"
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

for f in unpack.js openssl.cnf preflight.js sharp-android.js pillow_shim.py settings-preset.yaml ca-certificates.crt; do
  [ -f "$BOOT/payload/$f" ] || die "缺少引导脚本 $f"
  cp "$BOOT/payload/$f" "$P/$f"
done
echo "  引导脚本: $(ls "$P" | grep -vE '^(node|lib)$' | tr '\n' ' ')"

# ---------------------------------------------------------------- 2. 资源
say "2. 编译资源（图标 / 主题 / 配色）"
[ -d "$RESDIR" ] || die "找不到资源目录 $RESDIR"
env LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" compile --dir "$RESDIR" -o "$OUT/res.zip" \
    || die "aapt2 compile 失败"
echo "  编译产物: $(stat -c%s "$OUT/res.zip") 字节"

cat > "$OUT/res_manifest.xml" <<'XEOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="dev.dsh.native">
    <application android:icon="@mipmap/ic_launcher" android:theme="@style/AppTheme">
        <activity android:name=".A">
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts"/>
        </activity>
    </application>
</manifest>
XEOF

env LD_LIBRARY_PATH="$AAPT2_LIB" "$AAPT2" link \
    -o "$OUT/resources.apk" -I "$ANDROID_JAR_COMPILE" \
    --manifest "$OUT/res_manifest.xml" \
    --output-text-symbols "$OUT/symbols.txt" \
    "$OUT/res.zip" || die "aapt2 link 失败"

ICON_RES_ID=$(awk '$2=="mipmap" && $3=="ic_launcher"{print $4}' "$OUT/symbols.txt")
THEME_RES_ID=$(awk '$2=="style" && $3=="AppTheme"{print $4}' "$OUT/symbols.txt")
[ -n "$ICON_RES_ID" ] || die "未能取得 ic_launcher 资源 id"
[ -n "$THEME_RES_ID" ] || die "未能取得 AppTheme 资源 id"
SHORTCUTS_RES_ID=$(awk '$2=="xml" && $3=="shortcuts"{print $4}' "$OUT/symbols.txt")
[ -n "$SHORTCUTS_RES_ID" ] || die "未能取得 shortcuts 资源 id"
echo "  ic_launcher = $ICON_RES_ID    AppTheme = $THEME_RES_ID    shortcuts = $SHORTCUTS_RES_ID"

python3 - "$OUT/resources.apk" "$OUT/apk" <<'PYEOF'
import sys, zipfile, os
src, dst = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
n = 0
for name in z.namelist():
    if name == 'AndroidManifest.xml':
        continue
    if name == 'resources.arsc' or name.startswith('res/'):
        out = os.path.join(dst, name)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, 'wb') as g:
            g.write(z.read(name))
        n += 1
print(f"  已注入 {n} 个资源条目（resources.arsc + res/）")
PYEOF

# ---------------------------------------------------------------- 3. 清单
say "3. 生成二进制 AndroidManifest.xml"
DSH_ICON_RES_ID="$ICON_RES_ID" DSH_THEME_RES_ID="$THEME_RES_ID" \
  DSH_SHORTCUTS_RES_ID="$SHORTCUTS_RES_ID" \
  python3 "$BUILD/mkmanifest.py" "$OUT/AndroidManifest.xml" || die "清单生成失败"
echo "  $(stat -c%s "$OUT/AndroidManifest.xml") 字节（图标/主题 id 已注入）"

# ---------------------------------------------------------------- 3.4 纯逻辑测试
# FileListing 是纯 Java（无 Android 依赖），可在普通 JVM 上直接验证。
# 排序、边界、格式化一旦改坏，这里立刻失败 —— 不必等装到手机靠截图发现。
say "3.4 纯逻辑测试"
bash run_tests.sh

# ---------------------------------------------------------------- 3.45 架构约束
# 纯逻辑层必须保持无 Android 依赖 —— 否则就无法在普通 JVM 上测试，
# 「构建期跑测试」这个保证会静默失效。这是架构约束，不是风格偏好。
say "3.45 架构约束检查"
PURE_FILES="bootstrap/src/dev/dsh/nativeapp/FileListing.java bootstrap/src/dev/dsh/nativeapp/TextCodec.java bootstrap/src/dev/dsh/nativeapp/Version.java bootstrap/src/dev/dsh/nativeapp/CommandCodeUsage.java bootstrap/src/dev/dsh/nativeapp/TaskNotifier.java bootstrap/src/dev/dsh/nativeapp/FileOps.java bootstrap/src/dev/dsh/nativeapp/ConfigBackup.java bootstrap/src/dev/dsh/nativeapp/ShareTargets.java"
for f in $PURE_FILES; do
    [ -f "$f" ] || die "缺少纯逻辑文件 $f"
    if grep -nE '^import +android\.|^import +androidx\.' "$f" >/dev/null 2>&1; then
        grep -nE '^import +android\.|^import +androidx\.' "$f" | sed 's/^/    /'
        die "$f 引入了 Android 依赖 —— 它将无法离线测试"
    fi
done
echo "  [OK] 纯逻辑层无 Android 依赖（$(basename -a $PURE_FILES | tr '\n' ' '))"

# ---------------------------------------------------------------- 3.5 UI 规范
# 强制检查：原生界面必须走 DshUi 组件层，禁止系统默认样式
# （见 docs/DESIGN.md —— 用户要求原生 UI 与 DSH 视觉统一，此约束长期有效）
say "3.5 UI 规范检查"
# 只匹配真实调用（构造或 Builder），避免误报注释里对 AlertDialog 的说明
# 注意 || true：set -o pipefail 下 grep 无匹配会返回 1，导致整条管道失败、
# 脚本静默退出（同样的坑此前在 d8 步骤踩过一次）。
UI_BAD=$(grep -rnE 'AlertDialog\.Builder|new +AlertDialog' "$BOOT/src" 2>/dev/null | wc -l || true)
if [ "$UI_BAD" -gt 0 ]; then
  echo "  [FAIL] 发现 $UI_BAD 处系统原生 AlertDialog，违反 docs/DESIGN.md"
  grep -rnE 'AlertDialog\.Builder|new +AlertDialog' "$BOOT/src" | head -5 | sed 's/^/    /'
  die "请改用 DshUi.dialog()（见 docs/DESIGN.md）"
fi
DSUI_USE=$(grep -rlc 'DshUi\.' "$BOOT/src" 2>/dev/null | wc -l || true)
echo "  [OK] 无系统 AlertDialog；DshUi 使用文件数: $DSUI_USE"

# ---------------------------------------------------------------- 4. Java
say "4. 编译 Java"
find "$BOOT/src" -name '*.java' > "$OUT/sources.txt"
# 注意：必须检查 javac 的退出码。此前只在最后检查"有没有 class"，
# 导致编译报错时仍可能产出残缺 APK（实测踩过：class 文件只剩 1 个却照常打包）。
set +e
JAVAC_OUT=$(javac --release 8 -nowarn -proc:none \
      -classpath "$ANDROID_JAR_COMPILE" \
      -d "$OUT/classes" @"$OUT/sources.txt" 2>&1)
JAVAC_RC=$?
set -e
echo "$JAVAC_OUT" | grep -v 'deprecat' | head -10 || true
[ "$JAVAC_RC" -eq 0 ] || die "javac 编译失败（退出码 $JAVAC_RC）"
if echo "$JAVAC_OUT" | grep -q '^.*error:'; then
  die "javac 报告了编译错误（见上方输出）"
fi
CLASS_N=$(find "$OUT/classes" -name '*.class' | wc -l)
[ "$CLASS_N" -ge 8 ] || die "编译产物异常：只有 $CLASS_N 个 class（预期至少 8 个）"
echo "  class: $CLASS_N 个"

# ---------------------------------------------------------------- 5. dex
say "5. d8 转换 dex"
java -cp "$D8_JAR" com.android.tools.r8.D8 \
     --min-api 24 --release --lib "$ANDROID_JAR_COMPILE" \
     --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') 2>&1 \
     | grep -viE '^warning|superclass' | tail -3 || true
# 注意：上面的 || true 是必需的 —— set -o pipefail 下，
# 若 d8 只输出被过滤掉的警告，grep 返回 1 会让整个脚本静默退出。
[ -f "$OUT/dex/classes.dex" ] || die "未生成 classes.dex"
echo "  classes.dex: $(du -h "$OUT/dex/classes.dex" | cut -f1)"

# ---------------------------------------------------------------- 6. 打包
say "6. 打包 APK"
cd "$OUT/apk"
cp "$OUT/AndroidManifest.xml" .
cp "$OUT/dex/classes.dex" .
python3 "$BUILD/mkzip.py" . ../unsigned.apk
cd "$BUILD"

# ---------------------------------------------------------------- 7. 签名
say "7. 签名"
KS=$BOOT/release.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass dshnative -keypass dshnative \
    -alias dshnative -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DeepSeek Harness, OU=Android, O=DSH, L=NA, ST=NA, C=CN" 2>&1 | tail -1
fi
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:dshnative --key-pass pass:dshnative \
  --ks-key-alias dshnative --min-sdk-version 24 \
  --out "$BOOT/DSHNative-bootstrap.apk" "$OUT/unsigned.apk" 2>&1 | tail -3
[ -f "$BOOT/DSHNative-bootstrap.apk" ] || die "签名失败"

say "完成"
ls -lh "$BOOT/DSHNative-bootstrap.apk"
java -jar "$APKSIGNER_JAR" verify --min-sdk-version 24 --print-certs \
     "$BOOT/DSHNative-bootstrap.apk" 2>&1 | head -3
