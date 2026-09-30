// Exercise the actual shipped runtime, its filesystem operations and Web boot.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';

const root = path.resolve(process.argv[2]);
const requireRuntime = createRequire(path.join(root, 'probe.cjs'));
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-core-test-'));
const sourcePath = requireRuntime.resolve('@deepseek-ai/dsh-session-persistence-jsonl');
const probePath = path.join(path.dirname(sourcePath), 'native-consumer-probe.mjs');
let child;
try {
  assert.equal(JSON.parse(await fs.readFile(path.join(root, 'package.json'))).version, '0.2.0-rc.2');
  await fs.writeFile(probePath, await fs.readFile(sourcePath, 'utf8') +
    '\nexport { publishCurrentExclusive, SessionWriteLease };\n');
  const { publishCurrentExclusive, SessionWriteLease } = await import(pathToFileURL(probePath));
  const staged = path.join(scratch, 'staged');
  const target = path.join(scratch, 'current');
  await fs.writeFile(staged, 'verified historical session');
  const internals = { platform: 'linux', fs };
  assert.equal(await publishCurrentExclusive(staged, target, internals), true);
  assert.equal(await publishCurrentExclusive(staged, target, internals), false);
  assert.equal(await fs.readFile(target, 'utf8'), 'verified historical session');
  await fs.rm(target);
  const noLinks = { platform: 'android', fs: {
    ...fs, link: async () => { throw Object.assign(new Error('FUSE no hardlinks'), { code: 'EPERM' }); },
  } };
  assert.equal(await publishCurrentExclusive(staged, target, noLinks), true);
  await fs.writeFile(staged, 'must not overwrite');
  assert.equal(await publishCurrentExclusive(staged, target, noLinks), false);
  assert.equal(await fs.readFile(target, 'utf8'), 'verified historical session');
  await fs.rm(target);
  await assert.rejects(publishCurrentExclusive(staged, target, { platform: 'android', fs: {
    ...fs, link: async () => { throw Object.assign(new Error('disk full'), { code: 'ENOSPC' }); },
  } }), { code: 'ENOSPC' });
  assert.equal(await fs.stat(target).catch(() => null), null);

  const leaseDir = path.join(scratch, 'lease');
  const lease = await SessionWriteLease.acquire(leaseDir, 'test-session');
  await assert.rejects(SessionWriteLease.acquire(leaseDir, 'test-session'));
  await lease.release();
  const nextLease = await SessionWriteLease.acquire(leaseDir, 'test-session');
  await nextLease.release();

  const { Context } = requireRuntime('@deepseek-ai/cordis');
  const { default: Persistence } = await import(pathToFileURL(sourcePath));
  const context = new Context();
  const sessions = path.join(scratch, 'sessions');
  const persistence = new Persistence(context, { root: sessions });
  const header = { version: 4, id: 'native-core-history', createdAt: 1, cwd: scratch, isSeeded: false };
  const write = await persistence.create(header);
  await write.flush();
  await write.close();
  const reopened = await persistence.open(header.id, 'read');
  assert.deepEqual((await reopened.read()).events, []);
  await reopened.close();
  const resumed = await persistence.open(header.id, 'write');
  await resumed.flush();
  await resumed.close();

  // An Android import must use the explicit platform fallback, while the
  // desktop test above verifies that real contention is no longer swallowed.
  const flock = pathToFileURL(requireRuntime.resolve('@deepseek-ai/node-addon-system/flock')).href;
  const android = spawnSync(process.execPath, ['--input-type=module', '-e',
    `Object.defineProperty(process,'platform',{value:'android'});` +
    `const {tryLockExclusive}=await import(${JSON.stringify(flock)});await tryLockExclusive(1);`],
    { encoding: 'utf8', timeout: 10000 });
  assert.equal(android.status, 0, android.stderr);

  // No credentials/prompts: boot the real profile and serve its real frontend.
  const home = path.join(scratch, 'home');
  await fs.mkdir(home);
  const preset = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../../payload/settings-preset.yaml');
  await fs.copyFile(preset, path.join(home, 'settings.yaml'));
  child = spawn(process.execPath, ['--expose-internals', path.join(root, 'lib/bin.js'),
    '--profile', 'web', '--no-open', '--port', '0'], {
    cwd: scratch, env: { ...process.env, DSH_HOME: home, COMMANDCODE_API_KEY: '', DEEPSEEK_API_KEY: '' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout.on('data', chunk => { output += chunk; });
  child.stderr.on('data', chunk => { output += chunk; });
  const started = Date.now();
  let url;
  while (Date.now() - started < 45000) {
    url = output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];
    if (url) break;
    if (child.exitCode !== null) throw new Error('Web profile exited: ' + output.replace(/token=\S+/g, 'token=REDACTED'));
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert(url, 'Web profile did not become ready');
  const exchange = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(10000) });
  assert.equal(exchange.status, 303);
  const cookie = exchange.headers.get('set-cookie')?.split(';')[0];
  assert(cookie, 'launch token must exchange for the signed browser cookie');
  const response = await fetch(new URL(exchange.headers.get('location'), url), {
    headers: { cookie }, signal: AbortSignal.timeout(10000),
  });
  assert.equal(response.status, 200);
  assert.match(await response.text(), /<html|<!doctype/i);
  console.log('Actual core: session create/flush/reopen/resume, exclusive publication, FUSE fallback, disk errors, native lock contention, Android lock fallback, configured Web boot and frontend passed.');
} finally {
  if (child && child.exitCode === null) {
    const ended = new Promise(resolve => child.once('exit', resolve));
    child.kill('SIGTERM');
    const timer = setTimeout(() => child.kill('SIGKILL'), 3000);
    await ended;
    clearTimeout(timer);
  }
  await fs.rm(probePath, { force: true });
  await fs.rm(scratch, { recursive: true, force: true });
}
