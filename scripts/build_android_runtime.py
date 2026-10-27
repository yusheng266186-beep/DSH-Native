#!/usr/bin/env python3
"""Build the complete Android DSH shard from a pinned, verified payload archive.

All remaining fixed-core adapters run here. A missing anchor fails publication.
The other four shards remain byte-for-byte identical to the verified base release.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tarfile
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CLASSES = ('CoreUpgrade ConnectionRecovery VoiceUi FsWritePatch SessionPersistencePatch '
           'AccountUi AppSettingsUi FileActionsUi MobileLayout ModelEffortUi SessionProbe '
           'SessionStatus DraftRecovery JsonValue').split()


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(262144), b''):
            h.update(data)
    return h.hexdigest()


def apply_port(runtime):
    """Shared by pinned-archive and upstream npm builders; no half-adapted output path."""
    with tempfile.TemporaryDirectory(prefix='dsh-port-compiler-') as temp:
        classes = pathlib.Path(temp) / 'classes'
        sources = [str(ROOT / 'src/dev/dsh/nativeapp' / f'{name}.java') for name in CLASSES]
        sources += [str(p) for p in sorted((ROOT / 'tools/java').rglob('*.java'))]
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-d', str(classes), *sources], check=True)
        command = ['java', '-cp', str(classes), 'dev.dsh.nativeapp.AndroidRuntimePort', str(runtime), str(ROOT / 'payload')]
        subprocess.run(command, check=True)
        before = {p.relative_to(runtime).as_posix(): digest(p) for p in runtime.rglob('*') if p.is_file() and not p.is_symlink()}
        subprocess.run(command, check=True)
        after = {p.relative_to(runtime).as_posix(): digest(p) for p in runtime.rglob('*') if p.is_file() and not p.is_symlink()}
        assert before == after, 'Android adapter chain changed on re-entry'


def build(archive, manifest_path, output, runtime):
    pin = json.loads((ROOT / 'runtime/android-port.json').read_text())
    assert digest(manifest_path) == pin['base_manifest_sha256'], 'Untrusted base manifest'
    assert archive.stat().st_size == pin['base_archive_bytes'] and digest(archive) == pin['base_archive_sha256'], 'Untrusted base archive'
    if runtime.exists():
        raise ValueError('Use a new runtime output directory')
    runtime.mkdir(parents=True)
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='dsh-android-port-') as temp:
        raw = pathlib.Path(temp) / 'base.tar'
        with raw.open('wb') as stream:
            subprocess.run(['zstd', '-dc', str(archive)], stdout=stream, check=True)
        with tarfile.open(raw) as tar:
            tar.extractall(runtime, filter='data')
        apply_port(runtime)
        target = output / 'dsh.tar.zst'
        with target.open('wb') as stream:
            compressor = subprocess.Popen(['zstd', '-10', '-T2', '-c'], stdin=subprocess.PIPE, stdout=stream)
            with tarfile.open(fileobj=compressor.stdin, mode='w|', format=tarfile.PAX_FORMAT) as tar:
                for path in sorted(runtime.rglob('*')):
                    info = tar.gettarinfo(str(path), path.relative_to(runtime).as_posix())
                    info.uid = info.gid = 0
                    info.uname = info.gname = ''
                    info.mtime = 0
                    info.pax_headers = {}
                    if info.isfile():
                        with path.open('rb') as data:
                            tar.addfile(info, data)
                    else:
                        tar.addfile(info)
            compressor.stdin.close()
            if compressor.wait() != 0:
                raise RuntimeError('Runtime compression failed')
    manifest = json.loads(manifest_path.read_text())
    part = next(p for p in manifest['parts'] if p['name'] == 'dsh.tar.zst')
    part.update(size=target.stat().st_size, sha256=digest(target), revision=part['revision'] + 1)
    part['unpacked_size'] = max(part['unpacked_size'], sum(p.stat().st_size for p in runtime.rglob('*') if p.is_file() and not p.is_symlink()) + 16 * 1024 * 1024)
    obsolete = 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js.dshorig'
    part['remove'] = sorted(set(part.get('remove', [])) | {obsolete})
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (output / 'BUILD-INFO.txt').write_text('Android runtime port revision 1\nDSH 0.2.1-rc.3\n'
                                        f'Base: {pin["base_payload"]}\nBase archive SHA-256: {pin["base_archive_sha256"]}\n'
                                        '19 adapters verified for idempotence; Pillow image adapter included.\n'
                                        'Node, SDK and all four tool shards unchanged.\n')
    print(f'Android shard: {target.stat().st_size} bytes; SHA-256 {digest(target)}')
    print(f'Manifest SHA-256: {digest(output / "manifest.json")}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=pathlib.Path, required=True)
    parser.add_argument('--manifest', type=pathlib.Path, required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--runtime', type=pathlib.Path, required=True)
    args = parser.parse_args()
    build(args.archive.resolve(), args.manifest.resolve(), args.out.resolve(), args.runtime.resolve())
