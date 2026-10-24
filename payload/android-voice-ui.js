/* DSH-ANDROID-VOICE-v1 */
function dshVoiceNative() {
    const [native, setNative] = react.useState(window.__dshNativeVoice === 1);
    react.useEffect(() => {
        const ready = () => setNative(window.__dshNativeVoice === 1);
        window.addEventListener('dsh-native-voice-ready', ready); ready();
        return () => window.removeEventListener('dsh-native-voice-ready', ready);
    }, []);
    return native;
}
function dshVoiceId() { return 'voice-' + Date.now() + '-' + Math.random().toString(36).slice(2); }
function dshVoiceCommand(id, action, language, engine) {
    console.log('[dsh-voice] ' + JSON.stringify({id, action, language: language || 'zh-CN', ...(engine ? {engine} : {})}));
}
function dshVoiceError(code, en) {
    const errors = {
        1: ['语音服务网络超时，请重试', 'Speech service timed out. Please retry.'],
        2: ['语音服务网络不可用，请检查连接', 'Speech service network is unavailable.'],
        3: ['无法打开麦克风，请关闭其他录音应用后重试', 'Microphone unavailable. Close other recording apps and retry.'],
        4: ['系统语音服务处理失败，请检查所选识别引擎', 'The speech service failed. Check the selected engine.'],
        5: ['系统语音服务启动或连接失败（错误 5），请在系统语音设置选择可用识别引擎，也可使用键盘语音输入', 'The system speech service could not start or connect (error 5). Select a working engine in system voice settings, or use keyboard dictation.'],
        6: ['没有检测到语音，请重试', 'No speech detected. Please retry.'],
        7: ['没有识别到文字，请重试', 'No words recognized. Please retry.'],
        8: ['语音服务忙，请稍后重试', 'Speech service is busy. Please retry.'],
        9: ['麦克风未授权，可在应用权限中开启后重试', 'Allow microphone access in App permissions, then retry.'],
        11: ['系统未提供语音识别服务，请安装或启用语音服务，也可使用键盘语音输入', 'No system speech service. Install or enable one, or use keyboard dictation.'],
        12: ['系统语音服务不支持当前语言，请检查系统语音设置', 'The speech service does not support this language. Check system voice settings.'],
        13: ['当前语言的离线模型未就绪，请检查系统语音设置', 'The offline language model is unavailable. Check system voice settings.'],
        1001: ['识别文字过长，请缩短单次输入', 'The transcript is too long. Please use a shorter recording.'],
        1002: ['应用窗口尚未就绪，请关闭系统弹窗、返回 DSH 后重试', 'The App window is not ready. Close system dialogs, return to DSH and retry.']
    };
    return (errors[code] || ['语音识别失败，请重试（错误 ' + code + '）', 'Speech recognition failed. Please retry (error ' + code + ').'])[en ? 1 : 0];
}
function DshNativeVoiceInput({sessionId, inputActions, locked, onActiveChange, t}) {
    const en = t('cancel') !== '取消';
    const [phase, setPhase] = react.useState('idle'), [message, setMessage] = react.useState(''), [pending, setPending] = react.useState('');
    const [level, setLevel] = react.useState(0), [partial, setPartial] = react.useState(''), [seconds, setSeconds] = react.useState(0);
    const [windowAvailable, setWindowAvailable] = react.useState(false), [engine, setEngine] = react.useState('');
    const active = react.useRef(null), currentActions = react.useRef(inputActions), currentLocked = react.useRef(locked);
    currentActions.current = inputActions;
    currentLocked.current = locked;
    const expanded = phase !== 'idle';
    react.useEffect(() => {
        if (!expanded || phase === 'feedback') return;
        const tick = setInterval(() => {
            const run = active.current;
            if (run) setSeconds(Math.floor((Date.now() - run.started) / 1000));
        }, 1000);
        return () => clearInterval(tick);
    }, [expanded, phase === 'feedback']);
    react.useLayoutEffect(() => { onActiveChange(expanded); return () => onActiveChange(false); }, [expanded, onActiveChange]);
    const reset = () => {
        const run = active.current; active.current = null;
        if (run) { clearTimeout(run.timer); dshVoiceCommand(run.id, 'cancel'); }
        setPending(''); setMessage(''); setPartial(''); setLevel(0); setPhase('idle');
    };
    react.useEffect(() => {
        setPhase('idle'); setMessage(''); setPending(''); setPartial(''); setLevel(0); setSeconds(0);
        const result = event => {
            const value = event.detail, run = active.current;
            if (!run || !value || value.id !== run.id) return;
            if (value.state === 'level') { setLevel(Number.isFinite(value.level) ? Math.max(0,Math.min(1,value.level)) : 0); return; }
            if (value.state === 'partial') {
                if (typeof value.text === 'string' && value.text.length <= 16000) setPartial(value.text);
                return;
            }
            if (typeof value.windowAvailable === 'boolean') setWindowAvailable(value.windowAvailable);
            if (typeof value.engineLabel === 'string') setEngine(value.engineLabel);
            if (value.state === 'listening' || value.state === 'starting' || value.state === 'permission' || value.state === 'foreground') {
                setPhase(value.state); return;
            }
            if (value.state === 'recognizing' || value.state === 'fallback' || value.state === 'system-window') {
                setPhase(value.state); return;
            }
            clearTimeout(run.timer); active.current = null; setLevel(0); setPartial('');
            if (value.state === 'cancelled') { setPhase('idle'); return; }
            if (value.state === 'result') {
                const text = typeof value.text === 'string' ? value.text.trim() : '';
                if (!text) { setPhase('feedback'); setMessage(dshVoiceError(7, en)); return; }
                if (text.length > 16000) { setPhase('feedback'); setMessage(dshVoiceError(1001, en)); return; }
                if (!currentLocked.current && currentActions.current.insertText(text, run.span)) { setPhase('idle'); return; }
                setPending(text);
                setMessage(en ? 'Your draft changed. The transcript is kept below; insert it at the current cursor.' : '草稿已变化，识别文字已保留，可插入到当前光标。');
            } else setMessage(value.reason === 'inline-unavailable'
                ? (en ? 'This phone has no usable speech service for in-page recording. Choose an engine in the voice plugin, or use system voice below.' : '手机尚未提供可直接连接的识别服务。可在语音插件中选择引擎，或点击下方“使用系统语音”。')
                : dshVoiceError(value.error || 5, en));
            setPhase('feedback');
        };
        const escape = event => { if (event.key === 'Escape' && active.current) { event.preventDefault(); reset(); } };
        window.addEventListener('dsh-native-voice-result', result);
        document.addEventListener('keydown', escape);
        return () => {
            window.removeEventListener('dsh-native-voice-result', result);
            document.removeEventListener('keydown', escape);
            const run = active.current; active.current = null;
            if (run) { clearTimeout(run.timer); dshVoiceCommand(run.id, 'cancel'); }
        };
    }, [sessionId, en]);
    const start = (action = 'start') => {
        if (locked || active.current) return;
        const id = dshVoiceId();
        const run = {id, span: inputActions.captureInsertion(), started: Date.now()};
        active.current = run; setPending(''); setMessage(''); setPartial(''); setLevel(0); setSeconds(0); setEngine(''); setPhase('starting');
        run.timer = setTimeout(() => {
            if (active.current !== run) return;
            active.current = null; dshVoiceCommand(id, 'cancel');
            setMessage(en ? 'No response from Android speech recognition. Please retry.' : '系统语音识别未响应，请重试。'); setPhase('feedback');
        }, 90000);
        dshVoiceCommand(id, action, en ? 'en-US' : 'zh-CN');
    };
    const P = _deepseek_ai_dsh_client_ui_primitives;
    const h = react.createElement;
    const button = (label, action, key, disabled = false) => h(P.Button, {key, size:'sm', variant:'ghost', disabled,
        onMouseDown: event => event.preventDefault(), onClick: action, 'aria-label':label,
        style:{minWidth:36, minHeight:36, flexShrink:0}}, label);
    if (!expanded) return h(P.Button, {size:'sm', variant:'ghost', disabled:locked,
        'aria-label':en ? 'Start Android voice input' : '开始语音输入', title:en ? 'Android voice input' : '系统语音输入',
        onMouseDown:event => event.preventDefault(), onClick:()=>start(), style:{minWidth:36,minHeight:36}}, h(P.IconMicrophoneOutlineRegular,{size:18}));
    const labels = {starting:en?'Starting speech recognition…':'正在启动语音识别…', permission:en?'Allow microphone access…':'请允许麦克风权限…',
        foreground:en?'Waiting for the App window…':'正在等待应用窗口就绪…',
        listening:en?'Listening…':'正在聆听…', recognizing:en?'Recognizing…':'正在识别…',
        fallback:en?'Switching speech recognition…':'正在切换语音识别方式…',
        'system-window':en?'Speak in the system speech window…':'请在系统语音窗口中说话…'};
    return h('div', {'data-native-voice':true,'data-state':phase,style:{display:'flex',flexDirection:'column',gap:8,minWidth:0,width:'100%',
        padding:'8px 10px',boxSizing:'border-box',borderRadius:'var(--dsw-radius-md, 12px)',background:'var(--dsw-alias-interactive-bg-hover)',color:'var(--dsw-alias-label-primary)'}},
        h('style',null,'[data-native-voice] [data-voice-bar]{transition:height .12s ease}@supports(height:1dvh){[data-native-voice]{--dsh-voice-body-height:min(112px,22dvh)}}@media(prefers-reduced-motion:reduce){[data-native-voice] [data-voice-bar]{transition:none}}@media(max-height:420px){[data-native-voice]{--dsh-voice-heading-display:none;--dsh-voice-body-height:48px}}'),
        h('div',{'data-voice-heading':true,style:{display:'var(--dsh-voice-heading-display, flex)',alignItems:'center',gap:8,minWidth:0}},
            h(P.IconMicrophoneOutlineRegular,{size:18}),
            h('span',{title:engine,style:{fontSize:13,fontWeight:500,flex:1,minWidth:0,overflow:'hidden',textOverflow:'ellipsis',whiteSpace:'nowrap'}},en?'Voice input':'语音输入'),
            phase!=='feedback'&&h('span',{'aria-hidden':true,style:{display:'flex',height:20,alignItems:'center',gap:2}},
                ...Array.from({length:12},(_,i)=>h('i',{key:i,'data-voice-bar':true,style:{display:'block',width:3,borderRadius:4,
                    height:phase==='listening'?4+Math.round(level*(6+12*(1-Math.abs(i-5.5)/6))):4,background:'currentColor',opacity:phase==='listening'?.8:.3}}))),
            phase!=='feedback'&&h('span',{'data-voice-timer':true,style:{fontSize:12,fontVariantNumeric:'tabular-nums',color:'var(--dsw-alias-label-secondary)'}},Math.floor(seconds/60)+':'+String(seconds%60).padStart(2,'0'))),
        h('div',{'data-native-voice-body':true,role:'region',tabIndex:0,'aria-label':en?'Voice input details':'语音输入详情',
            style:{display:'grid',gap:6,minHeight:0,minWidth:0,maxHeight:'var(--dsh-voice-body-height, 112px)',overflow:'auto',overflowWrap:'anywhere',fontSize:12,lineHeight:1.4}},
            h('span',{role:phase==='feedback'?'alert':'status'},message||labels[phase]),
            partial&&h('div',{'data-native-partial':true,style:{color:'var(--dsw-alias-label-secondary)',whiteSpace:'pre-wrap'}},partial),
            pending&&h('div',{'data-native-transcript':true,style:{minWidth:0,whiteSpace:'pre-wrap',overflowWrap:'anywhere'}},pending)),
        h('div',{'data-native-voice-actions':true,style:{display:'flex',gap:8,flexWrap:'wrap',flexShrink:0,minWidth:0}},
        phase==='listening'&&button(en?'Stop and recognize':'结束并识别',()=>{if(active.current)dshVoiceCommand(active.current.id,'stop');},'stop'),
        phase==='feedback'&&!pending&&button(en?'Retry':'重试',()=>start(),'retry',locked),
        phase==='feedback'&&!pending&&windowAvailable&&button(en?'Use system voice':'使用系统语音',()=>start('start-window'),'window',locked),
        phase==='feedback'&&!pending&&button(en?'System voice settings':'系统语音设置',()=>dshVoiceCommand(dshVoiceId(),'settings'),'settings'),
        pending&&button(en?'Insert at cursor':'插入到当前光标',()=>{if(!locked&&inputActions.insertText(pending,inputActions.captureInsertion()))reset();},'insert',locked),
        button(en?'Cancel':'取消',reset,'cancel')));
}
function DshNativeVoiceSettings({t}) {
    const en = t('cancel') !== '取消';
    const [capabilities, setCapabilities] = react.useState(null), request = react.useRef('');
    const check = () => { request.current=dshVoiceId(); dshVoiceCommand(request.current,'check'); };
    react.useEffect(() => {
        const event = item => { if(item.detail?.id===request.current)setCapabilities(item.detail); };
        window.addEventListener('dsh-native-voice-result',event); check();
        return()=>window.removeEventListener('dsh-native-voice-result',event);
    },[]);
    const h=react.createElement, P=_deepseek_ai_dsh_client_ui_primitives;
    const engines=Array.isArray(capabilities?.engines)?capabilities.engines:[];
    return h('section',{'data-native-voice-settings':true,style:{display:'grid',gap:12,minWidth:0,overflowWrap:'anywhere'}},
        h('h3',null,en?'Voice input in DSH':'DSH 内语音输入'),
        h('p',null,en?'Record directly in the conversation with a compatible Android speech service. The system service may use the network. No desktop model download is needed.':'通过兼容的 Android 识别服务在对话内录音，跟随 DSH 主题，无额外识别弹窗。系统服务可能联网，无需下载桌面模型。'),
        h('p',{role:'status'},!capabilities?(en?'Checking speech services…':'正在检测语音服务…'):
            capabilities.state==='error'?(en?'The selected engine is unavailable. Check services and try again.':'所选引擎不可用，请重新检测后选择。'):
            capabilities.inlineAvailable?(en?'In-page recognition entry detected. Starting a recording will test the connection.':'已检测到页面内识别入口，开始录音时会检查连接。'):
            capabilities.windowAvailable?(en?'This phone currently exposes only the system speech window. DSH will ask before opening it.':'手机目前只提供系统语音窗口；只有点击“使用系统语音”才会打开。'):
            dshVoiceError(11,en)),
        h('label',{style:{display:'grid',gap:6,minWidth:0,fontSize:13}},en?'Speech engine':'识别引擎',
            h('select',{'aria-label':en?'Speech engine':'识别引擎',value:capabilities?.selectedEngine||'auto',disabled:!capabilities,
                style:{minWidth:0,maxWidth:'100%',width:'100%',minHeight:40,padding:'8px 10px',borderRadius:'var(--dsw-radius-sm, 8px)',
                    border:'1px solid var(--dsw-alias-border-l2)',background:'var(--dsw-specific-input-major)',color:'var(--dsw-alias-label-primary)',font:'inherit'},
                onChange:event=>{request.current=dshVoiceId();dshVoiceCommand(request.current,'engine',undefined,event.target.value);}},
                h('option',{value:'auto'},en?'Automatic · system provider':'自动 · 系统语音提供方'),
                ...engines.map(item=>h('option',{key:item.id,value:item.id},item.label||item.id)))),
        h('p',null,en?'A system-only speech window belongs to another app and cannot be hidden or restyled by DSH. You can still open it from the input panel. Results only fill the draft.':'仅提供窗口的识别服务由其他应用绘制，DSH 无法隐藏或换肤；输入面板仍保留手动入口。识别结果只填草稿，不自动发送。'),
        h('div',{style:{display:'flex',gap:8,flexWrap:'wrap'}},
            h(P.Button,{onClick:check},en?'Check speech services':'检测语音服务'),
            h(P.Button,{onClick:()=>dshVoiceCommand(dshVoiceId(),'settings')},en?'System voice settings':'系统语音设置')));
}
function DshVoiceInput(props) { const native=dshVoiceNative(); return react.createElement(native?DshNativeVoiceInput:VoiceInput,props); }
function DshVoicePreparation(props) { const native=dshVoiceNative(); return react.createElement(native?DshNativeVoiceSettings:VoicePreparation,props); }
function DshVoiceSetupPrompt(props) { const native=dshVoiceNative(); return native?null:react.createElement(VoiceSetupPrompt,props); }
/* DSH-ANDROID-VOICE-END */
