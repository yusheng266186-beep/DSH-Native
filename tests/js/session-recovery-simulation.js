'use strict';
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const script = fs.readFileSync(process.argv[2], 'utf8');
let mutation, visibility, observing = false, now = 0, inspections = 0;
const timers = new Map(), logs = [];
const parent = {closest: () => null, getClientRects: () => [{}]};
function text(value) { return {nodeType: 3, nodeValue: value, parentElement: parent, isConnected: true}; }
const body = {};
Object.defineProperty(body, 'innerText', {get() { throw Error('whole-page layout read'); }});
Object.defineProperty(body, 'textContent', {get() { throw Error('whole-page text scan'); }});
const document = {
  body, documentElement: {}, hidden: false,
  createTreeWalker(n) { inspections++; let i = 0; return {nextNode: () => (n.children || [])[i++] || null}; },
  querySelectorAll() { return []; },
  addEventListener(type, callback) { assert.equal(type, 'visibilitychange'); visibility = callback; },
};
const context = {
  document, console: {error: line => logs.push(line)}, Date: {now: () => now},
  MutationObserver: function (callback) { mutation = callback; this.observe = () => {observing = true;}; this.disconnect = () => {observing = false;}; },
  setTimeout: fn => { const id = timers.size + 1; timers.set(id, fn); return id; },
  clearTimeout: id => timers.delete(id),
};
context.window = context;
function flush() { const work = [...timers.values()]; timers.clear(); work.forEach(fn => fn()); }
vm.runInNewContext(script, context);
assert(observing);
const ordinary = text('streaming answer');
for (let i = 0; i < 200; i++) mutation([{type: 'characterData', target: ordinary}]);
assert.equal(timers.size, 1); assert.equal(inspections, 0); flush(); assert.equal(inspections, 1);
const error = text('resume failed for session secret-private-body');
mutation([{type: 'childList', addedNodes: [error]}]); flush();
assert.equal(logs.length, 1); assert(!logs[0].includes('secret-private-body')); assert(logs[0].includes('SessionPersistenceCorruptionError'));
mutation([{type: 'characterData', target: error}]); flush(); assert.equal(logs.length, 1);
now = 6000; mutation([{type: 'characterData', target: error}]); flush(); assert.equal(logs.length, 2);
mutation([{type: 'characterData', target: error}]); document.hidden = true; visibility();
assert(!observing); assert.equal(timers.size, 0); const before = inspections;
mutation([{type: 'characterData', target: error}]); flush(); assert.equal(inspections, before);
document.hidden = false; visibility(); assert(observing);
mutation([{type: 'childList', addedNodes: Array.from({length: 1000}, () => text('ordinary'))}]); flush();
assert(inspections - before <= 32);
vm.runInNewContext(script, context); assert.equal(timers.size, 0);
console.log('Recovery watcher: changed nodes coalesced, bounded work, no page text/layout scan, hidden suspension, private fixed marker, repeat limit and idempotence passed.');
