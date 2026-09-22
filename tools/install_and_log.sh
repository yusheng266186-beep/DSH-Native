#!/bin/bash
# 安装 APK 并抓取启动日志
ADB=/root/build/adb_tools/adb.sh
APK="${1:-/root/build/bootstrap/DSHNative-bootstrap.apk}"
PKG=dev.dsh.native

echo "=== 1. 卸载旧版本（签名/包名一致可跳过）==="
timeout 90 $ADB uninstall $PKG 2>&1 | grep -v 'WARNING: linker' | head -2

echo "=== 2. 安装 $APK ==="
timeout 300 $ADB install -r "$APK" 2>&1 | grep -v 'WARNING: linker' | tail -3

echo "=== 3. 清空日志缓冲 ==="
timeout 60 $ADB logcat -c 2>&1 | grep -v 'WARNING: linker'

echo "=== 4. 启动 App ==="
timeout 90 $ADB shell am start -n $PKG/dev.dsh.nativeapp.MainActivity 2>&1 | grep -v 'WARNING: linker' | head -3

echo "=== 5. 等待 12 秒后抓日志 ==="
sleep 12
timeout 90 $ADB logcat -d -v time 2>&1 | grep -v 'WARNING: linker' \
  | grep -iE 'DSHNative|dsh\.native|AndroidRuntime|FATAL|ClassNotFound|EACCES|Permission denied|selinux' | tail -60
