package dev.dsh.nativeapp;

/** 未发送任务草稿的同源页面恢复脚本：纯 Java、无原生权限桥。 */
final class DraftRecovery {

    static final String RESTORED_MARK = "[dsh-draft] restored";

    private DraftRecovery() { }

    static boolean isRestored(String message) {
        return message != null && message.indexOf(RESTORED_MARK) >= 0;
    }

    /**
     * 草稿只写入 DSH 自己的 localhost origin localStorage。恢复时仅填充空编辑器，
     * 不聚焦、不点击发送，也不向 Android 暴露 JavascriptInterface。
     */
    static String script() {
        return "(function(){"
             + "if(window.__dshDraftRecovery)return;window.__dshDraftRecovery=1;"
             + "var KEY='dsh-native-draft-v1',TTL=604800000,MAX=100000,timer=0,restored=0,editors=[];"
             + "function val(e){try{return 'value' in e?String(e.value||''):String(e.textContent||'');}catch(x){return '';}}"
             + "function visible(e){try{var r=e.getBoundingClientRect();var s=getComputedStyle(e);"
             + "return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}catch(x){return false;}}"
             + "function eligible(e){try{return !!e&&!e.disabled&&!e.readOnly&&visible(e)"
             + "&&!(e.closest&&e.closest('[role=dialog]'))"
             + "&&String(e.type||'').toLowerCase()!=='password';}catch(x){return false;}}"
             + "function load(){try{var raw=localStorage.getItem(KEY);if(!raw)return '';"
             + "var x=JSON.parse(raw);if(!x||x.v!==1||typeof x.t!=='string'||typeof x.at!=='number'"
             + "||Date.now()-x.at>TTL||x.t.length>MAX){localStorage.removeItem(KEY);return '';}"
             + "return x.t;}catch(e){try{localStorage.removeItem(KEY);}catch(x){}return '';}}"
             + "function write(e){clearTimeout(timer);timer=setTimeout(function(){try{var t=val(e);"
             + "if(!t){e.__dshDraftHad=0;localStorage.removeItem(KEY);return;}"
             + "e.__dshDraftHad=1;if(t.length>MAX)t=t.slice(0,MAX);"
             + "localStorage.setItem(KEY,JSON.stringify({v:1,t:t,at:Date.now()}));}catch(x){}},250);}"
             + "function attach(e){if(!eligible(e)||e.__dshDraftBound)return;e.__dshDraftBound=1;editors.push(e);"
             + "e.addEventListener('input',function(){write(e);},false);"
             + "if(!restored&&!val(e)){var t=load();if(t){restored=1;"
             + "if('value' in e)e.value=t;else e.textContent=t;e.__dshDraftHad=1;"
             + "try{e.dispatchEvent(new Event('input',{bubbles:true}));}catch(x){}"
             + "console.log('[dsh-draft] restored');}}}"
             + "function scan(){try{var q=document.querySelectorAll('textarea,[contenteditable]:not([contenteditable=false])');"
             + "for(var i=0;i<q.length;i++)attach(q[i]);}catch(e){}}"
             + "function sweep(){scan();try{for(var i=0;i<editors.length;i++){var e=editors[i];"
             + "if(e.__dshDraftHad&&!val(e)){e.__dshDraftHad=0;localStorage.removeItem(KEY);}}}catch(x){}}"
             + "scan();setInterval(sweep,1000);if(document.documentElement){"
             + "new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});}"
             + "})();";
    }
}
