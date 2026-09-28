'use strict';
const fs = require('fs');
const vm = require('vm');
const script = fs.readFileSync(process.argv[2], 'utf8');
const storage = new Map();

function makePage(logs) {
  const intervals = [];
  const editor = {
    value: '', type: 'text', disabled: false, readOnly: false, listeners: {},
    getBoundingClientRect() { return {width: 300, height: 80}; },
    closest() { return null; },
    addEventListener(name, fn) { (this.listeners[name] || (this.listeners[name] = [])).push(fn); },
    dispatchEvent(event) { (this.listeners[event.type] || []).forEach(fn => fn(event)); return true; },
  };
  const context = {
    console: {log: value => logs.push(String(value))},
    localStorage: {
      getItem: key => storage.has(key) ? storage.get(key) : null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: key => storage.delete(key),
    },
    document: {
      documentElement: {},
      querySelectorAll: () => [editor],
    },
    getComputedStyle: () => ({display: 'block', visibility: 'visible'}),
    MutationObserver: function (callback) { this.observe = function () {}; this.callback = callback; },
    Event: function (type, init) { this.type = type; this.bubbles = !!(init && init.bubbles); },
    Date, JSON, String, setTimeout, clearTimeout,
    setInterval: (fn, delay) => { intervals.push({fn, delay}); return intervals.length; },
  };
  context.window = context;
  return {context, editor, intervals};
}

(async function () {
  const firstLogs = [];
  const first = makePage(firstLogs);
  vm.runInNewContext(script, first.context);
  first.editor.value = 'unfinished task';
  first.editor.dispatchEvent({type: 'input'});
  await new Promise(resolve => setTimeout(resolve, 300));
  if (!storage.has('dsh-native-draft-v1')) throw new Error('draft was not saved');

  const secondLogs = [];
  const second = makePage(secondLogs);
  vm.runInNewContext(script, second.context);
  if (second.editor.value !== 'unfinished task') throw new Error('draft was not restored');
  if (!secondLogs.includes('[dsh-draft] restored')) throw new Error('restore marker missing');
  if (second.intervals.length !== 1 || second.intervals[0].delay !== 1000) {
    throw new Error('programmatic clear watcher missing');
  }
  // React 发送后可能直接把 value 设为空而不派发 input；轮询也必须清掉旧草稿。
  second.editor.value = '';
  second.intervals[0].fn();
  if (storage.has('dsh-native-draft-v1')) throw new Error('empty editor did not clear draft');
  console.log('DraftRecoveryJS: save / reload restore / clear / no auto-send');
})().catch(error => { console.error(error.stack || error); process.exit(1); });
