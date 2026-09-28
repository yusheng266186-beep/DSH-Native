package dev.dsh.nativeapp;

/** 调用 DSH WebUI 自带的会话搜索与归档筛选，不复制私有 RPC。 */
final class SessionOrganizer {
    static final String SEARCH_READY = "[dsh-session-tools] search-ready";
    static final String ARCHIVE_READY = "[dsh-session-tools] archive-ready";
    static final String UNSUPPORTED = "[dsh-session-tools] unsupported";

    private SessionOrganizer() { }

    static boolean isSearchReady(String value) {
        return value != null && value.indexOf(SEARCH_READY) >= 0;
    }

    static boolean isArchiveReady(String value) {
        return value != null && value.indexOf(ARCHIVE_READY) >= 0;
    }

    static boolean isUnsupported(String value) {
        return value != null && value.indexOf(UNSUPPORTED) >= 0;
    }

    static String focusSearchScript() {
        return "(function(){"
                + "function text(e){return ((e&&(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.title||e.textContent))||'').trim();}"
                + "function input(){var es=document.querySelectorAll('input');for(var i=0;i<es.length;i++){var t=text(es[i]);if(t==='搜索会话'||t==='搜索会话名称'||/^search sessions?$/i.test(t)||/^search session names$/i.test(t))return es[i];}return null;}"
                + "function ready(){var e=input();if(!e)return false;try{e.focus();e.click();console.log('" + SEARCH_READY + "');return true;}catch(x){return false;}}"
                + "if(ready())return;var bs=document.querySelectorAll('button,[role=button]');for(var i=0;i<bs.length;i++){var t=text(bs[i]);if(t==='搜索会话'||/^search sessions$/i.test(t)){bs[i].click();setTimeout(function(){if(!ready())console.log('" + UNSUPPORTED + " search');},120);return;}}"
                + "console.log('" + UNSUPPORTED + " search');})();";
    }

    static String showArchivedScript() {
        return "(function(){"
                + "function text(e){return ((e&&(e.getAttribute('aria-label')||e.title||e.textContent))||'').trim();}"
                + "function exact(labels){var es=document.querySelectorAll('button,[role=button],[role=menuitem]');for(var i=0;i<es.length;i++){var t=text(es[i]);for(var j=0;j<labels.length;j++)if(t===labels[j])return es[i];}return null;}"
                + "function choose(){var e=exact(['仅显示已归档','Archived only']);if(!e)return false;e.click();console.log('" + ARCHIVE_READY + "');return true;}"
                + "if(choose())return;var filter=exact(['筛选会话','Filter sessions']);if(filter){filter.click();setTimeout(function(){if(!choose())console.log('" + UNSUPPORTED + " archive');},120);return;}"
                + "console.log('" + UNSUPPORTED + " archive');})();";
    }
}
