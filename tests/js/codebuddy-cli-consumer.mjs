// Production adapter, pinned official SDK, protocol fixtures and Android platform boundary.
// All credentials and model replies are synthetic. SDK initialization may read metadata; no model prompt is sent.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
const root=path.resolve(process.argv[2]),require=createRequire(import.meta.url),cli=require('../../payload/codebuddy-cli.cjs');
let checks=0;const check=(value,label)=>{assert(value,label);checks++;};
const sdk=await cli.prepare(root),sdkFile=sdk.requireSdk.resolve('@tencent-ai/agent-sdk'),queryModule=sdk,original=queryModule.query;
const context={systemPrompt:'DSH assembled agent persona',messages:[{role:'user',content:[{type:'text',text:'Synthetic request'}]}],tools:[{name:'write',description:'Write a workspace file',parameters:{type:'object',properties:{path:{type:'string'},content:{type:'string'}},required:['path','content'],additionalProperties:false}}]};
let latest;const keepAlive=setInterval(()=>{},2000);
function fixture(messages){queryModule.query=args=>{latest=args;return (async function*(){yield* messages;})();};}
async function consume(options={},ctx=context){const out=[];for await(const chunk of cli.stream({model:'deepseek-v4.1-flash',reasoningEffort:'max',...options},ctx,{apiKey:'synthetic-key-do-not-log',root,idleTimeoutMs:1000}))out.push(chunk);return out;}
try{
 fixture([{type:'stream_event',event:{type:'message_start',message:{usage:{input_tokens:10,cache_read_input_tokens:4}}}},{type:'stream_event',event:{type:'content_block_start',index:0,content_block:{type:'text',text:''}}},{type:'stream_event',event:{type:'content_block_delta',index:0,delta:{type:'text_delta',text:'Synthetic reply'}}},{type:'stream_event',event:{type:'content_block_stop',index:0}},{type:'assistant',message:{content:[{type:'text',text:'Synthetic reply'}],usage:{output_tokens:3}}},{type:'result',subtype:'success',is_error:false,usage:{input_tokens:10,output_tokens:3,cache_read_input_tokens:4}}]);
 let out=await consume();check(out.filter(c=>c.type==='text-delta').map(c=>c.text).join('')==='Synthetic reply','streaming text is not duplicated by the full message');check(out.at(-1).reason.kind==='stop','success terminates');check(out.at(-2).usage.cacheReadTokens===4&&out.at(-2).usage.inputTokens===10,'usage remains disjoint');
 check(latest.options.effort==='max'&&latest.options.tools.length===0&&!latest.options.systemPrompt,'explicit CLI agent mode uses official persona, requested effort and no built-in tools');check(latest.options.settingSources.length===0&&latest.options.strictMcpConfig&&!latest.options.persistSession,'no inherited plugins, hooks or extra transcript');
 check(latest.options.env.CODEBUDDY_API_KEY==='synthetic-key-do-not-log'&&!latest.options.env.CODEBUDDY_CUSTOM_HEADERS,'key is private SDK environment, without client spoofing');
 check(latest.options.env.NODE_COMPILE_CACHE===path.join(root,'.codebuddy-home','compile-cache'),'compiled code cache stays in the private non-shareable CLI home');
 const input=[];for await(const item of latest.prompt)input.push(item);check(!JSON.stringify(input).includes('synthetic-key-do-not-log')&&JSON.stringify(input).includes('Synthetic request'),'prompt preserves user context without credential');
 fixture([{type:'assistant',message:{content:[{type:'tool_use',id:'synthetic-call',name:'mcp__dsh__tool_0_write',input:{path:'note.txt',content:'synthetic'}}],usage:{input_tokens:2,output_tokens:1}}}]);
 out=await consume();const tool=out.find(c=>c.type==='block-end').block;check(tool.name==='write'&&JSON.parse(tool.arguments).path==='note.txt'&&out.at(-1).reason.kind==='tool-calls','real tool names and arguments return to the DSH loop');
 const {Client}=sdk.requireSdk('@modelcontextprotocol/sdk/client/index.js'),{InMemoryTransport}=sdk.requireSdk('@modelcontextprotocol/sdk/inMemory.js');
 let captured,connected;
 queryModule.query=args=>{latest=args;return (async function*(){const pair=InMemoryTransport.createLinkedPair();connected=new Client({name:'fixture',version:'1'});await args.options.mcpServers.dsh.instance.connect(pair[1]);await connected.connect(pair[0]);captured=await connected.listTools();const result=await connected.callTool({name:captured.tools[0].name,arguments:{path:'never-executed',content:'synthetic'}});check(result.isError,'SDK cannot execute a host tool itself');yield {type:'result',subtype:'success',is_error:false};})();};
 await consume();check(JSON.stringify(captured.tools[0].inputSchema)===JSON.stringify(context.tools[0].parameters),'MCP preserves the original JSON schema');await connected.close();
 fixture([{type:'assistant',message:{content:[{type:'tool_use',id:'bad',name:'mcp__other__Bash',input:{}}]}}]);out=await consume();check(out.at(-1).reason.kind==='error'&&!out.some(c=>c.block?.type==='tool-call'),'unknown tools cannot escape the host tool registry');
 fixture([{type:'assistant',error:'api_error',message:{content:[{type:'text',text:'401 synthetic-key-do-not-log'}]}}]);out=await consume();check(!JSON.stringify(out).includes('synthetic-key-do-not-log')&&out.at(-1).reason.failure.message.includes('authentication'),'API failures never stream credentials as assistant text');
 fixture([{type:'result',subtype:'error_during_execution',is_error:true,result:'11128 unapproved channel'}]);out=await consume();check(out.at(-1).reason.failure.message.includes('11128'),'official CLI refusal stays visible');
 fixture([{type:'result',subtype:'success',is_error:false}]);await consume({reasoningEffort:'off',purpose:'session-title'});check(latest.options.thinking.type==='disabled'&&latest.options.systemPrompt.append===context.systemPrompt,'off and auxiliary-task instructions retain their semantics');
 const projected=cli.requestPrompt({messages:[{role:'user',content:[{type:'image',data:'aGVsbG8=',mimeType:'image/png'}]}]});check(cli.requestPrompt({messages:[{role:'assistant',content:[{type:'toolCall',arguments:{usage:'keep',timestamp:123}}]}]})[0].text.includes('\"usage\":\"keep\"'),'tool argument keys are never stripped');
 check(projected[1].source.data==='aGVsbG8='&&!projected[0].text.includes('aGVsbG8='),'image bytes use SDK image blocks');
 const controller=new AbortController();queryModule.query=args=>(async function*(){await new Promise(resolve=>args.options.abortController.signal.addEventListener('abort',resolve,{once:true}));throw new Error('cancelled');})();
 const pending=consume({signal:controller.signal});setTimeout(()=>controller.abort(),30);out=await pending;check(out.at(-1).reason.kind==='aborted','caller cancellation settles the CLI request');
 out=await consume();check(out.at(-1).reason.failure.code==='LLM_STREAM_IDLE_TIMEOUT','an unresponsive CLI has a bounded idle deadline');
 // Exercise the production prewarm lifecycle with a protocol client, including
 // credential/config changes. No session is reused after a user message.
 let noToolsTurn=false;const sessions=[],frames=[{type:'assistant',message:{content:[{type:'tool_use',name:'mcp__dsh__tool_0_write',id:'warm-tool',input:{}}]}},{type:'result',subtype:'success',is_error:false}];
 const protocol={requireSdk:sdk.requireSdk,unstable_v2_createSession:settings=>{
  const session={settings,sent:[],closed:false,connected:false,connect:async()=>{session.connected=true;},getAvailableModes:async()=>[],send:async message=>{session.sent.push(message);},stream:async function*(){yield sessions.indexOf(session)===0&&!noToolsTurn?frames[0]:frames[1];},close:()=>{session.closed=true;}};
  sessions.push(session);return session;
 }};
 function request(credential='synthetic-one',model='deepseek-v4.1-flash',signal=new AbortController()){
  return {hostTools:[{name:'tool_0_write',description:'Synthetic write',inputSchema:context.tools[0].parameters}],prompt:(async function*(){yield {type:'user',message:{role:'user',content:'synthetic context'}};})(),options:{model,env:{CODEBUDDY_API_KEY:credential},mcpServers:{},abortController:signal}};
 }
 let preparedQuery=cli.sessionQuery(protocol,request());await preparedQuery.next();await preparedQuery.return();
 check(sessions.length===2&&sessions[0].closed&&sessions[1].connected&&sessions[1].sent.length===0,'a tool turn boots one fresh standby without sending a model prompt');
 await Array.fromAsync(cli.sessionQuery(protocol,request()));check(sessions.length===3&&sessions[1].sent.length===1&&sessions[1].closed&&sessions[2].sent.length===0,'next matching request claims the standby exactly once and prepares only a fresh next session');
 cli.clearWarm();sessions.length=0;preparedQuery=cli.sessionQuery(protocol,request());await preparedQuery.next();await preparedQuery.return();const previous=sessions[1];
 await Array.fromAsync(cli.sessionQuery(protocol,request('synthetic-other')));check(previous.closed&&previous.sent.length===0&&sessions[2].sent.length===1,'credential change discards the standby without sending data to it');
 cli.clearWarm();sessions.length=0;preparedQuery=cli.sessionQuery(protocol,request());await preparedQuery.next();await preparedQuery.return();const differentModel=sessions[1];
 await Array.fromAsync(cli.sessionQuery(protocol,request('synthetic-one','other-model')));check(differentModel.closed&&differentModel.sent.length===0,'model changes cannot claim another configuration standby');
 cli.clearWarm();sessions.length=0;const cancellation=new AbortController();preparedQuery=cli.sessionQuery(protocol,request('synthetic-one','deepseek-v4.1-flash',cancellation));await preparedQuery.next();cancellation.abort();await preparedQuery.return();check(sessions.length===1&&sessions[0].closed,'cancellation creates no standby');
 cli.clearWarm();sessions.length=0;const waitingProtocol={...protocol,unstable_v2_createSession:settings=>{const session=protocol.unstable_v2_createSession(settings);if(sessions.length===2)session.getAvailableModes=()=>new Promise(()=>{});return session;}};
 preparedQuery=cli.sessionQuery(waitingProtocol,request());await preparedQuery.next();await preparedQuery.return();const waiting=sessions[1],cancelWaiting=new AbortController(),waitingQuery=cli.sessionQuery(waitingProtocol,request('synthetic-one','deepseek-v4.1-flash',cancelWaiting));const pendingWarm=waitingQuery.next();setTimeout(()=>cancelWaiting.abort(),20);await assert.rejects(pendingWarm,/ABORTED/);check(waiting.closed&&waiting.sent.length===0,'cancellation interrupts an unresponsive standby initialization before any prompt');
 cli.clearWarm();
 cli.clearWarm();sessions.length=0;noToolsTurn=true;
 await Array.fromAsync(cli.sessionQuery(protocol,request()));check(sessions.length===2&&sessions[1].connected&&!sessions[1].sent.length,'a completed normal reply also prepares the next quick follow-up without a model prompt');
 cli.clearWarm();sessions.length=0;const auxiliary=request();auxiliary.standby=false;
 await Array.fromAsync(cli.sessionQuery(protocol,auxiliary));check(sessions.length===1&&sessions[0].closed,'connection checks and auxiliary calls leave no idle child');
 const projectedHistory=cli.requestPrompt({messages:[{role:'assistant',content:[{type:'reasoning',text:'PRIVATE-OLD-REASONING'},{type:'text',text:'Keep visible answer'},{type:'tool-call',arguments:{reasoning:'KEEP-ARGUMENT'}}]},{role:'user',content:[{type:'text',text:'Keep user reasoning request'}]}]})[0].text;
 check(!projectedHistory.includes('PRIVATE-OLD-REASONING')&&projectedHistory.includes('Keep visible answer')&&projectedHistory.includes('KEEP-ARGUMENT')&&projectedHistory.includes('Keep user reasoning request'),'only prior assistant reasoning is omitted from the CLI projection; user text and tool arguments remain intact');
 const manifest=JSON.parse(await fs.readFile(path.join(root,'codebuddy-sdk-manifest.json'),'utf8'));
 const runtime=path.join(root,'.codebuddy-sdk-'+manifest.sdk+'-'+manifest.sha256.slice(0,16)),sandbox=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-sdk-boundary-'));
 try{
  check(manifest.layout==='headless-v1'&&manifest.bytes<12*1024*1024&&manifest.unpacked_bytes<65*1024*1024,'mobile bundle stays within its headless size budget');
  for(const removed of ['dist/web-ui','dist/codebuddy-lite-wb.mjs','dist-server','vendor/ripgrep','vendor/genie-trash','bin/native']){
   check(!await fs.stat(path.join(runtime,'node_modules/@tencent-ai/agent-sdk/cli',removed)).then(()=>true,()=>false),'unused desktop or external executable is absent: '+removed);
  }
  const preload=path.join(sandbox,'android.mjs');await fs.writeFile(preload,"Object.defineProperty(process,'platform',{value:'android'});\n");
  const startup=spawnSync(process.execPath,['--expose-internals','--import',preload,path.join(runtime,'node_modules/@tencent-ai/agent-sdk/cli/bin/codebuddy'),'--version'],{encoding:'utf8',timeout:20000});check(startup.status===0&&startup.stdout.trim()==='2.176.0','unmodified CLI starts with Android platform selection (not a device test)');
  const runtimeSdk=sdk.requireSdk('@tencent-ai/agent-sdk'),initialized=runtimeSdk.unstable_v2_createSession({model:'deepseek-v4.1-flash',tools:[],mcpServers:{},settingSources:[],strictMcpConfig:true,persistSession:false,executable:process.execPath,cwd:sandbox,env:{HOME:sandbox,NODE_OPTIONS:'--import='+preload,CODEBUDDY_API_KEY:'synthetic-no-model-call',CODEBUDDY_AUTH_TOKEN:undefined,CODEBUDDY_CUSTOM_HEADERS:undefined,CODEBUDDY_INTERNET_ENVIRONMENT:'internal'}});
  const initDeadline=setTimeout(()=>initialized.close(),10000);try{await initialized.connect();const modes=await initialized.getAvailableModes();check(Array.isArray(modes)&&modes.length>0,'actual trimmed SDK initializes and reads mode metadata with Android platform selection, without sending a model prompt');}finally{clearTimeout(initDeadline);initialized.close();}
  await fs.copyFile(new URL('../../payload/codebuddy-cli.cjs',import.meta.url),path.join(sandbox,'codebuddy-cli.cjs'));await fs.writeFile(path.join(sandbox,'codebuddy-sdk-manifest.json'),JSON.stringify({...manifest,sha256:'0'.repeat(64)}));await fs.writeFile(path.join(sandbox,'codebuddy-sdk.tar.zst'),'synthetic corrupt archive');
  const invalid=spawnSync(process.execPath,[path.join(sandbox,'codebuddy-cli.cjs'),'--prepare'],{encoding:'utf8',timeout:10000});check(invalid.status!==0&&!invalid.stdout.includes('prepared'),'unverified SDK archives cannot be installed');
 }finally{await fs.rm(sandbox,{recursive:true,force:true});}
 console.log('CodeBuddy CLI transport: '+checks+' streaming, tools, schema, cancellation, deadline, privacy, bundle and Android-platform checks passed; no model prompts or device.');
}finally{cli.clearWarm();clearInterval(keepAlive);queryModule.query=original;}
