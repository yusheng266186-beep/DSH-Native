// Exercise real Web startup, private compile-cache flushing and the shipped paint probe.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {chromium} from 'playwright';

const runtime=path.resolve(process.argv[2]),script=await fs.readFile(process.argv[3],'utf8');
const scratch=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-startup-consumer-'));
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let core,browser,checks=0;
const check=(value,why)=>{assert(value,why);checks++;};
async function stop(signal='SIGTERM') {
 if(!core||core.exitCode!==null)return;
 const exited=new Promise(resolve=>core.once('exit',resolve));core.kill(signal);
 const timer=setTimeout(()=>core.kill('SIGKILL'),3000);await exited;clearTimeout(timer);
}
async function boot(cache) {
 const started=Date.now();let output='';
 const env={...process.env,HOME:scratch,DSH_HOME:path.join(scratch,'home'),DSH_NATIVE_APP_ROOT:scratch,CODEBUDDY_API_KEY:'',DEEPSEEK_API_KEY:''};
 if(cache)env.NODE_COMPILE_CACHE=path.join(scratch,'private-cache');else delete env.NODE_COMPILE_CACHE;
 core=spawn(process.execPath,['--expose-internals',path.join(runtime,'lib/bin.js'),'--patch',path.join(scratch,'native.yml'),'--profile','web','--no-open','--port','0'],{cwd:scratch,env,stdio:['ignore','pipe','pipe']});
 const collect=bytes=>output=(output+bytes).slice(-48000);core.stdout.on('data',collect);core.stderr.on('data',collect);
 let url;for(let i=0;i<600;i++){
  url=output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];if(url)break;
  assert.equal(core.exitCode,null,'actual startup exited: '+output.replace(/token=\S+/g,'token=REDACTED'));await sleep(100);
 }
 check(!!url,'actual Web profile starts with the native plugin');return {url,ms:Date.now()-started};
}
async function cacheEntries(directory) {
 let count=0;for(const entry of await fs.readdir(directory,{withFileTypes:true}).catch(()=>[])){
  if(entry.isDirectory())count+=await cacheEntries(path.join(directory,entry.name));else count++;
 }return count;
}
try {
 await fs.symlink(runtime,path.join(scratch,'dsh'),'dir');await fs.mkdir(path.join(scratch,'home'));
 for(const name of ['android-tools.cjs','android-bridge.cjs'])await fs.copyFile(new URL('../../payload/'+name,import.meta.url),path.join(scratch,name));
 await fs.writeFile(path.join(scratch,'native.yml'),"- insert:\n    - id: android-startup-test\n      name: '"+path.join(scratch,'android-tools.cjs').replaceAll("'","''")+"'\n");
 const plain=await boot(false);await stop();
 const first=await boot(true);
 for(let n=0;n<120&&await cacheEntries(path.join(scratch,'private-cache'))<20;n++)await sleep(100);
 check(core.exitCode===null&&await cacheEntries(path.join(scratch,'private-cache'))>=20,'production native plugin flushes many modules while core is still alive');
 await stop('SIGKILL');
 const warm=await boot(true);
 browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:393,height:830},isMobile:true,hasTouch:true});
 await context.grantPermissions(['local-network-access'],{origin:new URL(warm.url).origin});
 const page=await context.newPage(),messages=[];page.on('console',message=>{if(message.text().startsWith('[dsh-startup]'))messages.push(message.text());});
 await page.goto(warm.url);await page.evaluate(script);await page.waitForFunction(()=>window.__dshLocalConnectionState==='connected');
 try { await page.waitForFunction(()=>document.querySelector('[data-slot="settings.launcher"] button')?.getClientRects().length,{},{timeout:10000}); }
 catch(error) {
  console.log('Startup shell anchors',await page.locator('body').evaluate(el=>[...el.querySelectorAll('[data-slot],[role],h1,h2')].map(node=>({tag:node.tagName,slot:node.getAttribute('data-slot'),role:node.getAttribute('role'),visible:!!node.getClientRects().length}))));
  if(process.env.DSH_STARTUP_EVIDENCE)await page.screenshot({path:process.env.DSH_STARTUP_EVIDENCE});throw error;
 }
 for(let n=0;n<100&&!messages.length;n++)await sleep(20);
 check(messages.length===1&&messages[0]==='[dsh-startup] painted 17','real mounted WebUI emits exactly one paint signal');
 await page.evaluate(script);await sleep(100);check(messages.length===1,'repeated onPageFinished injection does not duplicate completion');
 // A blank/hidden document cannot fake readiness, and a stale double-frame callback is discarded.
 await page.goto('about:blank');messages.length=0;
 await page.evaluate(script);await sleep(100);check(messages.length===0,'no shell means no paint signal');
 await page.evaluate(()=>{const node=document.createElement('button');node.dataset.slot='settings.launcher';node.style.display='none';document.body.append(node);});
 await sleep(100);check(messages.length===0,'hidden shell is not a visible first frame');
 await page.evaluate(()=>{document.querySelector('button').style.display='';});
 for(let n=0;n<100&&!messages.length;n++)await sleep(20);check(messages.length===1,'late visible shell finishes without another document load');
 await page.evaluate(script.replace('generation=17','generation=18'));await page.evaluate(script.replace('generation=17','generation=19'));
 await sleep(100);check(!messages.includes('[dsh-startup] painted 18')&&messages.includes('[dsh-startup] painted 19'),'old animation-frame callback cannot complete the next generation');
 await page.goto('about:blank');messages.length=0;
 await page.evaluate(()=>Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'hidden'}));
 await page.evaluate(script);await page.evaluate(()=>{document.body.innerHTML='<button data-slot="settings.launcher">Settings</button>';window.dispatchEvent(new Event('dsh-native-resume'));});
 await sleep(100);check(messages.length===0,'background does not claim a painted foreground shell');
 await page.evaluate(()=>{Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'visible'});document.dispatchEvent(new Event('visibilitychange'));});
 for(let n=0;n<100&&!messages.length;n++)await sleep(20);check(messages.length===1,'foreground visibility resumes paint detection even when the shell mounted in the background');
 console.log('Startup consumer: '+checks+' checks passed; actual core URL readiness (desktop, not phone): uncached '+plain.ms+'ms, initial cache '+first.ms+'ms, reused cache '+warm.ms+'ms.');
}finally {
 await browser?.close();await stop();await fs.rm(scratch,{recursive:true,force:true});
}
