// Exercise the production settings component and session RPC against the pinned real core.
// All homes, conversations and provider responses are synthetic and disposable.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import http from 'node:http';
import {chromium} from 'playwright';
const runtime=path.resolve(process.argv[2]);
const mobile=await fs.readFile(process.argv[3],'utf8');
const injection=mobile.slice(mobile.indexOf('<style id="dsh-native-responsive">'),mobile.indexOf('</head>'));
const frontend=path.join(runtime,'node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html');
const originalFrontend=await fs.readFile(frontend,'utf8');
const scratch=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-app-settings-'));
const home=path.join(scratch,'home'), project=path.join(scratch,'project');
let child,browser,context,page,origin,cookie,provider,checks=0,seq=0,providerCalls=0,delayReply=false,coreOutput='';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const check=(value,message)=>{assert(value,message);checks++;};
async function stop(){if(child){const stopped=child;child=null;if(stopped.exitCode===null){const exited=new Promise(r=>stopped.once('exit',r));stopped.kill('SIGTERM');await Promise.race([exited,sleep(5000).then(()=>stopped.kill('SIGKILL'))]);}}}
async function boot(){
 let output='';const platformArgs=process.platform==='darwin'?['--import',path.join(scratch,'android-platform.mjs')]:[];child=spawn(process.execPath,['--expose-internals',...platformArgs,path.join(runtime,'lib/bin.js'),'--patch',path.join(scratch,'search-enabled.yml'),'--profile','web','--no-open','--port','0'],{cwd:project,env:{...process.env,DSH_HOME:home,COMMANDCODE_API_KEY:'',DEEPSEEK_API_KEY:'',DSH_SYNTHETIC_KEY:'fixture-only'},stdio:['ignore','pipe','pipe']});
 child.stdout.on('data',b=>coreOutput=output=(output+b).slice(-32000));child.stderr.on('data',b=>coreOutput=output=(output+b).slice(-32000));
 let launch;for(let i=0;i<450;i++){launch=output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];if(launch)break;assert.equal(child.exitCode,null,'core exited');await sleep(100);}assert(launch,'core boot timed out');
 origin=new URL(launch).origin;cookie=(await fetch(launch,{redirect:'manual'})).headers.get('set-cookie').split(';')[0];return launch;
}
async function rpc(method,args={}){
 const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{cookie,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:'test-'+ ++seq,method,payload:{args}})});
 return (await response.json()).result;
}
async function ok(method,request){const result=await rpc(method,request===undefined?{}:{request});assert(result.ok,method+': '+JSON.stringify(result.error));return result.value;}
async function openSettings(target='sessions'){
 const accepted=await page.evaluate(target=>!window.dispatchEvent(new CustomEvent('dsh-native-app-settings',{cancelable:true,detail:{page:target}})),target);
 check(accepted,'settings entry must report ready');await page.locator('[data-app-page="'+target+'"]').waitFor();
}
async function choose(label){await page.locator('.app-tabs').getByRole('button',{name:label,exact:true}).click();}
const row=id=>page.locator('article[data-session-id="'+id+'"]');
try{
 await fs.writeFile(frontend,originalFrontend.replace(/<meta\b[^>]*name=["']viewport["'][^>]*>/ig,'<meta name="viewport" content="width=480" />').replace('</head>',injection+'</head>'));
 provider=http.createServer(async(req,res)=>{
  let bytes='';for await(const chunk of req)bytes+=chunk;
  const request=JSON.parse(bytes);check(req.url==='/v1/chat/completions','only local fixture receives prompts');
  check(request.model==='synthetic','real SDK model preserved');providerCalls++;
  res.writeHead(200,{'content-type':'text/event-stream'});
  const event=(delta,finish=null)=>res.write('data: '+JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:'synthetic',choices:[{index:0,delta,finish_reason:finish}]})+'\n\n');
  event({role:'assistant'});if(delayReply)await sleep(1800);
  event({content:'Synthetic reply after restore'});event({},'stop');res.end('data: [DONE]\n\n');
 });await new Promise(resolve=>provider.listen(0,'127.0.0.1',resolve));
 // The distributed runtime contains Linux/Android addons, not Darwin addons. On macOS use its production Android lock fallback; CI exercises real Linux locks.
 await fs.writeFile(path.join(scratch,'android-platform.mjs'),"Object.defineProperty(process,'platform',{value:'android'});\n");
 // Upstream content search is opt-in. Enable it only in this disposable fixture to verify trash filtering too.
 await fs.writeFile(path.join(scratch,'search-enabled.yml'),"- id: session-query-sqlite\n  config:\n    path: ':memory:'\n    openAt: first-search\n");
 await fs.mkdir(home);await fs.mkdir(project);
 await fs.writeFile(path.join(home,'settings.yaml'),`agent-default-model:
  provider: synthetic
  model: synthetic
llm-pi-ai:
  providers:
    synthetic:
      displayName: Synthetic local fixture
      api: openai-completions
      baseURL: http://127.0.0.1:${provider.address().port}/v1
      apiKeyEnv: DSH_SYNTHETIC_KEY
      models:
        - id: synthetic
          name: Synthetic
          contextWindow: 64000
          maxTokens: 512
          input: [text]
`);
 const launch=await boot();const workspace=(await ok('workspace/create',{path:project})).workspace;assert((await rpc('settings/update',{ns:'ui-settings-general',patch:{welcomeNoticeVersion:'2026-09-28.1'}})).ok);
 const create=async title=>{const created=await ok('session/create',{workspaceId:workspace.workspaceId});await ok('session/rename',{sessionId:created.sessionId,title});return created.sessionId;};
 const alpha=await create('Synthetic Alpha'), beta=await create('Synthetic Beta');
 browser=await chromium.launch({headless:true});context=await browser.newContext({locale:'zh-CN',viewport:{width:393,height:852},isMobile:true,hasTouch:true,deviceScaleFactor:3});
  page=await context.newPage();let pageErrors=[];page.on('pageerror',error=>pageErrors.push(error.message));
 await context.addCookies([{name:cookie.slice(0,cookie.indexOf('=')),value:cookie.slice(cookie.indexOf('=')+1),url:origin,httpOnly:true,sameSite:'Lax'}]);await page.goto(origin+'/');await page.locator('[data-slot="settings.launcher"]').waitFor();await sleep(600);
 await openSettings();await row(alpha).waitFor();check(await row(beta).isVisible(),'actual sessions rendered');
 await row(alpha).getByRole('button',{name:'重命名',exact:true}).click();await page.getByRole('textbox',{name:'会话标题',exact:true}).fill('Renamed Alpha');await page.getByRole('button',{name:'保存',exact:true}).click();await row(alpha).getByRole('heading',{name:'Renamed Alpha'}).waitFor();checks++;
 await row(alpha).getByRole('button',{name:'归档',exact:true}).click();await page.getByRole('button',{name:'确认',exact:true}).click();await row(alpha).waitFor({state:'detached'});
 check((await ok('session/androidList')).items.find(r=>r.sessionId===alpha).archived,'archive reached registry');
 await choose('已归档');await row(alpha).waitFor();await row(alpha).getByRole('button',{name:'恢复并继续对话',exact:true}).click();await page.locator('[data-app-page]').waitFor({state:'detached'});
 check(!(await ok('session/androidList')).items.find(r=>r.sessionId===alpha).archived,'restore reached registry');
 await page.screenshot({path:path.join(scratch,'restored.png')});
 await openSettings();await row(alpha).waitFor();await row(alpha).getByRole('button',{name:'移到回收站',exact:true}).click();await page.getByRole('button',{name:'确认',exact:true}).click();await row(alpha).waitFor({state:'detached'});
 const list=await rpc('session/list',{_request:{}});check(list.ok&&!list.value.items.some(r=>r.sessionId===alpha),'trashed session hidden from upstream list');
 await choose('回收站');await row(alpha).waitFor();await row(alpha).getByRole('button',{name:'恢复并继续对话',exact:true}).click();await page.locator('[data-app-page]').waitFor({state:'detached'});
 // Reopening a retained, previously removed ClientSession must admit an actual prompt and render the reply.
 await page.locator('[contenteditable="true"][role="textbox"]').click();await page.locator('[contenteditable="true"][role="textbox"]').pressSequentially('Synthetic prompt after trash restore');
 await page.getByRole('button',{name:'发送消息',exact:true}).click();
 await page.getByText('Synthetic reply after restore',{exact:true}).first().waitFor();
 check(providerCalls>0,'restored conversation called the local fixture through the actual SDK');
 await openSettings();await row(alpha).waitFor();check((await ok('session/androidList')).items.find(r=>r.sessionId===alpha).trashed===false,'trash restore durable');
 await page.getByRole('textbox',{name:'搜索会话标题',exact:true}).fill('Beta');await row(alpha).waitFor({state:'detached'});check(await row(beta).isVisible(),'filter titles');
 await page.getByRole('textbox',{name:'搜索会话标题',exact:true}).fill('');
 // Pagination consumes real saved rows and resets correctly after a title filter.
 const pageIds=[];for(let i=0;i<21;i++)pageIds.push(await create('Pagination '+i));
 await page.locator('.dsh-app-settings').getByRole('button',{name:'刷新',exact:true}).click();
 await page.getByRole('button',{name:'下一页',exact:true}).waitFor();
 check(await page.locator('article[data-session-id]').count()===20,'first page limits real rows');
 await page.getByRole('button',{name:'下一页',exact:true}).click();check(await page.locator('article[data-session-id]').count()===3,'next page contains remaining real rows');
 await page.getByRole('textbox',{name:'搜索会话标题',exact:true}).fill('Pagination 20');check(await page.locator('article[data-session-id]').count()===1,'title filter resets pagination');
 for(const sessionId of pageIds)await ok('workspace/archiveSession',{sessionId});
 await page.getByRole('textbox',{name:'搜索会话标题',exact:true}).fill('');await page.locator('.dsh-app-settings').getByRole('button',{name:'刷新',exact:true}).click();await row(alpha).waitFor();
 check(await page.locator('article[data-session-id]').count()===2,'archived pagination fixtures leave active list');
 const colors=await page.locator('.dsh-app-settings').evaluate(el=>{const s=getComputedStyle(el);return ['--dsw-alias-label-primary','--dsw-alias-label-secondary','--dsw-alias-border-l1','--dsw-alias-button-primary-fill','--dsw-alias-label-primary-inverted'].map(key=>[key,s.getPropertyValue(key)]);});
 console.log('Actual DSH settings tokens',JSON.stringify(colors));check(colors.every(([,value])=>value.trim()),'settings use real theme tokens');check(pageErrors.length===0,'real UI errors: '+pageErrors.join(';'));
 // A real read failure is visible and retry returns to the authoritative list.
 await page.route(origin+'/api/session/androidList',route=>route.fulfill({status:503,body:'unavailable'}));
 await page.locator('.dsh-app-settings').getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('alert').filter({hasText:'HTTP 503'}).waitFor();checks++;
 await page.unroute(origin+'/api/session/androidList');await page.getByRole('button',{name:'重试',exact:true}).click();await page.getByRole('alert').waitFor({state:'detached'});checks++;
 const messages=[];page.on('console',message=>{if(message.text().startsWith('[dsh-native-settings] '))messages.push({value:JSON.parse(message.text().slice('[dsh-native-settings] '.length)),url:message.location().url});});
 for(const [key,label] of [['tasks','任务状态、连接恢复与最近记录'],['models','服务商、默认模型与更新 App 模型列表'],['display','调整文字缩放'],['updates','App 更新、运行包恢复与重启服务'],['data','项目、文件、加密备份与插件'],['diagnostics','运行日志、诊断导出与网络检测']]){
  await openSettings(key);const before=messages.length;const action=page.locator('.dsh-app-settings').getByRole('button',{name:label,exact:true});await action.click();
  for(let i=0;i<100&&messages.length===before;i++)await sleep(10);
  const command=messages.at(-1);check(command?.value.action===key&&command.value.locale==='zh','real native navigation command '+key);check(new URL(command.url).origin===origin,'console bridge source is the authenticated core');
  check(await action.isDisabled(),'duplicate navigation prevented');
  await page.evaluate(id=>window.dispatchEvent(new CustomEvent('dsh-native-settings-ack',{detail:{id,accepted:true}})),command.value.id);await page.locator('[data-app-page]').waitFor({state:'detached'});checks++;
 }
 await openSettings('display');await page.getByRole('button',{name:'DSH 主题与语言',exact:true}).click();await page.locator('[data-app-page]').waitFor({state:'detached'});check(await page.locator('[data-shortcut-modal="settings"]').getByRole('switch').count()>0,'general action opens original DSH settings');
 await openSettings('models');await page.getByRole('button',{name:'DeepSeek 账号、余额与用量',exact:true}).click();await page.locator('[data-app-page]').waitFor({state:'detached'});check(await page.locator('[data-shortcut-modal="settings"]').getByText('账号与余额',{exact:true}).count()>0,'account action opens original DSH section');
 // Registry activity is authoritative: refuse archive/trash while the real agent is streaming.
 delayReply=true;await ok('session/prompt',{sessionId:alpha,requestId:'activity-fixture',mode:'queue',content:[{type:'text',text:'Synthetic delayed response'}]});
 for(let i=0;i<100;i++){if((await ok('session/androidList')).items.find(r=>r.sessionId===alpha).running)break;await sleep(20);}
 check(!(await rpc('workspace/archiveSession',{request:{sessionId:alpha}})).ok,'active archive rejected');
 check(!(await rpc('session/androidTrash',{request:{sessionId:alpha}})).ok,'active trash rejected');
 check(!(await ok('session/androidList')).items.find(r=>r.sessionId===alpha).trashed,'active conversation preserved');
 for(let i=0;i<200;i++){if(!(await ok('session/androidList')).items.find(r=>r.sessionId===alpha).running)break;await sleep(25);}delayReply=false;
 let found=await rpc('session/search',{request:{query:'Synthetic prompt after trash restore'}});check(found.ok&&found.value.items.some(item=>item.sessionId===alpha),'original full-text search works: '+JSON.stringify(found));
 await ok('session/androidTrash',{sessionId:alpha});found=await rpc('session/search',{request:{query:'Synthetic prompt after trash restore'}});check(found.ok&&!found.value.items.some(item=>item.sessionId===alpha)&&!found.value.hasMore,'trash excluded before search result limit');
 await ok('session/androidRestore',{sessionId:alpha});found=await rpc('session/search',{request:{query:'Synthetic prompt after trash restore'}});check(found.ok&&found.value.items.some(item=>item.sessionId===alpha),'restore makes original full-text search available');
 // Persist concurrent deletions and reject attempts to continue hidden sessions.
 const gamma=await create('Synthetic Gamma');await Promise.all([ok('session/androidTrash',{sessionId:beta}),ok('session/androidTrash',{sessionId:gamma})]);
 let journal=JSON.parse(await fs.readFile(path.join(home,'.native-session-trash.json'),'utf8'));
 check(journal.items.some(item=>item.id===beta)&&journal.items.some(item=>item.id===gamma),'concurrent trash journal keeps both sessions');
 check((await fs.stat(path.join(home,'.native-session-trash.json'))).mode%512===384,'trash metadata is private');
 check(!(await rpc('session/prompt',{request:{sessionId:beta,requestId:'trashed-fixture',mode:'queue',content:[{type:'text',text:'must not send'}]}})).ok,'trashed conversation rejects prompt');
 check(!(await rpc('session/androidTrash',{request:{sessionId:'missing-session'}})).ok,'unknown session cannot be removed');
 check((await ok('session/androidTrash',{sessionId:beta})).trashed,'repeat trash is idempotent');
 // A publication error rolls back the archive and keeps the original journal intact.
 const journalPath=path.join(home,'.native-session-trash.json'), preserved=await fs.readFile(journalPath);
 await fs.rename(journalPath,journalPath+'.preserved');await fs.mkdir(journalPath);
 check(!(await rpc('session/androidTrash',{request:{sessionId:alpha}})).ok,'unwritable trash publication reports failure');
 check(!(await ok('session/androidList')).items.find(r=>r.sessionId===alpha).archived,'failed deletion rolls back archive');
 check((await fs.readFile(journalPath+'.preserved')).equals(preserved),'failed deletion keeps original metadata');
 check(!(await fs.readdir(home)).some(name=>name.endsWith('.tmp')),'publication failure cleans stage');
 check(!(await rpc('session/androidRestore',{request:{sessionId:beta}})).ok,'failed restore reports publication failure');
 check((await ok('session/androidList')).items.find(r=>r.sessionId===beta).trashed&&(await ok('session/androidList')).items.find(r=>r.sessionId===beta).archived,'failed restore retains trash and archive');
 await fs.rm(journalPath,{recursive:true});await fs.rename(journalPath+'.preserved',journalPath);
 await context.close();context=null;await stop();
 await fs.writeFile(journalPath,'{broken synthetic journal');await boot();
 check(!(await rpc('session/androidList')).ok,'corrupt journal is reported, never treated as empty');
 check(!(await rpc('session/list',{_request:{}})).ok,'corrupt journal cannot expose hidden sessions');
 await stop();await fs.writeFile(journalPath,preserved);await boot();
 check((await ok('session/androidList')).items.find(r=>r.sessionId===beta).trashed,'trash persists across actual core restart');
 check(!(await rpc('session/list',{_request:{}})).value.items.some(r=>r.sessionId===gamma),'upstream list stays filtered after restart');
 await ok('session/androidRestore',{sessionId:beta});check(!(await ok('session/androidList')).items.find(r=>r.sessionId===beta).archived,'restart restore unarchives');
 // Reconnect in six real touch viewports, both locales and themes; measure actual controls.
 for(const scenario of [{w:320,h:800,lang:'zh',theme:'dark'},{w:360,h:800,lang:'en',theme:'light'},{w:436,h:880,lang:'zh',theme:'light'},{w:436,h:880,lang:'en',theme:'dark'},{w:600,h:960,lang:'zh',theme:'dark'},{w:869,h:436,lang:'en',theme:'light'}]){
  assert((await rpc('settings/update',{ns:'locale',patch:{preference:scenario.lang}})).ok);
  assert((await rpc('settings/update',{ns:'ui-theme',patch:{preference:scenario.theme}})).ok);
  await stop();await fs.writeFile(frontend,originalFrontend.replace(/<meta\b[^>]*name=["']viewport["'][^>]*>/ig,'<meta name="viewport" content="width='+Math.max(480,scenario.w)+'" />').replace('</head>',injection+'</head>'));await boot();
  context=await browser.newContext({locale:scenario.lang,viewport:{width:scenario.w,height:scenario.h},isMobile:true,hasTouch:true,reducedMotion:'reduce'});page=await context.newPage();
   await context.addCookies([{name:cookie.slice(0,cookie.indexOf('=')),value:cookie.slice(cookie.indexOf('=')+1),url:origin,httpOnly:true,sameSite:'Lax'}]);await page.goto(origin+'/');await page.locator('[data-slot="settings.launcher"]').waitFor();await sleep(400);await openSettings();await row(alpha).waitFor();
  check(await page.locator('#dsh-native-responsive').count()===1,'production mobile layout installed');check(await page.evaluate(()=>innerWidth)===Math.max(480,scenario.w),'production viewport width');
  const tokenValues=await page.locator('.dsh-app-settings').evaluate(el=>{const s=getComputedStyle(el);return ['--dsw-alias-bg-base','--dsw-alias-bg-layer-1','--dsw-alias-bg-layer-2','--dsw-alias-bg-layer-3','--dsw-alias-border-l1','--dsw-alias-label-primary','--dsw-alias-label-secondary','--dsw-alias-label-tertiary','--dsw-alias-button-primary-fill','--dsw-alias-label-primary-inverted'].map(key=>[key,s.getPropertyValue(key)]);});check(tokenValues.every(([,value])=>value.trim()),'actual theme tokens resolve');
  const bounds=await page.locator('.dsh-app-settings button').evaluateAll(els=>els.filter(el=>el.getBoundingClientRect().width>0).map(el=>{const r=el.getBoundingClientRect(),p=el.closest('.dsh-app-settings').getBoundingClientRect();return {text:el.textContent,height:r.height,left:r.left,right:r.right,parentRight:p.right,scroll:el.scrollWidth,width:el.clientWidth};}));
  for(const item of bounds){check(item.height>=35.5,'touch target height '+item.text);check(item.right<=item.parentRight+1,'button stays in settings '+JSON.stringify(item));check(item.scroll<=item.width+2,'label fits '+item.text);}
  check(await page.evaluate(()=>document.body.hasAttribute('data-ds-dark-theme'))===(scenario.theme==='dark'),'actual settings theme');
  if(process.env.DSH_SETTINGS_EVIDENCE_DIR){await fs.mkdir(process.env.DSH_SETTINGS_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,`settings-${scenario.w}-${scenario.lang}-${scenario.theme}.png`)});}
  await choose(scenario.lang==='en'?'Trash':'回收站');await row(gamma).waitFor();checks++;
  await context.close();context=null;
 }
 console.log('App settings actual core/browser: '+checks+' checks passed');
}catch(error){if(process.env.DSH_SETTINGS_EVIDENCE_DIR){await fs.mkdir(process.env.DSH_SETTINGS_EVIDENCE_DIR,{recursive:true});await page?.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,'failure.png')});}console.log('Local fixture calls',providerCalls);console.log('Synthetic core',coreOutput.replace(/token=\S+/g,'token=REDACTED').slice(-8000));console.log('Synthetic UI failure', (await page?.locator('body').innerText())?.slice(0,4000));throw error;}finally{await browser?.close();await stop();await fs.writeFile(frontend,originalFrontend);if(provider)await new Promise(resolve=>provider.close(resolve));await fs.rm(scratch,{recursive:true,force:true});}
