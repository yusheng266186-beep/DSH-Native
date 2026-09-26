package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.List;

/** 把系统分享导入结果转换为可确认、可提交的 DSH 任务。 */
final class ShareTask {
    private ShareTask() { }

    static String prompt(String projectName, List<String> fileNames) {
        List<String> safe = new ArrayList<String>();
        if (fileNames != null) {
            for (String raw : fileNames) {
                if (raw == null) continue;
                String name = raw.replace('\n', ' ').replace('\r', ' ').trim();
                if (name.length() > 120) name = name.substring(0, 120);
                if (name.length() > 0) safe.add(name);
            }
        }
        String project = projectName == null || projectName.length() == 0
                ? "默认工作区" : projectName;
        StringBuilder out = new StringBuilder();
        out.append("请处理我刚刚分享到项目“").append(project).append("”的文件：\n");
        int shown = Math.min(20, safe.size());
        for (int i = 0; i < shown; i++) out.append("- ").append(safe.get(i)).append('\n');
        if (safe.size() > shown) out.append("- 另有 ").append(safe.size() - shown).append(" 个文件\n");
        out.append("请先读取这些文件并直接完成其中明确的任务；"
                + "如果无法判断目标，只询问一个最必要的问题。");
        return out.toString();
    }

    /**
     * 填入编辑器并点击发送。选择器同时兼容 textarea 与 contenteditable；
     * 找不到发送按钮时保留已填文字，让用户手动确认。
     */
    static String javascript(String prompt) {
        String value = quote(prompt == null ? "" : prompt);
        return "(function(){try{var p=" + value + ";"
                + "var e=document.querySelector('textarea:not([disabled])')||"
                + "document.querySelector('[contenteditable=\\\"true\\\"]');"
                + "if(!e){console.error('[dsh-native] share-task-editor-not-found');return 'editor-not-found';}"
                + "if(e.tagName==='TEXTAREA'||e.tagName==='INPUT'){"
                + "var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');"
                + "if(d&&d.set)d.set.call(e,p);else e.value=p;}else{e.textContent=p;}"
                + "var ie=typeof InputEvent==='function'?new InputEvent('input',"
                + "{bubbles:true,inputType:'insertText',data:p}):new Event('input',{bubbles:true});"
                + "e.dispatchEvent(ie);"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));e.focus();"
                + "setTimeout(function(){var bs=Array.prototype.slice.call(document.querySelectorAll('button'));"
                + "var b=bs.find(function(x){var t=(x.getAttribute('aria-label')||x.title||x.textContent||'').trim();"
                + "return t==='发送消息'||/^send message$/i.test(t);});"
                + "if(b&&!b.disabled){b.click();console.error('[dsh-native] share-task-sent');}"
                + "else{console.error('[dsh-native] share-task-prefilled');}},350);return 'ready';"
                + "}catch(e){console.error('[dsh-native] share-task-error '+String(e));return 'error';}})()";
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                default:
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
