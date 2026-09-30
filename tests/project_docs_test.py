"""Exercise release-driven documentation updates without modifying real manifests."""

import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
if not SCRIPTS.exists():
    SCRIPTS = ROOT / "bootstrap/scripts"
sys.path.insert(0, str(SCRIPTS))
import project_docs as docs
import sync_project_metadata as metadata


class ProjectDocsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        values = {
            "scripts/mkmanifest.py": '"versionName", s("0.32.2")',
            "src/dev/dsh/nativeapp/MainActivity.java": "releases/download/payload-v10/ APK 版本: 0.32.2",
            "README.md": "# App\n\nUser guide.\n",
            "README.en.md": "# App\n\nEnglish guide.\n",
            "AGENTS.md": "# Work rules\n\nKeep signatures.\n",
            "docs/STATUS.md": "# Status\n\n" + docs.FACTS_START + "\n" + docs.FACTS_END + "\n",
            "docs/MODEL_REASONING.md": "# Models\n\n" + docs.MODELS_START + "\n" + docs.MODELS_END + "\n",
            "docs/PHASE5A.md": "# Old phase\n\nHistorical candidate: 0.27.0, 10 checks.\n",
            "release-notes/v0.27.0.md": "## v0.27.0\n\nOriginal release changes.\n",
        }
        for name, value in values.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value, encoding="utf-8")
        self.write_json("latest.json", dict(version="0.32.1", tag="v0.32.1-bootstrap",
                        payload="payload-v10", apk="DSHNative-bootstrap.apk",
                        apk_bytes=1048576, payload_bytes=2097152, sha256="a" * 64))
        self.write_json("latest-test.json", dict(version="0.26.0", tag="v0.26.0-test",
                        payload="payload-v9"))
        self.write_json("runtime/core-source.json", dict(version="0.2.0-rc.2"))
        self.write_json("tests/fixtures/command-code-reasoning-1.72.4.json", {
            "models": {"stealth/space-bunny-alpha": ["low", "medium", "high"],
                       "automatic-model": [], "declared-max": ["high", "max"]}})

    def write_json(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_candidate_status_history_and_old_test_payload(self):
        before = (self.root / "latest-test.json").read_bytes()
        self.assertEqual(docs.synchronize(self.root), [])
        status = (self.root / "docs/STATUS.md").read_text()
        self.assertIn("`0.32.1`", status)
        self.assertIn("`0.32.2`", status)
        self.assertIn("`0.26.0` · `v0.26.0-test` · `payload-v9`", status)
        self.assertEqual(before, (self.root / "latest-test.json").read_bytes())
        history = (self.root / "docs/PHASE5A.md").read_text()
        self.assertIn("历史记录", history)
        self.assertIn("Historical candidate: 0.27.0, 10 checks.", history)
        release = (self.root / "release-notes/v0.27.0.md").read_text()
        self.assertTrue(release.startswith("## v0.27.0"))
        self.assertIn("Original release changes.", release)
        self.assertEqual(docs.synchronize(self.root, check=True), [])

    def test_new_release_updates_every_status_and_detects_drift(self):
        docs.synchronize(self.root)
        stable = json.loads((self.root / "latest.json").read_text())
        stable.update(version="0.32.2", tag="v0.32.2-bootstrap", sha256="b" * 64)
        self.write_json("latest.json", stable)
        errors = docs.synchronize(self.root, check=True)
        self.assertTrue(any("README.en.md" in error for error in errors))
        self.assertEqual(docs.synchronize(self.root), [])
        self.assertIn("`" + "b" * 64 + "`", (self.root / "docs/STATUS.md").read_text())
        self.assertEqual(docs.synchronize(self.root, check=True), [])

    def test_model_table_distinguishes_declarations_from_requests(self):
        table = docs.model_table(self.root)
        self.assertIn("| `stealth/space-bunny-alpha` | low / medium / high | low / medium / high / max（请求） |", table)
        self.assertIn("| `automatic-model` | 未公布可调档位 | 服务商默认（不传参数） / max（请求） |", table)
        self.assertIn("| `declared-max` | high / max | high / max（请求） |", table)
        docs.synchronize(self.root)
        model = self.root / "docs/MODEL_REASONING.md"
        model.write_text(model.read_text().replace("max（请求）", "max", 1))
        self.assertTrue(any("MODEL_REASONING" in error for error in docs.synchronize(self.root, check=True)))

    def test_broken_local_link_and_code_examples(self):
        path = self.root / "README.md"
        path.write_text(path.read_text() + "\n[missing](docs/absent.md)\n"
                        + "[external](https://example.com/page)\n"
                        + "```md\n[example](not-a-real-file.md)\n```\n")
        errors = docs.synchronize(self.root)
        self.assertEqual(len(errors), 1)
        self.assertIn("docs/absent.md", errors[0])

    def test_inline_marker_documentation_is_not_a_generated_block(self):
        path = self.root / "README.md"
        path.write_text(path.read_text() + "\nUse `" + docs.STATUS_START + "` as a marker.\n")
        self.assertEqual(docs.synchronize(self.root), [])
        first = path.read_bytes()
        self.assertEqual(docs.synchronize(self.root), [])
        self.assertEqual(first, path.read_bytes())

    def test_duplicate_incomplete_and_reversed_markers_fail(self):
        for content in (docs.STATUS_START + "\nx\n" + docs.STATUS_START + "\ny\n" + docs.STATUS_END,
                        docs.STATUS_START + "\nx\n",
                        docs.STATUS_END + "\nx\n" + docs.STATUS_START):
            with self.subTest(content=content), self.assertRaises(ValueError):
                docs.replace_block(content, docs.STATUS_START, docs.STATUS_END, "new", insert=True)

    def test_english_release_link_is_synchronized(self):
        text = ("**Current stable release: 0.32.1**\n"
                "**[Download DSHNative-bootstrap.apk](https://example.com/releases/download/"
                "v0.32.1-bootstrap/DSHNative-bootstrap.apk)** (1.0 MiB)\n"
                "[Release](https://example.com/releases/tag/v0.32.1-bootstrap)\n"
                "SHA-256: `" + "a" * 64 + "`\nroughly 2.0 MiB runtime\n")
        changed = metadata.update_readme_en(text, "0.32.2", 2097152, 3145728, "b" * 64)
        self.assertIn("releases/tag/v0.32.2-bootstrap", changed)
        self.assertIn("releases/download/v0.32.2-bootstrap", changed)
        self.assertIn("(2.0 MiB)", changed)
        self.assertIn("roughly 3.0 MiB runtime", changed)

    def test_wrong_readme_download_tag_is_detected(self):
        stable = json.loads((self.root / "latest.json").read_text())
        chinese = ("**当前版本：0.32.1**\n"
                   "**[下载 DSHNative-bootstrap.apk](https://example.com/releases/download/"
                   "v0.32.0-bootstrap/DSHNative-bootstrap.apk)**（1.0 MiB）\n"
                   "SHA-256：`" + stable["sha256"] + "`\n约 2.0 MiB 运行包\n")
        english = ("**Current stable release: 0.32.1**\n"
                   "**[Download DSHNative-bootstrap.apk](https://example.com/releases/download/"
                   "v0.32.1-bootstrap/DSHNative-bootstrap.apk)** (1.0 MiB)\n"
                   "SHA-256: `" + stable["sha256"] + "`\nroughly 2.0 MiB runtime\n")
        zh, en = self.root / "README.md", self.root / "README.en.md"
        zh.write_text(chinese)
        en.write_text(english)
        self.assertEqual(metadata.check_readme_generation(self.root / "latest.json", zh, en),
                         ["README.md release download fields are stale"])


if __name__ == "__main__":
    unittest.main()
