package dev.dsh.nativeapp;

/** A configuration change reflows the existing document; never rewrites files or reloads it. */
final class ViewportState {
    private ViewportState() { }
    static String script(int screenWidthDp) {
        return "(()=>{const m=document.querySelector('meta[name=viewport]');if(m){const c='width="
                + MobileLayout.viewportWidth(screenWidthDp) + "';if(m.content!==c)m.content=c;}})();";
    }
}
