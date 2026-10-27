// Production DSH settings slots and RPC with the actual Java presenter and Android boundary fakes.
// All credentials, configurations and model responses here are synthetic.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {chromium} from 'playwright';
const [runtimeArg,mobileArg,classes]=process.argv.slice(2),runtime=path.resolve(runtimeArg);
const require=createRequire(import.meta.url),{encrypt}=require('../../tools/export_codebuddy_connection.cjs');
const root=await fs.mkdtemp(path.join(os.tmpdir(),'dsh-web-settings-')),home=path.join(root,'home'),project=path.join(root,'project');
let core,native,browser,context,page,origin,cookie,output='',checks=0,seq=0;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms)),check=(ok,label)=>{assert(ok,label);checks++;};
const pending=[];let readyResolve;const ready=new Promise(resolve=>readyResolve=resolve);
async function rpc(method,args={}){const response=await fetch(origin+'/api/'+method,{method:'POST',headers:{cookie,'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:'settings-fixture-'+ ++seq,method,payload:{args}})});const result=(await response.json()).result;assert(result.ok,method+': '+JSON.stringify(result.error));return result.value;}
async function stop(){for(const child of [native,core])if(child&&child.exitCode===null){const ended=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGTERM');await Promise.race([ended,sleep(4000).then(()=>child.kill('SIGKILL'))]);}}
try{
 await fs.mkdir(home);await fs.mkdir(project);await fs.writeFile(path.join(project,'editable-notes.txt'),'Synthetic file contents\n');
 await fs.copyFile(new URL('../../payload/settings-preset.yaml',import.meta.url),path.join(home,'settings.yaml'));
 await fs.writeFile(path.join(root,'android.mjs'),"Object.defineProperty(process,'platform',{value:'android'});\n");
 const flags=process.platform==='darwin'?['--import',path.join(root,'android.mjs')]:[];
 core=spawn(process.execPath,['--expose-internals',...flags,path.join(runtime,'lib/bin.js'),'--profile','web','--no-open','--port','0'],{cwd:project,env:{...process.env,DSH_HOME:home,CODEBUDDY_API_KEY:'synthetic-ui-bootstrap-key',DEEPSEEK_API_KEY:''},stdio:['ignore','pipe','pipe']});
 const collect=b=>output=(output+b).slice(-64000);core.stdout.on('data',collect);core.stderr.on('data',collect);
 let launch;for(let n=0;n<450;n++){launch=output.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=\S+)/)?.[1];if(launch)break;assert.equal(core.exitCode,null);await sleep(100);}assert(launch,'core startup');
 origin=new URL(launch).origin;cookie=(await fetch(launch,{redirect:'manual'})).headers.get('set-cookie').split(';')[0];
 await rpc('settings/update',{ns:'ui-settings-general',patch:{welcomeNoticeVersion:'2026-09-28.1'}});
 const workspace=(await rpc('workspace/create',{request:{path:project}})).workspace;const fileSession=await rpc('session/create',{request:{workspaceId:workspace.workspaceId}});await rpc('session/rename',{request:{sessionId:fileSession.sessionId,title:'File operations fixture'}});
 native=spawn('java',['-cp',classes,'dev.dsh.nativeapp.WebSettingsHostTest','--serve'],{stdio:['pipe','pipe','inherit']});
 readline.createInterface({input:native.stdout}).on('line',line=>{const value=JSON.parse(line);if(value.ready)readyResolve(value);else pending.shift()?.(value);});
 const handshake=await ready;
 browser=await chromium.launch({headless:true});
 const mobile=await fs.readFile(mobileArg,'utf8'),injection=mobile.slice(mobile.indexOf('<style id="dsh-native-responsive">'),mobile.indexOf('</head>'));
 for(const scenario of [{w:320,h:800,lang:'zh',theme:'dark'},{w:436,h:880,lang:'en',theme:'light'},{w:869,h:436,lang:'zh',theme:'light'},
  {w:360,h:430,lang:'en',theme:'dark',largeText:true},{w:768,h:1024,lang:'en',theme:'light',largeText:true},{w:1024,h:600,lang:'zh',theme:'dark',largeText:true}]){
  await rpc('settings/update',{ns:'locale',patch:{preference:scenario.lang}});await rpc('settings/update',{ns:'ui-theme',patch:{preference:scenario.theme}});
  context=await browser.newContext({viewport:{width:scenario.w,height:scenario.h},isMobile:true,hasTouch:true,locale:scenario.lang,reducedMotion:'reduce'});
  await context.grantPermissions(['local-network-access'],{origin});
  await context.addCookies([{name:cookie.slice(0,cookie.indexOf('=')),value:cookie.slice(cookie.indexOf('=')+1),url:origin,httpOnly:true,sameSite:'Lax'}]);
  page=await context.newPage();page.setDefaultTimeout(10000);const errors=[],fileCommands=[],nativeActions=[],prompts=[];page.on('request',request=>{if(new URL(request.url()).pathname==='/api/session/prompt')prompts.push(request);});let failNextRead=true,holdPoll,releasePoll;page.on('pageerror',error=>{errors.push(error.message);console.log('Browser boundary error: '+error.message);});
  page.on('console',message=>{if(!message.text().startsWith('[dsh-settings-ui] '))return;const command=message.text();const parsed=JSON.parse(command.slice('[dsh-settings-ui] '.length));if(parsed.op==='act')nativeActions.push(parsed);if(parsed.op==='file')fileCommands.push(parsed);let response;if(parsed.op==='read'&&failNextRead){failNextRead=false;response=Promise.resolve({id:parsed.id,ok:false,error:'synthetic read boundary failure'});}else{response=new Promise(resolve=>pending.push(resolve));native.stdin.write(command+'\n');}response.then(async reply=>{if(parsed.op==='state'&&holdPoll){const ready=holdPoll;holdPoll=null;await new Promise(resolve=>{releasePoll=resolve;ready();});}const text=JSON.stringify({ok:reply.ok,value:reply.value,error:reply.error});const total=Math.ceil(text.length/32000);for(let index=0;index<total;index++)await page.evaluate(detail=>window.dispatchEvent(new CustomEvent('dsh-settings-chunk',{detail})),{id:reply.id,index,total,part:text.slice(index*32000,(index+1)*32000)}).catch(()=>{});});});
  await page.route(origin+'/',async route=>{const response=await route.fetch();const html=(await response.text()).replace(/content="width=device-width, initial-scale=1"/,'content="width='+Math.max(480,scenario.w)+'"');await route.fulfill({response,body:html.replace('</head>',injection+'</head>')});});
  await page.addInitScript(()=>{Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>false});window.__fixtureSockets=[];const Native=window.WebSocket;window.WebSocket=class extends Native{constructor(...args){super(...args);window.__fixtureSockets.push(this);}};window.__DSH_CONNECTION_RECOVERY__={backoffBaseMs:5000,backoffMaxMs:5000};});
  page.on('console',m=>{if(process.env.DSH_SETTINGS_LAYOUT_PROBE&&m.text().startsWith('[dsh-native] layout-probe '))console.log(m.text());});
  await page.goto(origin+'/');await page.locator('[data-slot="settings.launcher"]').waitFor();
  // Stress layout with enlarged text in the real settings tree. This is a CSS
  // boundary approximation, not an Android WebView textZoom/device measurement.
  if(scenario.largeText)await page.addStyleTag({content:'[data-shortcut-modal="settings"][role="dialog"] :is(button,input,textarea,label,p,h2,h3,h4,span){font-size:21px;line-height:1.5}'});
  if(process.env.DSH_SETTINGS_LAYOUT_PROBE)await page.evaluate(await fs.readFile(process.env.DSH_SETTINGS_LAYOUT_PROBE,'utf8'));
  await page.waitForFunction(()=>window.__dshLocalConnectionState==='connected');
  const beforeSockets=await page.evaluate(()=>{window.__connectionSentinel=Math.random();return {count:window.__fixtureSockets.length,sentinel:window.__connectionSentinel};});
  await page.evaluate(()=>{Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>false});window.dispatchEvent(new Event('offline'));for(let i=0;i<5;i++)window.dispatchEvent(new Event('dsh-native-resume'));});
  await sleep(350);check(await page.evaluate(()=>window.__dshLocalConnectionState==='connected'&&window.__fixtureSockets.length)===beforeSockets.count,'internet offline hint and healthy foreground events keep the local carrier');
  const draft=page.locator('[data-composer-input]');if(await draft.count())await draft.fill('PRIVATE-RESUME-DRAFT');
  await page.evaluate(()=>{Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'hidden'});document.dispatchEvent(new Event('visibilitychange'));for(const socket of window.__fixtureSockets)if(socket.readyState===1)socket.close(4001,'synthetic background loss');});
  await page.waitForFunction(()=>window.__dshLocalConnectionState!=='connected');
  const reconnectAt=Date.now();await page.evaluate(()=>{Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'visible'});document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('dsh-native-resume'));});
  await page.waitForFunction(()=>window.__dshLocalConnectionState==='connected');check(Date.now()-reconnectAt<2500,'resume bypasses a five-second backoff using the original core connection');
  check(await page.evaluate(()=>window.__connectionSentinel)===beforeSockets.sentinel,'connection repair does not reload the page');check(prompts.length===0,'lifecycle recovery never resubmits a chat prompt');
  if(await draft.count()){check((await draft.innerText()).trim()==='PRIVATE-RESUME-DRAFT','connection repair preserves the actual composer draft');await draft.fill('');}
  await page.evaluate(()=>{Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>true});window.dispatchEvent(new Event('online'));});
  // Match Android's late onPageFinished state publication, after plugin/slot registration.
  await page.evaluate(session=>{window.__dshNativeAppState={integratedSettings:true,settingsSession:session,version:'fixture'};window.dispatchEvent(new Event('dsh-native-app-state'));},handshake.session);
  const open=async target=>{check(await page.evaluate(page=>!window.dispatchEvent(new CustomEvent('dsh-native-app-settings',{cancelable:true,detail:{page}})),target),'settings navigation accepted');};
  for(const target of ['tasks','data','updates','diagnostics','display']){
   await open(target);const section=page.locator('[data-device-page="'+target+'"]');await section.getByRole('button',{name:/^Save(d \d+)?$/}).waitFor();
   check(!(await section.innerText()).includes('SECRET-SYNTHETIC-CREDENTIAL'),'stored secret absent from DOM');
   const fields=section.locator('input');await fields.nth(0).fill('Edited '+target);await fields.nth(1).fill('synthetic-new-key');await section.getByRole('button',{name:/^Save(d \d+)?$/}).click();
   await section.getByRole('button',{name:/^Saved \d+$/}).waitFor();check(await fields.nth(0).inputValue()==='Edited '+target,'edit reaches actual Java controller');
   if(process.env.DSH_SETTINGS_EVIDENCE_DIR){await fs.mkdir(process.env.DSH_SETTINGS_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,'settings-'+target+'-'+scenario.w+'.png')});console.log('Settings geometry '+target+' '+scenario.w,await section.evaluate(el=>({width:el.clientWidth,height:el.clientHeight,rows:[...el.querySelectorAll('.device-row')].map(row=>({width:row.clientWidth,height:row.clientHeight,children:row.children.length}))})));}
   const choice=section.getByRole('button',{name:scenario.lang==='en'?'Select setting':'选择设置',exact:true});await choice.click();await page.getByRole('menuitem',{name:'115%',exact:true}).click();await choice.waitFor();await page.waitForFunction(()=>[...document.querySelectorAll('.device-choice-trigger')].some(el=>el.innerText==='115%'));check(await choice.innerText()==='115%','choice menu dispatches a live control');
   const bounds=await section.evaluate(el=>({scroll:el.scrollWidth,width:el.clientWidth,rect:el.getBoundingClientRect().toJSON(),vw:innerWidth}));
   check(bounds.scroll<=bounds.width+1&&bounds.rect.right<=bounds.vw+1,'device settings fit '+target);
   for(const item of await section.locator('button,input').evaluateAll(els=>els.map(el=>({width:el.clientWidth,scroll:el.scrollWidth}))))check(item.scroll<=item.width+2,'control fits '+target);
   for(const item of await section.locator('button,input').evaluateAll(els=>els.map(el=>({height:el.clientHeight,scroll:el.scrollHeight}))))check(item.scroll<=item.height+2,'control text fits its height '+target+': '+JSON.stringify(item));
  }
  await open('updates');const settingsPage=page.locator('[data-device-page="updates"]');
  await settingsPage.getByRole('button',{name:'Open model catalog',exact:true}).click();
  const modelDialog=page.getByRole('dialog').filter({has:page.getByRole('heading',{name:'Upstream models',exact:true})});
  await modelDialog.waitFor();
  const geometry=await modelDialog.evaluate(el=>({rect:el.getBoundingClientRect().toJSON(),scroll:el.scrollHeight,height:el.clientHeight,vh:innerHeight,scrollers:[...el.querySelectorAll('*')].filter(node=>getComputedStyle(node).overflowY.match(/auto|scroll/)).map(node=>({height:node.clientHeight,scroll:node.scrollHeight}))}));
  console.log('Model picker geometry',scenario.w,geometry);
  check(geometry.rect.top>=-1&&geometry.rect.bottom<=geometry.vh+1,'model catalog dialog remains within the viewport');
  const scrollable=await modelDialog.locator('*').evaluateAll(els=>els.some(el=>el.scrollHeight>el.clientHeight+30&&/auto|scroll/.test(getComputedStyle(el).overflowY)));
  check(scrollable,'long upstream catalog provides a bounded scroll container');
  const modelScroll=modelDialog.locator('[data-native-dialog-body]');const scrollRect=await modelScroll.boundingBox();
  const touch=await context.newCDPSession(page);
  await touch.send('Input.synthesizeScrollGesture',{x:scrollRect.x+scrollRect.width/2,y:scrollRect.y+scrollRect.height*.75,yDistance:-Math.min(180,scrollRect.height*.5),gestureSourceType:'touch',speed:500});
  await page.waitForFunction(()=>document.querySelector('[data-native-dialog-body]')?.scrollTop>0);
  check(await modelScroll.evaluate(el=>el.scrollTop>0),'a touch swipe scrolls the upstream catalog');await touch.detach();
  await modelDialog.getByRole('button',{name:/^upstream-model-60 /}).click();
  await settingsPage.getByRole('button',{name:'Selected model 60',exact:true}).waitFor();
  check(true,'last upstream model is reachable and selection reaches the Java controller');
  await settingsPage.getByRole('button',{name:'Open details',exact:true}).click();
  await page.getByRole('heading',{name:'Operation details',exact:true}).waitFor();
  check(await page.getByText('Operation details',{exact:true}).count()===1,'nested native dialog uses its own title without a repeated body heading');
  check(await page.evaluate(()=>!window.dispatchEvent(new Event('dsh-settings-back',{cancelable:true}))),'native detail dialog consumes hardware back');
  await page.getByText('Operation details',{exact:true}).waitFor({state:'detached'});
  const polled=new Promise(resolve=>holdPoll=resolve);await Promise.race([polled,sleep(6000).then(()=>{throw Error('state poll missing');})]);
  const previousActions=nativeActions.length;await settingsPage.getByRole('button',{name:/^Save(d \d+)?$/}).click();releasePoll();
  for(let n=0;n<100&&nativeActions.length===previousActions;n++)await sleep(20);
  check(nativeActions.length>previousActions,'a click during a background state refresh is queued and delivered');
  await page.waitForFunction(()=>!document.querySelector('[data-device-page="updates"] button')?.disabled);
  await open('sessions');await page.locator('.dsh-device-sessions').waitFor();check(await page.locator('.dsh-device-sessions').getByRole('button',{name:scenario.lang==='en'?'New conversation':'新建对话',exact:true}).isVisible(),'session manager integrated');
  await open('models');await page.getByRole('heading',{name:scenario.lang==='en'?'Import CodeBuddy CLI connection':'导入 CodeBuddy CLI 连接',exact:true}).waitFor();
  if(process.env.DSH_SETTINGS_EVIDENCE_DIR)await page.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,'settings-models-'+scenario.w+'.png')});
  const modelBounds=await page.locator('[data-device-slot="models"]').evaluate(el=>({width:el.clientWidth,scroll:el.scrollWidth,controls:[...el.querySelectorAll('button')].map(button=>({width:button.clientWidth,height:button.clientHeight,scrollWidth:button.scrollWidth,scrollHeight:button.scrollHeight}))}));
  check(modelBounds.scroll<=modelBounds.width+1,'model extensions fit their settings column');
  for(const control of modelBounds.controls)check(control.scrollWidth<=control.width+2&&control.scrollHeight<=control.height+2,'model action text is neither clipped nor overflowing: '+JSON.stringify(control));
  for(const label of await page.locator('.dsh-settings-desktop-nav>span').all()){
   if(await label.isVisible())check(await label.evaluate(el=>el.scrollWidth<=el.clientWidth+1&&el.scrollHeight<=el.clientHeight+1),'expanded-text navigation labels remain fully readable');
  }
  const documentAction=page.locator('[data-settings-open-document]');
  check(await documentAction.isVisible(),'original settings document action retains its real entry');
  check(await documentAction.evaluate(el=>el.scrollHeight<=el.clientHeight+2&&el.scrollWidth<=el.clientWidth+2),'original configuration action fits enlarged text');
  await page.getByRole('button',{name:scenario.lang==='en'?'Project models & catalog sync':'项目模型与目录同步',exact:true}).click();await page.locator('[data-device-page="models"]').getByRole('button',{name:/^Save(d \d+)?$/}).waitFor();checks++;
  if(scenario.lang==='en'){
   await page.getByRole('button',{name:'Import connection',exact:true}).click();
   const passphrase='synthetic-import-passphrase',key='synthetic-codebuddy-key-123456789';
   const bundle=Buffer.from(JSON.stringify(encrypt(key,passphrase)));
   await page.getByLabel('CodeBuddy connection file',{exact:true}).setInputFiles({name:'fixture.dshconnection',mimeType:'application/json',buffer:bundle});
   await page.getByLabel('Connection passphrase',{exact:true}).fill('wrong-passphrase');await page.getByRole('button',{name:'Import and use CodeBuddy',exact:true}).click();await page.getByText('Incorrect passphrase or damaged connection file',{exact:true}).waitFor();checks++;
   await page.getByLabel('Connection passphrase',{exact:true}).fill(passphrase);await page.getByRole('button',{name:'Import and use CodeBuddy',exact:true}).click();
   await page.getByText('CodeBuddy imported; chat access is unverified. New conversations use the global default; existing project overrides remain.',{exact:true}).waitFor();
   const state=await rpc('settings/describe');const profile=state.namespaces.find(item=>item.ns==='llm-pi-ai').value.providers.codebuddy;
   console.log('Public provider fields',JSON.stringify({baseURL:profile.baseURL,apiKeyEnv:profile.apiKeyEnv,headerNames:Object.keys(profile.headers||{})}));check(profile.baseURL==='https://www.codebuddy.cn/v2'&&profile.apiKeyEnv==='CODEBUDDY_API_KEY'&&!Object.keys(profile.headers||{}).length,'provider stored without secret headers');
   const credentials=await fs.readFile(path.join(home,'.credentials.yaml'),'utf8');check(credentials.includes(key),'credential stored via real core RPC');
   check(!JSON.stringify(state).includes(key)&&!(await page.locator('body').innerText()).includes(key),'credential absent from public description/DOM');
   const catalog=await rpc('session/modelCatalog');check(catalog.default.provider==='codebuddy'&&catalog.default.model==='deepseek-v4.1-flash'&&catalog.groups.find(g=>g.id==='codebuddy').models.length===21,'import consumed by real Host catalog');
  }
  await open('sessions');const sessionRow=page.locator('article[data-session-id="'+fileSession.sessionId+'"]');await sessionRow.waitFor();
  await sessionRow.getByRole('button',{name:scenario.lang==='en'?'Rename':'重命名',exact:true}).click();
  check(await page.evaluate(()=>!window.dispatchEvent(new Event('dsh-settings-back',{cancelable:true}))),'Android back consumed by confirmation');
  await page.getByRole('textbox',{name:scenario.lang==='en'?'Session title':'会话标题',exact:true}).waitFor({state:'detached'});
  check(await page.locator('[data-shortcut-modal="settings"]').isVisible(),'confirmation back preserves settings');
  await sessionRow.getByRole('button',{name:scenario.lang==='en'?'Continue':'继续对话',exact:true}).click();
  await page.locator('[data-shortcut-modal="settings"]').waitFor({state:'hidden'});
  const expand=page.locator('[data-sidebar-right-expand]');if(await expand.isVisible())await expand.click();
  await page.locator('[data-sidebar-right-guide-entry="files"]').click();await page.getByText('editable-notes.txt',{exact:true}).dblclick();
  const preview=page.locator('[data-textpreview-state="text"]');await preview.waitFor();
  const fileTools=preview.locator('.dsh-file-actions');await fileTools.getByRole('button',{name:scenario.lang==='en'?'Share':'分享',exact:true}).waitFor();
  for(const action of [scenario.lang==='en'?'Share':'分享',scenario.lang==='en'?'Open in app':'其他应用打开']){
   await fileTools.getByRole('button',{name:action,exact:true}).click();await fileTools.getByRole('button',{name:action,exact:true}).waitFor({state:'visible'});await sleep(100);
  }
  check(fileCommands.some(item=>item.action==='share'&&item.path.endsWith('/editable-notes.txt'))&&fileCommands.some(item=>item.action==='open'),'real file preview dispatches share/open');
  check(await fileTools.evaluate(el=>el.scrollWidth<=el.clientWidth+1),'preview actions fit mobile toolbar');
  await fileTools.getByRole('button',{name:scenario.lang==='en'?'Edit':'编辑',exact:true}).click();
  await page.locator('[data-device-page="files"]').getByRole('button',{name:/^Save(d \d+)?$/}).waitFor();
  const editor=page.locator('[data-device-page="files"]');await editor.getByRole('alert').filter({hasText:scenario.lang==='en'?'Text could not be read completely':'正文未能完整读取'}).waitFor();
  check(await editor.locator('textarea').isDisabled(),'failed chunk read cannot edit truncated text');
  await editor.getByRole('button',{name:scenario.lang==='en'?'Read again':'重新读取',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('[data-device-page="files"] textarea')?.value.length===70000);
  // Dispatch a synthetic large paste through React's real input handler, then save through the native protocol.
  await editor.locator('textarea').fill('Saved text');await editor.locator('textarea').evaluate(el=>{Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(el,'Saved text '+('text\n'.repeat(7200)));el.dispatchEvent(new Event('input',{bubbles:true}));});await editor.getByRole('button',{name:/^Save(d \d+)?$/}).click();
  await editor.getByRole('button',{name:/^Saved \d+$/}).waitFor();check((await editor.locator('textarea').inputValue()).length>32768,'large edited text survives chunk upload/save');
  check(fileCommands.at(-1)?.action==='edit'&&fileCommands.at(-1)?.path.endsWith('/editable-notes.txt'),'preview edit enters integrated file controller');
  check(await page.evaluate(()=>!window.dispatchEvent(new Event('dsh-settings-back',{cancelable:true}))),'Android back consumed by settings');
  await page.locator('[data-shortcut-modal="settings"]').waitFor({state:'hidden'});
  check(errors.length===0,'browser errors: '+errors.join(';'));
  if(process.env.DSH_SETTINGS_EVIDENCE_DIR){await fs.mkdir(process.env.DSH_SETTINGS_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,'integrated-'+scenario.w+'-'+scenario.lang+'.png')});}
  await context.close();context=null;
 }
 console.log('Integrated Web settings: '+checks+' real settings-slot, Java-controller, mobile layout and encrypted CodeBuddy/core-RPC checks passed.');
}catch(error){console.log('Original settings failure',error.message.slice(0,700));console.log('Settings core fixture',output.replace(/token=\S+/g,'token=REDACTED').slice(-5000));console.log('Settings UI fixture',(await page?.locator('body').innerText().catch(()=>''))?.slice(0,6000));if(page&&process.env.DSH_SETTINGS_EVIDENCE_DIR){await fs.mkdir(process.env.DSH_SETTINGS_EVIDENCE_DIR,{recursive:true});await page.screenshot({path:path.join(process.env.DSH_SETTINGS_EVIDENCE_DIR,'integrated-failure.png'),timeout:10000}).catch(()=>{});}throw error;}finally{await context?.close();await browser?.close();await stop();await fs.rm(root,{recursive:true,force:true});}
