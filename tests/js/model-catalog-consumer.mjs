// Load the exact published Android payload's model consumers, without sending prompts.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const [root, fixture, reasoningSnapshot] = process.argv.slice(2);
assert(root && fixture, 'usage: model-catalog-consumer.mjs <payload-root> <generated-yaml>');
const requirePayload = createRequire(path.join(root, 'consumer.cjs'));
const yaml = requirePayload('yaml');
const config = yaml.parse(fs.readFileSync(fixture, 'utf8'), { uniqueKeys: true });
if (reasoningSnapshot) {
  config['llm-pi-ai'].providers.commandcode.baseURL = 'https://offline.invalid/v1';
  config['llm-pi-ai'].providers.commandcode.apiKeyEnv = 'OFFLINE_TEST_KEY';
  config['llm-pi-ai'].providers.commandcode.compat = { supportsReasoningEffort: true };
}
const adapters = [];
// The registry seam records the REAL plugin adapter. Model lists and image
// resolution below run in the payload's own adapter, never a simulated catalog.
const ctx = {
  fiber: { entry: { options: {} } },
  inject() {}, on() {}, get(name) {
    return reasoningSnapshot && name === 'credentials' ? {
      resolve: async () => ({ value: 'offline-test-key' }), readRecord: async () => undefined,
    } : undefined;
  },
  logger: { warn() {}, error(message) { throw new Error(String(message)); } },
  llm: {
    registerConfigurableProviders() { return { replace() {} }; },
    registerModelDiscovery() {},
    registerAdapter(routes, adapter) {
      adapters.push({ routes, adapter });
      return { replace() {} };
    },
  },
};
for (const [pkg, ns] of [
  ['@deepseek-ai/dsh-llm-pi-ai', 'llm-pi-ai'],
  ['@deepseek-ai/dsh-llm-deepseek-api-key', 'llm-deepseek-api-key'],
]) {
  const plugin = await import(pathToFileURL(requirePayload.resolve(pkg)));
  ctx.fiber.entry.options.id = ns;
  plugin.apply(ctx, plugin.Config(config[ns]));
}
const command = adapters.find(entry => entry.routes.includes('commandcode'))?.adapter;
const direct = adapters.find(entry => entry.routes.includes('deepseek-official'))?.adapter;
assert(command && direct, 'both provider routes must register');
const models = await command.listModels('commandcode');
if (reasoningSnapshot) {
  const snapshot = JSON.parse(fs.readFileSync(reasoningSnapshot, 'utf8'));
  assert.deepEqual(models.map(model => model.id), Object.keys(snapshot.models));
  for (const [id, expected] of Object.entries(snapshot.models)) {
    const model = await command.resolveModel('commandcode', id);
    assert.deepEqual(model.reasoning?.efforts.map(effort => effort.id) ?? [], expected, id);
  }
  for (const id of ['deepseek-flash', 'deepseek-v4-pro']) {
    const model = await direct.resolveModel('deepseek-official', id);
    assert.deepEqual(model.reasoning.efforts.map(effort => effort.id), ['off', 'low', 'high', 'max']);
  }
  // Capture the real adapter/SDK's body in memory. No socket or provider call is made.
  const originalFetch = globalThis.fetch;
  const captured = [];
  globalThis.fetch = async (url, init) => {
    assert(String(url).startsWith('https://offline.invalid/'), 'offline capture cannot contact a provider');
    captured.push(JSON.parse(init.body));
    return new Response(JSON.stringify({ error: { message: 'offline body captured', type: 'invalid_request_error' } }),
      { status: 400, headers: { 'content-type': 'application/json' } });
  };
  try {
    for (const [model, reasoningEffort] of [
      ['deepseek/deepseek-v4.1-flash-fast', 'max'],
      ['Qwen/Qwen3.8-Max', 'xhigh'],
      ['gpt-6.1-sol', 'high'],
      ['MiniMaxAI/MiniMax-M2.5', 'off'],
    ]) {
      const before = captured.length;
      let rejected;
      const events = [];
      try {
        for await (const _ of command.stream({ provider: 'commandcode', model, reasoningEffort,
          messages: [{ role: 'user', content: [{ type: 'text', text: 'offline wire verification' }] }], tools: [] })) { events.push(_); }
      } catch (error) { rejected = error; }
      assert(captured.length > before, `${model}: adapter must reach body capture (${rejected?.message}; ${JSON.stringify(events)})`);
      assert.equal(captured.at(-1).model, model);
      assert.equal(captured.at(-1).reasoning_effort, reasoningEffort === 'off' ? undefined : reasoningEffort);
    }
    const before = captured.length;
    await assert.rejects(async () => {
      for await (const _ of command.stream({ provider: 'commandcode', model: 'Qwen/Qwen3.8-Max', reasoningEffort: 'max',
        messages: [], tools: [] })) { }
    }, /does not support reasoning effort/);
    assert.equal(captured.length, before, 'unsupported effort refused before request dispatch');
  } finally { globalThis.fetch = originalFetch; }
  console.log(`Actual payload reasoning: ${Object.keys(snapshot.models).length} official models; provider-specific choices; offline wire bodies; invalid effort refused.`);
  process.exit(0);
}
assert.deepEqual(models.map(model => model.id), ['old', 'fresh-vision', 'unknown-new']);
assert((await command.resolveModel('commandcode', 'fresh-vision')).inputModalities.includes('image'));
const old = await command.resolveModel('commandcode', 'old');
assert(!old.inputModalities.includes('image'), 'upstream text-only must revoke old vision');
assert.equal(old.context.contextWindow, 1048576);
assert.equal(old.defaultMaxTokens, 32000);
assert(!((await command.resolveModel('commandcode', 'unknown-new')).inputModalities.includes('image')));
assert((await direct.listModels('deepseek-official')).some(model => model.id === 'deepseek-flash'));
assert((await direct.resolveModel('deepseek-official', 'deepseek-flash')).inputModalities.includes('image'));
assert(!(await direct.resolveModel('deepseek-official', 'deepseek-v4-pro')).inputModalities.includes('image'));
console.log('Actual payload consumer: new IDs selectable; vision/text/unknown correct; token limits preserved; both routes registered.');
