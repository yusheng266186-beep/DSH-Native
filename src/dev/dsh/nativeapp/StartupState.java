package dev.dsh.nativeapp;

/** Startup readiness and animation boundaries, independent of Android timing. */
final class StartupState {
    static final long CYCLE_MS = 720L;
    static final String PAINTED = "[dsh-startup] painted ";
    private long generation;
    private boolean painted;
    private boolean connected;

    long navigate() { generation++; painted = false; connected = false; return generation; }
    long generation() { return generation; }
    boolean painted(long value) {
        if (value != generation) return false;
        painted = true; return true;
    }
    void connected(boolean value) { connected = value; }
    boolean ready() { return painted && connected; }

    static boolean cycleEnded(long start, long previous, long now) {
        return now >= start && now >= previous
                && (now - start) / CYCLE_MS > Math.max(0L, previous - start) / CYCLE_MS;
    }

    /** The launcher is mounted only after the core UI shell is available. No text is inspected. */
    static String script(long generation) {
        return "(function(){var generation=" + generation + ";"
                + "if(window.__dshStartupPaint&&window.__dshStartupPaint.generation===generation)return;"
                + "if(window.__dshStartupPaint)window.__dshStartupPaint.stop();"
                + "var stopped=false,observer,timer;function stop(){stopped=true;"
                + "if(observer)observer.disconnect();clearTimeout(timer);}"
                + "window.__dshStartupPaint={generation:generation,stop:stop};"
                + "function visible(el){return el&&el.getClientRects().length&&getComputedStyle(el).visibility!=='hidden'&&getComputedStyle(el).opacity!=='0';}"
                + "function check(){if(stopped)return;var el=document.querySelector('[data-slot=\"settings.launcher\"]');"
                // Slot wrappers use display:contents and have no rectangle of their own.
                + "if(!el)return;if(!visible(el)){var controls=el.querySelectorAll('button,[role=\"button\"]');"
                + "el=null;for(var i=0;i<controls.length;i++)if(visible(controls[i])){el=controls[i];break;}}if(!el)return;"
                + "stop();requestAnimationFrame(function(){requestAnimationFrame(function(){"
                + "if(window.__dshStartupPaint.generation===generation)console.log('" + PAINTED + "'+generation);});});}"
                + "observer=new MutationObserver(check);observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true});"
                + "timer=setTimeout(stop,30000);check();})();";
    }
}
