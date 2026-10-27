// Android installer and rollback share the same bounded GNU/PAX tar consumer.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { Writable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { TarExtractor } = require('./archive-tar.cjs');
async function main() {
  const [source, destination] = process.argv.slice(2);
  if (!source || !destination) throw new Error('Usage: node unpack.js <archive.tar.zst> <destination>');
  console.log('[unpack] 读取 ' + path.basename(source));
  const tar = new TarExtractor(destination);
  try {
    await pipeline(fs.createReadStream(source), zlib.createZstdDecompress(), new Writable({
      write(chunk, encoding, done) { try { tar.write(chunk); done(); } catch (error) { done(error); } }
    }));
    const result = tar.finish();
    console.log('[unpack] 完成: ' + result.files + ' 文件, ' + result.dirs + ' 目录, ' + result.links
      + ' 链接；最大元数据缓冲 ' + result.peakMetadata + ' bytes');
  } finally { tar.abort(); }
}
main().catch(error => { console.error('[unpack] 失败: ' + error.message); process.exitCode = 1; });
