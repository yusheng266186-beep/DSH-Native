package dev.dsh.nativeapp;

/** SessionProbe 注入脚本的离线回归测试。 */
public class SessionProbeTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) {
        String script = SessionProbe.script();
        if (args.length > 0 && "--dump-script".equals(args[0])) {
            System.out.print(script);
            return;
        }

        check("script is an IIFE", script.startsWith("(function(){")
                && script.endsWith("})();"), "wrapper missing");
        check("idempotent guard", script.contains("__dshSessionProbe"), "missing");
        check("only hooks existing fetch", script.contains("typeof window.fetch!=='function'"),
                "missing guard");
        check("captures relative and absolute session list", script.contains("function isList")
                && script.contains("api\\/session\\/list")
                && script.contains("ini.body"), "request not captured");
        check("reports running count", script.contains("[dsh-sess] r=")
                && script.contains("running===true"), "count missing");
        check("supports nested response", script.contains("x.result")
                && script.contains("x.value") && script.contains("x.items")
                && script.contains("x.sessions"), "shape missing");
        check("unknown response is not false idle", script.contains("if(!it)return"),
                "unrecognized response would report idle");
        check("uses original fetch for replay", script.contains("original.call(window"),
                "recursive replay");
        check("refreshes rpc id", script.contains("body.rpcId='probe-'"), "stale id");
        check("low frequency replay", script.contains("},5000)"), "wrong interval");
        check("no native bridge", !script.contains("addJavascriptInterface"), "unsafe bridge");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
