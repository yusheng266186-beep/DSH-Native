#!/usr/bin/env python3
"""Exercise the real publication gate without touching GitHub or the network."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
if not (ROOT / 'scripts/release.sh').is_file():
    ROOT = ROOT / 'bootstrap'
VERSION = re.search(r'"versionName",\s*s\("([0-9.]+)"\)',
                    (ROOT / 'scripts/mkmanifest.py').read_text())[1]

GH = '''#!/usr/bin/env python3
import hashlib, os, pathlib, sys
args = sys.argv[1:]
state = pathlib.Path(os.environ['RELEASE_TEST_STATE'])
if args[:2] == ['release', 'create']:
    state.write_text('uploaded')
elif args[:2] == ['release', 'view']:
    if not state.exists(): sys.exit(1)
    if '--jq' in args:
        query = args[args.index('--jq') + 1]
        apk = pathlib.Path(os.environ['RELEASE_TEST_APK'])
        if '.size' in query: print(apk.stat().st_size)
        elif '.digest' in query:
            value = hashlib.sha256(apk.read_bytes()).hexdigest()
            print('sha256:' + ('0' * 64 if os.environ['RELEASE_TEST_MODE'] == 'digest' else value))
else: sys.exit(2)
'''

CURL = '''#!/usr/bin/env python3
import os, pathlib, sys
args = sys.argv[1:]
out = pathlib.Path(args[args.index('-o') + 1])
url = next(a for a in args if a.startswith('https://'))
mode = os.environ['RELEASE_TEST_MODE']
if url.endswith('/manifest.json'):
    data = pathlib.Path(os.environ['RELEASE_TEST_MANIFEST']).read_bytes()
    out.write_bytes(data + (b' ' if mode == 'manifest' else b''))
else:
    data = pathlib.Path(os.environ['RELEASE_TEST_APK']).read_bytes()
    out.write_bytes(b'<html>gateway error</html>' if mode == 'html' else data if mode == 'full' else data[:1024])
    if '-w' in args: print('200' if mode in ('full', 'html') else '206', end='')
'''


class ReleaseGateTest(unittest.TestCase):
    def run_gate(self, mode, success):
        with tempfile.TemporaryDirectory(prefix='dsh-release-gate-') as directory:
            root = Path(directory)
            for name in ['scripts', 'docs', 'release-notes', 'patch', 'tools', 'runtime']:
                shutil.copytree(ROOT / name, root / name, ignore=shutil.ignore_patterns('node_modules', '__pycache__'))
            for name in ['README.md', 'README.en.md', 'AGENTS.md', 'LICENSE', 'latest.json', 'latest-test.json']:
                shutil.copyfile(ROOT / name, root / name)
            fixtures = root / 'tests/fixtures'
            fixtures.mkdir(parents=True)
            shutil.copyfile(Path(__file__).resolve().parent / 'fixtures/codebuddy-cli-catalog.json',
                            fixtures / 'codebuddy-cli-catalog.json')
            main = root / 'src/dev/dsh/nativeapp/MainActivity.java'
            main.parent.mkdir(parents=True)
            manifest = root / 'fixture-manifest.json'
            manifest.write_text(json.dumps({'parts': [{'size': 1234}]}))
            text = (ROOT / 'src/dev/dsh/nativeapp/MainActivity.java').read_text()
            text = re.sub(r'(PAYLOAD_MANIFEST_SHA256\s*=\s*")[a-f0-9]{64}',
                          lambda m: m[1] + hashlib.sha256(manifest.read_bytes()).hexdigest(), text)
            main.write_text(text)
            build = root / 'build/bootstrap'
            build.mkdir(parents=True)
            apk = build / 'DSHNative-bootstrap.apk'
            apk.write_bytes(b'PK\x03\x04' + bytes(range(256)) * 16)
            fakebin = root / 'fakebin'
            fakebin.mkdir()
            for name, body in [('gh', GH), ('curl', CURL), ('sleep', '#!/bin/sh\nexit 0\n')]:
                script = fakebin / name
                script.write_text(body)
                script.chmod(0o755)
            before = (root / 'latest.json').read_bytes()
            env = dict(os.environ, PATH=str(fakebin) + os.pathsep + os.environ['PATH'],
                       RELEASE_TEST_STATE=str(root / 'uploaded'), RELEASE_TEST_MODE=mode,
                       RELEASE_TEST_APK=str(apk), RELEASE_TEST_MANIFEST=str(manifest))
            result = subprocess.run(['/bin/bash', str(root / 'scripts/release.sh'), VERSION,
                                     str(build.parent), '', 'stable'], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
            self.assertEqual(result.returncode == 0, success, result.stdout[-4000:])
            if success:
                latest = json.loads((root / 'latest.json').read_text())
                self.assertEqual(latest['version'], VERSION)
                self.assertEqual(latest['sha256'], hashlib.sha256(apk.read_bytes()).hexdigest())
                self.assertEqual(latest['apk_bytes'], apk.stat().st_size)
                self.assertIn('v' + VERSION + '-bootstrap', (root / 'README.md').read_text())
            else:
                self.assertEqual((root / 'latest.json').read_bytes(), before)

    def test_valid_range_download(self):
        self.run_gate('range', True)

    def test_valid_complete_download(self):
        self.run_gate('full', True)

    def test_html_200_preserves_published_manifest(self):
        self.run_gate('html', False)

    def test_asset_digest_mismatch_preserves_published_manifest(self):
        self.run_gate('digest', False)

    def test_untrusted_payload_preserves_published_manifest(self):
        self.run_gate('manifest', False)


if __name__ == '__main__':
    unittest.main()
