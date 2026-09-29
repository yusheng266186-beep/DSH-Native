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
        check("semantic dialog anchor", script.contains("button[aria-haspopup=\"dialog\"]"),
                "missing selector");
        check("Chinese settings anchor", script.contains("v==='设置'"), "missing");
        check("English settings anchor", script.contains("/^settings$/i"), "missing");
        check("Chinese tools label", script.contains("App 工具"), "missing");
        check("English tools label", script.contains("App tools"), "missing");
        check("Chinese model refresh label", script.contains("更新模型列表"), "missing");
        check("English model refresh label", script.contains("Refresh models"), "missing");
        check("clones live WebUI styles", script.contains("trigger.cloneNode(false)")
                && script.contains("settings.cloneNode(true)"), "not cloned");
        check("collapsed sidebar removes ambiguous gear",
                script.contains("_rail(?:Row)?") && script.contains("r.width>64")
                && script.contains("removeChild(row);")
                && script.contains("removeChild(modelRow);return true"), "collapsed state missing");
        check("expansion is observed", script.contains("'aria-expanded'")
                && script.contains("'hidden'") && script.contains("'style'"), "observer incomplete");
        check("layout marker present", script.contains("data-dsh-native-tools"), "missing");
        check("mutation recovery present", script.contains("new MutationObserver(queue)"),
                "missing observer");
        check("periodic recovery present", script.contains("setInterval(queue,4000)"),
                "missing interval");
        check("ready marker present", script.contains(WebToolsEntry.READY_MARKER), "missing");
        check("missing marker present", script.contains(WebToolsEntry.MISSING_MARKER), "missing");
        check("open marker present", script.contains(WebToolsEntry.OPEN_MARKER), "missing");
        check("refresh marker present", script.contains(WebToolsEntry.REFRESH_MODELS_MARKER), "missing");
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
