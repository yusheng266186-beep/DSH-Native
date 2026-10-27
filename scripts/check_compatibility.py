#!/usr/bin/env python3
"""Verify the reviewed 1.0 runtime tuple and the actual inherited APK binaries."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent


def check(root=ROOT, payload=None):
    frozen = json.loads((root / "runtime/compatibility.json").read_text())
    core = json.loads((root / "runtime/core-source.json").read_text())
    lock = json.loads((root / "runtime/codebuddy/package-lock.json").read_text())
    main = (root / "src/dev/dsh/nativeapp/MainActivity.java").read_text()
    manifest = (root / "scripts/mkmanifest.py").read_text()
    adapter = (root / "payload/codebuddy-cli.cjs").read_text()
    assert core['version'] == frozen['core']['version'], 'core version drifted'
    assert 'releases/download/' + frozen['core']['payload'] + '/' in main, 'payload tag drifted'
    assert frozen['core']['manifest_sha256'] in main, 'trusted manifest digest drifted'
    assert lock['packages']['node_modules/@tencent-ai/agent-sdk']['version'] == frozen['codebuddy']['sdk'], 'SDK lock drifted'
    for key in ['sdk', 'cli']:
        assert f"manifest.{key}!=='{frozen['codebuddy'][key]}'" in adapter, f'{key} verifier drifted'
    assert f's("{frozen["android"]["package"]}")' in manifest, 'app package drifted'
    for field, key in [('minSdkVersion', 'minSdk'), ('targetSdkVersion', 'targetSdk')]:
        assert re.search(r'"' + field + r'", dec\(' + str(frozen['android'][key]) + r'\)', manifest), field + ' drifted'
    for name, digest in frozen['dependency_locks'].items():
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, 'dependency lock drifted: ' + name
    if payload is not None:
        expected = frozen['bootstrap_binaries']
        assert len(expected) == 11, 'incomplete bootstrap binary baseline'
        for name, value in expected.items():
            target = payload / name
            assert target.is_file() and target.stat().st_size == value['bytes'], 'bootstrap size drift: ' + name
            assert hashlib.sha256(target.read_bytes()).hexdigest() == value['sha256'], 'bootstrap digest drift: ' + name
    return frozen


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--payload', type=Path, help='actual extracted APK assets/payload')
    args = parser.parse_args()
    check(payload=args.payload)
    print('Compatibility freeze: reviewed core, SDK, Android tuple' + (' and 11 actual binary digests' if args.payload else '') + ' verified.')
