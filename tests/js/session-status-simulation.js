const fs = require('fs');
const vm = require('vm');

const scriptPath = process.argv[2];
if (!scriptPath) throw new Error('usage: node session-status-simulation.js <script>');
const script = fs.readFileSync(scriptPath, 'utf8');

function control(label, visible) {
  return {
    label,
    visible,
    textContent: '',
    getAttribute(name) {
      return name === 'aria-label' ? this.label : null;
    },
    getBoundingClientRect() {
      return { width: this.visible ? 40 : 0, height: this.visible ? 40 : 0 };
    },
  };
}

const stop = control('停止生成', false);
const send = control('发送消息', true);
const approval = control('允许一次', false);
const controls = [stop, send, approval];
const messages = [];
const intervals = [];
const document = {
  body: {},
  querySelectorAll(selector) {
    if (selector === '[aria-label],[title],[placeholder],[data-tooltip]') return controls;
    if (selector === 'button,[role=button],a') return controls;
    return [];
  },
};
const context = {
  window: {
    getComputedStyle: (element) => ({
      display: element.visible ? 'block' : 'none',
      visibility: element.visible ? 'visible' : 'hidden',
    }),
  },
  document,
  console: { log: (message) => messages.push(String(message)) },
  setInterval: (fn, delay) => {
    intervals.push({ fn, delay });
    return intervals.length;
  },
};

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

vm.runInNewContext(script, context);
assert(intervals.length === 1 && intervals[0].delay === 2000,
  'expected one 2 second watcher');
assert(messages[0] === '[dsh-dom] s=0 n=1 p=0',
  'idle state was not reported on first tick');

send.visible = false;
stop.visible = true;
intervals[0].fn();
assert(messages.at(-1) === '[dsh-dom] s=1 n=0 p=0',
  'running transition was not reported');

approval.visible = true;
intervals[0].fn();
assert(messages.at(-1) === '[dsh-dom] s=1 n=0 p=1',
  'approval transition was not reported');

const count = messages.length;
intervals[0].fn();
assert(messages.length === count, 'unchanged state should not spam the console');

stop.visible = false;
approval.visible = false;
intervals[0].fn();
assert(messages.at(-1) === '[dsh-dom] s=0 n=0 p=0',
  'indeterminate transition was not reported');

vm.runInNewContext(script, context);
assert(intervals.length === 1, 'repeat injection must stay idempotent');

console.log('SessionStatusSimulation: 7 pass / 0 fail');
