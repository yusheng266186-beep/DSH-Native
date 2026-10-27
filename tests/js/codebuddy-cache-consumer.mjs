// Pinned official CLI, production adapter, local synthetic provider. No paid API calls.
import assert from 'node:assert/strict';
import http from 'node:http';
import {createRequire} from 'node:module';
import {execFileSync} from 'node:child_process';
const require=createRequire(import.meta.url),cli=require('../../payload/codebuddy-cli.cjs');
const root=process.argv[2],sdk=await cli.prepare(root),original=sdk.query;
let checks=0,mode='text';const requests=[];
const check=(value,label)=>{assert(value,label);checks++;};
const server=http.createServer(async(request,response)=>{
 let text='';for await(const chunk of request)text+=chunk;
 let body={};try{body=JSON.parse(text||'{}');}catch{}
 if(request.method!=='POST'||!Array.isArray(body.messages)){
  response.writeHead(200,{'Content-Type':'application/json'});response.end('{"data":[]}');return;
 }
 requests.push(body);
 response.writeHead(200,{'Content-Type':'text/event-stream'});
 const call=mode==='tool',name=body.tools?.[0]?.function?.name;
 const delta=call?{role:'assistant',tool_calls:[{index:0,id:'synthetic-call',type:'function',function:{name,arguments:'{"path":"synthetic.txt","content":"fixture"}'}}]}:{role:'assistant',content:'OK'};
 for(const chunk of [
  {choices:[{index:0,delta,finish_reason:null}]},
  {choices:[{index:0,delta:{},finish_reason:call?'tool_calls':'stop'}],usage:{prompt_tokens:1000,completion_tokens:2,total_tokens:1002,prompt_tokens_details:{cached_tokens:900},prompt_cache_hit_tokens:900,prompt_cache_miss_tokens:100}}
 ])response.write('data: '+JSON.stringify({id:'synthetic-usage',object:'chat.completion.chunk',model:'deepseek-v4.1-flash',...chunk})+'\n\n');
 response.end('data: [DONE]\n\n');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const endpoint='http://127.0.0.1:'+server.address().port;
const controller=new AbortController(),deadline=setTimeout(()=>controller.abort(),45000);
try{
 sdk.query=args=>{
  // Official public base-URL option is overridden only in this offline fixture.
  args.options.env={...args.options.env,CODEBUDDY_BASE_URL:endpoint};return original(args);
 };
 const context={messages:[{role:'user',content:[{type:'text',text:'Reply only OK.'}]}],tools:[]};
 for(let round=0;round<2;round++){
  const out=[];for await(const chunk of cli.stream({model:'deepseek-v4.1-flash',reasoningEffort:'max',signal:controller.signal},context,{root,apiKey:'synthetic-provider-only',idleTimeoutMs:30000}))out.push(chunk);
  const usage=out.find(chunk=>chunk.type==='usage')?.usage;
  check(out.at(-1)?.reason.kind==='stop','actual official SDK completes against the local provider');
  check(usage?.inputTokens===100&&usage.cacheReadTokens===900&&usage.totalTokens===1002,'production adapter maps real SDK inclusive input without double counting cache');
  cli.clearWarm();
 }
 check(requests.length===2,'preparation does not generate hidden provider prompts');
 // Check serialization stability without logging request bodies or credentials.
 const left=JSON.stringify({tools:requests[0].tools,messages:requests[0].messages});
 const right=JSON.stringify({tools:requests[1].tools,messages:requests[1].messages});
 let prefix=0;while(prefix<Math.min(left.length,right.length)&&left[prefix]===right[prefix])prefix++;
 console.log('Synthetic fresh-session model-input common prefix: '+prefix+' / '+left.length+' characters (not a server cache-hit measurement).');
 mode='tool';context.tools=[{name:'write',description:'Synthetic write declaration',parameters:{type:'object',properties:{path:{type:'string'},content:{type:'string'}},required:['path','content']}}];
 const out=[];for await(const chunk of cli.stream({model:'deepseek-v4.1-flash',reasoningEffort:'max',signal:controller.signal},context,{root,apiKey:'synthetic-provider-only',idleTimeoutMs:30000}))out.push(chunk);
 const usage=out.find(chunk=>chunk.type==='usage')?.usage;
 check(out.at(-1)?.reason.kind==='tool-calls'&&out.some(chunk=>chunk.block?.name==='write'),'official SDK tool request returns to DSH without executing a file operation');
 check(usage?.inputTokens===100&&usage.cacheReadTokens===900&&usage.totalTokens===1002,'tool-turn SDK usage also uses disjoint input buckets');
 cli.clearWarm();mode='text';context.tools=[];
 const trace=[],log=console.log;
 console.log=value=>{if(String(value).startsWith('[dsh-perf] codebuddy '))trace.push(JSON.parse(value.slice('[dsh-perf] codebuddy '.length)));};
 try{
  for(let i=0;i<2;i++){
   for await(const _chunk of cli.stream({model:'deepseek-v4.1-flash',reasoningEffort:'max',signal:controller.signal},context,{root,apiKey:'synthetic-provider-only',idleTimeoutMs:15000})){}
   await new Promise(resolve=>setTimeout(resolve,200));
  }
 }finally{console.log=log;}
 check(trace.length===2&&trace[0].warm===false&&trace[1].warm===true,'actual SDK follow-up claims exactly one fresh standby');
 check(trace.every(row=>row.total>=row.first&&row.first>=row.submitted&&row.submitted>=row.setup),'actual SDK timings cover setup, submission, first content and completion');
 const children=()=>execFileSync('ps',['-axo','ppid=,comm='],{encoding:'utf8',timeout:5000}).split('\n').filter(line=>{const row=line.trim().split(/\s+/);return Number(row[0])===process.pid&&/(^|\/)node(js)?$/.test(row.slice(1).join(' '));}).length;
 check(children()===1,'only one idle official CLI child remains after a quick follow-up');
 await new Promise(resolve=>setTimeout(resolve,10300));
 check(children()===0,'the actual idle CLI exits after its ten-second expiry');
 console.log('Synthetic SDK timing ms: '+JSON.stringify(trace)+'; measured on the host, not an Android latency guarantee.');
 console.log('CodeBuddy cache consumer: '+checks+' actual SDK/adapter checks passed; synthetic provider only.');
}finally{
 clearTimeout(deadline);controller.abort();cli.clearWarm();sdk.query=original;
 server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
}
