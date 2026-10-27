package dev.dsh.nativeapp;

/** Read the rendered DSH theme without polling or changing the page. */
final class ThemeSync {
    static final String MARKER = "[dsh-theme] dark=";
    static String script() {
        return "(function(){if(window.__dshTheme)return;var last='',body,active=false;"
            + "var bodyWatch=new MutationObserver(report),treeWatch=new MutationObserver(bind);"
            + "function report(){if(!active||document.visibilityState==='hidden'||!body)return;"
            + "var value=body.hasAttribute('data-ds-dark-theme')?'1':'0';"
            + "if(value!==last){last=value;console.log('" + MARKER + "'+value);}}"
            + "function bind(){if(!active)return;if(body!==document.body){bodyWatch.disconnect();body=document.body;"
            + "if(body)bodyWatch.observe(body,{attributes:true,attributeFilter:['data-ds-dark-theme']});}report();}"
            + "function start(){active=true;treeWatch.observe(document.documentElement,{childList:true});bind();}"
            + "function stop(){active=false;bodyWatch.disconnect();treeWatch.disconnect();body=null;}"
            + "window.__dshTheme={start:start,stop:stop};window.addEventListener('pagehide',stop);"
            + "window.addEventListener('pageshow',start);window.addEventListener('dsh-native-resume',bind);"
            + "document.addEventListener('visibilitychange',bind);start();})();";
    }
}
