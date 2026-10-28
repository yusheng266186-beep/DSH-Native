#!/usr/bin/env python3
"""CI promotion must reject stale, foreign, failed or altered artifacts."""
import copy
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verified_build', ROOT/'scripts/verified_build.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = pathlib.Path(self.temp.name)/'app.apk'
        self.apk.write_bytes(b'synthetic-signed-artifact')
        self.repo = 'owner/repo'
        self.tree = 'a'*40
        self.run = {'id': 123, 'run_attempt': 2, 'head_sha': 'b'*40,
                    'path': '.github/workflows/build.yml', 'status': 'completed', 'conclusion': 'success',
                    'repository': {'full_name': self.repo}, 'head_repository': {'full_name': self.repo}}
        self.proof = {'schema': 1, 'checks': 'full-android-runtime', 'repository': self.repo,
                      'run_id': 123, 'run_attempt': 2, 'workflow_head_sha': 'b'*40,
                      'source_commit': 'c'*40, 'source_tree': self.tree, 'version': '1.1.6',
                      'apk_bytes': self.apk.stat().st_size, 'apk_sha256': build.digest(self.apk)}

    def validate(self, proof=None, run=None, commit_tree=None):
        build.validate(proof or self.proof, run or self.run, self.tree, self.repo,
                       '1.1.6', self.apk, commit_tree or self.tree)

    def test_squash_merge(self):
        # Commit IDs differ after squash; the complete source tree must match.
        self.validate()

    def test_proof_mismatches(self):
        for key, value in {'schema': 0, 'checks': 'compile-only', 'repository': 'fork/repo',
                           'run_id': 124, 'run_attempt': 1, 'workflow_head_sha': 'd'*40,
                           'source_tree': 'd'*40, 'version': '1.1.5', 'source_commit': '../invalid',
                           'apk_bytes': 1, 'apk_sha256': '0'*64}.items():
            with self.subTest(key=key):
                proof = {**self.proof, key: value}
                with self.assertRaises(ValueError):
                    self.validate(proof=proof)

    def test_run_mismatches(self):
        for key, value in {'status': 'in_progress', 'conclusion': 'failure',
                           'path': '.github/workflows/release.yml',
                           'repository': {'full_name': 'other/repo'},
                           'head_repository': {'full_name': 'fork/repo'}}.items():
            with self.subTest(key=key):
                with self.assertRaises(ValueError):
                    self.validate(run={**copy.deepcopy(self.run), key: value})

    def test_commit_tree_mismatch(self):
        with self.assertRaises(ValueError):
            self.validate(commit_tree='d'*40)

    def test_corrupt_same_size_apk(self):
        self.apk.write_bytes(b'x'*self.apk.stat().st_size)
        with self.assertRaises(ValueError):
            self.validate()


if __name__ == '__main__':
    unittest.main()
