#!/usr/bin/env python3
"""Release gates must reject drift, not merely exist as documents."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
if not (ROOT / 'runtime/compatibility.json').is_file():
    ROOT = ROOT / 'bootstrap'


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class CompatibilityTest(unittest.TestCase):
    def test_actual_reviewed_tuple(self):
        self.assertEqual(module('check_compatibility').check(ROOT)['schema'], 1)

    def test_drift_is_rejected(self):
        checker = module('check_compatibility')
        with tempfile.TemporaryDirectory(prefix='dsh-compat-') as directory:
            root = Path(directory)
            for path in ['runtime/compatibility.json', 'runtime/core-source.json', 'runtime/core-package-lock.json', 'runtime/codebuddy/package-lock.json',
                         'src/dev/dsh/nativeapp/MainActivity.java', 'scripts/mkmanifest.py', 'payload/codebuddy-cli.cjs']:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / path, target)
            checker.check(root)
            source = root / 'runtime/core-source.json'
            data = json.loads(source.read_text()); data['version'] = 'unreviewed'
            source.write_text(json.dumps(data))
            with self.assertRaisesRegex(AssertionError, 'core version drifted'):
                checker.check(root)
            shutil.copyfile(ROOT / 'runtime/core-source.json', source)
            manifest = root / 'scripts/mkmanifest.py'
            manifest.write_text(manifest.read_text().replace('dec(28)', 'dec(35)'))
            with self.assertRaisesRegex(AssertionError, 'targetSdkVersion drifted'):
                checker.check(root)
            shutil.copyfile(ROOT / 'scripts/mkmanifest.py', manifest)
            with self.assertRaisesRegex(AssertionError, 'bootstrap size drift'):
                checker.check(root, payload=root / 'missing')

    def test_notices_include_both_real_locks(self):
        notices = module('generate_dependency_notices').inventory(ROOT)
        sdk = next(row for row in notices['groups']['codebuddy'] if row['path'] == 'node_modules/@tencent-ai/agent-sdk')
        self.assertEqual(sdk['version'], '0.3.291')
        self.assertEqual(sdk['license'], 'MIT')
        self.assertTrue(sdk['integrity'].startswith('sha512-'))
        self.assertGreater(len(notices['groups']['dsh']), 100)


if __name__ == '__main__':
    unittest.main()
