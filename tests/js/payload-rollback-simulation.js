'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const zlib = require('zlib');
const child = require('child_process');

const snapshot = process.argv[2];
const unpack = process.argv[3];
if (!snapshot || !unpack) throw new Error('missing scripts');

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-rollback-'));
const source = path.join(root, 'source');
const restored = path.join(root, 'restored');
const archive = path.join(root, 'snapshot.tar.zst');

function check(ok, message) {
  if (!ok) throw new Error(message);
}

function octal(value, length) {
  return value.toString(8).padStart(length - 1, '0') + '\0';
}

function tarHeader(name, size) {
  const out = Buffer.alloc(512);
  out.write(name, 0, 100, 'utf8');
  out.write(octal(0o644, 8), 100, 8, 'ascii');
  out.write(octal(0, 8), 108, 8, 'ascii');
  out.write(octal(0, 8), 116, 8, 'ascii');
  out.write(octal(size, 12), 124, 12, 'ascii');
  out.write(octal(0, 12), 136, 12, 'ascii');
  out.fill(0x20, 148, 156);
  out[156] = '0'.charCodeAt(0);
  out.write('ustar\0', 257, 6, 'ascii');
  out.write('00', 263, 2, 'ascii');
  let sum = 0;
  for (const byte of out) sum += byte;
  out.write(sum.toString(8).padStart(6, '0') + '\0 ', 148, 8, 'ascii');
  return out;
}

try {
  fs.mkdirSync(path.join(source, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(source, 'nested'), { recursive: true });
  fs.writeFileSync(path.join(source, 'nested', 'hello.txt'), 'hello rollback\n');
  fs.writeFileSync(path.join(source, 'bin', 'run'), '#!/bin/sh\nexit 0\n');
  fs.chmodSync(path.join(source, 'bin', 'run'), 0o755);
  const longName = 'long-' + 'x'.repeat(120) + '.txt';
  fs.writeFileSync(path.join(source, longName), 'long name');
  fs.symlinkSync('../nested/hello.txt', path.join(source, 'bin', 'hello-link'));

  child.execFileSync(process.execPath, [snapshot, source, archive], { stdio: 'pipe' });
  check(fs.statSync(archive).size > 0, 'snapshot is empty');
  child.execFileSync(process.execPath, [unpack, archive, restored], { stdio: 'pipe' });
  check(fs.readFileSync(path.join(restored, 'nested', 'hello.txt'), 'utf8')
      === 'hello rollback\n', 'file content changed');
  check((fs.statSync(path.join(restored, 'bin', 'run')).mode & 0o111) !== 0,
      'executable mode lost');
  check(fs.readFileSync(path.join(restored, longName), 'utf8') === 'long name',
      'long file name lost');
  const restoredLink = path.join(restored, 'bin', 'hello-link');
  check(fs.lstatSync(restoredLink).isSymbolicLink()
      && fs.readlinkSync(restoredLink) === '../nested/hello.txt', 'symlink lost');

  // 目标目录预先存在同名符号链接时，目录条目必须替换该链接，不能跟随
  // 链接把归档内容写到恢复根目录之外。
  const linkedRestore = path.join(root, 'linked-restore');
  const outside = path.join(root, 'outside');
  fs.mkdirSync(linkedRestore, { recursive: true });
  fs.mkdirSync(outside, { recursive: true });
  fs.symlinkSync(outside, path.join(linkedRestore, 'nested'));
  child.execFileSync(process.execPath, [unpack, archive, linkedRestore], { stdio: 'pipe' });
  check(fs.lstatSync(path.join(linkedRestore, 'nested')).isDirectory()
      && !fs.lstatSync(path.join(linkedRestore, 'nested')).isSymbolicLink(),
      'directory symlink was followed');
  check(!fs.existsSync(path.join(outside, 'hello.txt')),
      'directory symlink escaped destination');

  // 构造 ../escape.txt，验证解压边界使用 root + 分隔符而不是字符串前缀。
  const payload = Buffer.from('escape');
  const pad = Buffer.alloc((512 - payload.length % 512) % 512);
  const maliciousTar = Buffer.concat([
    tarHeader('../escape.txt', payload.length), payload, pad, Buffer.alloc(1024)
  ]);
  const malicious = path.join(root, 'malicious.tar.zst');
  fs.writeFileSync(malicious, zlib.zstdCompressSync(maliciousTar));
  const target = path.join(root, 'safe');
  child.execFileSync(process.execPath, [unpack, malicious, target], { stdio: 'pipe' });
  check(!fs.existsSync(path.join(root, 'escape.txt')), 'archive escaped destination');

  console.log('PayloadRollbackSimulation: snapshot / restore / mode / symlink / traversal');
} finally {
  fs.rmSync(root, { recursive: true, force: true });
}
