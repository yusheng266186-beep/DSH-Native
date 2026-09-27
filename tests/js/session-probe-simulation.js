const fs = require('fs');
const vm = require('vm');

const scriptPath = process.argv[2];
if (!scriptPath) throw new Error('usage: node session-probe-simulation.js <script>');
const script = fs.readFileSync(scriptPath, 'utf8');

function response(body) {
  return {
    clone() { return response(body); },
    text() { return Promise.resolve(JSON.stringify(body)); },
  };
}

async function main() {
  const messages = [];
  const intervals = [];
  const calls = [];
  const replies = [
    { result: { value: { items: [{ running: true }, { running: false }] } } },
    { result: { value: { items: [{ running: false }] } } },
  ];
  const originalFetch = (url, init) => {
    calls.push({ url, init });
    return Promise.resolve(response(replies.shift() || { items: [] }));
  };
  const context = {
    window: { fetch: originalFetch },
    console: { log: (value) => messages.push(String(value)) },
    setInterval: (fn, delay) => {
      intervals.push({ fn, delay });
      return intervals.length;
    },
    Date,
    Math,
    JSON,
    Array,
    String,
  };

  vm.runInNewContext(script, context);
  const wrapped = context.window.fetch;
  if (wrapped === originalFetch) throw new Error('fetch was not wrapped at document start');
  if (intervals.length !== 1 || intervals[0].delay !== 5000) {
    throw new Error('expected one 5 second replay interval');
  }

  await wrapped('/api/session/list', {
    method: 'POST',
    headers: { Accept: 'application/json' },
    body: JSON.stringify({ rpcId: 'initial', input: {} }),
  });
  await new Promise((resolve) => setImmediate(resolve));
  if (!messages.includes('[dsh-sess] r=1')) throw new Error('initial response not reported');

  intervals[0].fn();
  await new Promise((resolve) => setImmediate(resolve));
  if (calls.length !== 2) throw new Error('captured request was not replayed once');
  const replayBody = JSON.parse(calls[1].init.body);
  if (!String(replayBody.rpcId).startsWith('probe-')) throw new Error('rpcId was not refreshed');
  if (!messages.includes('[dsh-sess] r=0')) throw new Error('idle response not reported');

  vm.runInNewContext(script, context);
  if (context.window.fetch !== wrapped || intervals.length !== 1) {
    throw new Error('repeat injection must stay idempotent');
  }

  console.log('SessionProbeSimulation: 8 pass / 0 fail');
}

main().catch((error) => {
  console.error(error.stack || error);
  process.exit(1);
});
