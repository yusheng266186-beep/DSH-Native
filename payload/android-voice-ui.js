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
function dshVoiceCommand(id, action, language) {
    console.log('[dsh-voice] ' + JSON.stringify({id, action, language: language || 'zh-CN'}));
}
function dshVoiceError(code, en) {
    const errors = {
        1: ['语音服务网络超时，请重试', 'Speech service timed out. Please retry.'],
        2: ['语音服务网络不可用，请检查连接', 'Speech service network is unavailable.'],
        3: ['无法打开麦克风，请关闭其他录音应用后重试', 'Microphone unavailable. Close other recording apps and retry.'],
        6: ['没有检测到语音，请重试', 'No speech detected. Please retry.'],
        7: ['没有识别到文字，请重试', 'No words recognized. Please retry.'],
        8: ['语音服务忙，请稍后重试', 'Speech service is busy. Please retry.'],
        9: ['麦克风未授权，可在应用权限中开启后重试', 'Allow microphone access in App permissions, then retry.'],
        11: ['系统未提供语音识别服务，请安装或启用语音服务，也可使用键盘语音输入', 'No system speech service. Install or enable one, or use keyboard dictation.'],
        12: ['系统语音服务不支持当前语言，请检查系统语音设置', 'The speech service does not support this language. Check system voice settings.'],
        13: ['当前语言的离线模型未就绪，请检查系统语音设置', 'The offline language model is unavailable. Check system voice settings.']
    };
    return (errors[code] || ['语音识别失败，请重试（错误 ' + code + '）', 'Speech recognition failed. Please retry (error ' + code + ').'])[en ? 1 : 0];
}
function DshNativeVoiceInput({sessionId, inputActions, locked, onActiveChange, t}) {
    const en = t('cancel') !== '取消';
    const [phase, setPhase] = react.useState('idle'), [message, setMessage] = react.useState(''), [pending, setPending] = react.useState('');
    const active = react.useRef(null), currentActions = react.useRef(inputActions), currentLocked = react.useRef(locked);
    currentActions.current = inputActions;
    currentLocked.current = locked;
    const expanded = phase !== 'idle';
    react.useLayoutEffect(() => { onActiveChange(expanded); return () => onActiveChange(false); }, [expanded, onActiveChange]);
    const reset = () => {
        const run = active.current; active.current = null;
        if (run) { clearTimeout(run.timer); dshVoiceCommand(run.id, 'cancel'); }
        setPending(''); setMessage(''); setPhase('idle');
    };
    react.useEffect(() => {
        setPhase('idle'); setMessage(''); setPending('');
        const result = event => {
            const value = event.detail, run = active.current;
            if (!run || !value || value.id !== run.id) return;
            if (value.state === 'listening' || value.state === 'starting' || value.state === 'permission') {
                setPhase(value.state); return;
            }
            if (value.state === 'recognizing') { setPhase('recognizing'); return; }
            clearTimeout(run.timer); active.current = null;
            if (value.state === 'cancelled') { setPhase('idle'); return; }
            if (value.state === 'result') {
                const text = typeof value.text === 'string' ? value.text.trim() : '';
                if (!text) { setPhase('feedback'); setMessage(dshVoiceError(7, en)); return; }
                if (text.length > 16000) { setPhase('feedback'); setMessage(dshVoiceError(5, en)); return; }
                if (!currentLocked.current && currentActions.current.insertText(text, run.span)) { setPhase('idle'); return; }
                setPending(text);
                setMessage(en ? 'Your draft changed. The transcript is kept below; insert it at the current cursor.' : '草稿已变化，识别文字已保留，可插入到当前光标。');
            } else setMessage(dshVoiceError(value.error || 5, en));
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
    const start = () => {
        if (locked || active.current) return;
        const id = dshVoiceId();
        const run = {id, span: inputActions.captureInsertion()};
        active.current = run; setPending(''); setMessage(''); setPhase('starting');
        run.timer = setTimeout(() => {
            if (active.current !== run) return;
            active.current = null; dshVoiceCommand(id, 'cancel');
            setMessage(en ? 'No response from Android speech recognition. Please retry.' : '系统语音识别未响应，请重试。'); setPhase('feedback');
        }, 90000);
        dshVoiceCommand(id, 'start', en ? 'en-US' : 'zh-CN');
    };
    const P = _deepseek_ai_dsh_client_ui_primitives;
    const h = react.createElement;
    const button = (label, action, key, disabled = false) => h(P.Button, {key, size:'sm', variant:'ghost', disabled,
        onMouseDown: event => event.preventDefault(), onClick: action, 'aria-label':label,
        style:{minWidth:36, minHeight:36, flexShrink:0}}, label);
    if (!expanded) return h(P.Button, {size:'sm', variant:'ghost', disabled:locked,
        'aria-label':en ? 'Start Android voice input' : '开始语音输入', title:en ? 'Android voice input' : '系统语音输入',
        onMouseDown:event => event.preventDefault(), onClick:start, style:{minWidth:36,minHeight:36}}, h(P.IconMicrophoneOutlineRegular,{size:18}));
    const labels = {starting:en?'Starting speech recognition…':'正在启动语音识别…', permission:en?'Allow microphone access…':'请允许麦克风权限…',
        listening:en?'Listening…':'正在聆听…', recognizing:en?'Recognizing…':'正在识别…'};
    return h('div', {'data-native-voice':true,'data-state':phase,style:{display:'flex',gap:8,alignItems:'center',flexWrap:'wrap',minWidth:0,width:'100%'}},
        h('span',{role:phase==='feedback'?'alert':'status',style:{flex:'1 1 120px',minWidth:0,overflowWrap:'anywhere',fontSize:12}},message||labels[phase]),
        phase!=='feedback'&&button(en?'Stop and recognize':'结束并识别',()=>{if(active.current)dshVoiceCommand(active.current.id,'stop');},'stop'),
        phase==='feedback'&&!pending&&button(en?'Retry':'重试',start,'retry',locked),
        pending&&h('div',{'data-native-transcript':true,style:{flexBasis:'100%',overflowWrap:'anywhere',maxHeight:128,overflow:'auto',whiteSpace:'pre-wrap'}},pending),
        pending&&button(en?'Insert at cursor':'插入到当前光标',()=>{if(!locked&&inputActions.insertText(pending,inputActions.captureInsertion()))reset();},'insert',locked),
        button(en?'Cancel':'取消',reset,'cancel'));
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
    return h('section',{'data-native-voice-settings':true,style:{display:'grid',gap:12,minWidth:0,overflowWrap:'anywhere'}},
        h('h3',null,en?'Android voice input':'Android 系统语音输入'),
        h('p',null,en?'Uses the speech recognition service installed on this phone. On-device recognition is preferred when supported; the system service may use a network connection. No desktop speech model download is needed.':'使用手机已安装的语音识别服务，支持时优先使用端侧识别；系统服务也可能联网识别，无需下载桌面语音模型。'),
        h('p',{role:'status'},!capabilities?(en?'Checking speech service…':'正在检测语音服务…'):!capabilities.available?dshVoiceError(11,en):capabilities.permission?(en?'Speech service available; microphone allowed.':'语音服务可用，麦克风已授权。'):(en?'Speech service available. Microphone permission is requested when you start.':'语音服务可用，开始输入时申请麦克风权限。')),
        h('p',null,en?'Tap the microphone in a conversation. Recognition only fills the draft; review it before sending.':'在对话输入框点击麦克风即可。识别结果只填入草稿，确认后再发送。'),
        h('div',{style:{display:'flex',gap:8,flexWrap:'wrap'}},
            h(P.Button,{onClick:check},en?'Check speech service':'检测语音服务'),
            h(P.Button,{onClick:()=>dshVoiceCommand(dshVoiceId(),'settings')},en?'System voice settings':'系统语音设置')));
}
function DshVoiceInput(props) { const native=dshVoiceNative(); return react.createElement(native?DshNativeVoiceInput:VoiceInput,props); }
function DshVoicePreparation(props) { const native=dshVoiceNative(); return react.createElement(native?DshNativeVoiceSettings:VoicePreparation,props); }
function DshVoiceSetupPrompt(props) { const native=dshVoiceNative(); return native?null:react.createElement(VoiceSetupPrompt,props); }
/* DSH-ANDROID-VOICE-END */
