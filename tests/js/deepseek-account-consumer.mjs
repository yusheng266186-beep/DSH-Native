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
import {chromium} from 'playwright';

const runtime = path.resolve(process.argv[2]), classes = path.resolve(process.argv[3]);
const mobileHtml = await fs.readFile(process.argv[4], 'utf8');
const mobileInjection = mobileHtml.slice(mobileHtml.indexOf('<style id="dsh-native-responsive">'), mobileHtml.indexOf('</head>'));
assert(mobileInjection.startsWith('<style'), 'account UI must use the production mobile layout');
const requireRuntime = createRequire(path.join(runtime, 'package.json'));
const yaml = requireRuntime('js-yaml');
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-account-'));
const home = path.join(scratch, 'home'), project = path.join(scratch, 'project');
let child, native, platform, browser, accountPage, output = '', attempts = [], mode = 'success', checks = 0, exchanges = 0, cancellations = 0, balanceFailed = false;
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
  const expectedSettings = await fs.readFile(path.join(home, 'settings.yaml'), 'utf8').catch(() => '');
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
  const initialized = new Promise(resolve => pending.push(resolve));
  lines.on('line', line => pending.shift()?.(JSON.parse(line)));
  native.on('exit', code => {assert(code === 0 || code === null, 'native transport exited');});
  const address = new URL(launch);
  native.stdin.write(JSON.stringify({port: Number(address.port), token: address.searchParams.get('token'), expectedSettings}) + '\n');
  let bootTimer;
  const ready = await Promise.race([initialized, new Promise((resolve, reject) => {
    bootTimer = setTimeout(() => reject(new Error('native configuration readiness timed out')), 45000);
  })]).finally(() => clearTimeout(bootTimer));
  assert(ready.ready, 'native core configuration was not ready');
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
      if (balanceFailed) {response.writeHead(503); response.end(); return;}
      body(response, {normal_wallets: [{currency: 'CNY', balance: '1.50'}], bonus_wallets: [{currency: 'CNY', balance: '2.25'}]});
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

  browser = await chromium.launch({headless: true});
  let context = await browser.newContext({viewport: {width: 480, height: 850}, isMobile: true, hasTouch: true, locale: 'zh-CN'});
  const origin = new URL(launch).origin;
  await context.grantPermissions(['local-network-access'], {origin});
  await context.request.get(launch);
  const httpRpc = async (method, args) => {
    const response = await context.request.post(origin + '/api/' + method, {data: {type: 'client-request', rpcId: 'account-ui-' + method,
      method, payload: {args}}});
    const reply = await response.json(); assert(reply.result?.ok, method + ' failed');
  };
  await httpRpc('settings/update', {ns: 'locale', patch: {preference: 'zh'}});
  await httpRpc('settings/update', {ns: 'ui-settings-general', patch: {welcomeNoticeVersion: '2026-09-28.1'}});
  await httpRpc('workspace/create', {request: {path: project}});
  accountPage = await context.newPage();
  const mobileRoute = (page, width) => page.route(origin + '/', async route => {
      const response = await route.fetch();
      const html = (await response.text()).replace(/content="width=device-width, initial-scale=1"/,
        'content="width=' + Math.max(480, width) + '"');
      await route.fulfill({response, body: html.replace('</head>', mobileInjection + '</head>')});
    });
  await mobileRoute(accountPage, 480);
  const pageErrors = []; accountPage.on('pageerror', error => pageErrors.push(error.message));
  await accountPage.goto(origin + '/');
  const later = accountPage.getByRole('button', {name: /^(稍后配置|Configure later)$/});
  if (await later.waitFor({state: 'visible', timeout: 5000}).then(() => true, () => false)) {
    await later.click(); await accountPage.getByRole('dialog').waitFor({state: 'hidden'});
  }
  await accountPage.waitForFunction(() => document.querySelector('button[aria-label="账号菜单"]'));
  check(await accountPage.evaluate(() => !('dshDesktop' in globalThis)), 'account UI manufactured a Desktop bridge');
  check(await accountPage.evaluate(() => !window.dispatchEvent(new Event('dsh-native-account-settings', {cancelable: true}))), 'native App settings did not open upstream settings');
  let settings = accountPage.locator('[data-shortcut-modal="settings"]');
  await settings.getByRole('button', {name: '登录', exact: true}).waitFor({state: 'visible'});
  const opened = context.waitForEvent('page');
  await settings.getByRole('button', {name: '登录', exact: true}).click();
  const authorization = await opened;
  await authorization.waitForURL(platformOrigin + '/dsh/authorize**');
  check(new URL(authorization.url()).pathname === '/dsh/authorize', 'upstream sign-in did not open browser authorization');
  await authorization.close();
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
  await accountPage.getByRole('dialog').filter({hasText: '等待登录'}).waitFor({state: 'hidden'});
  await settings.getByText('Test account', {exact: true}).waitFor({state: 'visible'});
  await settings.getByText('充值余额', {exact: true}).waitFor({state: 'visible'});
  await settings.getByText('赠金余额', {exact: true}).waitFor({state: 'visible'});
  check(/1\.50|1\.5/.test(await settings.textContent()) && /2\.25/.test(await settings.textContent()), 'upstream balances were not rendered');
  check((await settings.getByRole('link', {name: '查询用量', exact: true}).getAttribute('href')) === platformOrigin + '/usage', 'usage destination lost');
  check((await settings.getByRole('link', {name: '充值', exact: true}).getAttribute('href')) === platformOrigin + '/top_up', 'top-up destination lost');
  for (const scenario of [{width: 320, height: 850, theme: 'light'}, {width: 436, height: 850, theme: 'dark'}, {width: 869, height: 436, theme: 'light'}]) {
    await httpRpc('settings/update', {ns: 'ui-theme', patch: {preference: scenario.theme}});
    await context.close();
    context = await browser.newContext({viewport: {width: scenario.width, height: scenario.height}, isMobile: true, hasTouch: true, locale: 'zh-CN'});
    await context.grantPermissions(['local-network-access'], {origin}); await context.request.get(launch);
    accountPage = await context.newPage(); accountPage.on('pageerror', error => pageErrors.push(error.message));
    await mobileRoute(accountPage, scenario.width);
    await accountPage.goto(origin + '/');
    settings = accountPage.locator('[data-shortcut-modal="settings"]');
    await accountPage.waitForFunction(() => document.querySelector('button[aria-label="账号菜单"]'));
    check(await accountPage.evaluate(() => !window.dispatchEvent(new Event('dsh-native-account-settings', {cancelable: true}))), 'account entry failed after reload');
    await settings.getByText('Test account', {exact: true}).waitFor({state: 'visible'});
    await settings.getByText('赠金余额', {exact: true}).waitFor({state: 'visible'});
    const box = await settings.boundingBox(), vw = await accountPage.evaluate(() => document.documentElement.clientWidth);
    check(box.x >= -.5 && box.x + box.width <= vw + .5, 'account settings off screen');
    check(await settings.evaluate(e => e.scrollWidth <= e.clientWidth + 1), 'account settings horizontal overflow');
    check(await settings.getByRole('link', {name: '充值', exact: true}).isVisible(), 'top-up action hidden');
    check(await accountPage.evaluate(() => document.body.hasAttribute('data-ds-dark-theme')) === (scenario.theme === 'dark'), 'account theme mismatch');
    if (process.env.DSH_ACCOUNT_EVIDENCE_DIR) {
      await fs.mkdir(process.env.DSH_ACCOUNT_EVIDENCE_DIR, {recursive: true});
      await accountPage.screenshot({path: path.join(process.env.DSH_ACCOUNT_EVIDENCE_DIR, 'account-' + scenario.width + '-' + scenario.theme + '.png')});
    }
  }
  balanceFailed = true;
  await settings.getByRole('button', {name: '关闭', exact: true}).click();
  await accountPage.evaluate(() => window.dispatchEvent(new Event('dsh-native-account-settings', {cancelable: true})));
  await settings.getByRole('link', {name: '前往开放平台查看', exact: true}).first().waitFor({state: 'visible'});
  check(!(await settings.textContent()).includes('0.00'), 'failed balance became zero');
  check((await rpc('account/getProfile', {client: metadata})).status === 'ready', 'balance failure also broke profile');
  balanceFailed = false;
  check(pageErrors.length === 0, 'account component raised browser errors');
  await context.close(); await browser.close(); browser = undefined;
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
  console.log(`DeepSeekAccountConsumer: ${checks} checks; original account UI / balances / browser sign-in / native RPC / PKCE / callback rejection / credentials / restart / sign-out / cancel / expiry / failure`);
} catch (error) {
  if (accountPage && process.env.DSH_ACCOUNT_EVIDENCE_DIR) {
    await fs.mkdir(process.env.DSH_ACCOUNT_EVIDENCE_DIR, {recursive: true});
    await accountPage.screenshot({path: path.join(process.env.DSH_ACCOUNT_EVIDENCE_DIR, 'failure.png')}).catch(() => {});
    await fs.writeFile(path.join(process.env.DSH_ACCOUNT_EVIDENCE_DIR, 'failure.txt'), await accountPage.locator('body').innerText().catch(() => '') + '\n' + error.message);
  }
  throw error;
} finally {
  if (browser) await browser.close();
  await stop();
  if (platform) await new Promise(resolve => platform.close(resolve));
  await fs.rm(scratch, {recursive: true, force: true});
}
