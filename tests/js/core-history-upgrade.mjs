// Persist real history with the previous kernel, then read and extend it with the new one.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {pathToFileURL,fileURLToPath} from 'node:url';

if(process.argv[2]==='--worker'){
 const [phase,runtime,scratch]=process.argv.slice(3);
 if(process.platform==='darwin')Object.defineProperty(process,'platform',{value:'android'});
 const load=name=>import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai',name,'lib/index.js')));
 const {Context}=await load('cordis'),persistence=await load('dsh-session-persistence-jsonl'),{createUserMessage}=await load('dsh-llm');
 let checks=0;
 for(const compression of ['none','zstd']){
  const ctx=new Context(),root=path.join(scratch,compression),expected=path.join(scratch,compression+'.json');
  await ctx.plugin(persistence,{root,compression}).await();const store=ctx.sessionPersistence;
  const header={version:4,id:'history-before-upgrade',createdAt:42,cwd:scratch,isSeeded:false};
  if(phase==='before'){
   const writer=await store.create(header);
   await writer.append(['Original request','Original follow-up'].map((text,seq)=>({seq,time:100+seq,type:'user/message',surfaceOp:'append',data:createUserMessage({source:{kind:'user'},content:[{type:'text',text}]})})));
   await writer.flush();await writer.close();
   const reader=await store.open(header.id,'read'),saved=await reader.read();assert.equal(saved.events.length,2);await fs.writeFile(expected,JSON.stringify(saved));await reader.close();
  }else{
   const artifact=store.locate(header).path,bytes=await fs.readFile(artifact),hash=crypto.createHash('sha256').update(bytes).digest('hex');
   const reader=await store.open(header.id,'read');assert.deepEqual(await reader.read(),JSON.parse(await fs.readFile(expected,'utf8')));checks++;
   await reader.close();assert.equal(crypto.createHash('sha256').update(await fs.readFile(artifact)).digest('hex'),hash);checks++;
   const writer=await store.open(header.id,'write');
   await writer.append([{seq:2,time:102,type:'user/message',surfaceOp:'append',data:createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Continued after upgrade'}]})}]);
   await writer.flush();await writer.close();
   const reopened=await store.open(header.id,'read'),history=await reopened.read();await reopened.close();
   assert.deepEqual(history.events.slice(0,2),JSON.parse(await fs.readFile(expected,'utf8')).events);checks++;
   assert.equal(history.events[2].data.content[0].text,'Continued after upgrade');checks++;
  }
  await ctx.fiber.dispose();
 }
 console.log(JSON.stringify({checks}));
}else{
 const [previous,current]=process.argv.slice(2).map(value=>path.resolve(value)),scratch=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-history-upgrade-'));
 try{
  let checks=0;
  for(const [phase,runtime] of [['before',previous],['after',current]]){
   const result=spawnSync(process.execPath,['--expose-internals',fileURLToPath(import.meta.url),'--worker',phase,runtime,scratch],{encoding:'utf8',timeout:30000});
   assert.equal(result.status,0,result.stderr);checks+=JSON.parse(result.stdout.trim().split('\n').at(-1)).checks;
  }
  console.log('Kernel history upgrade: '+checks+' previous-kernel plain/compressed history, read-only byte preservation, resume and append checks passed. Synthetic messages only.');
 }finally{await fs.rm(scratch,{recursive:true,force:true});}
}
