// Load the exact published Android payload's model consumers, without sending prompts.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';

const [root, fixture] = process.argv.slice(2);
assert(root && fixture, 'usage: model-catalog-consumer.mjs <payload-root> <generated-yaml>');
const requirePayload = createRequire(path.join(root, 'consumer.cjs'));
const yaml = requirePayload('yaml');
const config = yaml.parse(fs.readFileSync(fixture, 'utf8'), { uniqueKeys: true });
const adapters = [];
// The registry seam records the REAL plugin adapter. Model lists and image
// resolution below run in the payload's own adapter, never a simulated catalog.
const ctx = {
  fiber: { entry: { options: {} } },
  inject() {}, on() {}, get() { return undefined; },
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
