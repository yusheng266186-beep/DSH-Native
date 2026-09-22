// sharp 的 Android 实现。
//
// 背景：sharp 的预编译产物链接 glibc，在 Android(bionic) 上无法 dlopen，
// 因此官方包在手机上不可用。但 DSH 只用到很窄的一组 API，完全可以自行实现：
//
//   sharp(data, {failOn, limitInputPixels}).metadata()
//   sharp(data, {...}).rotate().toColourspace('srgb')
//       .resize({width, height, fit: 'inside', withoutEnlargement: true})
//       .webp({quality}) | .jpeg({quality})   →   .toBuffer({resolveWithObject})
//
// 实际的图像处理交给 Python + Pillow（运行包里自带），
// 通过子进程调用 pillow_shim.py，避免任何原生编译。

'use strict';

const { execFile } = require('child_process');
const path = require('path');

const HELPER = path.join(__dirname, 'pillow_shim.py');

// 诊断通道：DSH 会把任何非预期异常包装成 "prompt rejected (session/agent-busy)"，
// 真实原因被藏在错误对象的 reason 字段里、界面上看不到。
// 因此这里把失败细节写到 App 私有目录下的日志，便于定位。
// （HOME 由 App 设为私有根目录）
function diag(msg) {
  const line = new Date().toISOString() + ' ' + msg;
  try {
    process.stderr.write('[sharp-shim] ' + msg + '\n');
  } catch (e) { /* ignore */ }
  // 同时写私有目录与共享目录：共享目录才能在设备外排查
  const fs = require('fs');
  const targets = [];
  if (process.env.HOME) targets.push(path.join(process.env.HOME, 'sharp-shim.log'));
  targets.push('/sdcard/DSHNative/sharp-shim.log');
  for (const p of targets) {
    try { fs.appendFileSync(p, line + '\n'); } catch (e) { /* ignore */ }
  }
}
// python3 由运行包提供，PATH 已指向 tools/bin
const PYTHON = process.env.DSH_PYTHON || 'python3';
const TIMEOUT_MS = 120000;
const MAX_BUFFER = 128 * 1024 * 1024;

function runHelper(req) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      diag('调用 ' + PYTHON + ' ' + HELPER + ' op=' + req.op
           + ' 输入=' + Math.round((req.data || '').length * 3 / 4) + 'B');
      child = execFile(PYTHON, [HELPER], {
        maxBuffer: MAX_BUFFER,
        timeout: TIMEOUT_MS,
        encoding: 'utf8',
      }, (err, stdout, stderr) => {
        if (err) {
          const detail = ((stderr || err.message || '') + '').slice(0, 500);
          diag('辅助进程失败: ' + detail);
          reject(new Error('图像处理进程失败: ' + detail));
          return;
        }
        let parsed;
        try {
          parsed = JSON.parse(stdout);
        } catch (e) {
          reject(new Error('图像处理返回无法解析: ' + String(stdout).slice(0, 200)));
          return;
        }
        if (parsed && parsed._error) {
          diag('Pillow 返回错误: ' + parsed._error);
          reject(new Error('图像处理失败: ' + parsed._error));
          return;
        }
        resolve(parsed);
      });
    } catch (e) {
      reject(e);
      return;
    }
    child.stdin.on('error', () => { /* 进程可能已提前退出，错误由回调处理 */ });
    child.stdin.end(JSON.stringify(req));
  });
}

function toBuffer(input) {
  if (Buffer.isBuffer(input)) return input;
  if (input instanceof Uint8Array) return Buffer.from(input);
  if (typeof input === 'string') return Buffer.from(input, 'binary');
  return Buffer.from(input);
}

class Sharp {
  constructor(input, options) {
    this.input = toBuffer(input);
    this.options = options || {};
    this.ops = [];
    this.outFormat = null;
    this.quality = 80;
  }

  /** DSH 会 clone 出多条编码流水线（不同质量各试一次） */
  clone() {
    const s = new Sharp(this.input, this.options);
    s.ops = this.ops.slice();
    s.outFormat = this.outFormat;
    s.quality = this.quality;
    return s;
  }

  rotate() { this.ops.push(['rotate']); return this; }
  autoOrient() { this.ops.push(['rotate']); return this; }
  toColourspace(cs) { this.ops.push(['colourspace', cs]); return this; }
  resize(opts) { this.ops.push(['resize', opts || {}]); return this; }
  raw() { this.ops.push(['raw']); return this; }

  jpeg(opts) { this.outFormat = 'jpeg'; if (opts && opts.quality) this.quality = opts.quality; return this; }
  jpg(opts) { return this.jpeg(opts); }
  webp(opts) { this.outFormat = 'webp'; if (opts && opts.quality) this.quality = opts.quality; return this; }
  png() { this.outFormat = 'png'; return this; }

  metadata() {
    return runHelper({ op: 'metadata', data: this.input.toString('base64') })
      .catch((e) => { diag('metadata 失败: ' + e.message); throw e; });
  }

  toBuffer(opts) {
    return runHelper({
      op: 'process',
      data: this.input.toString('base64'),
      ops: this.ops,
      format: this.outFormat,
      quality: this.quality,
    }).then((r) => {
      const buf = Buffer.from(r.data, 'base64');
      if (opts && opts.resolveWithObject) {
        return {
          data: buf,
          info: {
            width: r.width,
            height: r.height,
            size: buf.length,
            format: r.format,
          },
        };
      }
      return buf;
    });
  }
}

function sharp(input, options) {
  return new Sharp(input, options);
}

// 兼容 sharp 暴露的静态成员
sharp.cache = function () { return sharp; };
sharp.concurrency = function () { return sharp; };
sharp.simd = function () { return false; };
sharp.format = { jpeg: {}, png: {}, webp: {}, raw: {} };
sharp.versions = { android: 'pillow-shim', pillow: true };

module.exports = sharp;
module.exports.default = sharp;
