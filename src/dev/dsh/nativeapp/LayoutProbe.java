package dev.dsh.nativeapp;

/**
 * 在真实页面上测量「哪些控件被挤压」，并把**实际类名与尺寸**回报到 App 日志。
 *
 * <p><b>为什么需要它</b>
 *
 * <p>这一类缺陷（图标按钮被 flex 兄弟节点压扁、文件名盖住取消按钮）此前只能靠
 * 用户截图逐个发现。根因是上游的类名经过 CSS Module 编译后**变成哈希**，
 * 不再包含 remove / send 之类的语义词 —— 于是按源码里的变量名写选择器
 * （如 {@code [class*=remove]}）在真实页面上**永远匹配不到**，补丁形同虚设，
 * 而构建日志却显示「已写入」，看起来一切正常。
 *
 * <p>所以这里不猜：直接在运行时读 DOM，报告元素的真实 className、
 * aria-label 与渲染尺寸。由这些事实决定该写什么选择器。
 *
 * <p>只读：脚本不修改页面、不发送任何请求，仅通过既有的 console 桥输出。
 */
final class LayoutProbe {
    static final String MARKER = "[dsh-native] layout-probe";

    private LayoutProbe() { }

    static boolean isResult(String message) {
        return message != null && message.indexOf(MARKER) >= 0;
    }

    /** 扫描页面上所有「可见但尺寸可疑」的控件。 */
    static String script() {
        return "(function(){try{"
                + "var out=[];var vw=document.documentElement.clientWidth;"
                + "function box(e){var r=e.getBoundingClientRect();"
                + "return {w:Math.round(r.width),h:Math.round(r.height)};}"
                + "function walk(n){if(n.nodeType!==1)return;"
                + "var b=box(n);"
                + "var cn=String(n.className);"
                + "if(/visuallyHidden|VisuallyHidden/.test(cn))return;"
                + "if(b.w>0&&b.h>0){"
                // 方形图标类按钮：宽高差过大说明被挤压或被拉伸
                // 报告**所有**可见的方形按钮（而不是只报可疑的）：
                // 这样才能直接看到真实 className 与尺寸，用来判断是否被挤压。
                // 判定「疑似挤压」：宽高差超过 6px，或任一边小于 28px。
                + "if(n.tagName==='BUTTON'&&b.w<90&&b.h>=18&&b.h<90){"
                + "out.push({t:'sq',cls:String(n.className).slice(0,60),"
                + "aria:(n.getAttribute('aria-label')||'').slice(0,24),"
                + "w:b.w,h:b.h,d:Math.abs(b.w-b.h),"
                + "susp:Math.abs(b.w-b.h)>6||b.w<28||b.h<28});}"
                // 极窄的元素：多半被压到不可用
                + "if(b.w>0&&(b.w<14||b.h<10)){"
                + "out.push({t:'thin',cls:String(n.className).slice(0,60),"
                + "aria:(n.getAttribute('aria-label')||'').slice(0,24),"
                + "w:b.w,h:b.h});}"
                + "}"
                + "for(var i=0;i<n.children.length;i++)walk(n.children[i]);"
                + "}"
                + "walk(document.body);"
                + "out.sort(function(a,b){if(a.t!==b.t)return a.t==='sq'?-1:1;return (a.w*b.h)-(b.w*b.h);});"
                + "console.log('" + MARKER + " '+JSON.stringify({vw:vw,n:out.length,items:out.slice(0,10)}));"
                + "}catch(e){console.log('" + MARKER + " ERR '+e.message);}})();";
    }
}
