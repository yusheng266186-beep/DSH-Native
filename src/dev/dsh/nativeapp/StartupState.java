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

    /** Fixed numeric milestones only; no URLs, paths or conversation data. */
    static final class Timing {
        private static final String[] STAGES = {"assets", "runtime", "compat", "preflight", "process", "server", "core", "page", "paint", "handoff", "failed"};
        private final long started;
        private final long[] elapsed = new long[STAGES.length];
        Timing(long started) { this.started = started; java.util.Arrays.fill(elapsed, -1L); }
        synchronized boolean mark(String stage, long now) {
            if (now < started) return false;
            for (int i = 0; i < STAGES.length; i++) if (STAGES[i].equals(stage)) {
                if (elapsed[i] >= 0L) return false;
                elapsed[i] = now - started; return true;
            }
            return false;
        }
        synchronized String summary() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < STAGES.length; i++) if (elapsed[i] >= 0L) {
                if (out.length() > 0) out.append(", ");
                out.append(STAGES[i]).append('=').append(elapsed[i]).append("ms");
            }
            return out.length() == 0 ? "pending" : out.toString();
        }
    }

    static boolean cycleEnded(long start, long previous, long now) {
        return now >= start && now >= previous
                && (now - start) / CYCLE_MS > Math.max(0L, previous - start) / CYCLE_MS;
    }

    /** The launcher is mounted only after the core UI shell is available. No text is inspected. */
    static String script(long generation) {
        return "(function(){var generation=" + generation + ";"
                + "if(window.__dshStartupPaint&&window.__dshStartupPaint.generation===generation)return;"
                + "if(window.__dshStartupPaint)window.__dshStartupPaint.stop();"
                + "var stopped=false,observer;function stop(){stopped=true;"
                + "if(observer)observer.disconnect();document.removeEventListener('visibilitychange',check);window.removeEventListener('dsh-native-resume',check);}"
                + "window.__dshStartupPaint={generation:generation,stop:stop};"
                + "function visible(el){return el&&el.getClientRects().length&&getComputedStyle(el).visibility!=='hidden'&&getComputedStyle(el).opacity!=='0';}"
                + "function check(){if(stopped||document.visibilityState==='hidden')return;var el=document.querySelector('[data-slot=\"settings.launcher\"]');"
                // Slot wrappers use display:contents and have no rectangle of their own.
                + "if(!el)return;if(!visible(el)){var controls=el.querySelectorAll('button,[role=\"button\"]');"
                + "el=null;for(var i=0;i<controls.length;i++)if(visible(controls[i])){el=controls[i];break;}}if(!el)return;"
                + "stop();requestAnimationFrame(function(){requestAnimationFrame(function(){"
                + "if(window.__dshStartupPaint.generation===generation)console.log('" + PAINTED + "'+generation);});});}"
                + "observer=new MutationObserver(check);observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true});"
                + "document.addEventListener('visibilitychange',check);window.addEventListener('dsh-native-resume',check);check();})();";
    }
}
