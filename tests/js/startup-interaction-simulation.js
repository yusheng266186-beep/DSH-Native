'use strict';
const fs = require('fs'), vm = require('vm'), assert = require('assert');
let now = 0; const timers = new Map(), listeners = {}, reports = [];
const document = {hidden: false, addEventListener(type, fn) { listeners[type] = fn; }};
const context = {document, console: {log: line => reports.push(line)}, Date: {now: () => now},
 setTimeout: fn => {timers.set(1, fn); return 1;}, clearTimeout: id => timers.delete(id)};
context.window = context;
vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8'), context);
const event = {}; Object.defineProperty(event, 'target', {get() {throw Error('must never read typed content');}});
listeners.input(event); assert.equal(reports.length, 1);
for (let i = 0; i < 100; i++) {now++; listeners.input(event);}
assert.equal(reports.length, 1); assert.equal(timers.size, 1);
now += 500; for (const fn of [...timers.values()]) fn(); timers.clear();
assert.equal(reports.length, 2); assert(reports.every(value => value === '[dsh-user-interaction]'));
now++; listeners.keydown(event); assert.equal(timers.size, 1);
document.hidden = true; listeners.visibilitychange(); assert.equal(timers.size, 0);
listeners.input(event); assert.equal(reports.length, 2);
document.hidden = false; now += 1000; listeners.input(event); assert.equal(reports.length, 3);
console.log('Startup input: IME input, bounded native signals, trailing quiet-window reset, hidden cancellation and no text access passed.');
