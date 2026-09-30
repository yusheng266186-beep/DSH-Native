#!/usr/bin/env python3
"""Build only the DSH shard; retain the verified Android binaries and tools.

Inputs are an extracted, SHA-verified previous runtime and its pinned manifest.
The new CLI tarball and dependency tree are pinned by runtime/core-*.json.
Publication must upload all shards/checksums before uploading manifest.json.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def replace_once(path, before, after):
    text = path.read_text()
    if text.count(before) != 1:
        raise ValueError(f'Upstream patch anchor changed: {path}')
    path.write_text(text.replace(before, after))


def patch_android(runtime, base):
    modules = runtime / 'node_modules'
    # Preserve the already shipped bionic ELF, never an npm Linux/glibc binary.
    native = base / 'node_modules/node-pty/prebuilds/linux-arm64/pty.node'
    assert native.read_bytes()[:5] == b'\x7fELF\x02'
    assert native.read_bytes()[18:20] == b'\xb7\x00', 'not AArch64'
    for platform in ('linux-arm64', 'android-arm64'):
        dest = modules / 'node-pty/prebuilds' / platform / 'pty.node'
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(native, dest)
    shutil.copytree(base / 'node_modules/@mmmbuto', modules / '@mmmbuto')
    shutil.copyfile(base / 'node_modules/node-addon-require-builtin/lib/index.js',
                    modules / 'node-addon-require-builtin/lib/index.js')

    # Previous payload swallowed native addon failures on every platform. Keep
    # the Android fallback, but preserve actual native locks on desktop/CI.
    flock = modules / '@deepseek-ai/node-addon-system/lib/flock.js'
    replace_once(flock, "import { createRequire } from 'node:module';",
                 "import { createRequire } from 'node:module';\n"
                 "import { existsSync } from 'node:fs';")
    replace_once(flock, '    const { platform, arch } = process;',
                 '    const { platform, arch } = process;\n'
                 "    if (platform === 'android' || (platform === 'linux' && arch === 'arm64'\n"
                 "        && existsSync('/system/bin/linker64'))) {\n"
                 '        // Single managed Android runtime; native flock has no bionic build.\n'
                 "        binding = { tryLock: (_fd, done) => done(0) };\n"
                 '        return binding;\n'
                 '    }')

    # Retain exclusive publication, including on filesystems without hardlinks.
    # COPYFILE_EXCL refuses to overwrite a concurrently published generation;
    # ordinary link errors still propagate instead of being silently swallowed.
    persistence = modules / '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js'
    replace_once(persistence, 'import { link, lstat,',
                 'import { copyFile as androidCopyFile, link, lstat,')
    replace_once(persistence, 'import { readdirSync } from "node:fs";',
                 'import { readdirSync, constants as androidFsConstants } from "node:fs";')
    replace_once(persistence, '\t\tawait internals.fs.link(staged, currentPath);',
                 '\t\ttry { await internals.fs.link(staged, currentPath); } catch (error) {\n'
                 "            if (!['EPERM', 'EXDEV', 'ENOSYS', 'EOPNOTSUPP'].includes(error?.code)) throw error;\n"
                 '            await androidCopyFile(staged, currentPath, androidFsConstants.COPYFILE_EXCL);\n'
                 '        }')

    for platform in ('linux-arm64', 'linux-x64', 'wasm32'):
        for name in (f'sharp-{platform}', f'sharp-libvips-{platform}'):
            shutil.rmtree(modules / '@img' / name, ignore_errors=True)
    return digest(native)


def obsolete_paths(base, runtime):
    paths = []
    # Delete only previously shipped files. User profiles/workspaces are outside
    # this directory; files newly installed by the user are not enumerated here.
    for path in sorted(base.rglob('*')):
        rel = path.relative_to(base)
        if (path.is_file() or path.is_symlink()) and not (runtime / rel).exists():
            paths.append(rel.as_posix())
    paths.append('node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js.dshorig')
    return sorted(set(paths))


def expanded_size(runtime):
    # Round files to 4KiB and include directories/symlinks as allocation costs.
    return sum(4096 if p.is_dir() or p.is_symlink() else
               max(4096, ((p.stat().st_size + 4095) // 4096) * 4096)
               for p in runtime.rglob('*'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=pathlib.Path, required=True)
    parser.add_argument('--previous-manifest', type=pathlib.Path, required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--prepared-runtime', type=pathlib.Path,
                        help='Use an already npm-ci prepared clean runtime')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source = json.loads((ROOT / 'runtime/core-source.json').read_text())
    manifest = json.loads(args.previous_manifest.read_text())
    runtime = args.prepared_runtime
    if runtime is None:
        runtime = args.out / 'runtime'
        runtime.mkdir()  # Never overwrite a populated install.
        archive = args.out / 'upstream-cli.tgz'
        urllib.request.urlretrieve(source['tarball'], archive)
        expected = base64.b64decode(source['integrity'].removeprefix('sha512-'))
        assert hashlib.sha512(archive.read_bytes()).digest() == expected
        with tarfile.open(archive) as tar:
            tar.extractall(runtime, filter='data')
        for path in (runtime / 'package').iterdir():
            shutil.move(str(path), runtime / path.name)
        (runtime / 'package').rmdir()
        shutil.copyfile(ROOT / 'runtime/core-package-lock.json', runtime / 'package-lock.json')
        subprocess.run(['npm', 'ci', '--prefix', str(runtime), '--omit=dev',
                        '--ignore-scripts', '--no-audit', '--no-fund'], check=True)
    assert json.loads((runtime / 'package.json').read_text())['version'] == source['version']
    for pkg in (runtime / 'node_modules/@deepseek-ai').glob('dsh-*/package.json'):
        assert json.loads(pkg.read_text())['version'] == source['version'], pkg
    native_sha = patch_android(runtime, args.base)
    removals = obsolete_paths(args.base, runtime)
    shard = args.out / 'dsh.tar.zst'
    # Fixed owner/timestamp and sorted paths make archives independent of npm's
    # extraction times. GNU tar long paths are handled by App's unpack.js.
    tar = subprocess.Popen(['tar', '--sort=name', '--mtime=@0', '--owner=0',
                            '--group=0', '--numeric-owner', '-cf', '-', '-C', str(runtime), '.'],
                           stdout=subprocess.PIPE)
    zstd = subprocess.run(['zstd', '-10', '-T2', '-q', '-f', '-o', str(shard)], stdin=tar.stdout)
    tar.stdout.close()
    assert tar.wait() == 0 and zstd.returncode == 0
    old = next(p for p in manifest['parts'] if p['name'] == 'dsh.tar.zst')
    sentinel = runtime / 'lib/bin.js'
    new = dict(old, size=shard.stat().st_size, sha256=digest(shard),
               revision=old.get('revision', 0) + 1, remove=sorted(set(old.get('remove', []) + removals)),
               unpacked_size=expanded_size(runtime),
               sentinel=dict(path='lib/bin.js', size=sentinel.stat().st_size, sha256=digest(sentinel)))
    manifest['parts'] = [new if p['name'] == 'dsh.tar.zst' else p for p in manifest['parts']]
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    info = f"DSH CLI: {source['version']}\nBase payload: {source['base_payload']}\n" \
           f"DSH revision: {new['revision']}\nAndroid PTY SHA256: {native_sha}\n" \
           f"npm lock SHA256: {digest(ROOT / 'runtime/core-package-lock.json')}\n"
    (args.out / 'BUILD-INFO.txt').write_text(info)
    print(info, end='')
    print(f"DSH shard: {new['size']} bytes; expanded: {new['unpacked_size']}; removals: {len(new['remove'])}")
    print('manifest SHA256: ' + digest(args.out / 'manifest.json'))


if __name__ == '__main__':
    main()
