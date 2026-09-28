// 创建可由 unpack.js 恢复的 tar.zst 运行环境快照。
// 用法: node snapshot.js <sourceDir> <archive.tar.zst>
//
// 只遍历 sourceDir 本身，不跟随符号链接；先写唯一临时文件，压缩流完整结束后
// 才原子改名，因空间不足或进程中断而产生的半包不会被误认成可用快照。

'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const source = process.argv[2];
const output = process.argv[3];
if (!source || !output) {
  console.error('用法: node snapshot.js <sourceDir> <archive.tar.zst>');
  process.exit(2);
}

function putString(buffer, offset, length, value) {
  const bytes = Buffer.from(String(value), 'utf8');
  if (bytes.length > length) throw new Error('tar 字段过长');
  bytes.copy(buffer, offset);
}

function putOctal(buffer, offset, length, value) {
  const text = Math.max(0, Number(value) || 0).toString(8);
  if (text.length > length - 1) throw new Error('tar 数值字段溢出');
  putString(buffer, offset, length, text.padStart(length - 1, '0') + '\0');
}

function header(name, stat, type, linkName, size) {
  const out = Buffer.alloc(512);
  let shortName = name;
  const nameBytes = Buffer.byteLength(shortName);
  if (nameBytes > 100) shortName = '@LongName';
  putString(out, 0, 100, shortName);
  putOctal(out, 100, 8, stat ? stat.mode & 0o7777 : 0o644);
  putOctal(out, 108, 8, 0);
  putOctal(out, 116, 8, 0);
  putOctal(out, 124, 12, size || 0);
  putOctal(out, 136, 12, stat ? Math.floor(stat.mtimeMs / 1000) : 0);
  out.fill(0x20, 148, 156);
  out[156] = String(type || '0').charCodeAt(0);
  if (linkName) putString(out, 157, 100, linkName);
  putString(out, 257, 6, 'ustar\0');
  putString(out, 263, 2, '00');
  putString(out, 265, 32, 'dsh');
  putString(out, 297, 32, 'dsh');
  let sum = 0;
  for (const byte of out) sum += byte;
  putString(out, 148, 8, sum.toString(8).padStart(6, '0') + '\0 ');
  return out;
}

function padding(size) {
  const count = (512 - (size % 512)) % 512;
  return count ? Buffer.alloc(count) : null;
}

function longRecord(value, type) {
  const data = Buffer.from(value + '\0', 'utf8');
  const fake = { mode: 0o644, mtimeMs: 0 };
  const chunks = [header('././@LongLink', fake, type, '', data.length), data];
  const pad = padding(data.length);
  if (pad) chunks.push(pad);
  return chunks;
}

async function list(relative) {
  const absolute = path.join(source, relative);
  const names = await fs.promises.readdir(absolute);
  names.sort((a, b) => Buffer.from(a).compare(Buffer.from(b)));
  return names;
}

async function* tarStream() {
  const queue = (await list('')).map(name => name);
  while (queue.length) {
    const relative = queue.shift();
    if (!relative || path.isAbsolute(relative)
        || relative.split('/').some(part => part === '..' || part === '')) {
      throw new Error('不安全的快照路径');
    }
    const absolute = path.join(source, relative);
    const stat = await fs.promises.lstat(absolute);
    if (Buffer.byteLength(relative) > 100) {
      for (const chunk of longRecord(relative, 'L')) yield chunk;
    }

    if (stat.isSymbolicLink()) {
      const target = await fs.promises.readlink(absolute);
      if (Buffer.byteLength(target) > 100) {
        for (const chunk of longRecord(target, 'K')) yield chunk;
      }
      yield header(relative, stat, '2',
          Buffer.byteLength(target) <= 100 ? target : '', 0);
      continue;
    }
    if (stat.isDirectory()) {
      yield header(relative + '/', stat, '5', '', 0);
      const children = await list(relative);
      for (let i = children.length - 1; i >= 0; i--) {
        queue.unshift(relative + '/' + children[i]);
      }
      continue;
    }
    if (!stat.isFile()) continue;

    yield header(relative, stat, '0', '', stat.size);
    let read = 0;
    for await (const chunk of fs.createReadStream(absolute)) {
      read += chunk.length;
      if (read > stat.size) throw new Error('快照期间文件增长: ' + relative);
      yield chunk;
    }
    if (read !== stat.size) throw new Error('快照期间文件变化: ' + relative);
    const pad = padding(stat.size);
    if (pad) yield pad;
  }
  yield Buffer.alloc(1024);
}

async function main() {
  const root = await fs.promises.realpath(source);
  const info = await fs.promises.stat(root);
  if (!info.isDirectory()) throw new Error('源目录不存在');
  const parent = path.dirname(output);
  await fs.promises.mkdir(parent, { recursive: true });
  const staged = output + '.part-' + process.pid + '-' + Date.now();
  try {
    await pipeline(
      Readable.from(tarStream()),
      zlib.createZstdCompress(),
      fs.createWriteStream(staged, { flags: 'wx', mode: 0o600 })
    );
    const result = await fs.promises.stat(staged);
    if (result.size <= 0) throw new Error('快照为空');
    await fs.promises.rename(staged, output);
    console.log('[snapshot] 完成: ' + result.size + ' bytes');
  } catch (error) {
    try { await fs.promises.unlink(staged); } catch (ignored) { }
    throw error;
  }
}

main().catch(error => {
  console.error('[snapshot] 失败: ' + (error && error.message ? error.message : error));
  process.exit(1);
});
