// DSH Native —— 运行环境前置自检
// 用法: node preflight.js <rootDir>
//
// 在启动 dsh web 之前，把所有已知的 Android 兼容性风险点一次性验证完，
// 任一项失败都打印明确原因，避免用户反复重测。

const path = require('path');
const fs = require('fs');

const ROOT = process.argv[2] || __dirname;
const results = [];
const ok = (n, d) => results.push([n, true, d === undefined ? '' : String(d)]);
const bad = (n, e) => results.push([n, false, (e && e.message ? e.message : String(e)).split('\n')[0]]);

function sync(name, fn) {
  try { ok(name, fn()); } catch (e) { bad(name, e); }
}
function timed(name, promise, ms) {
  return Promise.race([
    promise.then(d => [name, true, d === undefined ? '' : String(d)])
         .catch(e => [name, false, (e && e.message ? e.message : String(e)).split('\n')[0]]),
    new Promise(r => setTimeout(() => r([name, false, `超时 ${ms}ms`]), ms)),
  ]);
}

// ---- 同步检查 ----
sync('crypto 模块', () =>
  require('crypto').createHash('sha256').update('x').digest('hex').slice(0, 16));

sync('zlib/zstd', () => {
  const z = require('zlib');
  if (typeof z.zstdCompressSync !== 'function') throw new Error('无 zstdCompressSync');
  const back = z.zstdDecompressSync(z.zstdCompressSync(Buffer.from('round-trip'))).toString();
  if (back !== 'round-trip') throw new Error('往返结果不符: ' + back);
  return '往返正常';
});

sync('文件读写', () => {
  const f = path.join(ROOT, '.preflight');
  fs.writeFileSync(f, 'ok');
  const back = fs.readFileSync(f, 'utf8');
  fs.unlinkSync(f);
  if (back !== 'ok') throw new Error('内容不符');
  return '正常';
});

sync('子进程 + 自带 bash', () => {
  const out = require('child_process')
    .execFileSync('bash', ['-c', 'echo BASH_OK'], { encoding: 'utf8', timeout: 8000 }).trim();
  if (out !== 'BASH_OK') throw new Error('输出异常: ' + out);
  return out;
});

sync('node-pty', () => {
  const pty = require('node-pty');
  if (typeof pty.spawn !== 'function') throw new Error('spawn 不可用');
  return 'spawn 可用';
});

// ---- 可选模块探测 ----
// level 的含义：
//   'info' = 该模块在 Android 上**本就不需要**（例如只在 Windows 用的），
//            缺失是预期状态，不需要任何处理 —— 标为信息而非警告，
//            否则日志里会出现让用户误以为有问题的感叹号。
//   'warn' = 缺失会让某项功能真正降级，需要用户知晓。
const optional = [
  ['koffi', 'info', '仅 Windows 使用（代码里加载的是 advapi32.dll / kernel32.dll）'],
  ['sharp', 'warn', '仅图片附件使用（无 Android 构建）'],
  ['@deepseek-ai/node-addon-system', 'info',
   '文件锁与 Landlock 沙箱；已用纯 JS 降级替代，单用户无影响'],
  ['node-addon-require-builtin', 'info', '已由纯 JS 垫片替代'],
];
for (const [mod, level, why] of optional) {
  try {
    require(mod);
    results.push([`可选:${mod}`, true, '可加载']);
  } catch (e) {
    const detail = (e.message || '').split('\n')[0].slice(0, 60);
    if (level === 'info') {
      results.push([`不需要:${mod}`, 'info', `${why}`]);
    } else {
      results.push([`可选:${mod}`, 'warn', `${why} — ${detail}`]);
    }
  }
}

// ---- 工具链探测（这些是运行包里应带的，缺失则功能受限）----
const tools = [
  ['git', ['--version'], '版本管理'],
  ['python3', ['-c', 'import ssl,sqlite3,json;print("ok")'], 'Python 脚本'],
  ['rg', ['--version'], '代码搜索'],
  ['bash', ['-c', 'echo ok'], 'shell'],
  ['jq', ['--version'], 'JSON 处理'],
];
for (const [exe, argv, why] of tools) {
  try {
    const out = require('child_process')
      .execFileSync(exe, argv, { encoding: 'utf8', timeout: 15000 }).trim();
    results.push([`工具:${exe}`, true, out.split('\n')[0].slice(0, 32)]);
  } catch (e) {
    results.push([`工具:${exe}`, false, `${why} —— ${(e.message || '').split('\n')[0].slice(0, 60)}`]);
  }
}

// ---- 异步检查 ----
(async () => {
  const async_ = [];

  async_.push(await timed('worker_threads', new Promise((res, rej) => {
    const { Worker } = require('worker_threads');
    const w = new Worker('const {parentPort}=require("worker_threads");parentPort.postMessage(2+2);', { eval: true });
    w.on('message', m => { w.terminate(); m === 4 ? res('正常') : rej(new Error('结果异常: ' + m)); });
    w.on('error', e => { w.terminate(); rej(e); });
  }), 8000));

  async_.push(await timed('DNS 解析', new Promise((res, rej) => {
    require('dns').lookup('api.commandcode.ai', (err, addr) => err ? rej(err) : res(addr));
  }), 10000));

  const all = results.concat(async_);
  console.log('PREFLIGHT_BEGIN');
  let failed = 0;
  for (const [n, good, d] of all) {
    const tag = good === 'info' ? 'INFO'
              : good === 'warn' ? 'WARN'
              : (good ? 'PASS' : 'FAIL');
    console.log(`PREFLIGHT|${tag}|${n}|${d}`);
    if (good === false) failed++;
  }
  console.log(`PREFLIGHT_END|${failed}`);
  process.exit(0);
})();
