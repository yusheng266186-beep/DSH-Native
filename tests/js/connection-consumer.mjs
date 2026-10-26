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
 }else{
  check(connection.state.getSnapshot()==='disconnected'&&calls===0,'remote connections retain offline suspension');
  browser.dispatchEvent(new Event('online'));await sleep(30);
  check(connection.state.getSnapshot()==='connected'&&calls===1,'remote connections resume normally when online');
 }
 loop.stop();unregister();for(const cleanup of cleanups)cleanup?.();
 check(connection.state.getSnapshot()===undefined,'dispose releases connection state');
}
delete globalThis.window;delete globalThis.document;
console.log('Actual Connection service: '+checks+' local/remote network and lifecycle checks passed; no model or device.');
