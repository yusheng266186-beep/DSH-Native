#!/usr/bin/env python3
"""Generate documentation state from published metadata and pinned source facts."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
from urllib.parse import unquote


STATUS_START = "<!-- dsh-doc-status:start -->"
STATUS_END = "<!-- dsh-doc-status:end -->"
FACTS_START = "<!-- dsh-release-facts:start -->"
FACTS_END = "<!-- dsh-release-facts:end -->"
MODELS_START = "<!-- dsh-model-table:start -->"
MODELS_END = "<!-- dsh-model-table:end -->"


def documentation_files(root: Path) -> list[Path]:
    paths = [root / name for name in ("README.md", "README.en.md", "AGENTS.md")]
    for directory in ("docs", "scripts", "tools", "patch", "release-notes"):
        paths.extend((root / directory).rglob("*.md"))
    return sorted(path for path in paths if path.is_file())


def facts(root: Path) -> dict:
    # Keep the published channels bound to their own APK, even during development.
    from sync_project_metadata import source_version, source_payload_tag
    import sync_project_metadata as metadata
    previous = metadata.ROOT
    metadata.ROOT = root
    try:
        source = source_version()
        payload = source_payload_tag()
    finally:
        metadata.ROOT = previous
    return {
        "stable": json.loads((root / "latest.json").read_text(encoding="utf-8")),
        "test": json.loads((root / "latest-test.json").read_text(encoding="utf-8")),
        "core": json.loads((root / "runtime/core-source.json").read_text(encoding="utf-8")),
        "source": source,
        "payload": payload,
    }


def kind(path: Path, root: Path) -> str:
    relative = path.relative_to(root).as_posix()
    if relative.startswith("release-notes/"):
        return "release"
    if path.name.startswith("PHASE") or path.name.startswith("UPSTREAM-ISSUE") \
            or path.name == "FILE-BROWSER-RESEARCH.md":
        return "history"
    if relative.startswith("patch/02-session-link-to-rename/"):
        return "legacy"
    return "current"


def status_block(path: Path, root: Path, data: dict) -> str:
    relative = os.path.relpath(root / "docs/STATUS.md", path.parent).replace(os.sep, "/")
    stable = data["stable"]["version"]
    source = data["source"]
    payload = data["payload"]
    core = data["core"]["version"]
    category = kind(path, root)
    english = path.name in ("README.en.md", "UPSTREAM-ISSUE.md")
    if english:
        scope = {
            "history": "Historical record: dates, measurements and plans below describe that iteration.",
            "release": "Release record: the version in the title describes this release only.",
            "legacy": "Archived patch: do not apply it to the current runtime.",
            "current": "Maintained documentation for the current source.",
        }[category]
        body = (f"> {scope} Published stable: **{stable}**; source: **{source}**; "
                f"source payload: `{payload}`; pinned DSH: `{core}` (upstream release candidate). "
                f"[Current status and verification boundaries]({relative}).")
    else:
        scope = {
            "history": "历史记录：下文日期、测试数字、候选状态与计划保留当时语境，不代表当前待办。",
            "release": "版本记录：标题版本对应本次发布，原始变更内容保留，不覆盖为新版说明。",
            "legacy": "归档补丁：禁止应用到当前运行包；现行替代方案见正文。",
            "current": "现行文档：按当前源码维护。",
        }[category]
        body = (f"> {scope} 已发布 stable：**{stable}**；源码：**{source}**；"
                f"源码运行包：`{payload}`；固定 DSH：`{core}`（上游候选版）。"
                f"[统一进度与验证边界]({relative})。")
    return f"{STATUS_START}\n{body}\n{STATUS_END}"


def replace_block(text: str, start: str, end: str, replacement: str,
                  *, insert: bool = False) -> str:
    starts = re.findall(r"^" + re.escape(start) + r"$", text, re.M)
    ends = re.findall(r"^" + re.escape(end) + r"$", text, re.M)
    if len(starts) == 1 and len(ends) == 1:
        pattern = r"(?m)^" + re.escape(start) + r"$[\s\S]*?^" + re.escape(end) + r"$"
        changed, count = re.subn(pattern, lambda _: replacement, text, count=1)
        if count != 1:
            raise ValueError(f"reversed generated block: {start}")
        return changed
    if starts or ends:
        raise ValueError(f"invalid or duplicate generated block: {start}")
    if not insert:
        raise ValueError(f"missing generated block: {start}")
    heading, separator, rest = text.partition("\n")
    if not separator or re.match(r"^#{1,6} ", heading) is None:
        raise ValueError("documentation must begin with a Markdown heading")
    return heading + "\n\n" + replacement + "\n" + rest


def release_facts(data: dict) -> str:
    stable, test = data["stable"], data["test"]
    def size(value: int) -> str:
        return f"{value / 1048576:.1f} MiB"
    body = "\n".join([
        "| 项目 | 当前值 / 权威来源 |", "|---|---|",
        f"| 已发布稳定版 | `{stable['version']}` · `{stable['tag']}` · `latest.json` |",
        f"| 稳定 APK | `{stable['apk']}` · {size(stable['apk_bytes'])} |",
        f"| 稳定运行包 | `{stable['payload']}` · 压缩总量 {size(stable['payload_bytes'])} |",
        f"| APK SHA-256 | `{stable['sha256']}` |",
        f"| 已发布测试版 | `{test['version']}` · `{test['tag']}` · `{test['payload']}`；独立保留，不冒充新版 |",
        f"| 当前源码版本 | `{data['source']}` · `scripts/mkmanifest.py` / `MainActivity.java` |",
        f"| 源码运行包 | `{data['payload']}` · `MainActivity.java` |",
        f"| 固定内核 | DSH `{data['core']['version']}`（上游候选版）· `runtime/core-source.json` |",
        "| Android 架构 | minSdk 24 · targetSdk 28 · ARM64 · 包名 `dev.dsh.native` |",
    ])
    return f"{FACTS_START}\n{body}\n{FACTS_END}"


def model_table(root: Path) -> str:
    snapshot = json.loads((root / "tests/fixtures/command-code-reasoning-1.72.4.json")
                          .read_text(encoding="utf-8"))
    rows = ["| Command Code 模型 ID | 固定官方快照声明 | App 提供的选项 |",
            "|---|---|---|"]
    for model, levels in snapshot["models"].items():
        official = " / ".join(levels) if levels else "未公布可调档位"
        # The extension always sends literal max, even if an upstream alias differed.
        offered = [level for level in levels if level != "max"]
        if not offered and not levels:
            offered = ["服务商默认（不传参数）"]
        offered.append("max（请求）")
        rows.append(f"| `{model}` | {official} | {' / '.join(offered)} |")
    return MODELS_START + "\n" + "\n".join(rows) + "\n" + MODELS_END


def expected_document(path: Path, root: Path, data: dict) -> str:
    text = path.read_text(encoding="utf-8")
    text = replace_block(text, STATUS_START, STATUS_END,
                         status_block(path, root, data), insert=True)
    if path == root / "docs/STATUS.md":
        text = replace_block(text, FACTS_START, FACTS_END, release_facts(data))
    if path == root / "docs/MODEL_REASONING.md":
        text = replace_block(text, MODELS_START, MODELS_END, model_table(root))
    return text


def link_errors(path: Path, root: Path, text: str) -> list[str]:
    # Validate local file links; external URLs and historical remote sources are not crawled.
    cleaned = re.sub(r"```[\s\S]*?```", "", text)
    errors = []
    for target in re.findall(r"!?\[[^\]\n]*\]\(([^)\n]+)\)", cleaned):
        target = target.strip().split(' "', 1)[0].strip("<>")
        if not target or target.startswith(("#", "/")) or re.match(r"[a-zA-Z][\w+.-]*:", target):
            continue
        local = unquote(target.split("#", 1)[0].split("?", 1)[0])
        if local and not (path.parent / local).exists():
            errors.append(f"{path.relative_to(root)}: missing local link target {target}")
    return errors


def synchronize(root: Path, *, check: bool = False) -> list[str]:
    data = facts(root)
    errors = []
    for path in documentation_files(root):
        current = path.read_text(encoding="utf-8")
        try:
            expected = expected_document(path, root, data)
        except ValueError as error:
            errors.append(f"{path.relative_to(root)}: {error}")
            continue
        if check:
            if expected != current:
                errors.append(f"{path.relative_to(root)}: generated documentation is stale")
        elif expected != current:
            path.write_text(expected, encoding="utf-8")
        errors.extend(link_errors(path, root, expected))
    return errors
