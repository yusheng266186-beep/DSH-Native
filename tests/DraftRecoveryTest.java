package dev.dsh.nativeapp;

/** DraftRecovery 的安全边界与脚本输出。 */
public class DraftRecoveryTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        if (args.length > 0 && "--dump-script".equals(args[0])) {
            System.out.print(DraftRecovery.script());
            return;
        }
        check("restore marker parsed", DraftRecovery.isRestored("x [dsh-draft] restored"), "wrong");
        check("unrelated ignored", !DraftRecovery.isRestored("draft"), "wrong");
        check("null safe", !DraftRecovery.isRestored(null), "wrong");
        String js = DraftRecovery.script();
        check("idempotent", js.contains("__dshDraftRecovery"), "missing");
        check("uses same-origin storage", js.contains("localStorage") && !js.contains("fetch("), "wrong");
        check("never sends", !js.contains(".click(") && !js.contains("submit("), "unsafe");
        check("does not steal focus", !js.contains(".focus("), "unsafe");
        check("restores only empty editor", js.contains("!restored&&!val(e)"), "missing");
        check("excludes dialogs and passwords", js.contains("[role=dialog]")
                && js.contains("password"), "missing");
        check("bounded and expiring", js.contains("MAX=100000") && js.contains("TTL=604800000"), "missing");
        check("programmatic send clear watched", js.contains("function sweep")
                && js.contains("__dshDraftHad"), "sent text could return");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
