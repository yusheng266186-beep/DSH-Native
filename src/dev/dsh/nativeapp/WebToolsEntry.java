package dev.dsh.nativeapp;

/**
 * DSH WebUI 内的原生工具入口注入脚本。
 *
 * <p>脚本只使用现有的控制台消息桥，不暴露 {@code JavascriptInterface}。
 * 它清理旧版的独立入口，设置与账号菜单由 DSH 自己管理。</p>
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

    /** Remove older cloned entries; the core launcher owns navigation and layout. */
    static String script() {
        return "(function(){"
                + "if(window.__dshNativeToolsEntry&&window.__dshNativeToolsEntry.integrated){window.__dshNativeToolsEntry.ensure();return;}"
                + "var reported=false;function ensure(){"
                + "var old=document.querySelectorAll('[data-dsh-native-tools]');"
                + "for(var i=0;i<old.length;i++)if(old[i].parentElement)old[i].parentElement.removeChild(old[i]);"
                + "var ready=!!document.querySelector('[data-slot=\"settings.launcher\"]');"
                + "if(ready&&!reported){reported=true;console.log('" + READY_MARKER + "');}return ready;}"
                + "window.__dshNativeToolsEntry={integrated:true,ensure:ensure};ensure();"
                + "setTimeout(function(){if(!ensure())console.error('" + MISSING_MARKER + "');},15000);"
                + "})();";
    }
}
