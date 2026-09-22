#!/bin/bash
# adb 封装：设定 Android 库路径与可写 HOME
ADB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export LD_LIBRARY_PATH="$ADB_DIR/x/data/data/com.termux/files/usr/lib"
export HOME=/data/data/com.termux/cache/adbhome
export TMPDIR=$HOME
exec "$ADB_DIR/x/data/data/com.termux/files/usr/bin/adb" "$@"
