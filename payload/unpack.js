// 解压 tar.zst 归档 —— 在 Android 的 Node 上运行
// 用法: node unpack.js <archive.tar.zst> <destDir>
//
// 只用 Node 内置模块：zlib 的 zstd 解压 + 自己解析 tar 格式。
// 不需要外部 tar/zstd 命令（Android 上均不可用）。

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const src = process.argv[2];
const dest = process.argv[3];
if (!src || !dest) { console.error('用法: node unpack.js <archive> <dest>'); process.exit(2); }

function log(msg) { console.log('[unpack] ' + msg); }

// --- 读取并解压 ---
log('读取 ' + path.basename(src));
const compressed = fs.readFileSync(src);
log('解压中 (' + (compressed.length / 1048576).toFixed(1) + ' MB)');
const tar = zlib.zstdDecompressSync(compressed);
log('解压完成 → ' + (tar.length / 1048576).toFixed(1) + ' MB tar');

// --- 解析 tar ---
function readString(buf, off, len) {
  const end = buf.indexOf(0, off);
  const stop = end === -1 || end > off + len ? off + len : end;
  return buf.toString('utf8', off, stop);
}
function readOctal(buf, off, len) {
  const s = readString(buf, off, len).trim();
  return s === '' ? 0 : parseInt(s, 8);
}

let pos = 0;
let files = 0, dirs = 0, links = 0, longName = null, longLink = null;
const pendingLinks = [];

fs.mkdirSync(dest, { recursive: true });
const destRoot = fs.realpathSync(dest);

function safeTarget(name) {
  if (!name || path.isAbsolute(name) || name.indexOf('\0') >= 0) return null;
  const pieces = name.replace(/\\/g, '/').split('/');
  if (pieces.some(p => p === '..')) return null;
  const target = path.resolve(destRoot, name);
  if (target !== destRoot && !target.startsWith(destRoot + path.sep)) return null;
  // 已存在的父目录若是符号链接，后续 writeFileSync 会跟随到运行目录之外。
  // 每一级都用 lstat 检查；ENOENT 代表后续可安全创建。
  let current = path.dirname(target);
  while (current !== destRoot && current.startsWith(destRoot + path.sep)) {
    try {
      if (fs.lstatSync(current).isSymbolicLink()) return null;
    } catch (e) {
      if (e.code !== 'ENOENT') return null;
    }
    current = path.dirname(current);
  }
  return target;
}

while (pos + 512 <= tar.length) {
  const header = tar.subarray(pos, pos + 512);
  // 全零块 = 归档结束
  if (header[0] === 0) break;

  let name = readString(header, 0, 100);
  const mode = readOctal(header, 100, 8);
  const size = readOctal(header, 124, 12);
  const type = String.fromCharCode(header[156]);
  const linkname = readString(header, 157, 100);
  const prefix = readString(header, 345, 155);
  if (prefix) name = prefix + '/' + name;
  if (longName !== null) { name = longName; longName = null; }
  const effectiveLink = longLink !== null ? longLink : linkname;
  longLink = null;

  pos += 512;
  const dataStart = pos;
  const dataEnd = pos + size;
  pos = dataEnd + ((512 - (size % 512)) % 512);   // 512 字节对齐

  const target = safeTarget(name);
  // 防目录穿越与父级符号链接逃逸
  if (target === null) {
    log('跳过越界路径: ' + name); continue;
  }

  if (type === 'L') {            // GNU 长文件名
    longName = tar.toString('utf8', dataStart, dataEnd).replace(/\0+$/, '');
    continue;
  }
  if (type === 'K') {            // GNU 长链接目标
    longLink = tar.toString('utf8', dataStart, dataEnd).replace(/\0+$/, '');
    continue;
  }
  if (type === '5') {            // 目录
    // 目录本身也可能是旧环境留下的符号链接。只检查父级不足以防止
    // mkdirSync 跟随它写到目标目录之外，因此先原位移除链接。
    try { if (fs.lstatSync(target).isSymbolicLink()) fs.unlinkSync(target); } catch (e) {}
    fs.mkdirSync(target, { recursive: true });
    dirs++;
    continue;
  }
  if (type === '2') {            // 符号链接 —— 记录，稍后处理
    pendingLinks.push([target, effectiveLink]);
    links++;
    continue;
  }
  if (type === '0' || type === '\0' || type === '') {   // 普通文件
    fs.mkdirSync(path.dirname(target), { recursive: true });
    try { if (fs.lstatSync(target).isSymbolicLink()) fs.unlinkSync(target); } catch (e) {}
    fs.writeFileSync(target, tar.subarray(dataStart, dataEnd));
    try { fs.chmodSync(target, mode & 0o777); } catch (e) {}
    files++;
    continue;
  }
  // 其他类型（硬链接等）忽略
}

// 建立符号链接（tar 里链接目标可能后出现，所以放最后）
for (const [t, ln] of pendingLinks) {
  try {
    fs.mkdirSync(path.dirname(t), { recursive: true });
    try { fs.unlinkSync(t); } catch (e) {}
    fs.symlinkSync(ln, t);
  } catch (e) {
    // Android 可能不支持某些链接，退化：若是相对链接则尝试复制
    try {
      const resolved = path.resolve(path.dirname(t), ln);
      if (fs.existsSync(resolved)) fs.copyFileSync(resolved, t);
    } catch (e2) {}
  }
}

log('完成: ' + files + ' 文件, ' + dirs + ' 目录, ' + links + ' 链接');
process.exit(0);
