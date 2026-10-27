#!/usr/bin/env python3
"""Run production AssetInstaller against the actual completed APK before publication."""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/dev/dsh/nativeapp'
if not SOURCE.is_dir():
    SOURCE = ROOT / 'bootstrap/src/dev/dsh/nativeapp'
if len(sys.argv) != 2:
    raise SystemExit('Usage: check_apk_components.py <signed-apk>')
apk = pathlib.Path(sys.argv[1]).resolve(strict=True)
compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
with tempfile.TemporaryDirectory(prefix='dsh-apk-installer-') as directory:
    subprocess.run([*compiler, '--release', '8', '-encoding', 'UTF-8', '-d', directory,
                    str(SOURCE / 'AssetInstaller.java'), str(ROOT / 'tests/AssetInstallerApkTest.java')], check=True)
    subprocess.run(['java', '-cp', directory, 'dev.dsh.nativeapp.AssetInstallerApkTest', str(apk)], check=True)
