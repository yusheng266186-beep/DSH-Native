import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {pathToFileURL} from 'node:url';
const runtime=path.resolve(process.argv[2]);
const module=path.join(runtime,'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js');
const probe=path.join(path.dirname(module),'android-create-probe.mjs');
const scratch=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-file-create-'));
let checks=0;
const check=(ok,message)=>{assert(ok,message);checks++;};
try {
 const source=await fs.readFile(module,'utf8');
 assert(source.includes('__dshPublishLink(tempPath, absolutePath, linkFile'));
 await fs.writeFile(probe,source+'\nexport { writeFileAtomic };\n');
 const {writeFileAtomic}=await import(pathToFileURL(probe));
 const target=path.join(scratch,'new.txt');
 const refused=code=>async()=>{throw Object.assign(new Error('synthetic refusal'),{code});};
 for(const code of ['EACCES','EPERM','EXDEV','ENOSYS','EOPNOTSUPP','ENOTSUP','EMLINK']) {
  const internals={platform:'android',linkFile:refused(code)};
  await writeFileAtomic(target,'new complete content',0o600,undefined,internals,{displayPath:target});
  check(await fs.readFile(target,'utf8')==='new complete content',code+' cannot create');
  await assert.rejects(writeFileAtomic(target,'must not replace',0o600,undefined,internals,{displayPath:target}),/without reading/);checks++;
  check(await fs.readFile(target,'utf8')==='new complete content',code+' overwrote existing file');
  await fs.rm(target);
 }
 // A creator arriving after staging must also win over this guarded write.
 await assert.rejects(writeFileAtomic(target,'loser',0o600,undefined,{platform:'android',linkFile:refused('EACCES'),
  inspectTemp:()=>fs.writeFile(target,'concurrent winner')},{displayPath:target}),/without reading/);checks++;
 check(await fs.readFile(target,'utf8')==='concurrent winner','concurrent creator overwritten');await fs.rm(target);
 for(const code of ['ENOSPC','EIO']) {
  await assert.rejects(writeFileAtomic(target,'data',0o600,undefined,{platform:'android',linkFile:refused(code)},{displayPath:target}),/synthetic refusal/);checks++;
  check(await fs.stat(target).catch(()=>null)===null,'real failure published a file');
 }
 await assert.rejects(writeFileAtomic(target,'data',0o600,undefined,{platform:'android',linkFile:refused('EACCES'),copyFile:refused('ENOSPC')},{displayPath:target}),/synthetic refusal/);checks++;
 check(await fs.stat(target).catch(()=>null)===null,'copy failure published a file');
 await fs.writeFile(target,'old');await writeFileAtomic(target,'edited',0o600,undefined,{platform:'android'});
 check(await fs.readFile(target,'utf8')==='edited','ordinary replacement regressed');
 check(!(await fs.readdir(scratch)).some(name=>name.endsWith('.tmpdir')),'staging leak');
 console.log('Actual file create: '+checks+' checks; denied hard links, exclusive copy, existing/concurrent files, disk errors and ordinary replacement passed.');
}finally{await fs.rm(probe,{force:true});await fs.rm(scratch,{recursive:true,force:true});}
