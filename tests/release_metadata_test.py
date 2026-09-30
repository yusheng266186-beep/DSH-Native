"""Source upgrades must not rewrite metadata belonging to older published APKs."""
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
script = ROOT / 'scripts/sync_project_metadata.py'
if not script.exists():
    script = ROOT / 'bootstrap/scripts/sync_project_metadata.py'
spec = importlib.util.spec_from_file_location('metadata', script)
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class MetadataTest(unittest.TestCase):
    def test_candidate_and_old_channel_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'scripts').mkdir()
            (root / 'scripts/mkmanifest.py').write_text('"versionName", s("0.32.0")')
            java = root / 'src/dev/dsh/nativeapp/MainActivity.java'
            java.parent.mkdir(parents=True)
            java.write_text('releases/download/payload-v10/ APK 版本: 0.32.0')
            manifest = dict(version='0.31.5', payload='payload-v9', apk_bytes=1048576,
                            payload_bytes=1048576, sha256='a' * 64)
            stable, test, readme = [root / name for name in ('latest.json', 'latest-test.json', 'README.md')]
            stable.write_text(json.dumps(manifest))
            test.write_text(json.dumps(dict(manifest, version='0.26.0')))
            readme.write_text('**当前版本：0.31.5** 1.0 MiB SHA-256：`' + 'a' * 64 + '`')
            old_root = metadata.ROOT
            metadata.ROOT = root
            try:
                self.assertEqual(metadata.check_consistency(stable, test, readme,
                                 allow_unpublished_source=True), [])
                self.assertIn('source version differs from both stable and test manifests',
                              metadata.check_consistency(stable, test, readme))
                stable.write_text(json.dumps(dict(manifest, version='0.32.0', payload='payload-v10')))
                readme.write_text('**当前版本：0.32.0** 1.0 MiB SHA-256：`' + 'a' * 64 + '`')
                self.assertEqual(metadata.check_consistency(stable, test, readme), [])
                stable.write_text(json.dumps(dict(manifest, version='0.32.0')))
                self.assertIn('stable manifest payload differs from MainActivity',
                              metadata.check_consistency(stable, test, readme))
                test.write_text(json.dumps(dict(manifest, version='0.33.0')))
                self.assertIn('test manifest payload differs from MainActivity',
                              metadata.check_consistency(stable, test, readme, allow_unpublished_source=True))
            finally:
                metadata.ROOT = old_root


if __name__ == '__main__':
    unittest.main()
