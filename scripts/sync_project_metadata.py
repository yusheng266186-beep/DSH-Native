#!/usr/bin/env python3
"""Synchronize release metadata into latest.json and README.

The release workflow owns the values.  Humans should not hand-edit version links,
APK size, payload size, or checksum in README because all four have drifted before.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parent.parent


def mib(value: int) -> str:
    amount = value / 1048576.0
    return f"{amount:.1f} MiB"


def manifest_bytes(path: pathlib.Path) -> int:
    data = json.loads(path.read_text(encoding="utf-8"))
    parts = data.get("parts")
    if not isinstance(parts, list) or not parts:
        raise ValueError("payload manifest has no parts")
    sizes = [part.get("size") for part in parts if isinstance(part, dict)]
    if len(sizes) != len(parts) or any(not isinstance(size, int) or size <= 0 for size in sizes):
        raise ValueError("payload manifest contains an invalid part size")
    return sum(sizes)


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_version() -> str:
    text = (ROOT / "scripts/mkmanifest.py").read_text(encoding="utf-8")
    match = re.search(r'"versionName",\s*s\("([0-9]+\.[0-9]+\.[0-9]+)"\)', text)
    if not match:
        raise ValueError("cannot read versionName from scripts/mkmanifest.py")
    return match.group(1)


def source_payload_tag() -> str:
    text = (ROOT / "src/dev/dsh/nativeapp/MainActivity.java").read_text(encoding="utf-8")
    match = re.search(r"releases/download/(payload-v[0-9]+)/", text)
    if not match:
        raise ValueError("cannot read payload tag from MainActivity.java")
    return match.group(1)


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    changed, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise ValueError(f"README field not found or ambiguous: {label}")
    return changed


def update_readme(text: str, version: str, apk_bytes: int,
                  payload_bytes: int, sha256: str) -> str:
    apk_size = mib(apk_bytes)
    payload_size = mib(payload_bytes)

    text = replace_once(
        text,
        r"(DSHNative-bootstrap\.apk\)\*\*（)[^）\n]+(）)",
        rf"\g<1>{apk_size}\g<2>",
        "top APK size",
    )
    text = re.sub(
        r"releases/download/v[0-9]+\.[0-9]+\.[0-9]+-bootstrap/DSHNative-bootstrap\.apk",
        f"releases/download/v{version}-bootstrap/DSHNative-bootstrap.apk",
        text,
    )
    text = re.sub(
        r"releases/tag/v[0-9]+\.[0-9]+\.[0-9]+-bootstrap",
        f"releases/tag/v{version}-bootstrap",
        text,
    )
    text = re.sub(r"\[v[0-9]+\.[0-9]+\.[0-9]+ 引导式（推荐）\]",
                  f"[v{version} 引导式（推荐）]", text)
    text = replace_once(
        text,
        r"\*\*当前版本：[0-9]+\.[0-9]+\.[0-9]+\*\*",
        f"**当前版本：{version}**",
        "current version",
    )
    text = re.sub(r"SHA-256：`[0-9a-f]{64,}`", f"SHA-256：`{sha256}`", text)

    # Current-release size locations only; historical examples are deliberately untouched.
    text = replace_once(
        text,
        r"(首启分块下载运行包（带断点续传与 SHA 校验） \| APK )[^|\n]+( \|)",
        rf"\g<1>{apk_size} + 首启约 {payload_size}\g<2>",
        "release table sizes",
    )
    text = replace_once(
        text,
        r"(1\. 安装 APK（)[^）\n]+(）)",
        rf"\g<1>{apk_size}\g<2>",
        "install APK size",
    )
    text = re.sub(r"约 [0-9]+(?:\.[0-9]+)?\s*MiB 运行包", f"约 {payload_size} 运行包", text)
    text = re.sub(r"首启分块下载约 [0-9]+(?:\.[0-9]+)?\s*MiB 运行包",
                  f"首启分块下载约 {payload_size} 运行包", text)
    text = re.sub(r"压缩后约 [0-9]+(?:\.[0-9]+)?\s*MiB",
                  f"压缩后约 {payload_size}", text)
    return text


def check_consistency(latest_path: pathlib.Path, readme_path: pathlib.Path) -> list[str]:
    errors: list[str] = []
    latest = json.loads(latest_path.read_text(encoding="utf-8"))
    readme = readme_path.read_text(encoding="utf-8")
    version = source_version()
    payload_tag = source_payload_tag()

    java = (ROOT / "src/dev/dsh/nativeapp/MainActivity.java").read_text(encoding="utf-8")
    if f"APK 版本: {version}" not in java:
        errors.append("MainActivity log version differs from manifest version")
    if latest.get("version") != version:
        errors.append("latest.json version differs from source version")
    if latest.get("payload") != payload_tag:
        errors.append("latest.json payload tag differs from MainActivity")
    if f"**当前版本：{version}**" not in readme:
        errors.append("README current version differs from source version")

    apk_bytes = latest.get("apk_bytes")
    payload_bytes = latest.get("payload_bytes")
    sha = latest.get("sha256")
    if not isinstance(apk_bytes, int) or apk_bytes <= 0:
        errors.append("latest.json is missing apk_bytes")
    if not isinstance(payload_bytes, int) or payload_bytes <= 0:
        errors.append("latest.json is missing payload_bytes")
    if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{64}", sha) is None:
        errors.append("latest.json is missing a valid sha256")
    if isinstance(apk_bytes, int) and mib(apk_bytes) not in readme:
        errors.append("README does not contain generated APK size")
    if isinstance(payload_bytes, int) and mib(payload_bytes) not in readme:
        errors.append("README does not contain generated payload size")
    if isinstance(sha, str) and f"SHA-256：`{sha}`" not in readme:
        errors.append("README checksum differs from latest.json")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version")
    parser.add_argument("--apk", type=pathlib.Path)
    parser.add_argument("--apk-bytes", type=int)
    parser.add_argument("--payload-manifest", type=pathlib.Path)
    parser.add_argument("--payload-bytes", type=int)
    parser.add_argument("--sha256")
    parser.add_argument("--latest", type=pathlib.Path, default=ROOT / "latest.json")
    parser.add_argument("--readme", type=pathlib.Path, default=ROOT / "README.md")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    if args.check:
        errors = check_consistency(args.latest, args.readme)
        if errors:
            for error in errors:
                print(f"[FAIL] {error}", file=sys.stderr)
            return 1
        print("[OK] release metadata is consistent")
        return 0

    latest = json.loads(args.latest.read_text(encoding="utf-8"))
    version = args.version or str(latest.get("version") or source_version())
    apk_bytes = args.apk_bytes
    if args.apk is not None:
        apk_bytes = args.apk.stat().st_size
    payload_bytes = args.payload_bytes
    if args.payload_manifest is not None:
        payload_bytes = manifest_bytes(args.payload_manifest)
    sha256 = args.sha256
    if args.apk is not None:
        sha256 = file_sha256(args.apk)

    if not apk_bytes or not payload_bytes or not sha256:
        parser.error("update mode requires APK size, payload size, and SHA-256")
    if re.fullmatch(r"[0-9a-f]{64}", sha256) is None:
        parser.error("SHA-256 must be 64 lowercase hexadecimal characters")

    latest.update({
        "version": version,
        "apk_bytes": int(apk_bytes),
        "payload_bytes": int(payload_bytes),
        "sha256": sha256,
    })
    args.latest.write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    readme = args.readme.read_text(encoding="utf-8")
    args.readme.write_text(update_readme(readme, version, int(apk_bytes),
                                         int(payload_bytes), sha256), encoding="utf-8")
    print(f"[OK] README/latest.json -> v{version}, {mib(apk_bytes)} + {mib(payload_bytes)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
