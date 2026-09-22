#!/bin/bash
# 无线调试配对 + 连接
# 用法: pair.sh <配对码> <配对端口> <连接端口> [设备IP]
ADB=/root/build/adb_tools/adb.sh
CODE="$1"; PAIRPORT="$2"; CONNPORT="$3"; IP="${4:-127.0.0.1}"
[ -z "$CODE" ] || [ -z "$PAIRPORT" ] || [ -z "$CONNPORT" ] && {
  echo "用法: pair.sh <配对码> <配对端口> <连接端口> [设备IP]"; exit 2; }

echo "=== 1. 配对 (${IP}:${PAIRPORT}) ==="
timeout 90 $ADB pair "${IP}:${PAIRPORT}" "$CODE" 2>&1 | grep -v 'WARNING: linker'
echo
echo "=== 2. 连接 (${IP}:${CONNPORT}) ==="
timeout 90 $ADB connect "${IP}:${CONNPORT}" 2>&1 | grep -v 'WARNING: linker'
echo
echo "=== 3. 设备列表 ==="
timeout 60 $ADB devices -l 2>&1 | grep -v 'WARNING: linker'
