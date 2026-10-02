// Execute the generated JavaScript with deterministic geometry, events and clocks.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(process.argv[2], 'utf8');
const marker = '[dsh-native] layout-probe ';
let now = 10000, next = 0, callback, observing = false;
const timers = new Map(), logs = [], controls = [];
function events(target) {
  const listeners = new Map();
  target.addEventListener = (name, fn) => {
    if (!listeners.has(name)) listeners.set(name, new Set());
    listeners.get(name).add(fn);
  };
  target.removeEventListener = (name, fn) => listeners.get(name)?.delete(fn);
  target.fire = name => { for (const fn of listeners.get(name) || []) fn(); };
  target.count = name => listeners.get(name)?.size || 0;
  return target;
}
function node(label, w, h, role = null) {
  const attrs = {'aria-label': label, role};
  return {
    nodeType: 1, className: 'actual-module-control', attrs,
    rect: {width: w, height: h, top: 20, bottom: 20 + h, left: 20, right: 20 + w},
    css: {visibility: 'visible', display: 'flex', minHeight: '0px', flexShrink: '0', position: 'static'},
    parentElement: {getBoundingClientRect: () => ({width: 300, height: 80, bottom: 100}), getAttribute: () => null},
    getBoundingClientRect() { return this.rect; },
    getAttribute(name) { return attrs[name] || null; },
    hasAttribute(name) { return attrs[name] != null; },
    matches: () => true, querySelector: () => null,
  };
}
// Composer comes after many unrelated controls, as in long conversations.
for (let i = 0; i < 240; i++) controls.push(node('other', 36, 36));
const send = node('Send message', 34, 34);
controls.push(send);
const document = events({hidden: false, documentElement: {clientWidth: 480}, querySelectorAll: () => controls});
const window = events({});
const context = vm.createContext({window, document, innerWidth: 480, innerHeight: 900,
  getComputedStyle: n => n.css,
  MutationObserver: class {
    constructor(fn) { callback = fn; }
    observe() { observing = true; }
    disconnect() { observing = false; }
  },
  Date: {now: () => now},
  setTimeout: (fn, ms) => {const id = ++next; timers.set(id, {fn, at: now + ms}); return id;},
  clearTimeout: id => timers.delete(id), console: {log: line => logs.push(line)},
});
function tick(ms) {
  const end = now + ms;
  for (;;) {
    const entry = [...timers].sort((a, b) => a[1].at - b[1].at)[0];
    if (!entry || entry[1].at > end) break;
    now = entry[1].at; timers.delete(entry[0]); entry[1].fn();
  }
  now = end;
}
const records = () => logs.filter(s => !s.includes(' ERR ')).map(s => JSON.parse(s.slice(marker.length)));
vm.runInContext(script, context);
tick(700);
assert(records().some(r => r.action === 'send' && r.w === 34 && r.h === 34));
assert(logs.length <= 21, 'initial snapshot must be bounded');
vm.runInContext(script, context);
assert.equal(document.count('input'), 1, 'duplicate injection must not duplicate listeners');
tick(2000);
assert.equal(logs.length, 21, 'identical geometry must not generate another snapshot');

logs.length = 0;
const image = node('移除图片 PRIVATE-FILE-NAME.png', 18, 44);
image.css.minHeight = '44px'; controls.push(image);
callback([{type: 'childList', addedNodes: [image], removedNodes: []}]);
tick(2000);
assert(records().some(r => r.action === 'remove-image' && r.issue === 'stretched'));
assert(!logs.join('').includes('PRIVATE-FILE-NAME'), 'file names must be normalised before logging');
assert(logs.every(s => s.length < 900), 'each JSON record must survive the native log limit');

logs.length = 0;
image.rect.height = 18; image.rect.bottom = 38;
document.fire('input');
for (let i = 0; i < 100; i++) document.fire('input');
assert.equal(timers.size, 1, 'frequent interactions must coalesce');
tick(1999); assert.equal(logs.length, 0);
tick(1); assert(records().some(r => r.action === 'remove-image' && r.h === 18 && !r.issue));

logs.length = 0;
const close = node('Close', 20, 44);
close.parentElement = {getBoundingClientRect: () => ({width: 200, height: 28, bottom: 48}), getAttribute: () => 'tab'};
controls.push(close); document.fire('click'); tick(2000);
assert(records().some(r => r.action === 'close' && r.issue === 'tab-overflow'));

logs.length = 0;
document.hidden = true; window.fire('resize'); tick(2000);
assert.equal(logs.length, 0, 'hidden pages must not report');
document.hidden = false; document.documentElement.clientWidth = 869;
document.fire('visibilitychange'); tick(2000);
assert(records().some(r => r.kind === 'summary' && r.vw === 869));

window.fire('pagehide'); assert.equal(observing, false);
assert.equal(document.count('input'), 0); assert.equal(timers.size, 0);
window.fire('pageshow'); assert.equal(observing, true);
assert.equal(document.count('input'), 1);

logs.length = 0;
send.getBoundingClientRect = () => {throw new Error('PRIVATE-ERROR-CONTENT');};
document.fire('change'); tick(2000);
assert(logs.some(s => s.endsWith('ERR measurement')));
assert(!logs.join('').includes('PRIVATE-ERROR-CONTENT'));
window.fire('pagehide');
console.log('LayoutProbe: late attachments, composer priority, JSON bounds, privacy, debounce, deduplication, tab overflow, resize, hidden pages, lifecycle and error redaction passed.');
