package dev.dsh.nativeapp;

/**
 * 有界、只读的布局诊断。加载、附件变动、打开面板与旋转后重新测量。
 * 仅报告固定动作名、CSS 类名与几何尺寸，不记录文件名、路径或页面正文。
 */
final class LayoutProbe {
    static final String MARKER = "[dsh-native] layout-probe";
    private LayoutProbe() { }

    static boolean isResult(String message) {
        return message != null && message.startsWith(MARKER + " ");
    }

    static String script() {
        return "(function(){"
                + "if(window.__dshLayoutProbe){window.__dshLayoutProbe.scan();return;}"
                + "var timer=0,last=0,previous='',active=false;"
                + "var selector='button,[role=button],[role=switch]';"
                + "function action(n){var a=n.getAttribute('aria-label')||'';"
                + "if(/^(Remove image |移除图片 )/.test(a))return 'remove-image';"
                + "if(/^(Remove file |移除文件 )/.test(a))return 'remove-file';"
                + "if(/^(Send message|发送消息)$/.test(a))return 'send';"
                + "if(/^(Queue message|排队发送)$/.test(a))return 'queue';"
                + "if(/^(Steer message|插话发送)$/.test(a))return 'steer';"
                + "if(/^(Stop generating|停止生成)$/.test(a))return 'stop';"
                + "if(/^(Close|关闭)$/.test(a))return 'close';"
                + "if(n.hasAttribute('data-textpreview-tool'))return 'file-tool';"
                + "if(n.getAttribute('role')==='switch')return 'switch';"
                + "return 'control';}"
                + "function round(x){return Math.round(x*10)/10;}"
                + "function measure(n){var r=n.getBoundingClientRect(),s=getComputedStyle(n);"
                + "if(r.width<=0||r.height<=0||s.visibility==='hidden'||s.display==='none'"
                + "||r.bottom<=0||r.top>=innerHeight||r.right<=0||r.left>=innerWidth)return null;"
                + "var p=n.parentElement,pr=p?p.getBoundingClientRect():{width:0,height:0};"
                + "var a=action(n),issue='';"
                + "if(/^(remove-image|remove-file|send|queue|steer|stop|close)$/.test(a)"
                + "&&Math.abs(r.width-r.height)>6)issue='stretched';"
                + "if(a==='control'&&r.width<=40&&n.querySelector('svg')"
                + "&&parseFloat(s.minHeight)>r.width+6)issue='stretched';"
                + "if(pr.width>0&&pr.height>0&&s.position!=='absolute'&&s.position!=='fixed'"
                + "&&p.getAttribute('role')==='tab'&&r.bottom>pr.bottom+1)issue='tab-overflow';"
                + "return {action:a,cls:String(n.className).slice(0,64),w:round(r.width),"
                + "h:round(r.height),minH:s.minHeight,shrink:s.flexShrink,"
                + "pw:round(pr.width),ph:round(pr.height),issue:issue};}"
                + "function report(){timer=0;if(!active||document.hidden)return;last=Date.now();"
                + "try{var q=document.querySelectorAll(selector),priority=[],other=[],out=[];"
                + "for(var i=0;i<q.length;i++){if(action(q[i])!=='control')priority.push(q[i]);"
                + "else if(other.length<160)other.push(q[i]);}"
                + "q=priority.concat(other);for(var j=0;j<q.length&&j<200;j++){"
                + "var b=measure(q[j]);if(b)out.push(b);}"
                + "out.sort(function(a,b){if(!!a.issue!==!!b.issue)return a.issue?-1:1;"
                + "return (a.action==='control'?1:0)-(b.action==='control'?1:0);});"
                + "var vw=document.documentElement.clientWidth,items=out.slice(0,20);"
                + "var signature=JSON.stringify({vw:vw,items:items});"
                + "if(signature===previous)return;previous=signature;"
                + "console.log('" + MARKER + " '+JSON.stringify({kind:'summary',vw:vw,"
                + "visible:out.length,reported:items.length}));"
                + "for(var k=0;k<items.length;k++)console.log('" + MARKER
                + " '+JSON.stringify(items[k]));"
                + "}catch(e){console.log('" + MARKER + " ERR measurement');}}"
                + "function schedule(){if(!active||timer)return;"
                + "timer=setTimeout(report,Math.max(700,2000-(Date.now()-last)));}"
                + "function changed(rows){for(var i=0;i<rows.length;i++){var m=rows[i];"
                + "if(m.type==='attributes'){schedule();return;}"
                + "var nodes=Array.prototype.slice.call(m.addedNodes).concat("
                + "Array.prototype.slice.call(m.removedNodes));"
                + "for(var j=0;j<nodes.length;j++){var n=nodes[j];"
                + "if(n.nodeType===1&&(n.matches(selector)||n.querySelector(selector))){"
                + "schedule();return;}}}}"
                + "var observer=new MutationObserver(changed);"
                + "function start(){if(active)return;active=true;"
                + "observer.observe(document.documentElement,{childList:true,subtree:true,"
                + "attributes:true,attributeFilter:['aria-label','aria-expanded','hidden']});"
                + "document.addEventListener('input',schedule,true);"
                + "document.addEventListener('change',schedule,true);"
                + "document.addEventListener('click',schedule,true);"
                + "document.addEventListener('visibilitychange',schedule);"
                + "window.addEventListener('resize',schedule);schedule();}"
                + "function stop(){active=false;clearTimeout(timer);timer=0;observer.disconnect();"
                + "document.removeEventListener('input',schedule,true);"
                + "document.removeEventListener('change',schedule,true);"
                + "document.removeEventListener('click',schedule,true);"
                + "document.removeEventListener('visibilitychange',schedule);"
                + "window.removeEventListener('resize',schedule);}"
                + "window.__dshLayoutProbe={scan:schedule};"
                + "window.addEventListener('pagehide',stop);window.addEventListener('pageshow',start);"
                + "start();})();";
    }
}
