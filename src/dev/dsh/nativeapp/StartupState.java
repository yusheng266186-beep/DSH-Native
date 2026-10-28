package dev.dsh.nativeapp;

/** Startup readiness and animation boundaries, independent of Android timing. */
final class StartupState {
    static final long SWIM_MS = 2400L;
    static final long HANDOFF_MS = 180L;
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
        private static final String[] STAGES = {"assets", "runtime", "compat", "config", "process", "server", "core", "page", "paint", "handoff", "failed"};
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

    /** Separate decorative timing from readiness: a slow loop must never extend startup. */
    static float swimPhase(long elapsed) {
        return Math.max(0L, elapsed) % SWIM_MS / (float) SWIM_MS;
    }

    /** A brief, occasional blink, with a fully open eye at both ends. */
    static float blinkClosure(long elapsed) {
        long local = Math.max(0L, elapsed) % (SWIM_MS * 3L) - 6200L;
        if (local < 0L || local >= 160L) return 0f;
        float wave = (float) Math.sin(Math.PI * local / 160f);
        return wave * wave;
    }

    /** Bounded smoothstep handoff, independent of the whale's current position. */
    static float handoffProgress(long elapsed) {
        float t = Math.min(1f, Math.max(0f, elapsed / (float) HANDOFF_MS));
        return t * t * (3f - 2f * t);
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
