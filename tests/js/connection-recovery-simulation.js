'use strict';
const fs = require('fs');
const vm = require('vm');
const script = fs.readFileSync(process.argv[2], 'utf8');
const logs = [];

function NativeWebSocket(url, protocols) {
  this.url = url;
  this.protocols = protocols;
  this.listeners = {};
}
NativeWebSocket.prototype.addEventListener = function (name, callback) {
  (this.listeners[name] || (this.listeners[name] = [])).push(callback);
};
NativeWebSocket.prototype.emit = function (name) {
  (this.listeners[name] || []).forEach(fn => fn({type: name}));
};
NativeWebSocket.CONNECTING = 0;
NativeWebSocket.OPEN = 1;
NativeWebSocket.CLOSING = 2;
NativeWebSocket.CLOSED = 3;

const context = {
  console: {log: value => logs.push(String(value))},
  WebSocket: NativeWebSocket,
};
context.window = context;
vm.runInNewContext(script, context);
const Wrapped = context.WebSocket;
vm.runInNewContext(script, context);
if (context.WebSocket !== Wrapped) throw new Error('second injection replaced wrapper');
const socket = new context.WebSocket('ws://127.0.0.1/secret-token');
if (!(socket instanceof NativeWebSocket)) throw new Error('prototype compatibility lost');
if (context.WebSocket.OPEN !== 1) throw new Error('static constants lost');
socket.emit('open');
const second = new context.WebSocket('ws://127.0.0.1/second-secret');
second.emit('open');
socket.emit('close');
if (logs[logs.length - 1] !== '[dsh-conn] open') throw new Error('one socket close hid another open socket');
second.emit('close');
socket.emit('error');
const expected = ['[dsh-conn] connecting', '[dsh-conn] open', '[dsh-conn] close', '[dsh-conn] error'];
for (const mark of expected) {
  if (!logs.includes(mark)) throw new Error('missing ' + mark + ': ' + logs.join(' / '));
}
if (logs.join('\n').includes('secret-token')) throw new Error('WebSocket URL leaked');
console.log('ConnectionRecoveryJS: lifecycle / idempotence / no URL leakage');
