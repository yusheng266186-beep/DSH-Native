#!/usr/bin/env python3
"""Exercise the actual APK tar consumer through one-byte streams and zstd installation."""
import hashlib
import io
import json
import pathlib
import subprocess
import tarfile
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PAYLOAD = ROOT / 'payload'
if not PAYLOAD.is_dir():
    PAYLOAD = ROOT / 'bootstrap/payload'

RUNNER = r'''
const fs=require('node:fs'),{TarExtractor}=require(process.argv[1]);
const tar=new TarExtractor(process.argv[3]),data=fs.readFileSync(process.argv[2]),chunk=Number(process.argv[4]);
try{for(let at=0;at<data.length;at+=chunk)tar.write(data.subarray(at,at+chunk));console.log(JSON.stringify(tar.finish()));}
finally{tar.abort();}
'''


def archive(format=tarfile.PAX_FORMAT, corrupt=False):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode='w', format=format) as tar:
        names = ['short.txt', 'node_modules/' + 'a' * 130 + '/module.js', '资料/' + '鲸鱼' * 30 + '.txt']
        assert all(len(piece.encode()) <= 255 for name in names for piece in name.split('/'))
        for name in names:
            data = ('CONTENT ' + name).encode()
            item = tarfile.TarInfo(name)
            item.size = len(data)
            item.mode = 0o755
            tar.addfile(item, io.BytesIO(data))
        item = tarfile.TarInfo('links/' + 'b' * 130)
        item.type = tarfile.SYMTYPE
        item.linkname = '../' + names[1]
        tar.addfile(item)
        item = tarfile.TarInfo('../KEEP')
        item.size = 3
        tar.addfile(item, io.BytesIO(b'BAD'))
    data = out.getvalue()
    if corrupt:
        data = b'!' + data[1:]
    return data, names


class InstallerTest(unittest.TestCase):
    def run_parser(self, directory, data, chunk=1):
        base = pathlib.Path(directory)
        source = base / 'archive.tar'
        source.write_bytes(data)
        destination = base / 'runtime'
        result = subprocess.run(['node', '-e', RUNNER, str(PAYLOAD / 'archive-tar.cjs'), str(source),
                                 str(destination), str(chunk)], capture_output=True, text=True)
        return result, destination

    def test_gnu_pax_utf8_links_modes_and_every_chunk_boundary(self):
        for format in [tarfile.GNU_FORMAT, tarfile.PAX_FORMAT]:
            for chunk in [1, 7, 511, 512, 65536]:
                with self.subTest(format=format, chunk=chunk), tempfile.TemporaryDirectory() as directory:
                    base = pathlib.Path(directory)
                    (base / 'KEEP').write_text('private canary')
                    data, names = archive(format)
                    result, runtime = self.run_parser(directory, data, chunk)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    stats = json.loads(result.stdout)
                    self.assertEqual(stats['files'], 3)
                    self.assertLessEqual(stats['peakMetadata'], 1024 * 1024 + 512)
                    for name in names:
                        self.assertEqual((runtime / name).read_text(), 'CONTENT ' + name)
                        self.assertEqual((runtime / name).stat().st_mode & 0o777, 0o755)
                    self.assertEqual((runtime / ('links/' + 'b' * 130)).read_text(), 'CONTENT ' + names[1])
                    self.assertEqual((base / 'KEEP').read_text(), 'private canary')

    def test_header_checksum_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            result, _ = self.run_parser(directory, archive(corrupt=True)[0])
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('checksum', result.stderr)

    def test_truncation_preserves_previous_file_and_cleans_temporary(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = pathlib.Path(directory) / 'runtime'
            runtime.mkdir()
            (runtime / 'short.txt').write_text('previous complete file')
            result, _ = self.run_parser(directory, archive()[0][:520])
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((runtime / 'short.txt').read_text(), 'previous complete file')
            self.assertFalse(list(runtime.rglob('.extract-*')))

    def test_pax_cannot_follow_parent_link(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            (base / 'outside').mkdir()
            (base / 'runtime').mkdir()
            (base / 'runtime/node_modules').symlink_to(base / 'outside', target_is_directory=True)
            result, runtime = self.run_parser(directory, archive()[0], 13)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(list((base / 'outside').iterdir()))

    def test_malformed_pax_length_refused(self):
        out = io.BytesIO()
        with tarfile.open(fileobj=out, mode='w', format=tarfile.USTAR_FORMAT) as tar:
            record = b'999999 path=unsafe\n'
            item = tarfile.TarInfo('extended')
            item.type = tarfile.XHDTYPE
            item.size = len(record)
            tar.addfile(item, io.BytesIO(record))
        with tempfile.TemporaryDirectory() as directory:
            result, _ = self.run_parser(directory, out.getvalue())
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('PAX', result.stderr)

    def test_real_zstd_launcher(self):
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            source = base / 'archive.tar.zst'
            source.write_bytes(subprocess.check_output(['node', '-e',
                "process.stdout.write(require('node:zlib').zstdCompressSync(require('node:fs').readFileSync(0)))"], input=archive()[0]))
            result = subprocess.run(['node', str(PAYLOAD / 'unpack.js'), str(source), str(base / 'runtime')], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('最大元数据缓冲', result.stdout)
            self.assertEqual(hashlib.sha256((base / 'runtime/short.txt').read_bytes()).digest(), hashlib.sha256(b'CONTENT short.txt').digest())


if __name__ == '__main__':
    unittest.main()
