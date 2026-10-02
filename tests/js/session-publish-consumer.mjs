// Exercise the production Java-generated patch against the exact shipped module.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const runtime = path.resolve(process.argv[2]);
const requireRuntime = createRequire(path.join(runtime, 'package.json'));
const modulePath = requireRuntime.resolve('@deepseek-ai/dsh-session-persistence-jsonl');
const probe = path.join(path.dirname(modulePath), 'android-publish-probe.mjs');
const scratch = await fs.mkdtemp(path.join(os.tmpdir(), 'dsh-publish-'));
const originalPlatform = process.platform;
let checks = 0;
const check = (ok, detail) => {assert(ok, detail); checks++;};
try {
  const source = await fs.readFile(modulePath, 'utf8');
  assert(source.includes('__dshPublishLink(tmp, finalPath, link)'), 'first materialization is not patched');
  await fs.writeFile(probe, source.replace('androidCopyFile, link, lstat', 'androidCopyFile, link as nativeLink, lstat')
    + '\nlet linkError = "EACCES";\nasync function link(...args) {if (linkError) throw Object.assign(new Error("synthetic link refusal"), {code: linkError}); return nativeLink(...args);}\n'
    + 'export function setLinkError(code) {linkError = code;}\nexport {publishCurrentExclusive};\n');
  Object.defineProperty(process, 'platform', {value: 'android'});
  const {default: Persistence, publishCurrentExclusive, setLinkError} = await import(pathToFileURL(probe));
  const staged = path.join(scratch, 'staged'), target = path.join(scratch, 'current');
  await fs.writeFile(staged, 'protected history');
  for (const code of ['EACCES', 'EPERM', 'EXDEV', 'ENOSYS', 'EOPNOTSUPP', 'ENOTSUP', 'EMLINK']) {
    const internals = {platform: 'android', fs: {...fs, link: async () => {throw Object.assign(new Error('link denied'), {code});}}};
    check(await publishCurrentExclusive(staged, target, internals), code + ' migration failed');
    check(!(await publishCurrentExclusive(staged, target, internals)), code + ' overwrote history');
    check(await fs.readFile(target, 'utf8') === 'protected history', code + ' changed history');
    await fs.rm(target);
  }
  for (const code of ['ENOSPC', 'EIO', 'ENOENT']) {
    await assert.rejects(publishCurrentExclusive(staged, target, {platform: 'android', fs: {...fs,
      link: async () => {throw Object.assign(new Error('real failure'), {code});}}}), {code}); checks++;
    check(await fs.stat(target).catch(() => null) === null, 'failure created history');
  }
  await assert.rejects(publishCurrentExclusive(staged, target, {platform: 'android', fs: {...fs,
    link: async () => {throw Object.assign(new Error('denied link'), {code: 'EACCES'});},
    copyFile: async () => {throw Object.assign(new Error('denied write'), {code: 'EACCES'});}}}), {code: 'EACCES'}); checks++;
  const {Context} = requireRuntime('@deepseek-ai/cordis');
  for (const compression of ['zstd', 'none']) {
    const context = new Context();
    const persistence = new Persistence(context, {root: path.join(scratch, compression), compression});
    const header = {version: 4, id: 'android-' + compression, createdAt: 1, cwd: scratch, isSeeded: false};
    const write = await persistence.create(header);
    await write.flush(); await write.close();
    const artifact = persistence.locate(header).path;
    check((await fs.stat(artifact)).size > 0, 'first save missing: ' + compression);
    const before = await fs.readFile(artifact);
    const read = await persistence.open(header.id, 'read');
    check((await read.read()).events.length === 0, 'reopen failed: ' + compression); await read.close();
    const resume = await persistence.open(header.id, 'write'); await resume.flush(); await resume.close();
    check((await fs.readFile(artifact)).equals(before), 'resume changed history');
    check(!(await fs.readdir(path.dirname(artifact))).some(name => name.endsWith('.tmp')), 'successful copy left stage');
  }
  setLinkError('ENOSPC');
  const context = new Context();
  const persistence = new Persistence(context, {root: path.join(scratch, 'full'), compression: 'zstd'});
  const header = {version: 4, id: 'disk-full', createdAt: 1, cwd: scratch, isSeeded: false};
  const failed = await persistence.create(header);
  await assert.rejects(failed.flush(), {code: 'ENOSPC'}); checks++;
  await failed.close().catch(() => {});
  check(await fs.stat(persistence.locate(header).path).catch(() => null) === null, 'disk full published a file');
  check(!(await fs.readdir(path.dirname(persistence.locate(header).path))).some(name => name.endsWith('.tmp')), 'failure left a stage');
  console.log(`Android session publication: ${checks} checks; real compressed/plain first save, reopen/resume, EACCES, exclusive collision, write denial, and disk errors.`);
} finally {
  Object.defineProperty(process, 'platform', {value: originalPlatform});
  await fs.rm(probe, {force: true}); await fs.rm(scratch, {recursive: true, force: true});
}
