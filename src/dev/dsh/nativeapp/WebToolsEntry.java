package dev.dsh.nativeapp;

/**
 * DSH WebUI 内的原生工具入口注入脚本。
 *
 * <p>脚本只使用现有的控制台消息桥，不暴露 {@code JavascriptInterface}。
 * 它以 DSH 自己的设置或账号菜单为语义锚点，按 settings.launcher 槽位
 * 在设置区域前单独纵向排列，不挤压原始入口。侧栏收起时移除克隆入口，
 * 展开后才显示带文字的 App 工具，原始设置与账号菜单仍由 DSH 管理。
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
                + "var ATTR='data-dsh-native-tools',"
                + "reported=false,scheduled=false;"
                + "function textOf(e){return ((e&&"
                + "(e.getAttribute('aria-label')||e.title||e.textContent))||'').trim();}"
                + "function settingsButton(){"
                + "var bs=Array.prototype.slice.call(document.querySelectorAll('button[aria-haspopup=\"dialog\"]'));"
                + "bs=bs.concat(Array.prototype.slice.call(document.querySelectorAll('button[aria-haspopup=\"menu\"][data-signed-out]')));"
                + "for(var i=0;i<bs.length;i++){var v=textOf(bs[i]);"
                + "if(v==='设置'||/^settings$/i.test(v)||v==='账号菜单'||v==='Account menu')return bs[i];}return null;}"
                + "function labelFor(e){return /[\\u3400-\\u9fff]/.test(textOf(e))"
                + "?'App 工具':'App tools';}"
                + "function replaceLabel(e,label){var ss=e.querySelectorAll('span');"
                + "if(e.getAttribute('data-signed-out')!==null){e.textContent=label;e.removeAttribute('data-signed-out');return;}"
                + "for(var i=0;i<ss.length;i++){var v=(ss[i].textContent||'').trim();"
                + "if(v==='设置'||/^settings$/i.test(v)){ss[i].textContent=label;return;}}}"
                + "function expanded(e){try{"
                + "var cls=String(e.className)+' '+String(e.parentElement&&e.parentElement.className);"
                + "if(e.getAttribute('data-collapsed')==='true'||"
                + "/(^|\\s)[^\\s]*_rail(?:Row)?(?:\\s|$)/.test(cls))return false;"
                + "if(e.getAttribute('data-collapsed')==='false')return true;"
                + "var ss=e.querySelectorAll('span');"
                + "for(var i=0;i<ss.length;i++){var t=(ss[i].textContent||'').trim();"
                + "if(t==='设置'||/^settings$/i.test(t)){"
                + "var q=ss[i].getBoundingClientRect&&ss[i].getBoundingClientRect();"
                + "if(q&&q.width>0&&q.height>0)return true;}}"
                + "var r=e.getBoundingClientRect&&e.getBoundingClientRect();"
                + "return !!(r&&r.width>64);"
                + "}catch(x){return false;}}"
                + "function prepareButton(e,label,marker,attr){"
                + "var remove=['aria-expanded','aria-haspopup','aria-keyshortcuts',"
                + "'aria-controls','aria-current','data-modal-autofocus'];"
                + "for(var i=0;i<remove.length;i++)e.removeAttribute(remove[i]);"
                + "e.setAttribute(attr,'button');e.setAttribute('aria-label',label);"
                + "e.setAttribute('title',label);replaceLabel(e,label);"
                + "e.addEventListener('click',function(ev){"
                + "ev.preventDefault();ev.stopPropagation();console.log(marker);});}"
                + "function ensure(){"
                + "var settings=settingsButton();"
                + "if(!settings||!settings.parentElement||!settings.parentElement.parentElement)"
                + "return false;"
                + "var trigger=settings.parentElement,host=trigger.parentElement;"
                + "var slot=settings.closest&&settings.closest('[data-slot=\"settings.launcher\"]');"
                + "if(slot&&slot.parentElement){trigger=slot.parentElement;host=trigger;}"
                + "var section=document.querySelector('['+ATTR+'=\"section\"]');"
                + "var outer=host.parentElement;if(!outer)return false;"
                + "var row=document.querySelector('['+ATTR+'=\"row\"]');"
                + "if(!expanded(settings)){if(row&&row.parentElement)"
                + "row.parentElement.removeChild(row);if(section&&section.parentElement)"
                + "section.parentElement.removeChild(section);return true;}"
                + "var label=labelFor(settings);"
                + "var sig=String(trigger.className)+'|'+String(settings.className)+'|'"
                + "+label+'|'+String(settings.childNodes.length);"
                + "if(row&&row.parentElement===section&&section&&section.parentElement===outer&&"
                + "row.getAttribute('data-dsh-native-signature')===sig){"
                + "return true;}"
                + "if(row&&row.parentElement)row.parentElement.removeChild(row);"
                + "if(section&&section.parentElement)section.parentElement.removeChild(section);"
                + "section=document.createElement('div');section.setAttribute(ATTR,'section');"
                + "section.setAttribute('style','display:flex;flex-direction:column;gap:4px;width:100%;min-width:0;flex-shrink:0');"
                + "row=trigger.cloneNode(false);row.setAttribute(ATTR,'row');"
                + "row.setAttribute('data-dsh-native-signature',sig);"
                + "var button=settings.cloneNode(true);prepareButton(button,label,'"
                + OPEN_MARKER + "',ATTR);row.appendChild(button);"
                + "var rows=[row];for(var j=0;j<rows.length;j++){"
                + "rows[j].setAttribute('style','display:block;width:100%;min-width:0');"
                + "rows[j].firstElementChild.setAttribute('style','display:flex;align-items:center;width:100%;min-width:0;min-height:44px;white-space:nowrap;overflow:hidden');"
                + "var spans=rows[j].querySelectorAll('span');for(var k=0;k<spans.length;k++)"
                + "spans[k].setAttribute('style','min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap');"
                + "section.appendChild(rows[j]);}outer.insertBefore(section,host);"
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
