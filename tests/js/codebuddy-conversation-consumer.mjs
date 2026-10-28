// Production official SDK -> DSH ToolRuntime -> LocalFileSystem, local provider only.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const [runtime,assets]=process.argv.slice(2).map(value=>path.resolve(value));
const load=name=>import(pathToFileURL(path.join(runtime,'node_modules/@deepseek-ai',name,'lib/index.js')));
const {Context}=await load('cordis'),{SystemPrompt}=await load('dsh-system-prompt'),{ToolRuntime}=await load('dsh-tools');
const {LocalFileSystem}=await load('dsh-fs-local'),suite=await load('dsh-tool-fs');
const require=createRequire(import.meta.url),cli=require('../../payload/codebuddy-cli.cjs'),sdk=await cli.prepare(assets),original=sdk.query;
const directory=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-codebuddy-conversation-'));
const ctx=new Context();new SystemPrompt(ctx,{});const tools=new ToolRuntime(ctx);
new LocalFileSystem(ctx,{cwd:directory,diffBasisMaxBytes:65536});
suite.apply(ctx,suite.Config({readImage:false}));
const target=path.join(directory,'conversation.txt'),requests=[];
let checks=0,mode='write';const check=(ok,label)=>{assert(ok,label);checks++;};
const server=http.createServer(async(request,response)=>{
 let raw='';for await(const chunk of request)raw+=chunk;
 let body;try{body=JSON.parse(raw);}catch{}
 if(!Array.isArray(body?.messages)){response.writeHead(200,{'Content-Type':'application/json'});response.end('{"data":[]}');return;}
 requests.push(body);
 if(mode==='cancel'){response.writeHead(200,{'Content-Type':'text/event-stream'});response.flushHeaders();return;}
 const tool=['write','read'].includes(mode),name=body.tools?.find(item=>item.function.name.endsWith('_'+mode))?.function.name;
 const args=mode==='write'?{file_path:target,content:'Synthetic round trip'}:{file_path:target};
 response.writeHead(200,{'Content-Type':'text/event-stream'});
 const delta=tool?{role:'assistant',tool_calls:[{index:0,id:'synthetic-'+mode,type:'function',function:{name,arguments:JSON.stringify(args)}}]}:{role:'assistant',content:'Verified reply'};
 for(const value of [{choices:[{index:0,delta,finish_reason:null}]},{choices:[{index:0,delta:{},finish_reason:tool?'tool_calls':'stop'}],usage:{prompt_tokens:1000,completion_tokens:2,total_tokens:1002,prompt_cache_hit_tokens:900,prompt_cache_miss_tokens:100}}])
  response.write('data: '+JSON.stringify({id:'synthetic',model:'deepseek-v4.1-flash',object:'chat.completion.chunk',...value})+'\n\n');
 response.end('data: [DONE]\n\n');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const deadline=new AbortController(),timer=setTimeout(()=>deadline.abort(),90000);
const context={systemPrompt:'Synthetic DSH context',messages:Array.from({length:60},(_,index)=>({role:index%2?'assistant':'user',content:[{type:'text',text:'VISIBLE-HISTORY-'+index}]})),tools:tools.schemas().filter(tool=>['write','read'].includes(tool.name))};
async function consume(options={},input=context){const chunks=[];for await(const chunk of cli.stream({model:'deepseek-v4.1-flash',reasoningEffort:'max',purpose:'offline-regression',signal:deadline.signal,...options},input,{root:assets,apiKey:'synthetic-local-provider-only',idleTimeoutMs:15000}))chunks.push(chunk);return chunks;}
try{
 sdk.query=args=>{args.options.env={...args.options.env,CODEBUDDY_BASE_URL:'http://127.0.0.1:'+server.address().port};return original(args);};
 for(const name of ['write','read']){
  mode=name;const out=await consume(),call=out.find(chunk=>chunk.block?.type==='tool-call')?.block;
  check(out.at(-1)?.reason.kind==='tool-calls'&&call?.name===name,'actual CLI requests DSH '+name);
  if(name==='write')check(!await fs.stat(target).then(()=>true,()=>false),'CLI has not written the file itself');
  const result=await tools.execute({name:call.name,arguments:JSON.parse(call.arguments),callId:call.id,signal:deadline.signal});
  check(!result.isError,'actual DSH '+name+' executes successfully');
  context.messages.push({role:'assistant',content:[call]},{role:'tool',toolCallId:call.id,content:result.content});
  check(await fs.readFile(target,'utf8')==='Synthetic round trip','DSH filesystem contains complete bytes');
 }
 mode='reply';let out=await consume();
 check(out.at(-1)?.reason.kind==='stop'&&out.filter(chunk=>chunk.type==='text-delta').map(chunk=>chunk.text).join('')==='Verified reply','tool results lead to an ordinary final reply');
 const sent=JSON.stringify(requests.at(-1).messages);
 check(context.messages.slice(0,60).every((_,index)=>sent.includes('VISIBLE-HISTORY-'+index)),'long conversation retains all visible turns');
 check(sent.includes('synthetic-write')&&sent.includes('synthetic-read')&&sent.includes('Synthetic round trip'),'both actual DSH tool results return through the official SDK');
 const beforeLong=requests.length,longText=' context'.repeat(350000)+' END-OF-LARGE-CONTEXT';
 out=await consume({}, {messages:[{role:'user',content:[{type:'text',text:longText}]}],tools:[]});
 check(out.at(-1)?.reason.kind==='stop','the actual unmodified CLI accepts the large context through the production 1M route');
 check(requests.length===beforeLong+1&&JSON.stringify(requests.at(-1).messages).includes(longText),'large input reaches the synthetic provider in full, without a preliminary compaction request');
 mode='cancel';const abort=new AbortController(),before=requests.length,pending=consume({signal:abort.signal});
 for(let n=0;n<1000&&requests.length===before;n++)await new Promise(resolve=>setTimeout(resolve,10));
 check(requests.length===before+1,'cancel test reaches the local provider exactly once');
 abort.abort();out=await pending;check(out.at(-1)?.reason.kind==='aborted','actual SDK cancellation settles');
 const count=requests.length;await new Promise(resolve=>setTimeout(resolve,200));
 check(requests.length===count,'cancelled request is not replayed');
 mode='reply';out=await consume();check(out.at(-1)?.reason.kind==='stop','explicit send after cancellation completes with a fresh session');
 // Compression/title tasks preserve their append instructions and can run after
 // a long conversation; this verifies transport, not model summarization quality.
 out=await consume({purpose:'session-compact'},{messages:[{role:'user',content:[{type:'text',text:'Synthetic compact task'}]}],systemPrompt:'SYNTHETIC-COMPACT-INSTRUCTION',tools:[]});
 check(out.at(-1)?.reason.kind==='stop'&&JSON.stringify(requests.at(-1).messages).includes('SYNTHETIC-COMPACT-INSTRUCTION'),'auxiliary compact instructions reach the official SDK');
 const png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII=';
 out=await consume({}, {messages:[{role:'user',content:[{type:'text',text:'Synthetic image transport'},{type:'image',mimeType:'image/png',data:png}]}],tools:[]});
 check(out.at(-1)?.reason.kind==='stop','official SDK accepts the DSH image projection');
 check(JSON.stringify(requests.at(-1).messages).includes(png),'image bytes reach the local provider without being replaced by a text-only placeholder');
 console.log('CodeBuddy conversation: '+checks+' actual SDK/DSH tool, long history, cancellation, resend and compact-transport checks passed; synthetic local provider only.');
}finally{
 clearTimeout(timer);deadline.abort();cli.clearWarm();sdk.query=original;
 server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
 await fs.rm(directory,{recursive:true,force:true});
}
