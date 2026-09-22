// 用可控的 fetch 驱动注入脚本，验证状态转换与上报时机
const fs = require('fs');
const vm = require('vm');

const src = fs.readFileSync('/tmp/poll.js', 'utf8');
const logs = [];
let tickFn = null;
let sessions = [{ sessionId: 'sess-a', running: false, updatedAt: 1 }];
let fetchFails = false;

const ctx = {
  window: {},
  console: { log: (m) => logs.push(String(m)) },
  setInterval: (fn) => { tickFn = fn; return 1; },
  Date: Date,
  fetch: async () => {
    if (fetchFails) throw new Error('network');
    return { ok: true, json: async () => ({ result: { value: { items: sessions } } }) };
  },
};
vm.createContext(ctx);
vm.runInContext(src, ctx);

const step = async (label, mut) => {
  mut();
  try { tickFn(); } catch (e) { logs.push('THREW: ' + e.message); }
  await new Promise(r => setTimeout(r, 20));
  console.log(`  ${label.padEnd(28)} logs=${JSON.stringify(logs)}`);
};

(async () => {
  await new Promise(r => setTimeout(r, 20));
  console.log('  初始 tick:', JSON.stringify(logs));

  await step('① 任务开始 (running=true)', () => {
    sessions = [{ sessionId: 'sess-a', running: true, updatedAt: 10 }];
  });
  await step('② 仍然运行中', () => {
    sessions = [{ sessionId: 'sess-a', running: true, updatedAt: 20 }];
  });
  await step('③ 任务结束 (running=false)', () => {
    sessions = [{ sessionId: 'sess-a', running: false, updatedAt: 30 }];
  });
  await step('④ 保持空闲', () => {});
  await step('⑤ 网络失败', () => { fetchFails = true; });
  await step('⑥ 网络恢复 + 新任务', () => {
    fetchFails = false;
    sessions = [{ sessionId: 'sess-b', running: true, updatedAt: 40 }];
  });
  await step('⑦ 再次结束', () => {
    sessions = [{ sessionId: 'sess-b', running: false, updatedAt: 50 }];
  });

  console.log();
  const starts = logs.filter(l => l.startsWith('[dsh-task] start'));
  const dones  = logs.filter(l => l.startsWith('[dsh-task] done'));
  const ok = starts.length === 2 && dones.length === 2
          && starts[0].includes('sess-a') && starts[1].includes('sess-b')
          && dones[0].includes('sess-a') && dones[1].includes('sess-b');
  console.log(`  start 上报 ${starts.length} 次，done 上报 ${dones.length} 次`);
  console.log(`  ${ok ? '只在状态变化时上报，且会话 id 正确' : '上报行为不符预期'}`);
  console.log(`  ${logs.some(l => l.startsWith('THREW')) ? '网络失败时抛异常' : '网络失败被静默吞掉'}`);
})();
