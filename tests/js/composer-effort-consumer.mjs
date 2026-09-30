// Mount the published composer's real React component and ModelDirectory.
// Only its RPC/projection inputs and presentation atoms are replaced at the seams.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';
import { createRequire } from 'node:module';
import React, { act } from 'react';
import * as ReactDOM from 'react-dom';
import { createRoot } from 'react-dom/client';
import * as jsx from 'react/jsx-runtime';
import { JSDOM } from 'jsdom';

const [runtime, clientPath, catalogPath] = process.argv.slice(2);
const requireRuntime = createRequire(path.join(runtime, 'consumer.cjs'));
const dom = new JSDOM('<body><div id="app"></div></body>', { pretendToBeVisual: true });
for (const key of ['window', 'document', 'Node', 'Element', 'HTMLInputElement', 'HTMLButtonElement'])
  globalThis[key] = dom.window[key];
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
Element.prototype.scrollIntoView = function () {};
// This is the external-store contract, not a replacement selector implementation.
function store(initial) {
  let value = initial;
  const listeners = new Set();
  return {
    getSnapshot: () => value,
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    set(next) { value = next; listeners.forEach(fn => fn()); },
    update(fn) { const next = structuredClone(value); fn(next); this.set(next); },
  };
}
const primitives = new Proxy({
  rankByName: (items, query) => items.filter(item => item.name.toLowerCase().includes(query.toLowerCase())),
  observeStickyMenuGroups: () => () => {},
  MenuSurface: React.forwardRef((props, ref) => React.createElement('div', { ...props, ref })),
  MenuGroup: ({ children }) => React.createElement('div', null, children),
  Input: React.forwardRef((props, ref) => React.createElement('input', { ...props, ref })),
  Toast: ({ text }) => React.createElement('div', { role: 'alert' }, text),
}, { get: (target, name) => name in target ? target[name] : () => null });
let module;
window.__ModuleLoader__ = { load(value) { module = value; } };
vm.runInThisContext(fs.readFileSync(clientPath, 'utf8'), { filename: clientPath });
const plugin = module.factory(name => {
  if (name === 'react') return React;
  if (name === 'react-dom') return ReactDOM;
  if (name === 'react/jsx-runtime') return jsx;
  if (name.endsWith('/dsh-client-store')) return { createSnapshotStore: store };
  if (name.endsWith('/dsh-client-ui-primitives')) return primitives;
  return requireRuntime(name);
});
let Component;
let dictionaries;
plugin.apply({
  effect(fn) { fn(); }, plugin() {},
  locale: { register(_ns, values) { dictionaries = values; }, bind: () => key => key },
  inject(names, callback) {
    if (names[0] !== 'slots') return;
    callback({ modelDirectories: {}, sessions: {}, slots: {
      inject(_name, fn) { fn(); }, register(_definition, component) { Component = component; },
    } });
  },
});
assert(Component, 'actual composer slot must register');
const catalog = JSON.parse(fs.readFileSync(catalogPath));
const root = createRoot(document.getElementById('app'));
const requests = [];
let rejectMax = false;
const projected = store({ next: catalog.default });
const directory = new plugin.ModelDirectory({
  async selectModel(request) {
    requests.push(request);
    if (rejectMax) return { ok: false, error: { code: 'session/model-unavailable', message: 'provider rejected max' } };
    projected.set({ next: { provider: request.provider, model: request.model, reasoningEffort: request.reasoningEffort } });
    return { ok: true };
  },
}, 'existing-session', () => true, {
  store: store({ value: catalog, status: 'ready', error: null }),
  async load() {},
  reasoningFor(selection) {
    return catalog.groups.find(g => g.id === selection.provider)?.models.find(m => m.id === selection.model)?.reasoning;
  },
}, projected, () => false);
const button = text => [...document.querySelectorAll('button')].find(b => b.textContent === text || b.firstElementChild?.textContent === text);
const trigger = () => document.querySelector('[aria-haspopup="menu"]');
let checked = 0;
try {
  for (const locale of ['zh', 'en']) {
    const t = (key, vars = {}) => Object.entries(vars).reduce((text, [name, value]) => text.replace(`{${name}}`, value), dictionaries[locale][key] ?? key);
    await act(async () => root.render(React.createElement(Component, {
      locked: false, available: true, directory: directory.store,
      load: () => directory.load(), select: selection => directory.select(selection), t,
    })));
    for (const group of catalog.groups) for (const model of group.models) {
      await act(async () => projected.set({ next: { provider: group.id, model: model.id } }));
      const before = requests.length;
      await act(async () => trigger().click());
      assert(button(t('menu.effort')), `${group.id}/${model.id}: effort menu must be available`);
      await act(async () => button(t('menu.effort')).click());
      const max = button(t('effort.maxRequest'));
      assert(max && !max.disabled, `${group.id}/${model.id}: requested max must be selectable`);
      assert.equal(max.title, t('effort.maxNotice'));
      await act(async () => max.click());
      assert.equal(requests.length, before + 1);
      assert.deepEqual(requests.at(-1), { sessionId: 'existing-session', provider: group.id, model: model.id, reasoningEffort: 'max' });
      assert.equal(directory.store.getSnapshot().current.reasoningEffort, 'max');
      assert(trigger().textContent.includes(t('effort.maxRequest')));
      await act(async () => trigger().click());
      await act(async () => button(t('menu.effort')).click());
      assert.equal(button(t('effort.maxRequest')).getAttribute('aria-checked'), 'true');
      // Re-selecting the current max closes without another write.
      await act(async () => button(t('effort.maxRequest')).click());
      assert.equal(requests.length, before + 1);
      checked++;
    }
    await act(async () => projected.set({ next: { provider: 'commandcode', model: 'stealth/space-bunny-alpha', reasoningEffort: 'high' } }));
    rejectMax = true;
    await act(async () => trigger().click());
    await act(async () => button(t('menu.effort')).click());
    await act(async () => button(t('effort.maxRequest')).click());
    assert.equal(directory.store.getSnapshot().current.reasoningEffort, 'high');
    assert.equal(directory.store.getSnapshot().pending, null);
    assert(document.querySelector('[role="alert"]').textContent.includes('provider rejected max'));
    assert.equal(requests.at(-1).reasoningEffort, 'max', 'rejection must not retry or substitute high');
    rejectMax = false;
    await act(async () => trigger().click());
  }
  console.log(`Actual composer: ${checked} model/locale selections, max RPC, persisted projection, reopened checkmark, explicit upstream rejection passed.`);
} finally {
  await act(async () => root.unmount());
  directory.dispose();
  dom.window.close();
}
