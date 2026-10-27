// The actual patched Connection service, with lifecycle/network inputs only.
import assert from 'node:assert/strict';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const {installConnection}=await import(pathToFileURL(path.resolve(process.argv[2],'node_modules/@deepseek-ai/dsh-client-connection/lib/client.node.js')));
let checks=0;const check=(value,label)=>{assert(value,label);checks++;};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
for(const local of [true,false]){
 const browser=new EventTarget();browser.navigator={onLine:false};globalThis.window=browser;
 const doc=new EventTarget();doc.visibilityState='visible';globalThis.document=doc;
 let connection,calls=0;const cleanups=[];
 const ctx={provide(name,value){connection=value;},effect(callback){cleanups.push(callback());}};
 installConnection(ctx,{location:{hostname:local?'127.0.0.1':'remote.invalid'},recovery:{backoffBaseMs:1,backoffMaxMs:1}});
 const unregister=connection.registerGenerationSource(async(signal,ready)=>{calls++;ready({synthetic:true});await new Promise(resolve=>{if(signal.aborted)resolve();else signal.addEventListener('abort',resolve,{once:true});});});
 const loop=connection.start({});await sleep(30);
 if(local){
  check(connection.state.getSnapshot()==='connected'&&calls===1,'loopback connects even when Android reports no internet');
  browser.dispatchEvent(new Event('offline'));browser.dispatchEvent(new Event('dsh-native-resume'));await sleep(180);
  check(connection.state.getSnapshot()==='connected'&&calls===1,'healthy local generation survives offline/resume inputs');
  doc.visibilityState='hidden';browser.__dshLocalConnectionState='synthetic-stale';browser.dispatchEvent(new Event('dsh-native-resume'));
  check(browser.__dshLocalConnectionState==='synthetic-stale','native resume before document visibility does not report a foreground state');
  doc.visibilityState='visible';doc.dispatchEvent(new Event('visibilitychange'));
  check(browser.__dshLocalConnectionState==='connected'&&calls===1,'visible document republishes actual state without rebuilding a healthy generation');
 }else{
  check(connection.state.getSnapshot()==='disconnected'&&calls===0,'remote connections retain offline suspension');
  browser.dispatchEvent(new Event('online'));await sleep(30);
  check(connection.state.getSnapshot()==='connected'&&calls===1,'remote connections resume normally when online');
 }
 loop.stop();unregister();for(const cleanup of cleanups)cleanup?.();
 check(connection.state.getSnapshot()===undefined,'dispose releases connection state');
}
{
 const browser=new EventTarget();browser.navigator={onLine:true};globalThis.window=browser;
 const doc=new EventTarget();doc.visibilityState='visible';globalThis.document=doc;
 let connection,calls=0,lose;const cleanups=[];
 installConnection({provide(_name,value){connection=value;},effect(fn){cleanups.push(fn());}},
  {location:{hostname:'127.0.0.1'}});
 const unregister=connection.registerGenerationSource(async(signal,ready)=>{
  calls++;ready({synthetic:true});
  await new Promise((resolve,reject)=>{lose=()=>reject(new Error('synthetic generation lost'));signal.addEventListener('abort',resolve,{once:true});});
 });
 const loop=connection.start({}, {backoffBaseMs:10000,backoffMaxMs:10000});await sleep(30);
 doc.visibilityState='hidden';lose();await sleep(30);
 for(let i=0;i<20;i++)browser.dispatchEvent(new Event('dsh-native-resume'));
 await sleep(180);
 check(calls===1&&connection.state.getSnapshot()==='connecting','hidden app keeps existing bounded backoff without a reconnect storm');
 doc.visibilityState='visible';doc.dispatchEvent(new Event('visibilitychange'));
 for(let i=0;i<20;i++)browser.dispatchEvent(new Event('dsh-native-resume'));
 await sleep(230);
 check(calls===2&&connection.state.getSnapshot()==='connected','foreground shortens long backoff exactly once after actual generation loss');
 for(let i=0;i<20;i++){
  doc.visibilityState='hidden';doc.dispatchEvent(new Event('visibilitychange'));
  doc.visibilityState='visible';doc.dispatchEvent(new Event('visibilitychange'));
  browser.dispatchEvent(new Event('online'));browser.dispatchEvent(new Event('dsh-native-resume'));
 }
 await sleep(230);
 check(calls===2,'repeated background/network events preserve a healthy generation');
 loop.stop();unregister();for(const cleanup of cleanups)cleanup?.();
 browser.__dshLocalConnectionState='disposed';browser.dispatchEvent(new Event('dsh-native-resume'));
 await sleep(180);
 check(calls===2&&browser.__dshLocalConnectionState==='disposed','disposed listeners and timers cannot reconnect an old page');
}
delete globalThis.window;delete globalThis.document;
console.log('Actual Connection service: '+checks+' local/remote network and lifecycle checks passed; no model or device.');
