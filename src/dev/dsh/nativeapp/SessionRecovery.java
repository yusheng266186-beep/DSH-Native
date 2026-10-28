package dev.dsh.nativeapp;

import java.util.Locale;

/**
 * 会话恢复失败的识别与页面操作脚本。
 *
 * <p>这类错误来自 DSH 网页端，原来只会写进原生日志，用户看到的是输入框
 * 没有反应。把识别规则放在纯逻辑类里，既方便测试，也避免在 Activity 中
 * 散落一组容易漏掉的字符串判断。
 */
final class SessionRecovery {

    private SessionRecovery() { }

    /** 判断控制台或接口诊断文本是否表示会话恢复失败。 */
    static boolean isFailure(String message) {
        if (message == null || message.length() == 0) return false;
        String s = message.toLowerCase(Locale.ROOT);
        return s.contains("sessionpersistencecorruptionerror")
                || s.contains("session persistence corruption")
                || s.contains("stored log is corrupt")
                || s.contains("resume failed for session")
                || s.contains("failed to resume session")
                || s.contains("session.v3.jsonl.zstd");
    }

    /** 面向用户的稳定标题，避免把实现细节直接放进网页。 */
    static String title() {
        return "当前会话恢复失败";
    }

    /** 面向用户的恢复建议。详细错误仍会写入诊断日志。 */
    static String detail() {
        return "输入框可能暂时不可用。可以先重试恢复；如果仍失败，请新建会话。"
                + "原会话日志会保留，导出诊断后可继续排查。";
    }

    /** 注入到 DSH 页面顶部的可恢复提示条。 */
    static String overlayScript() {
        return "(function(){"
                + "var old=document.getElementById('__dshRecovery');"
                + "if(old)return;"
                + "var box=document.createElement('div');box.id='__dshRecovery';"
                + "box.style.cssText='position:fixed;left:10px;right:10px;top:10px;z-index:2147483647;"
                + "background:#fff4e5;color:#3d2b1f;border:1px solid #e0a15b;border-radius:12px;"
                + "padding:12px 14px;box-shadow:0 4px 18px rgba(0,0,0,.24);font:14px/1.5 system-ui,sans-serif';"
                + "box.innerHTML='<div style=\"font-weight:700;margin-bottom:4px\">当前会话恢复失败</div>'"
                + "+'<div style=\"margin-bottom:10px\">输入框可能暂时不可用。可以先重试恢复；如果仍失败，请新建会话。原会话日志会保留，导出诊断后可继续排查。</div>'"
                + "+'<div style=\"display:flex;flex-wrap:wrap;gap:8px\">'"
                + "+'<a href=\"dsh-recovery://retry\" style=\"padding:7px 10px;border-radius:8px;background:#1769aa;color:white;text-decoration:none\">重试恢复</a>'"
                + "+'<a href=\"dsh-recovery://new\" style=\"padding:7px 10px;border-radius:8px;background:#2f7d32;color:white;text-decoration:none\">新建会话</a>'"
                + "+'<a href=\"dsh-recovery://logs\" style=\"padding:7px 10px;border-radius:8px;background:#6b5b95;color:white;text-decoration:none\">导出诊断</a>'"
                + "+'<button type=\"button\" style=\"padding:7px 10px;border-radius:8px;border:1px solid #b58b5a;background:transparent;color:#3d2b1f\">关闭提示</button>'"
                + "+'</div>';"
                + "var b=box.querySelector('button');if(b)b.onclick=function(){box.remove();};"
                + "(document.body||document.documentElement).appendChild(box);"
                + "})();";
    }

    /** 关闭提示条，不改动当前会话页面。 */
    static String dismissScript() {
        return "(function(){var e=document.getElementById('__dshRecovery');if(e)e.remove();})();";
    }

    /** 监听页面可见错误文本，补上没有走控制台或 fetch 的失败路径。 */
    static String domWatcherScript() {
        return "(function(){if(window.__dshRecoveryWatch)return;window.__dshRecoveryWatch=1;"
                + "var pending=[],timer=0,last=-Infinity;"
                + "var re=/(SessionPersistenceCorruptionError|session persistence corruption|stored log is corrupt|resume failed for session|failed to resume session)/i;"
                + "function inspect(n){if(!n||!n.isConnected)return;"
                + "var walk=document.createTreeWalker(n,4),t=n.nodeType===3?n:walk.nextNode(),count=0,budget=32768;"
                + "while(t&&count++<128&&budget>0){var text=(t.nodeValue||'').slice(0,4096);budget-=text.length;"
                + "if(re.test(text)){var e=t.parentElement;if(e&&!e.closest('#__dshRecovery')&&e.getClientRects().length"
                + "&&Date.now()-last>=5000){last=Date.now();console.error('[dsh-session-recovery-dom] SessionPersistenceCorruptionError');return;}}"
                + "t=walk.nextNode();}}"
                + "function flush(){timer=0;if(document.hidden){pending=[];return;}"
                + "var work=pending;pending=[];for(var i=0;i<work.length;i++)inspect(work[i]);}"
                + "function queue(n){if(document.hidden||!n||pending.length>=32)return;"
                + "if(pending.indexOf(n)<0)pending.push(n);if(!timer)timer=setTimeout(flush,120);}"
                + "var observer=new MutationObserver(function(records){if(document.hidden)return;"
                + "for(var i=0;i<records.length;i++){var r=records[i];if(r.type==='characterData')queue(r.target);"
                + "else for(var j=0;j<r.addedNodes.length;j++)queue(r.addedNodes[j]);}});"
                + "function visible(){observer.disconnect();if(timer)clearTimeout(timer);timer=0;pending=[];"
                + "if(document.hidden)return;observer.observe(document.documentElement,{subtree:true,childList:true,characterData:true});"
                + "var es=document.querySelectorAll('[role=alert],[role=status],[aria-live=assertive],[aria-live=polite]');"
                + "for(var i=0;i<es.length&&i<32;i++)queue(es[i]);}"
                + "document.addEventListener('visibilitychange',visible);visible();})();";
    }

    /** 尝试点击 DSH 自己的「新建会话」按钮。 */
    static String newSessionScript() {
        return "(function(){"
                + "var labels=['新建会话','新建对话','新建聊天','New session','New conversation','New chat'];"
                + "function visible(e){try{var r=e.getBoundingClientRect();"
                + "if(!r||r.width<=0||r.height<=0)return false;"
                + "var s=getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden';"
                + "}catch(x){return false;}}"
                + "var es=document.querySelectorAll('button,[role=button],a'),i,j,e,t;"
                + "for(i=0;i<es.length;i++){e=es[i];if(!visible(e))continue;"
                + "t=((e.getAttribute('aria-label')||e.getAttribute('title')||e.textContent||'')+'').trim();"
                + "for(j=0;j<labels.length;j++){if(t===labels[j]){e.click();"
                + "console.log('[dsh-native] new-session-clicked');return;}}}"
                + "console.log('[dsh-native] new-session-not-found');"
                + "})();";
    }
}
