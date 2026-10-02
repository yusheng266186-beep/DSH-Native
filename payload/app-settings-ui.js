/* DSH-ANDROID-APP-SETTINGS-v1 */
// This component uses the upstream settings slot, primitives, locale and workspace service.
function androidInstallAppSettings(ctx) {
    const h = react_jsx_runtime.jsx, hs = react_jsx_runtime.jsxs;
    const P = _deepseek_ai_dsh_client_ui_primitives;
    const pages = ['home', 'sessions', 'tasks', 'models', 'display', 'updates', 'data', 'diagnostics'];
    const words = {
        nav: ['App 设置', 'App settings'], home: ['App 设置', 'App settings'],
        sessions: ['会话管理', 'Sessions'], tasks: ['任务中心', 'Tasks'], models: ['模型中心', 'Models'],
        display: ['显示与语言', 'Display & language'], updates: ['更新与维护', 'Updates & maintenance'],
        data: ['数据与扩展', 'Data & extensions'], diagnostics: ['诊断与日志', 'Diagnostics & logs'],
        back: ['返回', 'Back'], refresh: ['刷新', 'Refresh'], search: ['搜索会话标题', 'Search session titles'],
        active: ['未归档', 'Active'], archived: ['已归档', 'Archived'], all: ['全部会话', 'All sessions'], trash: ['回收站', 'Trash'],
        open: ['继续对话', 'Continue'], restoreOpen: ['恢复并继续对话', 'Restore & continue'],
        restore: ['恢复', 'Restore'], rename: ['重命名', 'Rename'], archive: ['归档', 'Archive'], delete: ['移到回收站', 'Move to trash'],
        untitled: ['未命名会话', 'Untitled session'], running: ['运行中', 'Running'], idle: ['空闲', 'Idle'],
        empty: ['这里暂无会话', 'No sessions here'], loading: ['正在读取会话…', 'Loading sessions…'],
        timeout: ['连接超时，请刷新查看操作是否已完成。', 'Connection timed out. Refresh to check whether the operation completed.'],
        failed: ['操作失败，请重试', 'Operation failed. Retry.'], retry: ['重试', 'Retry'],
        save: ['保存', 'Save'], cancel: ['取消', 'Cancel'], confirm: ['确认', 'Confirm'], done: ['已完成', 'Done'],
        activeError: ['此会话仍有运行或排队任务。请先结束任务，再操作。', 'This session has active or queued work. Stop it before proceeding.'],
        archiveHint: ['归档会保留完整记录，可恢复后继续对话。', 'Archiving keeps the full record. Restore it to continue.'],
        trashHint: ['会话会从侧栏和搜索中隐藏，记录保留在回收站，可随时恢复。不会释放存储空间。', 'The session is hidden from the sidebar and search. Its record stays in Trash and can be restored. Storage is retained.'],
        title: ['会话标题', 'Session title'], previous: ['上一页', 'Previous'], next: ['下一页', 'Next'],
        new: ['新建对话', 'New conversation'], unavailable: ['原生工具暂不可用，请从 App 顶部设置重试。', 'Native tools are unavailable. Retry from the app settings button.'],
        service: ['服务状态', 'Service status'], general: ['DSH 主题与语言', 'DSH theme & language'],
        scale: ['调整文字缩放', 'Adjust text scale'], modelCenter: ['服务商、默认模型与更新 App 模型列表', 'Providers, default model & refresh app models'],
        account: ['DeepSeek 账号、余额与用量', 'DeepSeek account, balances & usage'],
        updateTools: ['App 更新、运行包恢复与重启服务', 'App updates, runtime recovery & restart'],
        dataTools: ['项目、文件、加密备份与插件', 'Projects, files, encrypted backups & plugins'],
        taskTools: ['任务状态、连接恢复与最近记录', 'Task status, recovery & recent history'],
        diagnosticTools: ['运行日志、诊断导出与网络检测', 'Logs, diagnostic export & network checks'],
        settingHint: ['管理会话、模型和设备相关设置。', 'Manage conversations, models and device settings.'],
        sessionHint: ['归档可恢复；删除会移到可恢复的回收站。运行中的会话请先停止任务。', 'Archives can be restored. Deleted sessions go to recoverable Trash. Stop active work first.'],
        nativeHint: ['打开工具页后，返回会回到这里。', 'Return from the tools panel to come back here.']
    };
    const css = `.dsh-app-settings{display:flex;flex-direction:column;gap:16px;min-width:0;color:var(--dsw-alias-label-primary)}
.dsh-app-settings h2,.dsh-app-settings h3,.dsh-app-settings p{margin:0;overflow-wrap:anywhere}
.dsh-app-settings h2{font-size:16px;font-weight:600}.dsh-app-settings h3{font-size:14px;font-weight:500}
.dsh-app-settings p,.dsh-app-settings small{font-size:13px;color:var(--dsw-alias-label-secondary);line-height:1.55}
.dsh-app-settings .app-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:8px}
.dsh-app-settings .app-card{border:1px solid var(--dsw-alias-border-l1);border-radius:12px;padding:12px;display:flex;flex-direction:column;gap:10px;min-width:0}
.dsh-app-settings .app-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}
.dsh-app-settings .app-row>input{flex:1;min-width:120px}.dsh-app-settings .app-heading{flex:1;min-width:120px}
.dsh-app-settings button{min-height:36px;max-width:100%;white-space:normal;height:auto;overflow-wrap:anywhere;flex-shrink:0}
.dsh-app-settings .app-grid>button{text-align:start;justify-content:flex-start;min-height:46px}
.dsh-app-settings .app-tabs>button[aria-pressed=true]{background:var(--dsw-alias-bg-layer-2);font-weight:600}
.dsh-app-settings .app-error{color:var(--dsw-alias-label-error,#b94040)}
@media(max-width:600px){.dsh-app-settings button{min-height:44px}.dsh-app-settings .app-grid{grid-template-columns:1fr}.dsh-app-settings .app-card{padding:10px}}`;
    function AppSection(props) {
        const locale = react.useSyncExternalStore(cb => ctx.locale.subscribe(cb), () => ctx.locale.getSnapshot());
        const en = /^en/.test(locale.active), t = key => words[key]?.[en ? 1 : 0] || key;
        const [page, setPage] = react.useState(() => pages.includes(window.__dshNativeSettingsPage) ? window.__dshNativeSettingsPage : 'home');
        const [notice, setNotice] = react.useState(''), [bridgeBusy, setBridgeBusy] = react.useState(false);
        const [nativeState, setNativeState] = react.useState(() => window.__dshNativeAppState || {});
        const bridge = react.useRef(null);
        react.useEffect(() => {
            const navigate = event => {if (pages.includes(event.detail?.page)) {setPage(event.detail.page); setNotice('');}};
            const state = () => setNativeState(window.__dshNativeAppState || {});
            const ack = event => {
                if (event.detail?.id !== bridge.current?.id) return;
                clearTimeout(bridge.current.timer); bridge.current = null; setBridgeBusy(false);
                if (event.detail.accepted) props.close(); else setNotice(t('unavailable'));
            };
            window.addEventListener('dsh-native-app-settings', navigate);
            window.addEventListener('dsh-native-app-state', state);
            window.addEventListener('dsh-native-settings-ack', ack);
            return () => {
                window.removeEventListener('dsh-native-app-settings', navigate);
                window.removeEventListener('dsh-native-app-state', state);
                window.removeEventListener('dsh-native-settings-ack', ack);
                if (bridge.current) clearTimeout(bridge.current.timer);
            };
        }, [props.close, en]);
        const nativeAction = action => {
            if (bridge.current) return;
            const id = 'app-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
            setBridgeBusy(true); setNotice('');
            bridge.current = {id, timer: setTimeout(() => {bridge.current = null; setBridgeBusy(false); setNotice(t('unavailable'));}, 3000)};
            console.log('[dsh-native-settings] ' + JSON.stringify({id, action, locale: en ? 'en' : 'zh'}));
        };
        const section = id => window.dispatchEvent(new CustomEvent('dsh-native-app-settings', {cancelable:true, detail:{section:id}}));
        const button = (key, action, options = {}) => h(P.Button, {variant:'outline', ...options, onClick:action, children:t(key)}, key);
        const nativeButton = (key, action) => button(key, () => nativeAction(action), {disabled:bridgeBusy});
        const items = {tasks:['taskTools','tasks'], models:['modelCenter','models'], updates:['updateTools','updates'], data:['dataTools','data'], diagnostics:['diagnosticTools','diagnostics']};
        return hs('section', {className:'dsh-app-settings', 'data-app-page':page, children:[
            h('style', {children:css}),
            hs('div', {className:'app-row', children:[page !== 'home' && button('back', () => {setPage('home'); window.__dshNativeSettingsPage='home';}), h('h2', {className:'app-heading',children:t(page)})]}),
            page === 'home' ? hs(react.Fragment, {children:[h('p',{children:t('settingHint')}), h('div',{className:'app-grid',children:pages.slice(1).map(key => button(key, () => {setPage(key); window.__dshNativeSettingsPage=key;}))}),
                nativeState.version && h('small',{children:'App ' + nativeState.version})]}) : page === 'sessions' ? h(SessionManager,{t,en,close:props.close}) : hs(react.Fragment,{children:[
                h('p',{children:t('nativeHint')}), page === 'display' ? hs('div',{className:'app-row',children:[button('general',()=>section('general')),nativeButton('scale','display')]}) : nativeButton(items[page][0],items[page][1]),
                page === 'models' && button('account',()=>section('account')),
                page === 'tasks' && nativeState.task && h('p',{children:nativeState.task}),
                page === 'display' && nativeState.zoom && h('small',{children:nativeState.zoom + '%'})]}),
            notice && h('p',{role:'alert',className:'app-error',children:notice})]});
    }
    async function rpc(method, request, signal) {
        const args = request === undefined ? {} : {request};
        const id='app-settings-'+Date.now()+'-'+Math.random().toString(36).slice(2);
        const controller=new AbortController(), cancel=()=>controller.abort();
        if(signal?.aborted)controller.abort(); else signal?.addEventListener('abort',cancel,{once:true});
        const timer=setTimeout(cancel,15000);
        try {
            const response = await fetch('/api/' + method, {method:'POST', credentials:'same-origin', signal:controller.signal,
                headers:{'content-type':'application/json'}, body:JSON.stringify({type:'client-request',rpcId:id,method,payload:{args}})});
            if (!response.ok) throw new Error('HTTP ' + response.status);
            const packet = await response.json();
            if(packet.type!=='server-response'||packet.rpcId!==id||!packet.result)throw new Error('Invalid core reply');
            if (!packet.result.ok) {const error = new Error(packet.result.error?.message || 'Core operation failed'); error.code=packet.result.error?.code; throw error;}
            return packet.result.value;
        } catch(error) {
            if(controller.signal.aborted&&!signal?.aborted){const timeout=new Error('Core request timed out');timeout.code='android/core-timeout';throw timeout;}
            throw error;
        } finally {clearTimeout(timer);signal?.removeEventListener('abort',cancel);}
    }
    function SessionManager({t,en,close}) {
        const [rows,setRows]=react.useState([]), [mode,setMode]=react.useState('active'), [query,setQuery]=react.useState(''), [page,setPage]=react.useState(0);
        const [loading,setLoading]=react.useState(true), [readError,setReadError]=react.useState(''), [error,setError]=react.useState(''), [notice,setNotice]=react.useState(''), [busy,setBusy]=react.useState(false);
        const [confirmation,setConfirmation]=react.useState(null), [title,setTitle]=react.useState('');
        const alive=react.useRef(true), read=react.useRef(null), locked=react.useRef(false), revision=react.useRef(0);
        const explain = err => err.code==='android/core-timeout'?t('timeout'):/session-active|ActiveSession|active work|queued|still running/i.test((err.code || '') + ' ' + (err.message || '')) ? t('activeError') : t('failed') + ' (' + (err.code || err.message || 'unknown') + ')';
        const refresh = async (silent=false) => {
            if (locked.current || (silent && read.current)) return;
            read.current?.abort(); const controller=new AbortController(); read.current=controller; const ticket=++revision.current;
            if (!silent) {setLoading(true);setError('');}
            try {const value=await rpc('session/androidList',undefined,controller.signal);
                if(!Array.isArray(value.items)||value.items.some(row=>typeof row.sessionId!=='string'||!row.sessionId||row.sessionId.length>512)||new Set(value.items.map(row=>row.sessionId)).size!==value.items.length)throw new Error('Invalid session list');
                if(alive.current && ticket===revision.current){setRows(value.items);setReadError('');}}
            catch(err){if(alive.current && ticket===revision.current && err.name!=='AbortError')setReadError(explain(err));}
            finally{if(ticket===revision.current){read.current=null;if(alive.current)setLoading(false);}}
        };
        react.useEffect(()=>{alive.current=true; refresh(); const timer=setInterval(()=>{if(document.visibilityState==='visible')refresh(true);},4000);
            return()=>{alive.current=false;clearInterval(timer);read.current?.abort();};},[]);
        const act = async (kind,row) => {
            if(locked.current)return; locked.current=true; read.current?.abort(); read.current=null; revision.current++; setBusy(true);setError('');setNotice('');
            try {
                const request={sessionId:row.sessionId};
                if(kind==='rename') await rpc('session/rename',{...request,title:title.trim()});
                if(kind==='archive') await rpc('workspace/archiveSession',request);
                if(kind==='delete') await rpc('session/androidTrash',request);
                if(kind==='restore'||kind==='restoreOpen') await rpc('session/androidRestore',request);
                if(kind==='open'||kind==='restoreOpen') {await ctx.get('sessions').refresh(); if(alive.current){ctx.get('uiWorkspace').openSession(row.sessionId);close();}}
                else if(alive.current){setConfirmation(null);setNotice(t('done'));}
            } catch(err){if(alive.current)setError(explain(err));}
            finally {locked.current=false;if(alive.current){setBusy(false);await refresh(true);}}
        };
        const filtered=rows.filter(row=> mode==='trash'?row.trashed: !row.trashed && (mode==='all'|| (mode==='archived'?row.archived:!row.archived)))
            .filter(row=>(row.title||t('untitled')).toLocaleLowerCase().includes(query.toLocaleLowerCase().trim()))
            .sort((a,b)=>(mode==='trash'?b.deletedAt-a.deletedAt: b.updatedAt-a.updatedAt));
        const total=Math.max(1,Math.ceil(filtered.length/20)), current=Math.min(page,total-1), failure=error||readError;
        const button=(key,onClick,extra={})=>h(P.Button,{variant:'outline',disabled:busy,onClick,...extra,children:t(key)},key);
        const ask=(kind,row)=>{setTitle(row.title||'');setError('');setConfirmation({kind,row});};
        return hs(react.Fragment,{children:[h('p',{children:t('sessionHint')}),
            hs('div',{className:'app-row',children:[h(P.Input,{'aria-label':t('search'),placeholder:t('search'),value:query,onChange:event=>{setQuery(event.target.value);setPage(0);}}),button('refresh',()=>refresh()),button('new',()=>{ctx.get('uiWorkspace').startSession();close();})]}),
            h('div',{className:'app-row app-tabs',children:['active','archived','all','trash'].map(key=>button(key,()=>{setMode(key);setPage(0);}, {'aria-pressed':mode===key}))}),
            loading && h('p',{role:'status',children:t('loading')}), failure && hs('div',{className:'app-row',children:[h('p',{role:'alert',className:'app-error',children:failure}),button('retry',()=>refresh())]}), notice && h('p',{role:'status',children:notice}),
            !loading && !failure && !filtered.length && h('p',{children:t('empty')}),
            filtered.slice(current*20,(current+1)*20).map(row=>hs('article',{className:'app-card','data-session-id':row.sessionId,children:[
                h('h3',{children:row.title||t('untitled')}), h('small',{children:(row.trashed?t('trash'):row.archived?t('archived'):row.running?t('running'):t('idle'))+' · '+new Date(row.deletedAt||row.updatedAt).toLocaleString(en?'en':'zh')}),
                h('div',{className:'app-row',children:row.trashed?[button('restore',()=>act('restore',row)),button('restoreOpen',()=>act('restoreOpen',row))]:[
                    button(row.archived?'restoreOpen':'open',()=>act(row.archived?'restoreOpen':'open',row)),button('rename',()=>ask('rename',row)),
                    row.archived?button('restore',()=>act('restore',row)):button('archive',()=>ask('archive',row)),button('delete',()=>ask('delete',row))]})]},row.sessionId)),
            total>1 && hs('div',{className:'app-row',children:[button('previous',()=>setPage(current-1),{disabled:busy||current===0}),h('small',{children:(current+1)+' / '+total+' · '+filtered.length}),button('next',()=>setPage(current+1),{disabled:busy||current>=total-1})]}),
            h(P.Modal,{open:!!confirmation,onClose:()=>{if(!busy)setConfirmation(null);},title:confirmation?t(confirmation.kind):'',closeLabel:t('cancel'),
                footer:hs('div',{className:'dsh-app-settings',children:[hs('div',{className:'app-row',children:[button('cancel',()=>setConfirmation(null)),button(confirmation?.kind==='rename'?'save':'confirm',()=>act(confirmation.kind,confirmation.row),{variant:'primary',disabled:busy||(confirmation?.kind==='rename'&&(!title.trim()||title.trim().length>200))})]})]}),
                children:hs('div',{className:'dsh-app-settings',children:[confirmation?.kind==='rename'?h(P.Input,{'aria-label':t('title'),value:title,maxLength:200,onChange:event=>setTitle(event.target.value),autoFocus:true}):h('p',{children:t(confirmation?.kind==='archive'?'archiveHint':'trashHint')}),
                    confirmation && h('p',{children:confirmation.row.title||t('untitled')}),error&&h('p',{role:'alert',className:'app-error',children:error})]})})]});
    }
    ctx.slots.inject('settings.section',()=>ctx.slots.register({name:'settings.section',id:'android-app',order:90,label:()=>/^en/.test(ctx.locale.getSnapshot().active)?words.nav[1]:words.nav[0]},AppSection));
}
/* DSH-ANDROID-APP-SETTINGS-END */
