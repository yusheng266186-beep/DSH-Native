#!/usr/bin/env python3
"""Synchronize stable/test release metadata and both stable READMEs.

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
    text = re.sub(r"约 [0-9]+(?:\.[0-9]+)?\s*MiB 运行包", f"约 {payload_size} 运行包", text)
    text = re.sub(r"首启分块下载约 [0-9]+(?:\.[0-9]+)?\s*MiB 运行包",
                  f"首启分块下载约 {payload_size} 运行包", text)
    text = re.sub(r"压缩后约 [0-9]+(?:\.[0-9]+)?\s*MiB",
                  f"压缩后约 {payload_size}", text)
    return text


def update_readme_en(text: str, version: str, apk_bytes: int,
                     payload_bytes: int, sha256: str) -> str:
    apk_size = mib(apk_bytes)
    payload_size = mib(payload_bytes)
    text = replace_once(
        text,
        r"(DSHNative-bootstrap\.apk\)\*\* \()[^)\n]+(\))",
        rf"\g<1>{apk_size}\g<2>",
        "English top APK size",
    )
    text = re.sub(
        r"releases/download/v[0-9]+\.[0-9]+\.[0-9]+-bootstrap/DSHNative-bootstrap\.apk",
        f"releases/download/v{version}-bootstrap/DSHNative-bootstrap.apk",
        text,
    )
    text = replace_once(
        text,
        r"\*\*Current stable release: [0-9]+\.[0-9]+\.[0-9]+\*\*",
        f"**Current stable release: {version}**",
        "English current version",
    )
    text = re.sub(r"SHA-256: `[0-9a-f]{64,}`", f"SHA-256: `{sha256}`", text)
    text = re.sub(r"roughly [0-9]+(?:\.[0-9]+)?\s*MiB runtime",
                  f"roughly {payload_size} runtime", text)
    return text


def validate_manifest(path: pathlib.Path, payload_tag: str, label: str) -> tuple[dict, list[str]]:
    errors: list[str] = []
    if not path.exists():
        return {}, [f"{label} manifest is missing"]
    latest = json.loads(path.read_text(encoding="utf-8"))
    version = latest.get("version")
    if not isinstance(version, str) or re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is None:
        errors.append(f"{label} manifest has an invalid version")
    if latest.get("payload") != payload_tag:
        errors.append(f"{label} manifest payload differs from MainActivity")

    apk_bytes = latest.get("apk_bytes")
    payload_bytes = latest.get("payload_bytes")
    sha = latest.get("sha256")
    if not isinstance(apk_bytes, int) or apk_bytes <= 0:
        errors.append(f"{label} manifest is missing apk_bytes")
    if not isinstance(payload_bytes, int) or payload_bytes <= 0:
        errors.append(f"{label} manifest is missing payload_bytes")
    if not isinstance(sha, str) or re.fullmatch(r"[0-9a-f]{64}", sha) is None:
        errors.append(f"{label} manifest is missing a valid sha256")
    return latest, errors


def version_tuple(value: str) -> tuple[int, int, int]:
    match = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)", value)
    if match is None:
        raise ValueError(f"invalid semantic version: {value}")
    return tuple(int(part) for part in match.groups())


def check_consistency(stable_path: pathlib.Path, test_path: pathlib.Path,
                      readme_path: pathlib.Path,
                      readme_en_path: pathlib.Path | None = None,
                      allow_unpublished_source: bool = False) -> list[str]:
    errors: list[str] = []
    readme = readme_path.read_text(encoding="utf-8")
    readme_en = ""
    if readme_en_path is not None:
        if readme_en_path.exists():
            readme_en = readme_en_path.read_text(encoding="utf-8")
        else:
            errors.append("English README is missing")
    version = source_version()
    payload_tag = source_payload_tag()
    stable, stable_errors = validate_manifest(stable_path, payload_tag, "stable")
    errors.extend(stable_errors)
    test: dict = {}
    if test_path.exists():
        test, test_errors = validate_manifest(test_path, payload_tag, "test")
        errors.extend(test_errors)

    java = (ROOT / "src/dev/dsh/nativeapp/MainActivity.java").read_text(encoding="utf-8")
    if f"APK 版本: {version}" not in java:
        errors.append("MainActivity log version differs from source version")
    published_versions = {stable.get("version"), test.get("version")}
    if version not in published_versions:
        published = [item for item in published_versions if isinstance(item, str)]
        source_is_next = allow_unpublished_source and published \
            and all(version_tuple(version) > version_tuple(item) for item in published)
        if not source_is_next:
            errors.append("source version differs from both stable and test manifests")
    stable_version = stable.get("version")
    if isinstance(stable_version, str) and f"**当前版本：{stable_version}**" not in readme:
        errors.append("README current version differs from stable manifest")
    if isinstance(stable_version, str) and readme_en \
            and f"**Current stable release: {stable_version}**" not in readme_en:
        errors.append("English README current version differs from stable manifest")

    apk_bytes = stable.get("apk_bytes")
    payload_bytes = stable.get("payload_bytes")
    sha = stable.get("sha256")
    if isinstance(apk_bytes, int) and mib(apk_bytes) not in readme:
        errors.append("README does not contain generated APK size")
    if isinstance(apk_bytes, int) and readme_en and mib(apk_bytes) not in readme_en:
        errors.append("English README does not contain generated APK size")
    if isinstance(payload_bytes, int) and mib(payload_bytes) not in readme:
        errors.append("README does not contain generated payload size")
    if isinstance(payload_bytes, int) and readme_en and mib(payload_bytes) not in readme_en:
        errors.append("English README does not contain generated payload size")
    if isinstance(sha, str) and f"SHA-256：`{sha}`" not in readme:
        errors.append("README checksum differs from latest.json")
    if isinstance(sha, str) and readme_en and f"SHA-256: `{sha}`" not in readme_en:
        errors.append("English README checksum differs from latest.json")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version")
    parser.add_argument("--apk", type=pathlib.Path)
    parser.add_argument("--apk-bytes", type=int)
    parser.add_argument("--payload-manifest", type=pathlib.Path)
    parser.add_argument("--payload-bytes", type=int)
    parser.add_argument("--sha256")
    parser.add_argument("--channel", choices=("stable", "test"), default="stable")
    parser.add_argument("--latest", type=pathlib.Path)
    parser.add_argument("--test-latest", type=pathlib.Path,
                        default=ROOT / "latest-test.json")
    parser.add_argument("--readme", type=pathlib.Path, default=ROOT / "README.md")
    parser.add_argument("--readme-en", type=pathlib.Path, default=ROOT / "README.en.md")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--allow-unpublished-source", action="store_true",
                        help="allow a strictly newer source version for pull-request APKs")
    args = parser.parse_args()

    if args.check:
        stable_path = args.latest or ROOT / "latest.json"
        errors = check_consistency(stable_path, args.test_latest, args.readme,
                                   args.readme_en,
                                   args.allow_unpublished_source)
        if errors:
            for error in errors:
                print(f"[FAIL] {error}", file=sys.stderr)
            return 1
        print("[OK] release metadata is consistent")
        return 0

    latest_path = args.latest or ROOT / (
        "latest-test.json" if args.channel == "test" else "latest.json")
    latest = json.loads(latest_path.read_text(encoding="utf-8"))
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
    latest_path.write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    if args.channel == "stable":
        readme = args.readme.read_text(encoding="utf-8")
        args.readme.write_text(update_readme(readme, version, int(apk_bytes),
                                             int(payload_bytes), sha256), encoding="utf-8")
        if args.readme_en.exists():
            readme_en = args.readme_en.read_text(encoding="utf-8")
            args.readme_en.write_text(update_readme_en(
                readme_en, version, int(apk_bytes), int(payload_bytes), sha256),
                encoding="utf-8")
    print(f"[OK] {args.channel} metadata -> v{version}, "
          f"{mib(apk_bytes)} + {mib(payload_bytes)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
