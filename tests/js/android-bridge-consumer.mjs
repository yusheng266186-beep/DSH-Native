// Actual Cordis ToolRuntime -> shipped plugin/client -> production Java HTTP server.
// Android operations are synthetic; no device, clipboard contents or screenshot is accessed.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url),runtime=path.resolve(process.argv[2]),classes=path.resolve(process.argv[3]);
const scratch=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-bridge-consumer-'));let child,core,checks=0;
const check=(value,message)=>{assert(value,message);checks++;};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const load=name=>import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai',name,'lib/index.js')));
const cli=async(method,fields)=>{
 const process=spawn(globalThis.process.execPath,[path.join(scratch,'android-bridge.cjs'),method,JSON.stringify(fields)],{stdio:['ignore','pipe','pipe']});
 let output='',error='';process.stdout.on('data',chunk=>output+=chunk);process.stderr.on('data',chunk=>error+=chunk);
 const code=await new Promise(resolve=>process.once('exit',resolve));return {code,output,error};
};
try{
 for(const name of ['android-bridge.cjs','android-tools.cjs'])await fs.copyFile(new URL('../../payload/'+name,import.meta.url),path.join(scratch,name));
 await fs.symlink(runtime,path.join(scratch,'dsh'),'dir');
 child=spawn('java',['-cp',classes,'dev.dsh.nativeapp.NativeBridgeTest','--serve',scratch],{stdio:['ignore','pipe','pipe']});
 let ready='';child.stdout.on('data',data=>ready+=data);let failure='';child.stderr.on('data',data=>failure+=data);
 for(let i=0;i<200&&!ready.includes('READY');i++){assert.equal(child.exitCode,null,failure);await sleep(20);}check(ready.includes('READY'),'production Java bridge ready');
 const bridge=require(path.join(scratch,'android-bridge.cjs'));
 const clipboard=await bridge.invoke({method:'clipboard.get'});
 check(clipboard.ok&&clipboard.result.text==='synthetic clipboard','client reaches native clipboard operation');
 const write=await bridge.invoke({method:'clipboard.set',text:'synthetic text'});
 check(write.ok&&write.result.method==='clipboard.set','write command reaches authenticated native dispatch');
 const command=await cli('clipboard.get',{});check(command.code===0&&JSON.parse(command.output).result.hasText,'agent CLI works without credentials in arguments');
 const denied=await cli('notification.post',{title:'test',text:'synthetic'});
 check(denied.code===1&&JSON.parse(denied.output).error==='NOTIFICATIONS_DISABLED','native permission errors remain explicit through CLI');
 const invalid=await cli('clipboard.get',{text:'unexpected'});check(invalid.code===1&&JSON.parse(invalid.output).error==='INVALID_REQUEST','HTTP request validation keeps native error through CLI');
 const {Context}=await load('cordis'),{SystemPrompt}=await load('dsh-system-prompt'),{ToolRuntime}=await load('dsh-tools');
 const ctx=new Context(),prompt=new SystemPrompt(ctx,{}),tools=new ToolRuntime(ctx);
 await require(path.join(scratch,'android-tools.cjs')).apply(ctx);
 const tool=tools.get('android');check(!!tool&&tool.parameters.properties.method.enum.includes('screenshot'),'actual tool registry advertises native capabilities');
 const result=await tools.execute({name:'android',arguments:{method:'clipboard.get'},callId:'native-fixture',signal:new AbortController().signal});
 check(!result.isError&&result.value.text==='synthetic clipboard','actual ToolRuntime consumes real Java bridge response');
 const refused=await tools.execute({name:'android',arguments:{method:'notification.post',title:'test',text:'synthetic'},callId:'native-denied',signal:new AbortController().signal});
 check(refused.isError&&JSON.stringify(refused).includes('NOTIFICATIONS_DISABLED'),'native failure is an actual model tool error');
 // The real launcher must accept the exact insert patch used by MainActivity.
 const plugin=path.join(scratch,'android-tools.cjs');
 await fs.writeFile(path.join(scratch,'native.yml'),"- insert:\n    - id: android-native-test\n      name: '"+plugin.replaceAll("'","''")+"'\n");
 const home=path.join(scratch,'home');await fs.mkdir(home);
 core=spawn(process.execPath,['--expose-internals',path.join(runtime,'lib/bin.js'),'--patch',path.join(scratch,'native.yml'),'--profile','web','--no-open','--port','0'],{cwd:scratch,env:{...process.env,DSH_HOME:home,COMMANDCODE_API_KEY:'synthetic-fixture-only',DEEPSEEK_API_KEY:''},stdio:['ignore','pipe','pipe']});
 let output='';core.stdout.on('data',data=>output=(output+data).slice(-32000));core.stderr.on('data',data=>output=(output+data).slice(-32000));
 for(let i=0;i<450&&!output.match(/dsh web: http:\/\/127\.0\.0\.1:\d+/);i++){assert.equal(core.exitCode,null,'native plugin blocked actual Web profile startup: '+output.replace(/token=\S+/g,'token=REDACTED'));await sleep(100);}
 check(!!output.match(/dsh web: http:\/\/127\.0\.0\.1:\d+/),'actual core loads App native insert patch');
 check(!output.includes('android-native-test never started'),'native plugin started through production loader');
 const descriptor=path.join(scratch,'.native-bridge.json');const original=await fs.readFile(descriptor);
 await fs.chmod(descriptor,0o644);await assert.rejects(bridge.invoke({method:'capabilities'}),/BRIDGE_NOT_READY/);checks++;await fs.chmod(descriptor,0o600);
 const token=JSON.parse(original).token;
 check(!command.output.includes(token)&&!denied.output.includes(token)&&!invalid.error.includes(token),'native credentials never enter CLI results');
 await fs.writeFile(descriptor,JSON.stringify({...JSON.parse(original),token:'0'.repeat(64)}));
 const forbidden=await bridge.invoke({method:'clipboard.get'});
 check(!forbidden.ok&&forbidden.error==='FORBIDDEN'&&!JSON.stringify(forbidden).includes(token),'stale credentials keep authentication error without exposing or retrying action');
 await fs.writeFile(descriptor,original);
 const malformed=await tools.execute({name:'android',arguments:{method:'clipboard.get',text:'unexpected'},callId:'native-invalid',signal:new AbortController().signal});
 check(malformed.isError&&JSON.stringify(malformed).includes('INVALID_REQUEST'),'actual ToolRuntime retains request validation error');
 const abort=new AbortController();abort.abort();await assert.rejects(bridge.invoke({method:'screenshot'},{signal:abort.signal}),/CANCELLED/);checks++;
 // A real peer that never replies exercises the shipped 12-second deadline, without mocking timers.
 let slowRequests=0;const slow=http.createServer(request=>{slowRequests++;request.resume();});
 await new Promise(resolve=>slow.listen(0,'127.0.0.1',resolve));
 try{
  await fs.writeFile(descriptor,JSON.stringify({...JSON.parse(original),port:slow.address().port}));
  await assert.rejects(bridge.invoke({method:'clipboard.set',text:'synthetic'}),/BRIDGE_TIMEOUT/);checks++;
  check(slowRequests===1,'timed-out native mutation is never automatically retried');
 }finally{slow.closeAllConnections();await new Promise(resolve=>slow.close(resolve));await fs.writeFile(descriptor,original);}
 console.log('Android bridge consumer: '+checks+' checks; actual tool registry, native HTTP, CLI, permissions, cancellation, credentials and Web profile loader passed (synthetic Android actions).');
}finally{
 for(const process of [core,child])if(process&&process.exitCode===null){const exited=new Promise(resolve=>process.once('exit',resolve));process.kill('SIGTERM');await Promise.race([exited,sleep(4000).then(()=>process.kill('SIGKILL'))]);}
 await fs.rm(scratch,{recursive:true,force:true});
}
