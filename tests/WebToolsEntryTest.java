package dev.dsh.nativeapp;

/** WebToolsEntry 的离线回归测试。 */
public class WebToolsEntryTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) {
        String script = WebToolsEntry.script();
        if (args.length > 0 && "--dump-script".equals(args[0])) {
            System.out.print(script);
            return;
        }

        check("script is an IIFE", script.startsWith("(function(){")
                && script.endsWith("})();"), "wrapper missing");
        check("singleton guard present", script.contains("__dshNativeToolsEntry"),
                "missing guard");
        check("uses core launcher", script.contains("settings.launcher"), "missing launcher");
        check("removes old entries", script.contains("removeChild(old[i])"), "old entry retained");
        check("no cloned entry", !script.contains("cloneNode") && !script.contains("insertBefore"), "new sidebar entry");
        check("no mutation loop", !script.contains("MutationObserver") && !script.contains("setInterval"), "obsolete work loop");
        check("no fixed overlay", !script.contains("position:fixed")
                && !script.contains("position: fixed"), "overlay found");
        check("no native bridge exposure", !script.contains("addJavascriptInterface"),
                "unsafe bridge found");
        check("ready parser exact", WebToolsEntry.isReady("x "
                + WebToolsEntry.READY_MARKER), "not parsed");
        check("missing parser exact", WebToolsEntry.isMissing("x "
                + WebToolsEntry.MISSING_MARKER), "not parsed");
        check("unrelated console ignored", !WebToolsEntry.isReady("ready")
                && !WebToolsEntry.isMissing(null), "false positive");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}

