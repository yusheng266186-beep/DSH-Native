package dev.dsh.nativeapp;

/** WebSocket 连接观测与安全恢复策略：纯 Java、无 Android 依赖。 */
final class ConnectionRecovery {

    static final int UNKNOWN = 0;
    static final int CONNECTING = 1;
    static final int CONNECTED = 2;
    static final int RETRYING = 3;
    static final int ERROR = 4;

    static final long RELOAD_AFTER_MS = 30_000L;
    static final int MAX_AUTO_RELOADS = 2;
    private static final String MARK = "[dsh-conn] ";

    private ConnectionRecovery() { }

    /** Keep the app's local carrier independent of Android's internet hint. */
    static String patchClient(String source, String helper) {
        final String tag = "/* DSH-ANDROID-CONNECTION-v1 */", end = "/* DSH-ANDROID-CONNECTION-END */";
        if (source == null || helper == null || !helper.startsWith(tag) || !helper.contains(end)) return null;
        int start = source.indexOf(tag);
        if (start >= 0) {
            int finish = source.indexOf(end, start);
            if (finish < 0) return null;
            source = source.substring(0,start) + source.substring(finish+end.length()+(source.startsWith("\n",finish+end.length())?1:0));
        }
        source = source.replace("\nandroidConnection(ctx, handle);", "");
        source = source.replace("function watchBrowserNetwork(controller) {", "function watchBrowserNetwork(controller, local = false) {");
        String guard = "if (browser === void 0 || initiallyAvailable === void 0) return () => {};";
        if (source.contains(guard)) source = source.replace(guard, "if (local || browser === void 0 || initiallyAvailable === void 0) return () => {};");
        else if (!source.contains("if (local || browser === void 0 || initiallyAvailable === void 0)")) return null;
        source = source.replace("stopNetworkWatch: watchBrowserNetwork(controller)", "stopNetworkWatch: watchBrowserNetwork(controller, handle.isLoopback)");
        if (!source.contains("watchBrowserNetwork(controller, local = false)") || !source.contains("watchBrowserNetwork(controller, handle.isLoopback)")) return null;
        String anchor = "ctx.provide(\"connection\", handle);";
        if (source.indexOf(anchor) < 0 || source.indexOf(anchor) != source.lastIndexOf(anchor)) return null;
        String install = "function installConnection(ctx, options = {}) {";
        if (source.indexOf(install) < 0 || source.indexOf(install) != source.lastIndexOf(install)) return null;
        return source.replace(anchor, anchor + "\nandroidConnection(ctx, handle);")
                .replace(install, helper.trim() + "\n" + install);
    }

    static int parseConsole(String message) {
        if (message == null) return -1;
        int at = message.indexOf(MARK);
        if (at < 0) return -1;
        String value = message.substring(at + MARK.length()).trim();
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(0, space);
        if ("connecting".equals(value)) return CONNECTING;
        if ("open".equals(value)) return CONNECTED;
        if ("close".equals(value)) return RETRYING;
        if ("error".equals(value)) return ERROR;
        return -1;
    }

    static boolean isProblem(int state) {
        return state == RETRYING || state == ERROR;
    }

    /** 任务运行时绝不自动刷新；短暂断线与达到上限时也不刷新。 */
    static boolean shouldReload(boolean taskActive, long lostForMs, int attempts) {
        return !taskActive && lostForMs >= RELOAD_AFTER_MS
                && attempts >= 0 && attempts < MAX_AUTO_RELOADS;
    }

    static String label(int state, boolean english) {
        if (english) {
            switch (state) {
                case CONNECTING: return "Connecting";
                case CONNECTED: return "Connected";
                case RETRYING: return "Reconnecting";
                case ERROR: return "Connection error";
                default: return "Checking connection";
            }
        }
        switch (state) {
            case CONNECTING: return "正在连接";
            case CONNECTED: return "连接正常";
            case RETRYING: return "正在重连";
            case ERROR: return "连接异常";
            default: return "正在检测连接";
        }
    }

    /**
     * 尽早包装页面的 WebSocket 构造器，只上报生命周期，不读取 URL、消息或令牌。
     * 包装保留原型和标准常量，重复注入由页面标记拦截。
     */
    static String script() {
        return "(function(){"
             + "if(window.__dshConnectionWatch)return;window.__dshConnectionWatch=1;"
             + "var Native=window.WebSocket;if(!Native)return;"
             + "var last='',openCount=0;function report(s){"
             + "if(s==='open'&&window.__dshLocalConnectionState&&window.__dshLocalConnectionState!=='connected')return;"
             + "if(s!==last){last=s;console.log('[dsh-conn] '+s);}"
             + "}"
             + "function Wrapped(url,protocols){"
             + "var ws=arguments.length>1?new Native(url,protocols):new Native(url);"
             + "var opened=0,closed=0;if(openCount===0)report('connecting');"
             + "ws.addEventListener('open',function(){if(!opened){opened=1;openCount++;}report('open');});"
             + "ws.addEventListener('close',function(){if(closed)return;closed=1;"
             + "if(opened&&openCount>0)openCount--;report(openCount>0?'open':'close');});"
             + "ws.addEventListener('error',function(){if(openCount===0)report('error');});"
             + "return ws;"
             + "}"
             + "Wrapped.prototype=Native.prototype;"
             + "try{Object.defineProperty(Wrapped,'name',{value:'WebSocket'});}catch(e){}"
             + "var ks=['CONNECTING','OPEN','CLOSING','CLOSED'];"
             + "for(var i=0;i<ks.length;i++){try{Wrapped[ks[i]]=Native[ks[i]];}catch(e){}}"
             + "window.WebSocket=Wrapped;"
             + "})();";
    }
}
