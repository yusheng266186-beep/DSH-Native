// DSH Native POC —— 内置于 APK 的 Node 服务
// 用法: node srv.js <rootDir> <port>

const http = require('http');
const os = require('os');
const fs = require('fs');
const path = require('path');

const ROOT = process.argv[2] || __dirname;
const PORT = parseInt(process.argv[3] || '3080', 10);

function probe() {
  const checks = [];
  checks.push(['Node 版本', process.version + ' (' + process.arch + ')']);
  checks.push(['可执行文件', process.execPath]);
  checks.push(['根目录', ROOT]);
  try {
    checks.push(['CPU 核心', String(os.cpus().length)]);
    checks.push(['总内存', (os.totalmem() / 1073741824).toFixed(2) + ' GB']);
    checks.push(['空闲内存', (os.freemem() / 1073741824).toFixed(2) + ' GB']);
  } catch (e) { checks.push(['系统信息', '失败: ' + e.message]); }
  try {
    const t = path.join(ROOT, '.write_test');
    fs.writeFileSync(t, 'ok');
    const back = fs.readFileSync(t, 'utf8');
    fs.unlinkSync(t);
    checks.push(['文件读写', back === 'ok' ? '正常' : '数据不一致']);
  } catch (e) { checks.push(['文件读写', '失败: ' + e.message]); }
  try {
    const crypto = require('crypto');
    const h = crypto.createHash('sha256').update('probe').digest('hex');
    checks.push(['加密模块', 'SHA256 ' + h.slice(0, 16) + '…']);
  } catch (e) { checks.push(['加密模块', '失败: ' + e.message]); }
  try {
    const https = require('https');
    checks.push(['HTTPS 模块', typeof https.request === 'function' ? '可用' : '异常']);
  } catch (e) { checks.push(['HTTPS 模块', '失败: ' + e.message]); }
  try {
    const { execSync } = require('child_process');
    const out = execSync('/system/bin/echo subprocess_ok', { encoding: 'utf8', timeout: 5000 }).trim();
    checks.push(['子进程执行', out]);
  } catch (e) { checks.push(['子进程执行', '失败: ' + e.message.split('\n')[0]]); }
  try {
    const t0 = Date.now();
    let s = 0;
    for (let i = 0; i < 3e7; i++) s += i % 7;
    checks.push(['计算性能', '3e7 次循环 ' + (Date.now() - t0) + 'ms']);
  } catch (e) { checks.push(['计算性能', '失败: ' + e.message]); }
  checks.push(['ESM 支持', process.features && process.features.require_module ? 'require(esm) 可用' : '支持 import 语法']);
  return checks;
}

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function page() {
  const rows = probe().map(([k, v]) =>
    '<tr><td class="k">' + esc(k) + '</td><td class="v">' + esc(v) + '</td></tr>').join('');
  return `<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH Native · 自检</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; padding:16px; background:#0b0e14; color:#e6e6e6;
         font:14px/1.6 -apple-system,system-ui,"PingFang SC","Microsoft YaHei",sans-serif; }
  h1 { font-size:19px; margin:0 0 4px; letter-spacing:.3px; }
  .sub { color:#8b949e; font-size:12px; margin-bottom:16px; }
  .badge { display:inline-block; background:#1f6feb22; color:#58a6ff;
           border:1px solid #1f6feb66; border-radius:999px;
           padding:2px 10px; font-size:12px; margin-bottom:14px; }
  table { width:100%; border-collapse:collapse; background:#11151c;
          border:1px solid #21262d; border-radius:10px; overflow:hidden; }
  td { padding:9px 12px; border-bottom:1px solid #1b2027; vertical-align:top;
       word-break:break-all; }
  tr:last-child td { border-bottom:none; }
  td.k { color:#8b949e; width:34%; white-space:nowrap; }
  td.v { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:12.5px; }
  .foot { margin-top:16px; color:#6e7681; font-size:11.5px; }
</style></head>
<body>
  <h1>DSH Native · 运行时自检</h1>
  <div class="sub">单 APK 内置 Node · 无 Termux · 无 proot</div>
  <div class="badge">进程已启动 · pid ${process.pid}</div>
  <table>${rows}</table>
  <div class="foot">启动于 ${new Date().toISOString()} · 端口 ${PORT}</div>
</body></html>`;
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, pid: process.pid, version: process.version }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(page());
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('LISTENING http://127.0.0.1:' + PORT + '/');
  console.log('node ' + process.version + ' on ' + process.arch);
});
