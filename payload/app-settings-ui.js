/* DSH-ANDROID-APP-SETTINGS-v1 */
function androidSettingsNavigation(rows,active,onSelect) { return androidSettingsNavigation.render(rows,active,onSelect); }
// This component uses the upstream settings slot, primitives, locale and workspace service.
function androidInstallAppSettings(ctx) {
    const h = react_jsx_runtime.jsx, hs = react_jsx_runtime.jsxs;
    const P = _deepseek_ai_dsh_client_ui_primitives;
    // Use the same upstream menu, navigation cells and modal-layer behavior on phones.
    function SettingsNavigation({rows,active,onSelect}) {
        const [open,setOpen]=react.useState(false);
        react.useEffect(()=>setOpen(false),[active]);
        react.useEffect(()=>{const back=event=>{if(open){event.preventDefault();setOpen(false);}};window.addEventListener('dsh-device-back',back);return()=>window.removeEventListener('dsh-device-back',back);},[open]);
        return hs(react.Fragment,{children:[h('style',{children:'[data-slot="settings.general.item"]>div{min-width:0;max-width:100%;flex-wrap:wrap;row-gap:12px}[data-slot="settings.general.item"]>div>:first-child{min-width:0}[data-slot="settings.general.item"]>div>:last-child{flex-shrink:0;max-width:100%}.dsh-settings-mobile-nav{display:none}@media(max-width:520px){[data-shortcut-modal="settings"] .dsh-settings-desktop-nav{display:none}[data-shortcut-modal="settings"] .dsh-settings-mobile-nav{display:block;width:100%;min-width:0}[data-shortcut-modal="settings"] .dsh-settings-nav-trigger{display:flex;align-items:center;justify-content:space-between;width:100%;gap:12px;text-align:start;min-height:40px}[data-shortcut-modal="settings"] .dsh-settings-nav-trigger>span{min-width:0;overflow-wrap:anywhere}}'}),
            h('div',{className:'dsh-settings-mobile-nav',children:h(P.Menu,{open,onClose:()=>setOpen(false),portal:true,align:'start',items:rows.map(row=>({id:row.id,label:row.label})),selectedId:active,onSelect:id=>{setOpen(false);onSelect(id);},anchor:hs(P.Button,{className:'dsh-settings-nav-trigger',variant:'outline','aria-label':'Settings section','aria-haspopup':'menu','aria-expanded':open,onClick:()=>setOpen(value=>!value),children:[h('span',{children:rows.find(row=>row.id===active)?.label}),h(P.IconChevronDownOutlineRegular,{size:14})]})})}),
            ...rows.map(row=>hs('button',{type:'button',className:SettingsRoot_module_css_default.navCell+' dsh-settings-desktop-nav'+(row.id===active?' '+SettingsRoot_module_css_default.active:''),'data-settings-category':row.id,'aria-current':row.id===active?'true':undefined,'data-modal-autofocus':row.id===active?'':undefined,onClick:()=>onSelect(row.id),children:[navIcon(row.id),h('span',{className:SettingsRoot_module_css_default.navLabel,children:row.label})]},row.id))]});
    }
    androidSettingsNavigation.render=(rows,active,onSelect)=>h(SettingsNavigation,{rows,active,onSelect});
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
    async function rpc(method, request, signal, raw=false) {
        const args = raw ? request : request === undefined ? {} : {request};
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
        react.useEffect(()=>{const back=event=>{if(confirmation){event.preventDefault();if(!busy)setConfirmation(null);}};window.addEventListener('dsh-device-back',back);return()=>window.removeEventListener('dsh-device-back',back);},[confirmation,busy]);
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
    // The transport is UI-only: current-document control IDs, no paths, scripts or arbitrary methods.
    const nativePending = new Map();
    let nativeQueue = Promise.resolve();
    window.addEventListener('dsh-settings-chunk', event => {
        const packet=event.detail, entry=nativePending.get(packet?.id);
        if(!entry||!Number.isInteger(packet.index)||!Number.isInteger(packet.total)||packet.total<1||packet.total>256||packet.index<0||packet.index>=packet.total||typeof packet.part!=='string'||packet.part.length>32000)return;
        if(entry.total!==undefined&&entry.total!==packet.total)return;
        entry.total=packet.total;entry.parts[packet.index]=packet.part;
        if(entry.parts.filter(value=>typeof value==='string').length!==packet.total)return;
        clearTimeout(entry.timer);nativePending.delete(packet.id);
        try{const reply=JSON.parse(entry.parts.join(''));if(!reply.ok)throw new Error(reply.error||'Settings operation failed');entry.resolve(reply.value);}catch(error){entry.reject(error);}
    });
    function nativeRequest(op, fields={}) {
        const run=()=>new Promise((resolve,reject)=>{
            const session=window.__dshNativeAppState?.settingsSession;
            if(!/^[a-f0-9]{32}$/.test(session||'')){reject(new Error('Settings bridge unavailable'));return;}
            const id='ws-'+Date.now()+'-'+Math.random().toString(36).slice(2,10);
            const timer=setTimeout(()=>{nativePending.delete(id);reject(new Error('Settings timed out. Check whether the action completed before retrying.'));},15000);
            nativePending.set(id,{resolve,reject,timer,parts:[]});
            console.log('[dsh-settings-ui] '+JSON.stringify({id,session,op,...fields}));
        });
        const next=nativeQueue.then(run,run);nativeQueue=next.catch(()=>{});return next;
    }
    const nativeCss=`
.dsh-device-settings{display:flex;flex-direction:column;min-width:0;width:100%;color:var(--dsw-alias-label-primary);font:inherit;container-type:inline-size}
.dsh-device-settings *{box-sizing:border-box}
.dsh-device-settings .device-group{display:flex;flex-direction:column;min-width:0;gap:8px}
.dsh-device-settings .device-row{display:flex;align-items:center;flex-wrap:wrap;min-width:0;gap:8px}
.dsh-device-settings .device-row>*{min-width:0;max-width:100%}
.dsh-device-settings .device-row>.device-group{flex:1 1 220px}
.dsh-device-settings .device-text{font-size:14px;line-height:20px;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}
.dsh-device-settings .device-heading{font-size:14px;line-height:20px;font-weight:500;margin:0;overflow-wrap:anywhere}
.dsh-device-settings .device-muted{color:var(--dsw-alias-label-secondary);font-size:12px;line-height:18px;white-space:pre-wrap;overflow-wrap:anywhere;margin:0}
.dsh-device-settings .device-page-title{font-size:14px;font-weight:500;margin:0 0 16px}
.dsh-device-settings .device-setting{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,280px);align-items:start;gap:24px;padding:16px 0;border-bottom:.5px solid var(--dsw-alias-border-l2)}
.dsh-device-settings .device-setting:last-child{border-bottom:0}
.dsh-device-settings .device-copy,.dsh-device-settings .device-controls{display:flex;flex-direction:column;gap:8px;min-width:0}
.dsh-device-settings .device-setting-plain{display:flex;flex-direction:column;gap:12px}
.dsh-device-settings .device-label{font-size:12px;line-height:18px;color:var(--dsw-alias-label-secondary);overflow-wrap:anywhere}
.dsh-device-settings input,.dsh-device-settings textarea{max-width:100%;width:100%;min-width:0;font:inherit}
.dsh-device-settings textarea{border:1px solid var(--dsw-alias-border-l2);border-radius:var(--dsw-radius-sm);color:inherit;background:var(--dsw-alias-bg-layer-2);min-height:160px;resize:vertical;padding:10px}
.dsh-device-settings button{white-space:normal;overflow-wrap:anywhere;max-width:100%;height:auto;min-height:32px;line-height:20px}
.dsh-device-settings .device-choice-trigger{display:flex;align-items:center;justify-content:space-between;gap:12px;min-width:0;width:100%}
.dsh-device-settings .device-choice-trigger>span{min-width:0;overflow-wrap:anywhere}
.dsh-device-settings .device-choice-trigger>svg{flex:none}
.dsh-device-settings .device-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px;padding-top:16px;margin-top:8px;border-top:.5px solid var(--dsw-alias-border-l2)}
.dsh-device-settings .device-actions>.device-row{justify-content:flex-end;flex:1}
.dsh-device-import-row{flex-wrap:wrap;min-width:0}.dsh-device-import-row>.device-copy{flex:1 1 240px;min-width:0}.dsh-device-import-row>button{flex:none}.dsh-device-settings.device-import-form{gap:12px}.dsh-device-models{display:flex;flex-direction:column;align-items:stretch;min-width:0;width:100%;gap:16px}
.dsh-device-sessions .app-card{border-width:0 0 .5px;border-radius:0;padding:16px 0}
.dsh-device-settings progress,.dsh-device-settings img{max-width:100%}
.dsh-device-settings img{object-fit:contain}
.dsh-device-settings .device-error{color:var(--dsw-alias-label-error);overflow-wrap:anywhere}
@container(max-width:560px){.dsh-device-settings .device-setting{grid-template-columns:minmax(0,1fr);gap:12px}.dsh-device-settings .device-row{align-items:stretch}.dsh-device-settings .device-row>.device-group{flex-basis:100%}}
@media(pointer:coarse){.dsh-device-settings button{min-height:40px}}
`;
    function NativeChoices({nodes,busy,en,act}) {
        const [open,setOpen]=react.useState(false),selected=nodes.find(node=>node.selected)||nodes[0];
        const signature=nodes.map(node=>node.id).join(',');react.useEffect(()=>setOpen(false),[signature]);
        react.useEffect(()=>{const back=event=>{if(open){event.preventDefault();setOpen(false);}};window.addEventListener('dsh-device-back',back);return()=>window.removeEventListener('dsh-device-back',back);},[open]);
        return h(P.Menu,{open,onClose:()=>setOpen(false),portal:true,align:'end',selectedId:selected?.id,
            items:nodes.map(node=>({id:node.id,label:node.text,disabled:!node.enabled})),
            onSelect:id=>{const node=nodes.find(node=>node.id===id);setOpen(false);if(node?.enabled&&!busy)act('act',{control:id});},
            anchor:hs(P.Button,{className:'device-choice-trigger',variant:'outline',disabled:busy||nodes.every(node=>!node.enabled),'aria-label':en?'Select setting':'选择设置','aria-haspopup':'menu','aria-expanded':open,onClick:()=>setOpen(value=>!value),children:[h('span',{children:selected?.text}),h(P.IconChevronDownOutlineRegular,{size:14})]})});
    }
    function NativeInput({node,edits,locked,en,activate}) {
        const [value,setValue]=react.useState(node.value||''),[loading,setLoading]=react.useState(false),[loadError,setLoadError]=react.useState(false),[retry,setRetry]=react.useState(0),ref=react.useRef(null);
        react.useEffect(()=>{
            if(edits.current.has(node.id)||document.activeElement===ref.current)return;
            if(node.password)return;
            if(node.length<=32768){setValue(node.value||'');return;}
            let alive=true;setLoading(true);setLoadError(false);
            (async()=>{let text=node.value||'';while(text.length<node.length){const part=await nativeRequest('read',{control:node.id,offset:text.length});if(part.length!==node.length||!part.value)throw new Error('Text changed. Reopen the editor.');text+=part.value;}if(alive)setValue(text);})().catch(()=>{if(alive)setLoadError(true);}).finally(()=>{if(alive)setLoading(false);});
            return()=>{alive=false;};
        },[node.id,node.value,node.length,retry]);
        const props={ref,id:'dsh-setting-'+node.id,value,readOnly:node.readonly,disabled:locked||!node.enabled||loading||loadError,'aria-label':node.hint||(en?'Setting value':'设置值'),placeholder:node.password&&node.configured?(en?'Configured; leave unchanged to keep':'已配置；未修改则保留'):node.hint||'',onChange:event=>{setValue(event.target.value);edits.current.set(node.id,event.target.value);}};
        const field=node.multiline?h('textarea',props):h(P.Input,{...props,type:node.password?'password':'text',readOnly:node.readonly,onClick:node.readonly&&node.clickable?activate:undefined,autoComplete:'off'});
        return loadError?hs('div',{className:'device-group',children:[field,h('p',{role:'alert',className:'device-error',children:en?'Text could not be read completely. Retry before editing.':'正文未能完整读取，重新读取后才能编辑。'}),h(P.Button,{variant:'outline',onClick:()=>setRetry(value=>value+1),children:en?'Read again':'重新读取'})]}):field;
    }
    function NativePage({page,locale,fileRequest}) {
        const en=/^en/.test(locale.active),[snapshot,setSnapshot]=react.useState(null),[error,setError]=react.useState(''),[busy,setBusy]=react.useState(true);
        const alive=react.useRef(true),locked=react.useRef(false),edits=react.useRef(new Map());
        const display=value=>{if(alive.current){setSnapshot(value);setError('');}};
        const flush=async()=>{for(const [control,value] of edits.current){const ticket='edit-'+Date.now()+'-'+Math.random().toString(36).slice(2,8);for(let offset=0;offset<Math.max(1,value.length);offset+=8192)await nativeRequest('input',{control,ticket,offset,value:value.slice(offset,offset+8192),done:offset+8192>=value.length});edits.current.delete(control);}};
        const act=async(op,fields={})=>{if(locked.current)return;locked.current=true;setBusy(true);setError('');try{await flush();display(await nativeRequest(op,fields));}catch(failure){if(alive.current)setError(failure.message);}finally{locked.current=false;if(alive.current)setBusy(false);}};
        react.useEffect(()=>{alive.current=true;locked.current=true;edits.current.clear();nativeRequest(fileRequest?'file':'open',fileRequest?{action:fileRequest.action,path:fileRequest.path,locale:en?'en':'zh'}:{page,locale:en?'en':'zh'}).then(display).catch(failure=>{if(alive.current)setError(failure.message);}).finally(()=>{locked.current=false;if(alive.current)setBusy(false);});
            const timer=setInterval(()=>{if(locked.current||document.visibilityState==='hidden'||edits.current.size)return;locked.current=true;nativeRequest('state').then(display).catch(failure=>{if(alive.current)setError(failure.message);}).finally(()=>{locked.current=false;});},1000);
            return()=>{alive.current=false;clearInterval(timer);edits.current.clear();nativeRequest('close').catch(()=>{});};
        },[page,en,fileRequest?.id]);
        react.useEffect(()=>{const back=event=>{if((snapshot?.pages?.length||0)>1){event.preventDefault();if(!busy)act('back');}};window.addEventListener('dsh-device-back',back);return()=>window.removeEventListener('dsh-device-back',back);});
        const render=node=>{
            if(!node||(node.type==='group'&&!node.children?.length))return null;
            const key=node.id;
            if(node.type==='input')return h(NativeInput,{key,node,edits,locked:busy,en,activate:()=>act('act',{control:node.id})});
            if(node.type==='button'||node.clickable){const children=node.text||node.children?.map(render),button=h(P.Button,{key,variant:node.selected||node.primary?'primary':'outline',disabled:busy||!node.enabled,onClick:()=>act('act',{control:node.id}),'aria-pressed':node.selected||undefined,children});return node.long?hs('div',{key,className:'device-row',children:[button,h(P.Button,{variant:'ghost',disabled:busy||!node.enabled,onClick:()=>act('long',{control:node.id}),children:en?'More':'更多'})]}):button;}
            if(node.type==='text')return h(node.semantic==='title'||node.large?'h3':node.semantic==='section'||node.bold?'h4':'p',{key,className:node.semantic==='title'?'device-page-title':node.semantic==='hint'||node.semantic==='status'?'device-muted':node.semantic==='label'?'device-label':node.large||node.bold||node.semantic==='section'?'device-heading':'device-text',role:node.semantic==='status'?'status':undefined,children:node.text});
            if(node.type==='progress')return h('progress',{key,max:node.max||100,value:node.indeterminate?undefined:node.value,'aria-label':en?'Progress':'进度'});
            if(node.type==='image')return node.src?h('img',{key,src:node.src,alt:en?'File preview':'文件预览'}):h('p',{key,className:'device-muted',children:en?'Preview unavailable':'无法显示预览'});
            if(node.semantic==='body')return renderBody(node);
            if(node.children?.length>1&&node.children.every(child=>child.semantic==='choice'))return h(NativeChoices,{key,nodes:node.children,busy,en,act});
            return h('div',{key,className:node.row?'device-row':'device-group',children:renderChildren(node.children||[])});
        };
        const renderChildren=nodes=>{
            const items=[];
            for(let i=0;i<nodes.length;i++){
                const node=nodes[i];
                if(node.semantic==='choice'){
                    const choices=[node];while(nodes[i+1]?.semantic==='choice')choices.push(nodes[++i]);
                    items.push(choices.length>1?h(NativeChoices,{key:node.id,nodes:choices,busy,en,act}):render(node));
                }else if(node.semantic==='label'&&nodes[i+1]?.type==='input'){
                    const input=nodes[++i];items.push(hs('div',{key:node.id,className:'device-group',children:[h('label',{className:'device-label',htmlFor:'dsh-setting-'+input.id,children:node.text}),render(input)]}));
                }else items.push(render(node));
            }
            return items;
        };
        const renderBody=node=>{
            const sections=[];let current={nodes:[]};
            for(const child of node.children||[]){
                if(child.semantic==='section'){if(current.nodes.length||current.title)sections.push(current);current={title:child,nodes:[]};}
                else current.nodes.push(child);
            }
            if(current.nodes.length||current.title)sections.push(current);
            return h('div',{key:node.id,className:'device-group',children:sections.map((section,index)=>{
                section={...section,nodes:[...section.nodes]};
                const hints=[];while(section.nodes[0]?.semantic==='hint')hints.push(section.nodes.shift());
                if(page==='display'&&!section.title)section.nodes=section.nodes.filter(child=>!['title','hint'].includes(child.semantic));
                if(!section.title&&!section.nodes.length&&!hints.length)return null;
                const controls=renderChildren(section.nodes);
                return section.title?hs('section',{key:section.title.id,className:'device-setting',children:[hs('div',{className:'device-copy',children:[render(section.title),...hints.map(render)]}),h('div',{className:'device-controls',children:controls})]}):h('div',{key:index,className:'device-setting-plain',children:[...hints.map(render),...controls]});
            })});
        };
        const dialogs=snapshot?.pages||[],main=dialogs[0],footer=main?.footer?{...main.footer,children:main.footer.children?.filter(node=>!['返回','Back','关闭','Close'].includes(node.text))}:null;
        return hs('div',{className:'dsh-device-settings','data-device-page':page,children:[h('style',{children:nativeCss}),busy&&!snapshot&&h('p',{role:'status',children:en?'Loading…':'正在读取…'}),main&&render(main.body),footer?.children?.length>0&&h('div',{className:'device-actions',children:render(footer)}),snapshot?.notice&&h('p',{role:'status',className:'device-muted',children:snapshot.notice}),error&&hs('div',{role:'alert',className:'device-error',children:[h('p',{children:error}),h(P.Button,{variant:'outline',onClick:()=>act('open',{page,locale:en?'en':'zh'}),children:en?'Retry':'重试'})]}),!busy&&!error&&!main&&h(P.Button,{variant:'outline',onClick:()=>act('open',{page,locale:en?'en':'zh'}),children:en?'Open settings':'打开设置'}),dialogs.slice(1).map(dialog=>h(P.Modal,{key:dialog.id,open:true,onClose:()=>{if(!busy&&dialog.cancelable)act('back');},closeLabel:en?'Back':'返回',title:en?'Settings':'设置',footer:dialog.footer&&h('div',{className:'dsh-device-settings device-actions',children:render(dialog.footer)}),children:h('div',{className:'dsh-device-settings',children:render(dialog.body)})}))]});
    }
    function DeviceSection({page,close}) {
        const locale=react.useSyncExternalStore(cb=>ctx.locale.subscribe(cb),()=>ctx.locale.getSnapshot());
        const [fileRequest,setFileRequest]=react.useState(()=>page==='files'?window.__dshNativeFileRequest:null);
        react.useEffect(()=>{const update=()=>{if(page==='files')setFileRequest(window.__dshNativeFileRequest);};window.addEventListener('dsh-device-file',update);return()=>window.removeEventListener('dsh-device-file',update);},[page]);
        return h(NativePage,{page,locale,fileRequest:page==='files'?fileRequest:null});
    }
    function DeviceHome() {
        const locale=react.useSyncExternalStore(cb=>ctx.locale.subscribe(cb),()=>ctx.locale.getSnapshot()),en=/^en/.test(locale.active);
        const [info,setInfo]=react.useState(window.__dshNativeAppState||{});
        react.useEffect(()=>{const update=()=>setInfo(window.__dshNativeAppState||{});window.addEventListener('dsh-native-app-state',update);return()=>window.removeEventListener('dsh-native-app-state',update);},[]);
        return hs('div',{className:'dsh-device-settings',children:[h('style',{children:nativeCss}),h('p',{className:'device-text',children:'DSH Native '+(info.version||'')}),h('p',{className:'device-muted',children:info.task||''}),h('p',{className:'device-text',children:en?'Display, models, files and maintenance follow the current workspace settings.':'显示、模型、文件和维护使用当前工作区的设置。'})]});
    }
    const codeBuddyDefaultModels=[{"id":"default-model","name":"Auto","contextWindow":176000,"maxTokens":24000,"input":["text","image"],"reasoningEfforts":{"off":null,"max":"max"}},{"id":"fast-model","name":"Fast","contextWindow":200000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"medium":"medium","max":"max"}},{"id":"balanced-model","name":"Balanced","contextWindow":256000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"medium":"medium","max":"max"}},{"id":"primary-model","name":"Primary","contextWindow":272000,"maxTokens":72000,"input":["text","image"],"reasoningEfforts":{"high":"high","max":"max"}},{"id":"deep-model","name":"Deep","contextWindow":176000,"maxTokens":24000,"input":["text","image"],"reasoningEfforts":{"off":null,"max":"max"}},{"id":"deepseek-v4.1-flash","name":"Deepseek-V4.1-Flash","contextWindow":300000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"high":"high","max":"max"}},{"id":"gpt-6-astra","name":"GPT-6-Astra","contextWindow":400000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"hy4-preview","name":"Hy4 preview","contextWindow":200000,"maxTokens":64000,"input":["text","image"],"reasoningEfforts":{"high":"high","max":"max"}},{"id":"hy3","name":"Hy3","contextWindow":192000,"maxTokens":64000,"input":["text","image"],"reasoningEfforts":{"low":"low","high":"high","max":"max"}},{"id":"kimi-k2.8-preview","name":"Kimi-K2.8-Preview","contextWindow":300000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"low":"low","high":"high","max":"max"}},{"id":"gpt-5.6-sol","name":"GPT-5.6-Sol","contextWindow":1000000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"gpt-5.6-terra","name":"GPT-5.6-Terra","contextWindow":1000000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"gpt-5.6-luna","name":"GPT-5.6-Luna","contextWindow":1000000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"gpt-5.5","name":"GPT-5.5","contextWindow":1000000,"maxTokens":128000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"gpt-5.4","name":"GPT-5.4","contextWindow":272000,"maxTokens":72000,"input":["text","image"],"reasoningEfforts":{"low":"low","medium":"medium","high":"high","xhigh":"xhigh","max":"max"}},{"id":"gemini-3.5-flash","name":"Gemini-3.5-Flash","contextWindow":1000000,"maxTokens":65536,"input":["text","image"],"reasoningEfforts":{"medium":"medium","max":"max"}},{"id":"glm-5.3-flash","name":"GLM-5.3-Flash","contextWindow":300000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"low":"low","high":"high","max":"max"}},{"id":"glm-5.3","name":"GLM-5.3","contextWindow":1000000,"maxTokens":48000,"input":["text","image"],"reasoningEfforts":{"low":"low","high":"high","max":"max"}},{"id":"glm-5.2","name":"GLM-5.2","contextWindow":1000000,"maxTokens":48000,"input":["text","image"],"reasoningEfforts":{"high":"high","xhigh":"xhigh","max":"max"}},{"id":"kimi-k3","name":"Kimi-K3","contextWindow":1000000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"medium":"medium","max":"max"}},{"id":"kimi-k2.6","name":"Kimi-K2.6","contextWindow":256000,"maxTokens":32000,"input":["text","image"],"reasoningEfforts":{"medium":"medium","max":"max"}}];
    function CodeBuddyImport() {
        const locale=react.useSyncExternalStore(cb=>ctx.locale.subscribe(cb),()=>ctx.locale.getSnapshot()),en=/^en/.test(locale.active);
        const fileInput=react.useRef(null);
        const [file,setFile]=react.useState(null),[password,setPassword]=react.useState(''),[busy,setBusy]=react.useState(false),[message,setMessage]=react.useState(''),[opened,setOpened]=react.useState(false);
        react.useEffect(()=>{const back=event=>{if(opened){event.preventDefault();if(!busy)setOpened(false);}};window.addEventListener('dsh-device-back',back);return()=>window.removeEventListener('dsh-device-back',back);},[opened,busy]);
        const importFile=async()=>{
            if(!file||busy)return;setBusy(true);setMessage('');let plain;
            try{
                if(file.size>65536)throw new Error(en?'Connection file is too large':'连接文件过大');
                const bundle=JSON.parse(await file.text());
                if(bundle.format!=='DSH-CODEBUDDY-1'||bundle.iterations!==200000||!window.crypto?.subtle)throw new Error(en?'Unsupported connection file or WebView cryptography unavailable':'连接文件不支持，或 WebView 无法使用加密接口');
                const decode=value=>Uint8Array.from(atob(value),char=>char.charCodeAt(0));
                const material=await crypto.subtle.importKey('raw',new TextEncoder().encode(password),'PBKDF2',false,['deriveKey']);
                const key=await crypto.subtle.deriveKey({name:'PBKDF2',salt:decode(bundle.salt),iterations:bundle.iterations,hash:'SHA-256'},material,{name:'AES-GCM',length:256},false,['decrypt']);
                try{plain=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:decode(bundle.iv),additionalData:new TextEncoder().encode(bundle.format)},key,decode(bundle.data)));}
                catch(error){throw new Error(en?'Incorrect passphrase or damaged connection file':'口令不正确或连接文件已损坏');}
                const value=JSON.parse(new TextDecoder().decode(plain));
                if(value.provider!=='codebuddy'||value.baseURL!=='https://www.codebuddy.cn/v2'||value.model!=='deepseek-v4.1-flash'||typeof value.apiKey!=='string'||value.apiKey.length<16||value.apiKey.length>4096||/[\x00-\x1f\x7f]/.test(value.apiKey))throw new Error(en?'Invalid CodeBuddy connection':'CodeBuddy 连接内容不合法');
                window.dispatchEvent(new Event('dsh-codebuddy-importing'));
                const description=await rpc('settings/describe',{},undefined,true);
                const namespace=description.namespaces?.find(item=>item.ns==='llm-pi-ai');
                const previous=namespace?.value?.providers?.codebuddy||{};
                const profile={...previous,displayName:'CodeBuddy CLI',api:'openai-completions',baseURL:value.baseURL,apiKeyEnv:'CODEBUDDY_API_KEY',compat:{...previous.compat,supportsReasoningEffort:true},models:Array.isArray(previous.models)&&previous.models.length?previous.models:codeBuddyDefaultModels};
                delete profile.headers;
                await rpc('settings/mutate',{ns:'llm-pi-ai',ops:[{op:'set',path:['providers','codebuddy'],value:profile}]},undefined,true);
                await rpc('credentials/set',{ref:'CODEBUDDY_API_KEY',value:value.apiKey},undefined,true);
                await rpc('settings/update',{ns:'agent-default-model',patch:{provider:'codebuddy',model:value.model,reasoningEffort:'max'}},undefined,true);
                await nativeRequest('codebuddy-default');
                setOpened(false);setPassword('');setFile(null);if(fileInput.current)fileInput.current.value='';setMessage(en?'CodeBuddy imported; chat access is unverified. New conversations use the global default; existing project overrides remain.':'CodeBuddy 已导入，尚未验证对话权限。新会话使用全局默认，已有项目覆盖保留。');
                window.dispatchEvent(new Event('dsh-codebuddy-imported'));
            }catch(error){setMessage(error.message||String(error));}
            finally{if(plain)plain.fill(0);setBusy(false);}
        };
        const importButton=h(P.Button,{variant:'primary',disabled:busy||!file||!password,onClick:importFile,children:busy?(en?'Importing…':'正在导入…'):(en?'Import and use CodeBuddy':'导入并使用 CodeBuddy')});
        return hs(react.Fragment,{children:[h('style',{children:nativeCss}),hs('div',{className:DeveloperToolsRow_module_css_default.row+' dsh-device-import-row',children:[hs('div',{className:'device-copy',children:[h('h3',{className:DeveloperToolsRow_module_css_default.title,style:{margin:0,fontWeight:400},children:en?'Import CodeBuddy CLI connection':'导入 CodeBuddy CLI 连接'}),h('p',{className:DeveloperToolsRow_module_css_default.description,style:{marginBottom:0},children:en?'Connect through the bundled official CLI agent. DSH handles file tools and permissions.':'通过内置官方 CLI 代理连接，文件工具和权限由 DSH 处理。'})]}),h(P.Button,{variant:'outline',size:'sm',onClick:()=>setOpened(true),children:en?'Import connection':'导入连接'})]}),!opened&&message&&h('p',{role:'status',className:'device-muted',children:message}),
            h(P.Modal,{open:opened,title:en?'CodeBuddy CLI connection':'CodeBuddy CLI 连接',closeLabel:en?'Close':'关闭',onClose:()=>{if(!busy)setOpened(false);},footer:h('div',{className:'dsh-device-settings',children:importButton}),children:hs('div',{className:'dsh-device-settings device-import-form',children:[h('p',{className:'device-muted',children:en?'Choose your encrypted connection file and enter its separate passphrase. Keys stay in DSH credentials.':'选择加密连接文件并输入独立口令，密钥保存在 DSH 凭据中。'}),hs('div',{className:'device-row',children:[h('input',{ref:fileInput,type:'file',accept:'*/*',style:{display:'none'},disabled:busy,'aria-label':en?'CodeBuddy connection file':'CodeBuddy 连接文件',onChange:event=>setFile(event.target.files?.[0]||null)}),h(P.Button,{variant:'outline',disabled:busy,onClick:()=>fileInput.current?.click(),children:en?'Choose connection file':'选择连接文件'}),h('span',{className:'device-muted',children:file?.name||(en?'No file selected':'未选择文件')})]}),h('label',{className:'device-label',htmlFor:'dsh-codebuddy-passphrase',children:en?'Connection passphrase':'连接口令'}),h(P.Input,{id:'dsh-codebuddy-passphrase',type:'password',autoComplete:'off',value:password,disabled:busy,placeholder:en?'Connection passphrase':'连接口令',onChange:event=>setPassword(event.target.value)}),message&&h('p',{role:'status',className:'device-text',children:message})]})})]});
    }

    function FileWebActions({absolutePath,directory=false}) {
        const locale=react.useSyncExternalStore(cb=>ctx.locale.subscribe(cb),()=>ctx.locale.getSnapshot()),en=/^en/.test(locale.active);
        const [busy,setBusy]=react.useState(false),[error,setError]=react.useState(''),[state,setState]=react.useState(window.__dshNativeAppState||{});
        react.useEffect(()=>{const update=()=>setState(window.__dshNativeAppState||{});window.addEventListener('dsh-native-app-state',update);return()=>window.removeEventListener('dsh-native-app-state',update);},[]);
        if(!state.integratedSettings||typeof absolutePath!=='string'||!absolutePath)return null;
        const act=async action=>{if(busy)return;setBusy(true);setError('');try{
            if(action==='edit'||action==='browse'){
                window.__dshNativeFileRequest={id:'file-'+Date.now(),action,path:absolutePath};
                window.dispatchEvent(new CustomEvent('dsh-native-app-settings',{cancelable:true,detail:{page:'files'}}));
                window.dispatchEvent(new Event('dsh-device-file'));
            }else await nativeRequest('file',{action,path:absolutePath,locale:en?'en':'zh'});
        }catch(failure){setError(failure.message);}finally{setBusy(false);}};
        return hs('span',{className:'dsh-file-actions',children:[h('style',{children:'[data-native-file-toolbar]:has(.dsh-file-actions){height:auto;min-height:38px;flex-wrap:wrap;padding-top:6px;padding-bottom:6px}[data-native-file-toolbar]>[data-slot="sidebar.right.tab.document.actions"]{flex:0 0 100%;display:flex;justify-content:flex-end}[data-native-file-toolbar] .dsh-file-actions{justify-content:flex-end;width:100%}.dsh-file-actions{display:inline-flex;flex-wrap:wrap;align-items:center;gap:6px;min-width:0;max-width:100%}.dsh-file-actions button{height:auto;min-height:32px;max-width:100%;white-space:normal;overflow-wrap:anywhere}.dsh-file-actions [role=alert]{font-size:12px;overflow-wrap:anywhere;color:var(--dsw-alias-label-error)}'}),...(directory?[['browse','管理文件','Manage files']]:[['edit','编辑','Edit'],['open','其他应用打开','Open in app'],['share','分享','Share']]).map(([action,zh,label])=>h(P.Button,{key:action,size:'sm',variant:'outline',disabled:busy,onClick:()=>act(action),children:en?label:zh})),error&&h('span',{role:'alert',children:error})]});
    }
    for(const slot of ['sidebar.right.tab.document.actions','deliverables.file.actions','deliverables.review.file.actions','sidebar.right.tab.files.actions'])ctx.slots.inject(slot,()=>ctx.slots.register({name:slot,id:'android-file-actions',order:20},props=>h(FileWebActions,{...props,directory:slot==='sidebar.right.tab.files.actions'})));

    let settingsMode,sectionDisposers=[];
    ctx.slots.inject('settings.section',()=>{
        const register=()=>{
            const mode=!!window.__dshNativeAppState?.integratedSettings;if(settingsMode===mode)return;settingsMode=mode;
            for(const dispose of sectionDisposers)dispose();sectionDisposers=[];
            const add=(id,label,order,component)=>sectionDisposers.push(ctx.slots.register({name:'settings.section',id,order,label},component));
            if(!mode){add('android-app',()=>/^en/.test(ctx.locale.getSnapshot().active)?words.nav[1]:words.nav[0],90,AppSection);return;}
            const en=()=>/^en/.test(ctx.locale.getSnapshot().active);
            add('android-app',()=>en()?'Device':'设备',90,DeviceHome);
            add('android-sessions',()=>en()?'Conversations':'会话管理',25,props=>hs('section',{className:'dsh-app-settings dsh-device-sessions',children:[h('style',{children:css+nativeCss}),h(SessionManager,{t:key=>words[key]?.[en()?1:0]||key,en:en(),close:props.close})]}));
            for(const [page,zh,label,order] of [['tasks','任务','Tasks',26],['files','文件','Files',70],['data','工作区与数据','Workspace & data',75],['updates','更新与维护','Updates & maintenance',80],['diagnostics','诊断与日志','Diagnostics & logs',85]])add('android-'+page,()=>en()?label:zh,order,props=>h(DeviceSection,{...props,page}));
        };
        register();window.addEventListener('dsh-native-app-state',register);
        return()=>{window.removeEventListener('dsh-native-app-state',register);for(const dispose of sectionDisposers)dispose();sectionDisposers=[];settingsMode=undefined;};
    });
    for(const [slot,page,id] of [['settings.general.item','display','android-display'],['settings.models.footer','models','android-project-models']])ctx.slots.inject(slot,()=>ctx.slots.register({name:slot,id,order:90},props=>{
        const [state,setState]=react.useState(window.__dshNativeAppState||{}),[expanded,setExpanded]=react.useState(page==='display');
        const locale=react.useSyncExternalStore(cb=>ctx.locale.subscribe(cb),()=>ctx.locale.getSnapshot()),en=/^en/.test(locale.active);
        react.useEffect(()=>{const update=()=>setState(window.__dshNativeAppState||{});window.addEventListener('dsh-native-app-state',update);return()=>window.removeEventListener('dsh-native-app-state',update);},[]);
        react.useEffect(()=>{const reset=()=>setExpanded(false);window.addEventListener('dsh-codebuddy-importing',reset);window.addEventListener('dsh-codebuddy-imported',reset);return()=>{window.removeEventListener('dsh-codebuddy-importing',reset);window.removeEventListener('dsh-codebuddy-imported',reset);};},[]);
        if(!state.integratedSettings)return null;
        return hs('div',{'data-device-slot':page,className:page==='models'?'dsh-device-models':'dsh-device-display',children:[page==='models'&&h(CodeBuddyImport,{}),page==='models'&&!expanded?h(P.Button,{variant:'outline',onClick:()=>setExpanded(true),children:en?'Project models & catalog sync':'项目模型与目录同步'}):h(DeviceSection,{...props,page})]});
    }));

}
/* DSH-ANDROID-APP-SETTINGS-END */
