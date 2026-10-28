package dev.dsh.nativeapp;

/** Optional capability inspection never controls core reuse or first-frame readiness. */
final class StartupDiagnostics {
    static final long RETRY_MS = 60L * 60 * 1000;
    static final long QUIET_MS = 5000L;
    static final String INTERACTION = "[dsh-user-interaction]";
    private StartupDiagnostics() { }
    static boolean quiet(long now, long resumedAt, long touchedAt) {
        return now >= resumedAt && now >= touchedAt
                && now - resumedAt >= QUIET_MS && now - touchedAt >= QUIET_MS;
    }
    static boolean due(String expected, String passed, String attempted, long attemptedAt, long now) {
        if (expected == null || expected.isEmpty() || expected.equals(passed)) return false;
        if (!expected.equals(attempted) || attemptedAt <= 0 || now < attemptedAt) return true;
        return now - attemptedAt >= RETRY_MS;
    }
    /** IME composition goes through WebView's input connection rather than Activity key events. */
    static String interactionScript() {
        return "(function(){if(window.__dshIdleInput)return;window.__dshIdleInput=1;var timer=0,last=-Infinity;"
                + "function signal(){timer=0;if(!document.hidden){last=Date.now();console.log('" + INTERACTION + "');}}"
                + "function activity(){if(document.hidden)return;if(timer)clearTimeout(timer);timer=0;"
                + "if(Date.now()-last>=500)signal();else timer=setTimeout(signal,500);}"
                + "document.addEventListener('input',activity,true);document.addEventListener('keydown',activity,true);"
                + "document.addEventListener('visibilitychange',function(){if(document.hidden&&timer){clearTimeout(timer);timer=0;}});})();";
    }
}
