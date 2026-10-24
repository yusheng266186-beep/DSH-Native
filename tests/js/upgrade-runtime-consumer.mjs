// Recreate the directories left by an in-place upgrade, then use App production
// repairs and the real profile. No user files, grants or paid prompts are used.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {spawn, spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';

const [runtimeArg, manifestArg, classes] = process.argv.slice(2);
const runtime = path.resolve(runtimeArg);
const manifest = JSON.parse(await fs.readFile(manifestArg, 'utf8'));
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-upgrade-'));
const home = path.join(scratch, 'home'), project = path.join(scratch, 'project');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let child, output = '', origin, cookie, seq = 0;
const checkImport = name => spawnSync(process.execPath, ['--expose-internals', '--input-type=module', '-e',
  `await import(${JSON.stringify(pathToFileURL(path.join(runtime, 'node_modules/@deepseek-ai', name, 'lib/index.js')).href)})`],
  {encoding: 'utf8', timeout: 15000});
async function stop() {
  if (!child || child.exitCode !== null) return;
  const old = child; child = undefined;
  const ended = new Promise(resolve => old.once('exit', resolve));
  old.kill('SIGTERM');
  const timer = setTimeout(() => old.kill('SIGKILL'), 4000);
  await ended; clearTimeout(timer);
}
async function boot() {
  output = '';
  const flags = process.platform === 'darwin' ? ['--import', path.join(scratch, 'android.mjs')] : [];
  child = spawn(process.execPath, ['--expose-internals', ...flags, path.join(runtime, 'lib/bin.js'),
    '--patch', path.join(scratch, 'inspector.yml'), '--profile', 'web', '--no-open', '--port', '0'],
    {cwd: project, env: {...process.env, DSH_HOME: home, COMMANDCODE_API_KEY: '', DEEPSEEK_API_KEY: ''},
      stdio: ['ignore', 'pipe', 'pipe']});
  const collect = b => {output = (output + b).slice(-64000);};
  child.stdout.on('data', collect); child.stderr.on('data', collect);
  let launch;
  for (let n = 0; n < 450; n++) {
    launch = output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];
    if (launch) break;
    assert.equal(child.exitCode, null, 'upgrade profile exited');
    await sleep(100);
  }
  assert(launch, 'upgrade profile did not start');
  origin = new URL(launch).origin;
  const exchange = await fetch(launch, {redirect: 'manual', signal: AbortSignal.timeout(15000)});
  assert.equal(exchange.status, 303);
  cookie = exchange.headers.get('set-cookie').split(';')[0];
}
async function rpc(method, args) {
  const response = await fetch(origin + '/api/' + method, {method: 'POST',
    headers: {cookie, 'content-type': 'application/json'}, signal: AbortSignal.timeout(20000),
    body: JSON.stringify({type: 'client-request', rpcId: 'upgrade-' + ++seq, method, payload: {args}})});
  const result = (await response.json()).result;
  assert(result?.ok, method + ': ' + JSON.stringify(result?.error));
  return result.value;
}
try {
  // An overlay removes obsolete files, but historically retained all their
  // parent directories. Node stops at an empty nested package instead of the
  // valid hoisted dependency. Include the cumulative removal paths as shipped.
  for (const part of manifest.parts.filter(part => part.target === 'dsh'))
    for (const rel of part.remove ?? []) {
      assert(!path.isAbsolute(rel) && !rel.split('/').includes('..'));
      await fs.mkdir(path.dirname(path.join(runtime, rel)), {recursive: true});
    }
  const broken = checkImport('dsh-skill-filesystem');
  assert.notEqual(broken.status, 0, 'fixture must reproduce the old upgrade failure');
  assert.match(broken.stderr, /chokidar\/index\.js|ERR_MODULE_NOT_FOUND/);
  const repair = spawnSync('java', ['-Dfile.encoding=UTF-8', '-cp', classes,
    'dev.dsh.nativeapp.CoreUpgradeTest', '--apply', runtime], {encoding: 'utf8', timeout: 45000});
  assert.equal(repair.status, 0, repair.stderr);
  for (const name of ['dsh-skill-filesystem', 'dsh-experimental-client-ui-agent-team']) {
    const imported = checkImport(name);
    assert.equal(imported.status, 0, imported.stderr);
  }
  await fs.mkdir(home); await fs.mkdir(project);
  const preset = await fs.readFile(new URL('../../payload/settings-preset.yaml', import.meta.url), 'utf8');
  await fs.writeFile(path.join(home, 'settings.yaml'), preset + '\nllm-deepseek-account:\n  models:\n'
    + '    - id: obsolete-cached-model\n      name: Old cached name\n');
  await fs.writeFile(path.join(scratch, 'android.mjs'), "Object.defineProperty(process,'platform',{value:'android'});\n");
  await fs.writeFile(path.join(scratch, 'inspector.yml'), '- insert:\n  - id: experimental-inspector\n'
    + '    name: "@deepseek-ai/dsh-experimental-inspector"\n    config:\n      captureFetch: false\n');
  await boot();
  const team = await rpc('pluginManager/setBundleEnabled', {
    name: '@deepseek-ai/dsh-experimental-agent-team-profile', enabled: true});
  assert.equal(team.application, 'applied', 'team bundle must activate after upgrading');
  assert.equal(team.warnings?.length ?? 0, 0, 'team bundle activation must be clean');
  const workspace = (await rpc('workspace/create', {request: {path: project}})).workspace;
  const session = await rpc('session/create', {request: {workspaceId: workspace.workspaceId}});
  assert(session.sessionId, 'standard preset must create a real session');
  const inspection = await fetch(origin + '/api/experimental-inspector/bootstrap', {
    headers: {cookie}, signal: AbortSignal.timeout(20000)});
  assert.equal(inspection.status, 200, 'inspector Worker must finish initialization');
  assert(await inspection.json());
  await rpc('session/androidTrash', {request: {sessionId: session.sessionId}});
  await stop(); await boot();
  await rpc('session/androidRestore', {request: {sessionId: session.sessionId}});
  const rows = await rpc('session/androidList', {});
  assert(rows.items.some(row => row.sessionId === session.sessionId && !row.archived && !row.trashed));
  assert(!/entries did not activate|models is no longer configurable|internals are not exposed|never started|failed to import/.test(output),
    'restarted profile contains activation errors');
  console.log('Actual upgrade: reproduced empty-dependency failure; production cleanup, skill/team imports, legacy account catalog, inspector Worker, session creation and restart/restore passed.');
} finally {
  await stop();
  await fs.rm(scratch, {recursive: true, force: true});
}
