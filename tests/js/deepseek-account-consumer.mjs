// The real Web core and production native RPC transport, with a local Platform fixture.
// No real account, provider key or paid model request is used.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import readline from 'node:readline';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';

const runtime = path.resolve(process.argv[2]), classes = path.resolve(process.argv[3]);
const requireRuntime = createRequire(path.join(runtime, 'package.json'));
const yaml = requireRuntime('js-yaml');
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-account-'));
const home = path.join(scratch, 'home'), project = path.join(scratch, 'project');
let child, native, platform, output = '', attempts = [], mode = 'success', checks = 0, exchanges = 0, cancellations = 0;
const check = (condition, message) => {assert(condition, message); checks++;};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const metadata = {version: '0.2.0-rc.2', locale: 'zh-CN', timezoneOffsetSeconds: 28800};
const fakeToken = 'synthetic-account-grant';
let platformOrigin, launch;

function body(response, data) {
  response.writeHead(200, {'content-type': 'application/json'});
  response.end(JSON.stringify({code: 0, data: {biz_code: 0, biz_data: data}}));
}

async function boot() {
  output = '';
  child = spawn(process.execPath, ['--expose-internals', path.join(runtime, 'lib/bin.js'),
    '--patch', path.join(scratch, 'platform.yml'), '--profile', 'web', '--no-open', '--port', '0'], {
    cwd: project, env: {...process.env, DSH_HOME: home, COMMANDCODE_API_KEY: '', DEEPSEEK_API_KEY: ''},
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const collect = chunk => {output = (output + chunk).slice(-64000);};
  child.stdout.on('data', collect); child.stderr.on('data', collect);
  launch = undefined;
  for (let i = 0; i < 450; i++) {
    launch = output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];
    if (launch) break;
    assert.equal(child.exitCode, null, 'real core exited during account boot');
    await sleep(100);
  }
  assert(launch, 'real account core did not start');
  native = spawn('java', ['-Dfile.encoding=UTF-8', '-cp', classes, 'dev.dsh.nativeapp.CoreRpcConsumer'], {stdio: ['pipe', 'pipe', 'pipe']});
  const lines = readline.createInterface({input: native.stdout});
  const pending = [];
  lines.on('line', line => pending.shift()?.(JSON.parse(line)));
  native.on('exit', code => {assert(code === 0 || code === null, 'native transport exited');});
  const address = new URL(launch);
  native.stdin.write(JSON.stringify({port: Number(address.port), token: address.searchParams.get('token')}) + '\n');
  return async (method, args = {}, snapshot = false, accountConfig = false) => {
    const answer = new Promise(resolve => pending.push(resolve));
    native.stdin.write(JSON.stringify({method, args: JSON.stringify(args), snapshot, accountConfig}) + '\n');
    let timer;
    const reply = await Promise.race([answer, new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error('native account RPC timed out')), 15000);
    })]).finally(() => clearTimeout(timer));
    if (snapshot) {assert(reply.yaml, 'real live settings projection failed'); return Buffer.from(reply.yaml, 'base64').toString();}
    assert(reply.type === 'server-response' && reply.rpcId?.startsWith('native-') && reply.result?.ok, method + ' failed');
    return reply.result.value;
  };
}

async function stop() {
  if (native) {native.stdin.end(); native = undefined;}
  if (child) {
    const process = child; child = undefined;
    if (process.exitCode === null) {
      const exited = new Promise(resolve => process.once('exit', resolve));
      process.kill('SIGTERM');
      await Promise.race([exited, sleep(6000).then(() => process.kill('SIGKILL'))]);
    }
  }
}

async function until(rpc, phase) {
  for (let i = 0; i < 100; i++) {
    const view = await rpc('account/getState');
    if (view.attempt?.phase === phase) return view;
    await sleep(100);
  }
  throw new Error('account did not reach ' + phase);
}

