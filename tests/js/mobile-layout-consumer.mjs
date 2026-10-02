// Render the shipped WebUI with the exact Java patch in a touch Chromium browser.
// Only synthetic workspaces, drafts and uploads are used; no model prompt is sent.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {chromium} from 'playwright';

const runtime = path.resolve(process.argv[2]);
const patched = await fs.readFile(process.argv[3], 'utf8');
const probe = await fs.readFile(process.argv[4], 'utf8');
const injection = patched.slice(patched.indexOf('<style id="dsh-native-responsive">'), patched.indexOf('</head>'));
assert(injection.startsWith('<style'), 'must consume the generated Java patch');
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-layout-'));
const evidence = process.env.DSH_LAYOUT_EVIDENCE_DIR;
const fileName = 'long-unbroken-file-title-'.repeat(4) + '.txt';
const uploadName = 'PRIVATE-UPLOAD-NAME-'.repeat(5) + '.txt';
let child, browser, output = '';
let checks = 0;
function check(ok, detail) {assert(ok, detail); checks++;}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
try {
  const home = path.join(scratch, 'home'), project = path.join(scratch, 'layout-project');
  await fs.mkdir(home); await fs.mkdir(project);
  await fs.copyFile(new URL('../../payload/settings-preset.yaml', import.meta.url), path.join(home, 'settings.yaml'));
  await fs.writeFile(path.join(project, fileName), 'Synthetic file preview\n');
  await fs.writeFile(path.join(project, 'second.js'), 'const synthetic = true;\n');
  child = spawn(process.execPath, ['--expose-internals', path.join(runtime, 'lib/bin.js'),
    '--profile', 'web', '--no-open', '--port', '0'], {
    cwd: project, env: {...process.env, DSH_HOME: home, COMMANDCODE_API_KEY: '', DEEPSEEK_API_KEY: ''},
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const collect = chunk => {output = (output + chunk).slice(-32000);};
  child.stdout.on('data', collect); child.stderr.on('data', collect);
  let launch;
  for (let i = 0; i < 450; i++) {
    launch = output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];
    if (launch) break;
    assert.equal(child.exitCode, null, 'real Web profile exited during startup');
    await sleep(100);
  }
  assert(launch, 'real Web profile did not become ready');
  const origin = new URL(launch).origin;
  const exchange = await fetch(launch, {redirect: 'manual', signal: AbortSignal.timeout(10000)});
  const cookie = exchange.headers.get('set-cookie')?.split(';')[0];
  assert(cookie, 'launch authentication failed');
  let serial = 0;
  async function rpc(method, args) {
    const response = await fetch(origin + '/api/' + method, {
      method: 'POST', headers: {cookie, 'content-type': 'application/json'},
      body: JSON.stringify({type: 'client-request', rpcId: 'layout-' + ++serial, method, payload: {args}}),
      signal: AbortSignal.timeout(10000),
    });
    const reply = await response.json();
    assert(reply.result?.ok, method + ' failed: ' + JSON.stringify(reply.result?.error));
    return reply.result.value;
  }
  await rpc('workspace/create', {request: {path: project}});
  await rpc('settings/update', {ns: 'ui-settings-general', patch: {welcomeNoticeVersion: '2026-09-28.1'}});
  browser = await chromium.launch({headless: true});
  if (evidence) await fs.mkdir(evidence, {recursive: true});
  const scenarios = [
    {w: 320, h: 800, lang: 'zh', theme: 'dark'},
    {w: 360, h: 800, lang: 'en', theme: 'light'},
    {w: 436, h: 880, lang: 'zh', theme: 'light'},
    {w: 436, h: 880, lang: 'en', theme: 'dark'},
    {w: 600, h: 960, lang: 'zh', theme: 'dark'},
    {w: 869, h: 436, lang: 'en', theme: 'light'},
  ];
  const measurements = [];
  for (const scenario of scenarios) {
    await rpc('settings/update', {ns: 'locale', patch: {preference: scenario.lang}});
    await rpc('settings/update', {ns: 'ui-theme', patch: {preference: scenario.theme}});
    const context = await browser.newContext({viewport: {width: scenario.w, height: scenario.h},
      isMobile: true, hasTouch: true, reducedMotion: 'reduce', colorScheme: scenario.theme,
      locale: scenario.lang === 'zh' ? 'zh-CN' : 'en-US'});
    try {
      await context.grantPermissions(['local-network-access'], {origin});
      await context.request.get(launch);
      const page = await context.newPage(), logs = [], errors = [];
      page.setDefaultTimeout(10000);
      page.on('console', m => {if (m.text().startsWith('[dsh-native] layout-probe ')) logs.push(m.text());});
      page.on('pageerror', e => errors.push(e.message));
      // Preserve the Host's module registry, authentication and theme bootstrap.
      await page.route(origin + '/', async route => {
        const response = await route.fetch();
        let html = await response.text();
        html = html.replace(/content="width=device-width, initial-scale=1"/,
          'content="width=' + Math.max(480, scenario.w) + '"');
        await route.fulfill({response, body: html.replace('</head>', injection + '</head>')});
      });
      await page.goto(origin + '/');
      const later = page.getByRole('button', {name: /^(Configure later|稍后配置)$/});
      await later.waitFor({state: 'visible'});
      await later.click();
      await page.getByRole('dialog').waitFor({state: 'hidden'});
      await page.evaluate(probe);
      const send = page.getByRole('button', {name: /^(Send message|发送消息)$/});
      const size = async locator => {
        const r = await locator.boundingBox(); assert(r, 'control must be visible'); return r;
      };
      const square = async (locator, expected, label) => {
        const r = await size(locator);
        check(Math.abs(r.width - expected) < .5 && Math.abs(r.height - expected) < .5,
          label + ': expected ' + expected + 'px square, got ' + r.width + 'x' + r.height);
        return r;
      };
      const noPageOverflow = async () => check(await page.evaluate(() =>
        document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), 'page has horizontal overflow');
      check(await page.evaluate(() => document.documentElement.clientWidth) === Math.max(480, scenario.w), 'viewport floor changed');
      check(await page.evaluate(() => matchMedia('(pointer:coarse)').matches), 'must exercise the coarse pointer CSS path');
      await square(send, 34, 'empty composer send');
      await square(page.getByRole('button', {name: /^(Open sidebar|打开侧边栏)$/}), 36, 'sidebar toggle');
      await noPageOverflow();
      await page.locator('[data-composer-input]').fill('PRIVATE-DRAFT-CONTENT');
      await page.locator('input[type=file]').setInputFiles([
        {name: uploadName, mimeType: 'text/plain', buffer: Buffer.from('PRIVATE-ATTACHMENT-CONTENT')},
        {name: 'PRIVATE-IMAGE-NAME.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6WJkAAAAASUVORK5CYII=', 'base64')},
        {name: 'second-upload.txt', mimeType: 'text/plain', buffer: Buffer.from('Synthetic second attachment')},
      ]);
      const removeImage = page.getByRole('button', {name: /^(Remove image |移除图片 )/});
      const removeFiles = page.getByRole('button', {name: /^(Remove file |移除文件 )/});
      await removeImage.waitFor({state: 'visible'});
      await square(removeImage, 18, 'image remove');
      await square(removeFiles.first(), 18, 'long-name file remove');
      await square(removeFiles.last(), 18, 'multiple file remove');
      const sendBox = await square(send, 34, 'send with attachments');
      const model = page.locator('[data-slot="conversation.input.model"] button[aria-haspopup=menu]');
      const modelBox = await size(model);
      check(modelBox.x + modelBox.width <= sendBox.x + .5, 'model selector overlaps send button');
      await model.click();
      const modelMenu = page.getByRole('menu'); await modelMenu.waitFor({state: 'visible'});
      const menuBox = await size(modelMenu), menuVw = await page.evaluate(() => document.documentElement.clientWidth);
      check(menuBox.x >= -.5 && menuBox.x + menuBox.width <= menuVw + .5, 'model menu extends off screen');
      await page.keyboard.press('Escape');
      await sleep(2200);
      check(logs.some(s => s.includes('"action":"remove-image"') && s.includes('"h":18')), 'probe missed a late image attachment');
      check(logs.some(s => s.includes('"action":"remove-file"') && s.includes('"h":18')), 'probe missed a late file attachment');
      await noPageOverflow();
      if (evidence) await page.screenshot({path: path.join(evidence, `${scenario.w}-${scenario.lang}-${scenario.theme}-composer.png`)});

      // The old rule is a negative control: it must reproduce all three defects.
      const oldRule = await page.addStyleTag({content: '@media(pointer:coarse){button:not([role=switch]),[role=button]:not([role=switch]){min-height:44px;}}'});
      check(Math.abs((await size(send)).height - 44) < .5 && Math.abs((await size(removeImage)).height - 44) < .5, 'negative control failed to reproduce the regression');
      await oldRule.evaluate(e => e.remove());
      await square(send, 34, 'send restored after negative control');

      // Open the shipped image lightbox and use its real close handler.
      await page.locator('[data-slot="conversation.input.attachments"] button').filter({has: page.locator('img')}).click();
      const lightbox = page.getByRole('dialog'); await lightbox.waitFor({state: 'visible'});
      const imageClose = lightbox.getByRole('button', {name: /^(Close original image preview|关闭原图预览)$/});
      await square(imageClose, 36, 'image preview close');
      await imageClose.click(); await lightbox.waitFor({state: 'hidden'});
      await removeFiles.first().click();
      check(await removeFiles.count() === 1, 'file remove action did not update the composer');

      // The dockkit file tab is where the user observed the cross below the title.
      await page.locator('[data-sidebar-right-expand]').click();
      await page.locator('[data-sidebar-right-guide-entry="files"]').click();
      await page.getByText(fileName, {exact: true}).dblclick();
      const tabs = page.locator('[data-dockkit-tab]');
      await page.locator('[data-textpreview-state="text"]').waitFor({state: 'visible'});
      for (const tab of await tabs.all()) {
        const close = tab.getByRole('button', {name: /^(Close|关闭)$/});
        const b = await square(close, 20, 'file tab close'), t = await size(tab);
        check(b.y >= t.y - .5 && b.y + b.height <= t.y + t.height + .5, 'file tab close dropped below the title row');
      }
      const tabClose = tabs.last().getByRole('button', {name: /^(Close|关闭)$/});
      const oldTabRule = await page.addStyleTag({content: '@media(pointer:coarse){button:not([role=switch]){min-height:44px;}}'});
      check(Math.abs((await size(tabClose)).height - 44) < .5, 'negative control failed to reproduce the file-tab regression');
      await oldTabRule.evaluate(e => e.remove());
      let visibleTools = 0;
      for (const tool of await page.locator('[data-textpreview-tool]').all()) {
        if (await tool.isVisible()) {await square(tool, 28, 'file preview tool'); visibleTools++;}
      }
      check(visibleTools > 0, 'file preview tools were not rendered');
      await noPageOverflow();
      if (evidence) await page.screenshot({path: path.join(evidence, `${scenario.w}-${scenario.lang}-${scenario.theme}-file.png`)});
      await tabClose.click();
      check(await page.locator('[data-textpreview-state="text"]').count() === 0, 'file tab close handler did not close the preview');
      await page.locator('[data-sidebar-right-toggle]').click();

      // Preserve the working settings adaptation and verify its actual switch geometry.
      await page.getByRole('button', {name: /^(Settings|设置)$/}).click();
      const settings = page.locator('[data-shortcut-modal="settings"][role=dialog]');
      await settings.waitFor({state: 'visible'});
      const settingsBox = await size(settings);
      const vw = await page.evaluate(() => document.documentElement.clientWidth);
      check(settingsBox.width <= vw - 16 + 1, 'settings exceed the viewport');
      check(settingsBox.x >= -.5 && settingsBox.x + settingsBox.width <= vw + .5, 'settings are offset off screen');
      for (const control of await settings.getByRole('switch').all()) {
        const r = await size(control); check(r.width >= 35.5 && Math.abs(r.height - 20) < .5, 'settings switch was squeezed or enlarged');
      }
      for (const button of await settings.locator('nav button').all()) {
        const lines = await button.evaluate(e => {
          const walker = document.createTreeWalker(e, NodeFilter.SHOW_TEXT), tops = new Set();
          for (let n = walker.nextNode(); n; n = walker.nextNode()) {
            if (!n.textContent.trim()) continue;
            const range = document.createRange(); range.selectNodeContents(n);
            for (const r of range.getClientRects()) if (r.width > 0 && r.height > 0) tops.add(Math.round(r.top));
          }
          return tops.size;
        });
        check(lines <= 1, 'settings button text wraps onto another line');
      }
      if (evidence) await page.screenshot({path: path.join(evidence, `${scenario.w}-${scenario.lang}-${scenario.theme}-settings.png`)});
      const settingsClose = settings.getByRole('button', {name: /^(Close|关闭)$/});
      await square(settingsClose, 28, 'settings close');
      await settings.locator('nav').getByRole('button', {name: /^(Models|模型)$/}).click();
      await square(settingsClose, 28, 'models settings close');
      await settings.locator('nav').getByRole('button', {name: /^(Built-in plugins|内置插件)$/}).click();
      await square(settingsClose, 28, 'plugins settings close');
      await settingsClose.click();
      await settings.waitFor({state: 'hidden'});
      check(await settings.count() === 0, 'settings close interaction failed');
      check(errors.length === 0, 'WebUI raised an exception');
      check(logs.every(s => s.length < 900 && !/PRIVATE-|Synthetic file preview/.test(s)), 'layout diagnostics leaked content or exceeded the native limit');
      measurements.push({...scenario, send: [sendBox.width, sendBox.height], model: [modelBox.width, modelBox.height], settings: [settingsBox.width, settingsBox.height]});
      console.log(`Actual mobile WebUI: ${scenario.w}x${scenario.h} ${scenario.lang} ${scenario.theme} passed.`);
    } finally {await context.close();}
  }
  if (evidence) await fs.writeFile(path.join(evidence, 'measurements.json'), JSON.stringify(measurements, null, 2));
  console.log(`Actual mobile layout: ${checks} geometry/interaction/privacy checks passed across ${scenarios.length} touch scenarios.`);
} finally {
  await browser?.close();
  if (child && child.exitCode === null) {
    const ended = new Promise(resolve => child.once('exit', resolve));
    child.kill('SIGTERM'); const timer = setTimeout(() => child.kill('SIGKILL'), 3000);
    await ended; clearTimeout(timer);
  }
  await fs.rm(scratch, {recursive: true, force: true});
}
