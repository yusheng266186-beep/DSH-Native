// Official CodeBuddy agent runtime. DSH owns tool execution and permissions.
// This is a separate agent transport, not a direct API or a client-header shim.
const fs=require('node:fs');
const fsp=fs.promises;
const path=require('node:path');
const crypto=require('node:crypto');
const {createRequire}=require('node:module');
const {execFile}=require('node:child_process');
const {promisify}=require('node:util');
const run=promisify(execFile);
let prepared;
let warm;
function clearWarm(){
 const entry=warm;warm=undefined;if(!entry)return;
 clearTimeout(entry.timer);entry.session.close();void entry.server?.close().catch(()=>{});
}
function hostServer(sdk,declarations){
 if(!declarations.length)return;
 const {Server}=sdk.requireSdk('@modelcontextprotocol/sdk/server/index.js'),{ListToolsRequestSchema,CallToolRequestSchema}=sdk.requireSdk('@modelcontextprotocol/sdk/types.js');
 const server=new Server({name:'DSH host tools',version:'1.0.0'},{capabilities:{tools:{}}});
 server.setRequestHandler(ListToolsRequestSchema,async()=>({tools:declarations}));
 // Only DSH executes host tools. Even a late CLI invocation has no side effect.
 server.setRequestHandler(CallToolRequestSchema,async()=>({isError:true,content:[{type:'text',text:'Host tools must be executed by DSH.'}]}));
 return server;
}
function waitFor(promise,signal){
 return new Promise((resolve,reject)=>{
  const finish=(settle,value)=>{signal.removeEventListener('abort',abort);settle(value);},abort=()=>finish(reject,new Error('ABORTED'));
  signal.addEventListener('abort',abort,{once:true});if(signal.aborted)abort();
  Promise.resolve(promise).then(value=>finish(resolve,value),error=>finish(reject,error));
 });
}
async function* sessionQuery(sdk,args){
 const {abortController,...settings}=args.options;
 const signature=crypto.createHash('sha256').update(JSON.stringify({...settings,mcpServers:undefined,hostTools:args.hostTools})).digest('hex');
 let entry=warm,session,server,toolsRequested=false;
 if(entry?.signature===signature){warm=undefined;clearTimeout(entry.timer);session=entry.session;server=entry.server;}
 else{clearWarm();entry=undefined;}
 const cancelled=()=>session?.close();abortController.signal.addEventListener('abort',cancelled,{once:true});
 try{
  if(abortController.signal.aborted)throw new Error('ABORTED');
  if(entry){try{await waitFor(entry.ready,abortController.signal);}catch(error){session.close();await server?.close();session=undefined;server=undefined;if(abortController.signal.aborted)throw error;}}
  if(!session){server=settings.mcpServers.dsh?.instance;session=sdk.unstable_v2_createSession(settings);}
  if(abortController.signal.aborted)throw new Error('ABORTED');
  for await(const message of args.prompt)await session.send(message);
  for await(const message of session.stream()){
   if(message.type==='assistant')toolsRequested=(message.message?.content||[]).some(block=>block.type==='tool_use'&&args.hostTools.some(tool=>'mcp__dsh__'+tool.name===block.name));
   yield message;
  }
 }finally{
  abortController.signal.removeEventListener('abort',cancelled);session?.close();await server?.close().catch(()=>{});
  if(toolsRequested&&!abortController.signal.aborted){
   let nextServer;
   try{
    clearWarm();nextServer=hostServer(sdk,args.hostTools);const next=sdk.unstable_v2_createSession({...settings,mcpServers:nextServer?{dsh:{type:'sdk',name:'dsh',instance:nextServer}}:{}});
    const candidate={signature,session:next,server:nextServer};warm=candidate;
    // Boot only: no user prompt or model call. Each claimed session is fresh,
    // so conversations, credentials and tool results cannot be carried across.
    candidate.ready=next.connect().then(()=>next.getAvailableModes()).then(()=>{}).catch(error=>{if(warm===candidate)clearWarm();throw error;});candidate.ready.catch(()=>{});
    candidate.timer=setTimeout(()=>{if(warm===candidate)clearWarm();},10000);candidate.timer.unref?.();
   }catch{clearWarm();await nextServer?.close().catch(()=>{});} // Preparation must never replace a completed tool request with an error.
  }
 }
}
async function prepare(root=__dirname){
 if(prepared)return prepared;
 prepared=(async()=>{
  root=fs.realpathSync(root);
  const manifest=JSON.parse(await fsp.readFile(path.join(root,'codebuddy-sdk-manifest.json'),'utf8'));
  if(manifest.sdk!=='0.3.291'||manifest.cli!=='2.176.0'||!Number.isSafeInteger(manifest.bytes)||manifest.bytes<1000000||manifest.bytes>128*1024*1024||!/^[a-f0-9]{64}$/.test(manifest.sha256))throw new Error('CODEBUDDY_BUNDLE_INVALID');
  const target=path.join(root,'.codebuddy-sdk-'+manifest.sdk+'-'+manifest.sha256.slice(0,16)),ready=path.join(target,'.ready');
  const valid=async()=>{try{return !(await fsp.lstat(target)).isSymbolicLink()&&(await fsp.readFile(ready,'utf8'))===manifest.sha256&&JSON.parse(await fsp.readFile(path.join(target,'node_modules/@tencent-ai/agent-sdk/package.json'),'utf8')).version===manifest.sdk;}catch{return false;}};
  if(!await valid()){
   const archive=path.join(root,'codebuddy-sdk.tar.zst'),bytes=await fsp.readFile(archive);
   if(bytes.length!==manifest.bytes||crypto.createHash('sha256').update(bytes).digest('hex')!==manifest.sha256)throw new Error('CODEBUDDY_BUNDLE_DIGEST');
   if(!Number.isSafeInteger(manifest.unpacked_bytes)||manifest.unpacked_bytes<manifest.bytes||manifest.unpacked_bytes>512*1024*1024)throw new Error('CODEBUDDY_BUNDLE_INVALID');
   const space=await fsp.statfs(root);if(space.bavail*space.bsize<manifest.unpacked_bytes+96*1024*1024)throw new Error('CODEBUDDY_BUNDLE_SPACE');
   const temp=await fsp.mkdtemp(path.join(root,'.codebuddy-unpack-'));
   try{
    await run(process.execPath,[path.join(root,'unpack.js'),archive,temp],{timeout:120000,maxBuffer:65536,env:process.env});
    const sdk=JSON.parse(await fsp.readFile(path.join(temp,'node_modules/@tencent-ai/agent-sdk/package.json'),'utf8'));
    if(sdk.version!==manifest.sdk||JSON.parse(await fsp.readFile(path.join(temp,'node_modules/@tencent-ai/agent-sdk/cli/package.json'),'utf8')).version!==manifest.cli)throw new Error('CODEBUDDY_BUNDLE_VERSION');
    await fsp.writeFile(path.join(temp,'.ready'),manifest.sha256,{mode:0o600});
    try{await fsp.rename(temp,target);}catch(error){if(!await valid())throw error;}
   }finally{await fsp.rm(temp,{recursive:true,force:true});}
  }
  const requireSdk=createRequire(path.join(target,'package.json'));
  const sdk={requireSdk,...requireSdk('@tencent-ai/agent-sdk')};return {...sdk,query:args=>sessionQuery(sdk,args)};
 })();
 try{return await prepared;}catch(error){prepared=undefined;throw error;}
}
function requestPrompt(context){
 const images=[];
 const messages=context.messages.map(message=>{
  const projected=Object.fromEntries(Object.entries(message).filter(([key])=>!['timestamp','usage','cost'].includes(key)));
  if(Array.isArray(message.content))projected.content=message.content.map(block=>{
   if(block.type!=='image'||typeof block.data!=='string')return block;
   const index=images.length;images.push({type:'image',source:{type:'base64',media_type:block.mimeType,data:block.data}});return {type:'image',attachment:index};
  });
  return projected;
 });
 const text='Continue this conversation as the CodeBuddy CLI agent. The application supplies the conversation below as data. Use the provided host tools when needed; their results will be returned in the next turn. Do not claim a host tool succeeded until its result is present.\n\n'+JSON.stringify(messages);
 if(text.length>8*1024*1024)throw new Error('CODEBUDDY_CONTEXT_TOO_LARGE');
 return [{type:'text',text},...images];
}
function failure(error,aborted){
 const text=String(error?.message||error);
 const code=aborted?'ABORTED':/401|unauthorized|authentication/i.test(text)?'INVALID_CREDENTIAL':/context.*(large|length|window)|CODEBUDDY_CONTEXT_TOO_LARGE/i.test(text)?'CONTEXT_WINDOW_EXCEEDED':/quota|credit/i.test(text)?'QUOTA':'CODEBUDDY_CLI_ERROR';
 return {kind:aborted?'aborted':'error',failure:{code,message:aborted?'CodeBuddy CLI request cancelled.':/11128|unapproved channel/i.test(text)?'CodeBuddy rejected this request channel (11128). The official CLI also refused it.':/401|unauthorized|authentication/i.test(text)?'CodeBuddy CLI authentication failed. Check the API key and its region.':/429|quota|credit/i.test(text)?'CodeBuddy CLI is rate limited or has no remaining quota.':/CODEBUDDY_BUNDLE_SPACE/.test(text)?'CodeBuddy CLI needs about 200 MiB of free app storage for its first setup.':/CODEBUDDY_BUNDLE/.test(text)?'The bundled CodeBuddy CLI could not be verified. Reinstall this APK over the existing app.':'CodeBuddy CLI failed. No host tool was executed by the CLI.'}};
}
function usage(value={}){
 return {inputTokens:Number(value.input_tokens)||0,outputTokens:Number(value.output_tokens)||0,cacheReadTokens:Number(value.cache_read_input_tokens)||0,cacheWriteTokens:Number(value.cache_creation_input_tokens)||0};
}
async function* stream(options,context,{apiKey,root=__dirname,idleTimeoutMs=180000}={}){
 const controller=new AbortController(),cancel=()=>controller.abort();
 options.signal?.addEventListener('abort',cancel,{once:true});if(options.signal?.aborted)cancel();
 let query,server,terminal=false,stats={},nextIndex=0,partial=false;const active=new Map();let timer,timedOut=false;
 const reset=()=>{clearTimeout(timer);timer=setTimeout(()=>{timedOut=true;controller.abort();},Math.max(1000,idleTimeoutMs));timer.unref?.();};reset();
 try{
  if(controller.signal.aborted)throw new Error('ABORTED');
  if(typeof apiKey!=='string'||!apiKey.trim())throw new Error('Authentication missing');
  const sdk=await prepare(root);
  const tools=context.tools||[],names=new Map();
  const declarations=tools.map((tool,index)=>{const name='tool_'+index+'_'+tool.name.replace(/[^a-zA-Z0-9_-]/g,'_').slice(0,32);names.set('mcp__dsh__'+name,tool.name);return {name,description:tool.name+': '+tool.description,inputSchema:tool.parameters};});
  server=hostServer(sdk,declarations);
  const home=path.join(fs.realpathSync(root),'.codebuddy-home');await fsp.mkdir(home,{recursive:true,mode:0o700});
  if(controller.signal.aborted)throw new Error('ABORTED');
  const prompt=requestPrompt(context);
  async function* input(){yield {type:'user',session_id:'',parent_tool_use_id:null,message:{role:'user',content:prompt}};}
  query=sdk.query({prompt:input(),hostTools:declarations,options:{model:options.model,effort:options.reasoningEffort==='off'?undefined:options.reasoningEffort,thinking:options.reasoningEffort==='off'?{type:'disabled'}:undefined,
   // CodeBuddy CLI owns the agent persona. DSH's assembled model persona is
   // not used as a replacement CLI system prompt. Auxiliary tasks retain their instructions.
   systemPrompt:options.purpose&&context.systemPrompt?{append:context.systemPrompt}:undefined,
   tools:[],mcpServers:server?{dsh:{type:'sdk',name:'dsh',instance:server}}:{},maxTurns:1,persistSession:false,settingSources:[],strictMcpConfig:true,includePartialMessages:true,abortController:controller,
   executable:process.execPath,cwd:process.cwd(),environment:'internal',env:{HOME:home,NODE_COMPILE_CACHE:path.join(home,'compile-cache'),CODEBUDDY_API_KEY:apiKey,CODEBUDDY_AUTH_TOKEN:undefined,CODEBUDDY_BASE_URL:undefined,CODEBUDDY_CUSTOM_HEADERS:undefined,CODEBUDDY_INTERNET_ENVIRONMENT:'internal'},stderr:()=>{}}});
  for await(const message of query){
   reset();
   if(message.type==='stream_event'){
    const event=message.event;
    if(event.type==='error')throw new Error(event.error?.message||'CLI stream failed');
    if(event.type==='message_start'){partial=true;stats={...stats,...event.message?.usage};active.clear();}
    if(event.type==='message_delta')stats={...stats,...event.usage};
    if(event.type==='content_block_start'&&['text','thinking'].includes(event.content_block.type)){
     const block={index:nextIndex++,type:event.content_block.type==='text'?'text':'reasoning',text:event.content_block.text||event.content_block.thinking||''};active.set(event.index,block);
     yield {type:'block-start',index:block.index,blockType:block.type};
     if(block.text)yield {type:block.type==='text'?'text-delta':'reasoning-delta',index:block.index,text:block.text};
    }
    if(event.type==='content_block_delta'&&active.has(event.index)){
     const block=active.get(event.index),text=event.delta.text||event.delta.thinking||'';block.text+=text;
     if(text)yield {type:block.type==='text'?'text-delta':'reasoning-delta',index:block.index,text};
    }
    if(event.type==='content_block_stop'&&active.has(event.index)){
     const block=active.get(event.index);active.delete(event.index);yield {type:'block-end',index:block.index,block:{type:block.type,text:block.text}};
    }
   }
   if(message.type==='assistant'){
    if(message.error)throw new Error(message.message?.content?.map(block=>block.text||'').join(' ')||message.error);
    const blocks=message.message?.content||[];stats={...stats,...message.message?.usage};
    if(!partial)for(const block of blocks)if(block.type==='text'||block.type==='thinking'){
     const index=nextIndex++,type=block.type==='text'?'text':'reasoning',text=block.text||block.thinking||'';
     yield {type:'block-start',index,blockType:type};yield {type:type==='text'?'text-delta':'reasoning-delta',index,text};yield {type:'block-end',index,block:{type,text}};
    }
    const calls=blocks.filter(block=>block.type==='tool_use');
    if(calls.length){
     for(const block of calls){const name=names.get(block.name);if(!name)throw new Error('CLI requested an unregistered host tool');
      const index=nextIndex++,args=JSON.stringify(block.input);if(args.length>2*1024*1024)throw new Error('CLI tool arguments too large');
      yield {type:'block-start',index,blockType:'tool-call'};yield {type:'tool-call-delta',index,id:block.id,name,argumentsDelta:args};yield {type:'block-end',index,block:{type:'tool-call',id:block.id,name,arguments:args}};
     }
     yield {type:'usage',usage:usage(stats)};yield {type:'finish',reason:{kind:'tool-calls'}};terminal=true;return;
    }
   }
   if(message.type==='result'){
    if(message.is_error||message.subtype!=='success')throw new Error(message.result||'CLI request failed');
    yield {type:'usage',usage:usage(message.usage||stats)};yield {type:'finish',reason:{kind:'stop'}};terminal=true;return;
   }
  }
  throw new Error('CLI stream ended without a result');
 }catch(error){if(!terminal){yield {type:'usage',usage:usage(stats)};yield {type:'finish',reason:timedOut?{kind:'error',failure:{code:'LLM_STREAM_IDLE_TIMEOUT',message:'CodeBuddy CLI stopped responding.'}}:failure(error,controller.signal.aborted)};}}
 finally{clearTimeout(timer);options.signal?.removeEventListener('abort',cancel);await query?.return().catch(()=>{});controller.abort();await server?.close().catch(()=>{});}
}
module.exports={prepare,requestPrompt,failure,stream,sessionQuery,clearWarm};
if(require.main===module){
 if(process.argv[2]==='--prepare')prepare().then(()=>console.log('CodeBuddy CLI verified and prepared.')).catch(()=>{console.error('CodeBuddy CLI preparation failed.');process.exitCode=1;});
 else if(process.argv[2]==='--check'){
  (async()=>{
   let apiKey='';for await(const part of process.stdin){apiKey+=part;if(apiKey.length>4096)throw new Error('Invalid credential');}
   apiKey=apiKey.trim();const controller=new AbortController(),stop=()=>controller.abort();process.once('SIGTERM',stop);let text='',reason;
   try{for await(const chunk of stream({provider:'codebuddy',model:process.argv[3]||'deepseek-v4.1-flash',reasoningEffort:'max',signal:controller.signal},{messages:[{role:'user',content:[{type:'text',text:'Reply only OK.'}]}],tools:[]},{apiKey})){
    if(chunk.type==='text-delta')text+=chunk.text;if(chunk.type==='finish')reason=chunk.reason;
   }}finally{apiKey='';process.removeListener('SIGTERM',stop);}
   console.log(JSON.stringify({ok:reason?.kind==='stop'&&text.trim().length>0,channelRejected:/11128/.test(reason?.failure?.message||''),keyRejected:/authentication/i.test(reason?.failure?.message||'')}));
  })().then(()=>process.exit(0)).catch(()=>{console.log('{"ok":false}');process.exit(1);});
 }
}
