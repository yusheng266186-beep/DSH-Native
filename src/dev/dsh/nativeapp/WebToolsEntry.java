package dev.dsh.nativeapp;

/**
 * DSH WebUI 内的原生工具入口注入脚本。
 *
 * <p>脚本只使用现有的控制台消息桥，不暴露 {@code JavascriptInterface}。
 * 它以 DSH 自己的「设置」按钮为语义锚点，克隆同一行的结构与样式，
 * 因而入口参与侧边栏布局，不会覆盖网页内容。侧栏收起时隐藏克隆入口，
 * 保证唯一可见的齿轮仍然是 DSH 自己的设置；展开后才显示带文字的 App 工具。
 * DSH 的 React 树重绘后，MutationObserver 会以幂等方式重新挂载。</p>
 */
final class WebToolsEntry {
    static final String OPEN_MARKER = "[dsh-native] open-settings";
    static final String READY_MARKER = "[dsh-native] tools-entry-ready";
    static final String MISSING_MARKER = "[dsh-native] tools-entry-missing";

    private WebToolsEntry() { }

    static boolean isReady(String message) {
        return message != null && message.indexOf(READY_MARKER) >= 0;
    }

    static boolean isMissing(String message) {
        return message != null && message.indexOf(MISSING_MARKER) >= 0;
    }

    /** 返回可重复注入的 WebUI 脚本。 */
    static String script() {
        return "(function(){"
                + "if(window.__dshNativeToolsEntry){"
                + "try{window.__dshNativeToolsEntry.ensure();}catch(e){}return;}"
                + "var ATTR='data-dsh-native-tools',reported=false,scheduled=false;"
                + "function textOf(e){return ((e&&"
                + "(e.getAttribute('aria-label')||e.title||e.textContent))||'').trim();}"
                + "function settingsButton(){"
                + "var bs=document.querySelectorAll('button[aria-haspopup=\"dialog\"]');"
                + "for(var i=0;i<bs.length;i++){var v=textOf(bs[i]);"
                + "if(v==='设置'||/^settings$/i.test(v))return bs[i];}return null;}"
                + "function labelFor(e){return /[\\u3400-\\u9fff]/.test(textOf(e))"
                + "?'App 工具':'App tools';}"
                + "function replaceLabel(e,label){var ss=e.querySelectorAll('span');"
                + "for(var i=0;i<ss.length;i++){var v=(ss[i].textContent||'').trim();"
                + "if(v==='设置'||/^settings$/i.test(v)){ss[i].textContent=label;return;}}}"
                + "function expanded(e){try{"
                + "var r=e.getBoundingClientRect&&e.getBoundingClientRect();"
                + "if(r&&r.width>0)return r.width>44;"
                + "return !/(^|\\s)[^\\s]*_rail(?:\\s|$)/.test(String(e.className));"
                + "}catch(x){return false;}}"
                + "function sync(row,settings){var show=expanded(settings);"
                + "row.hidden=!show;row.setAttribute('data-dsh-native-expanded',show?'1':'0');}"
                + "function prepareButton(e,label){"
                + "var remove=['aria-expanded','aria-haspopup','aria-keyshortcuts',"
                + "'aria-controls','aria-current','data-modal-autofocus'];"
                + "for(var i=0;i<remove.length;i++)e.removeAttribute(remove[i]);"
                + "e.setAttribute(ATTR,'button');e.setAttribute('aria-label',label);"
                + "e.setAttribute('title',label);replaceLabel(e,label);"
                + "e.addEventListener('click',function(ev){"
                + "ev.preventDefault();ev.stopPropagation();console.log('"
                + OPEN_MARKER + "');});}"
                + "function ensure(){"
                + "var settings=settingsButton();"
                + "if(!settings||!settings.parentElement||!settings.parentElement.parentElement)"
                + "return false;"
                + "var trigger=settings.parentElement,host=trigger.parentElement;"
                + "var label=labelFor(settings);"
                + "var sig=String(trigger.className)+'|'+String(settings.className)+'|'"
                + "+label+'|'+String(settings.childNodes.length);"
                + "var row=document.querySelector('['+ATTR+'=\"row\"]');"
                + "if(row&&row.parentElement===host&&"
                + "row.getAttribute('data-dsh-native-signature')===sig){"
                + "sync(row,settings);return true;}"
                + "if(row&&row.parentElement)row.parentElement.removeChild(row);"
                + "row=trigger.cloneNode(false);row.setAttribute(ATTR,'row');"
                + "row.setAttribute('data-dsh-native-signature',sig);"
                + "var button=settings.cloneNode(true);prepareButton(button,label);"
                + "row.appendChild(button);sync(row,settings);host.insertBefore(row,trigger);"
                + "if(!reported){reported=true;console.log('" + READY_MARKER + "');}"
                + "return true;}"
                + "function queue(){if(scheduled)return;scheduled=true;"
                + "setTimeout(function(){scheduled=false;try{ensure();}catch(e){}},40);}"
                + "window.__dshNativeToolsEntry={ensure:ensure};ensure();"
                + "try{new MutationObserver(queue).observe(document.documentElement,"
                + "{childList:true,subtree:true,attributes:true,"
                + "attributeFilter:['aria-label','aria-expanded','class','hidden','style']});}catch(e){}"
                + "setInterval(queue,4000);"
                + "setTimeout(function(){try{if(!ensure())console.error('"
                + MISSING_MARKER + "');}catch(e){console.error('"
                + MISSING_MARKER + "');}},15000);"
                + "})();";
    }
}
