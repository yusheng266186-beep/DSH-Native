// Production adapter, pinned official SDK, protocol fixtures and Android platform boundary.
// All credentials and model replies are synthetic. No model request is sent.
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
 const manifest=JSON.parse(await fs.readFile(path.join(root,'codebuddy-sdk-manifest.json'),'utf8'));
 const runtime=path.join(root,'.codebuddy-sdk-'+manifest.sdk+'-'+manifest.sha256.slice(0,16)),sandbox=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-sdk-boundary-'));
 try{
  const preload=path.join(sandbox,'android.mjs');await fs.writeFile(preload,"Object.defineProperty(process,'platform',{value:'android'});\n");
  const startup=spawnSync(process.execPath,['--expose-internals','--import',preload,path.join(runtime,'node_modules/@tencent-ai/agent-sdk/cli/bin/codebuddy'),'--version'],{encoding:'utf8',timeout:20000});check(startup.status===0&&startup.stdout.trim()==='2.176.0','unmodified CLI starts with Android platform selection (not a device test)');
  await fs.copyFile(new URL('../../payload/codebuddy-cli.cjs',import.meta.url),path.join(sandbox,'codebuddy-cli.cjs'));await fs.writeFile(path.join(sandbox,'codebuddy-sdk-manifest.json'),JSON.stringify({...manifest,sha256:'0'.repeat(64)}));await fs.writeFile(path.join(sandbox,'codebuddy-sdk.tar.zst'),'synthetic corrupt archive');
  const invalid=spawnSync(process.execPath,[path.join(sandbox,'codebuddy-cli.cjs'),'--prepare'],{encoding:'utf8',timeout:10000});check(invalid.status!==0&&!invalid.stdout.includes('prepared'),'unverified SDK archives cannot be installed');
 }finally{await fs.rm(sandbox,{recursive:true,force:true});}
 console.log('CodeBuddy CLI transport: '+checks+' streaming, tools, schema, cancellation, deadline, privacy, bundle and Android-platform checks passed; no service calls or device.');
}finally{clearInterval(keepAlive);queryModule.query=original;}
