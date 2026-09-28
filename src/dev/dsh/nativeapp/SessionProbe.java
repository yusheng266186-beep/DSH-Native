package dev.dsh.nativeapp;

/**
 * 页面最早期安装的会话状态探针。
 *
 * <p>它只包装页面已有的 {@code fetch}，记住 DSH 自己发出的只读
 * {@code api/session/list} 请求并低频重放。脚本只向控制台上报运行中会话数，
 * 不向网页暴露任何原生能力。</p>
 */
final class SessionProbe {
    private SessionProbe() { }

    /** 返回可重复注入的探针脚本。 */
    static String script() {
        return "(function(){"
                + "if(window.__dshSessionProbe)return;"
                + "if(typeof window.fetch!=='function')return;"
                + "window.__dshSessionProbe=1;"
                + "var original=window.fetch,request=null;"
                + "function urlOf(a){try{return typeof a==='string'?a:"
                + "(a&&a.url?a.url:String(a));}catch(x){return '';}}"
                + "function isList(u){return /(^|\\/)api\\/session\\/list(?:[?#]|$)/.test(String(u));}"
                + "function rows(j){var x=j;"
                + "if(x&&x.result)x=x.result;if(x&&x.value)x=x.value;"
                + "if(Array.isArray(x))return x;"
                + "if(x&&Array.isArray(x.items))return x.items;"
                + "if(x&&Array.isArray(x.sessions))return x.sessions;return null;}"
                + "function report(t){try{var it=rows(JSON.parse(t));if(!it)return;var n=0;"
                + "for(var i=0;i<it.length;i++){if(it[i]&&it[i].running===true)n++;}"
                + "console.log('[dsh-sess] r='+n);}catch(x){}}"
                + "function observe(p,u){try{if(!isList(u))return;"
                + "p.then(function(r){try{r.clone().text().then(report).catch(function(){});"
                + "}catch(x){}}).catch(function(){});}catch(x){}}"
                + "window.fetch=function(){var a=arguments[0],ini=arguments[1]||{},u=urlOf(a);"
                + "try{if(isList(u)&&ini.body){"
                + "request={u:u,m:ini.method||'POST',h:ini.headers,b:String(ini.body),"
                + "c:ini.credentials||'same-origin'};}}catch(x){}"
                + "var p=original.apply(this,arguments);observe(p,u);return p;};"
                + "function replay(){if(!request)return;try{"
                + "var body=JSON.parse(request.b);"
                + "body.rpcId='probe-'+Date.now()+'-'+Math.floor(Math.random()*1000000);"
                + "var headers={};try{if(request.h&&typeof request.h.forEach==='function'){"
                + "request.h.forEach(function(v,k){headers[k]=v;});}"
                + "else if(request.h){for(var k in request.h)headers[k]=request.h[k];}}catch(x){}"
                + "if(!headers['Content-Type']&&!headers['content-type'])"
                + "headers['Content-Type']='application/json';"
                + "original.call(window,request.u,{method:request.m,headers:headers,"
                + "body:JSON.stringify(body),credentials:request.c})"
                + ".then(function(r){return r.text();}).then(report).catch(function(){});"
                + "}catch(x){}}"
                + "window.__dshSessionProbeRefresh=replay;setInterval(replay,5000);"
                + "})();";
    }

    /** 让已安装的探针立即重放一次最近的只读会话列表请求。 */
    static String refreshScript() {
        return "(function(){try{if(window.__dshSessionProbeRefresh)"
                + "window.__dshSessionProbeRefresh();}catch(e){}})();";
    }
}