try {
  await fs.mkdir(home); await fs.mkdir(project);
  await fs.copyFile(new URL('../../payload/settings-preset.yaml', import.meta.url), path.join(home, 'settings.yaml'));
  platform = http.createServer(async (request, response) => {
    let bytes = ''; for await (const chunk of request) bytes += chunk;
    const data = bytes ? JSON.parse(bytes) : {};
    if (request.url === '/auth-api/v0/dsh/auth_init') {
      if (mode === 'network') {response.writeHead(503); response.end(); return;}
      check(data.code_challenge_method === 'S256' && /^[A-Za-z0-9_-]{43}$/.test(data.code_challenge), 'core must generate PKCE');
      check(data.locale === 'zh_CN' && data.login_source === 'web', 'native client metadata lost');
      check(data.redirect_uri.endsWith('/oauth/callback') && new URL(data.redirect_uri).hostname === '127.0.0.1', 'callback origin is not the initiating loopback');
      attempts.push(data);
      body(response, {authorize_url: platformOrigin + '/dsh/authorize?id=synthetic', authorize_id: 'synthetic', expires_in: mode === 'expiry' ? .25 : 60});
    } else if (request.url === '/auth-api/v0/dsh/auth_exchange') {
      exchanges++;
      const attempt = attempts.at(-1);
      check(crypto.createHash('sha256').update(data.code_verifier).digest('base64url') === attempt.code_challenge, 'PKCE verification failed');
      check(data.redirect_uri === attempt.redirect_uri && data.code === 'synthetic-code', 'core exchanged a different callback');
      check(typeof data.device_id === 'string' && data.device_id.length > 10, 'device identity not persisted');
      body(response, {token: fakeToken, authorized_url: platformOrigin + '/dsh/authorized'});
    } else if (request.url === '/auth-api/v0/dsh/auth_cancel') {cancellations++; body(response, {});
    } else if (request.url === '/auth-api/v0/users/logout') {check(request.headers['x-dsh-auth-token'] === fakeToken, 'logout grant missing'); body(response, {});
    } else if (request.url === '/auth-api/v0/users/current') {
      check(request.headers['x-dsh-auth-token'] === fakeToken, 'profile grant missing');
      body(response, {id: 'synthetic-user', email: 'masked@example.test', id_profile: {name: 'Test account'}});
    } else if (request.url === '/api/v0/users/get_user_summary') {
      check(request.headers['x-dsh-auth-token'] === fakeToken, 'balance grant missing');
      body(response, {normal_wallets: [{currency: 'CNY', balance: '1.50'}], bonus_wallets: []});
    } else {response.writeHead(404); response.end();}
  });
  await new Promise(resolve => platform.listen(0, '127.0.0.1', resolve));
  platformOrigin = 'http://127.0.0.1:' + platform.address().port;
  await fs.writeFile(path.join(scratch, 'platform.yml'), '- id: deepseek-account\n  config:\n    platformOrigin: ' + platformOrigin
    + '\n    inferenceOrigin: ' + platformOrigin + '\n    allowLoopbackHttp: true\n    requestTimeoutMs: 2000\n    logoutRetryDelayMs: 10\n'
    + '- id: llm-deepseek-account\n  config:\n    baseURL: ' + platformOrigin + '\n');
  let rpc = await boot();
  check((await rpc('account/getState')).status === 'signed-out', 'fresh core is unexpectedly signed in');
  const snapshot = await rpc('settings/describe', {}, true);
  const projected = yaml.load(snapshot), preset = yaml.load(await fs.readFile(new URL('../../payload/settings-preset.yaml', import.meta.url), 'utf8'));
  check(projected['agent-default-model'].model === preset['agent-default-model'].model, 'live default model lost');
  check(projected['llm-pi-ai'].providers.commandcode.models.length === preset['llm-pi-ai'].providers.commandcode.models.length, 'live model catalog lost during projection');
  check(projected['llm-pi-ai'].providers.commandcode.models[0].input.includes('image'), 'vision input lost during projection');
  check(projected['llm-pi-ai'].providers.commandcode.models[0].reasoningEfforts.max === 'max', 'reasoning map lost during projection');
  check(!(await rpc('session/modelCatalog')).groups.some(g => g.id === 'deepseek-account'), 'signed-out account models are visible');

  await rpc('account/startSignIn', {client: metadata, callbackOrigin: new URL(launch).origin, loginSource: 'web'});
  let view = await until(rpc, 'waiting-browser');
  check(new URL(view.attempt.authorizeUrl).pathname === '/dsh/authorize', 'authorization link not projected');
  const same = await rpc('account/startSignIn', {client: metadata, callbackOrigin: new URL(launch).origin, loginSource: 'web'});
  check(same.attempt.id === view.attempt.id && attempts.length === 1, 'double login created parallel attempts');
  const init = attempts.at(-1);
  const bad = await fetch(init.redirect_uri + '?state=wrong&code=synthetic-code');
  check(bad.status === 400 && exchanges === 0, 'invalid state exchanged credentials');
  await rpc('credentials/set', {ref: 'COMMANDCODE_API_KEY', value: 'synthetic-command-key'});
  const callback = await fetch(init.redirect_uri + '?state=' + init.state + '&code=synthetic-code', {redirect: 'manual'});
  check(callback.status === 302 && new URL(callback.headers.get('location')).pathname === '/dsh/authorized', 'successful callback did not complete');
  view = await until(rpc, 'succeeded');
  check(view.status === 'credential-stored' && !JSON.stringify(view).includes(fakeToken), 'safe account projection leaked the grant');
  check((await rpc('session/modelCatalog')).groups.find(g => g.id === 'deepseek-account')?.models.length > 0, 'authenticated models unavailable');
  check((await rpc('account/getProfile', {client: metadata})).status === 'ready', 'profile read failed');
  check((await rpc('account/getBalance', {client: metadata})).value[0].balance === '1.50', 'balance read failed');
  await rpc('credentials/set', {ref: 'DEEPSEEK_API_KEY', value: 'synthetic-api-key'});
  check((await rpc('account/getState')).status === 'credential-stored', 'API key write removed account authorization');
  let credentials = await fs.readFile(path.join(home, '.credentials.yaml'), 'utf8');
  check(credentials.includes('synthetic-command-key') && credentials.includes('synthetic-api-key') && credentials.includes(fakeToken), 'independent credentials were overwritten');

  const accountSettings = await rpc('settings/describe', {}, true, true);
  const accountModel = yaml.load(accountSettings)['agent-default-model'];
  check(accountModel.provider === 'deepseek-account' && accountModel.reasoningEffort === 'max', 'native selection did not retain the account provider');
  await fs.writeFile(path.join(home, 'settings.yaml'), accountSettings);
  await stop(); rpc = await boot();
  check((await rpc('account/getState')).status === 'credential-stored', 'account grant did not survive core restart');
  check((await rpc('session/modelCatalog')).groups.some(g => g.id === 'deepseek-account'), 'account model route did not survive restart');
  const catalogAfterRestart = await rpc('session/modelCatalog');
  check(catalogAfterRestart.default.provider === 'deepseek-account' && catalogAfterRestart.default.model === accountModel.model
    && catalogAfterRestart.default.reasoningEffort === 'max', 'native account default was not consumed by the real core');
  check(catalogAfterRestart.groups.find(g => g.id === 'deepseek-account').models[0].reasoning.efforts.some(e => e.id === 'max'), 'account effort missing from the real composer catalog');
  check(await rpc('account/hasRunningAccountTasks') === false, 'idle fixture reported running account tasks');
  check((await rpc('account/signOut', {client: metadata})).status === 'signed-out', 'sign-out did not remove grant');
  credentials = await fs.readFile(path.join(home, '.credentials.yaml'), 'utf8');
  check(!credentials.includes(fakeToken) && credentials.includes('synthetic-api-key') && credentials.includes('synthetic-command-key'), 'sign-out removed API keys or kept the grant');

  await rpc('account/startSignIn', {client: metadata, callbackOrigin: new URL(launch).origin, loginSource: 'web'});
  view = await until(rpc, 'waiting-browser');
  check((await rpc('account/cancelSignIn', {attemptId: view.attempt.id})).attempt.phase === 'cancelled', 'explicit cancellation failed');
  check((await rpc('account/getState')).status === 'signed-out', 'cancel stored an account grant');
  mode = 'expiry';
  await rpc('account/startSignIn', {client: metadata, callbackOrigin: new URL(launch).origin, loginSource: 'web'});
  check((await until(rpc, 'expired')).status === 'signed-out', 'expired login stored a grant');
  mode = 'network';
  await rpc('account/startSignIn', {client: metadata, callbackOrigin: new URL(launch).origin, loginSource: 'web'});
  check((await until(rpc, 'failed')).attempt.errorCode === 'network', 'network failure not projected');
  check(!output.includes(fakeToken) && !output.includes('synthetic-api-key'), 'core log leaked credentials');
  check(exchanges === 1 && cancellations >= 1, 'authorization fixture did not exercise exchange and cancellation');
  console.log(`DeepSeekAccountConsumer: ${checks} checks; real native RPC / PKCE / metadata / callback rejection / credentials / restart / sign-out / cancel / expiry / failure`);
} finally {
  await stop();
  if (platform) await new Promise(resolve => platform.close(resolve));
  await fs.rm(scratch, {recursive: true, force: true});
}
