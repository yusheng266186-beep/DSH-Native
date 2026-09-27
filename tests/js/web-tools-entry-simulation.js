const fs = require('fs');
const vm = require('vm');

const scriptPath = process.argv[2];
if (!scriptPath) throw new Error('usage: node web-tools-entry-simulation.js <script>');
const script = fs.readFileSync(scriptPath, 'utf8');

class Element {
  constructor(tagName, attrs = {}, text = '') {
    this.tagName = String(tagName).toUpperCase();
    this.attrs = { ...attrs };
    this.className = attrs.class || '';
    this.children = [];
    this.parentElement = null;
    this.ownText = text;
    this.listeners = {};
    this.title = attrs.title || '';
  }

  get childNodes() { return this.children; }

  get textContent() {
    return this.children.length > 0
      ? this.children.map((child) => child.textContent).join('')
      : this.ownText;
  }

  set textContent(value) {
    this.children = [];
    this.ownText = String(value);
  }

  setAttribute(name, value) {
    this.attrs[name] = String(value);
    if (name === 'class') this.className = String(value);
    if (name === 'title') this.title = String(value);
  }

  getAttribute(name) {
    if (name === 'class') return this.className || null;
    if (name === 'title') return this.title || null;
    return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null;
  }

  removeAttribute(name) {
    delete this.attrs[name];
    if (name === 'title') this.title = '';
  }

  appendChild(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  insertBefore(child, reference) {
    child.parentElement = this;
    const index = this.children.indexOf(reference);
    if (index < 0) this.children.push(child);
    else this.children.splice(index, 0, child);
    return child;
  }

  removeChild(child) {
    const index = this.children.indexOf(child);
    if (index >= 0) this.children.splice(index, 1);
    child.parentElement = null;
    return child;
  }

  cloneNode(deep) {
    const clone = new Element(this.tagName, { ...this.attrs }, this.ownText);
    clone.className = this.className;
    clone.title = this.title;
    if (deep) for (const child of this.children) clone.appendChild(child.cloneNode(true));
    return clone;
  }

  querySelectorAll(selector) {
    const wanted = selector.toLowerCase();
    const out = [];
    const visit = (node) => {
      for (const child of node.children) {
        if (wanted === 'span' && child.tagName === 'SPAN') out.push(child);
        visit(child);
      }
    };
    visit(this);
    return out;
  }

  addEventListener(type, listener) {
    (this.listeners[type] ||= []).push(listener);
  }

  dispatch(type) {
    let prevented = false;
    let stopped = false;
    const event = {
      preventDefault: () => { prevented = true; },
      stopPropagation: () => { stopped = true; },
    };
    for (const listener of this.listeners[type] || []) listener(event);
    return { prevented, stopped };
  }
}

function descendants(root) {
  const out = [];
  const visit = (node) => {
    for (const child of node.children) {
      out.push(child);
      visit(child);
    }
  };
  visit(root);
  return out;
}

function settingsTree(language) {
  const chinese = language === 'zh';
  const label = chinese ? '设置' : 'Settings';
  const button = new Element('button', {
    class: chinese ? 'trigger wide' : 'trigger rail',
    'aria-label': label,
    'aria-haspopup': 'dialog',
    'aria-expanded': 'false',
  });
  button.appendChild(new Element('svg'));
  if (chinese) button.appendChild(new Element('span', {}, label));
  else button.appendChild(new Element('span', {}, label));
  const row = new Element('div', { class: chinese ? 'triggerRow wide' : 'triggerRow railRow' });
  row.appendChild(button);
  const host = new Element('div', { class: 'settingsArea' });
  host.appendChild(row);
  return { host, row, button };
}

let tree = settingsTree('zh');
const documentElement = new Element('html');
documentElement.appendChild(tree.host);

const document = {
  documentElement,
  querySelectorAll(selector) {
    if (selector !== 'button[aria-haspopup="dialog"]') return [];
    return descendants(documentElement).filter((node) => node.tagName === 'BUTTON'
      && node.getAttribute('aria-haspopup') === 'dialog');
  },
  querySelector(selector) {
    if (selector === '[data-dsh-native-tools="row"]') {
      return descendants(documentElement).find((node) =>
        node.getAttribute('data-dsh-native-tools') === 'row') || null;
    }
    return null;
  },
};

const messages = [];
const errors = [];
const timers = [];
let observerCallback;
class MutationObserver {
  constructor(callback) { observerCallback = callback; }
  observe() {}
}

const context = {
  window: {},
  document,
  MutationObserver,
  console: {
    log: (message) => messages.push(String(message)),
    error: (message) => errors.push(String(message)),
  },
  setInterval: () => 1,
  setTimeout: (fn, delay) => {
    timers.push({ fn, delay });
    return timers.length;
  },
};

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function toolsRows() {
  return descendants(documentElement).filter((node) =>
    node.getAttribute('data-dsh-native-tools') === 'row');
}

function runShortTimers() {
  for (const timer of timers.splice(0, timers.length)) if (timer.delay < 1000) timer.fn();
}

vm.runInNewContext(script, context);
assert(toolsRows().length === 1, 'one tools row should be inserted');
assert(tree.host.children[0].getAttribute('data-dsh-native-tools') === 'row',
  'tools row should participate in layout before Settings');
let toolsButton = toolsRows()[0].children[0];
assert(toolsButton.getAttribute('aria-label') === 'App 工具', 'Chinese label missing');
assert(toolsButton.getAttribute('aria-haspopup') === null, 'dialog state leaked from Settings');
const click = toolsButton.dispatch('click');
assert(click.prevented && click.stopped, 'click should stay in the native entry');
assert(messages.includes('[dsh-native] open-settings'), 'open marker missing');
assert(messages.filter((item) => item === '[dsh-native] tools-entry-ready').length === 1,
  'ready marker should be emitted once');

vm.runInNewContext(script, context);
assert(toolsRows().length === 1, 'repeat injection must not duplicate the row');

const next = settingsTree('en');
tree.host.children = [];
tree.row.parentElement = null;
tree.host.appendChild(next.row);
tree = { host: tree.host, row: next.row, button: next.button };
observerCallback();
runShortTimers();
assert(toolsRows().length === 1, 'React-style rerender should restore one row');
toolsButton = toolsRows()[0].children[0];
assert(toolsButton.getAttribute('aria-label') === 'App tools', 'English label missing');
assert(messages.filter((item) => item === '[dsh-native] tools-entry-ready').length === 1,
  'rerender should not repeat the migration event');
assert(errors.length === 0, 'normal mounting should not report a missing anchor');

console.log('WebToolsEntrySimulation: 12 pass / 0 fail');
