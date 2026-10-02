package dev.dsh.nativeapp;

/**
 * LayoutProbe 的离线测试。
 *
 * CSS Module 的运行时类名由构建器生成。补丁只用实测的稳定锚点，
 * 诊断保留真实类名与尺寸，不输出可能带文件名的原始 aria-label。
 */
public class LayoutProbeTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        String js = LayoutProbe.script();
        if (args.length == 1 && "--dump-script".equals(args[0])) {
            System.out.print(js);
            return;
        }

        System.out.println("=== 1. 只读、不改动页面 ===");
        check("no DOM mutation", !js.contains(".style.") && !js.contains(".remove()")
                && !js.contains(".setAttribute"), "script mutates the page");
        check("no fetch or network", !js.contains("fetch(") && !js.contains("XMLHttpRequest"),
                "script performs network calls");
        check("reads only clientWidth", js.contains("document.documentElement.clientWidth"),
                "missing viewport read");

        System.out.println("=== 2. 报告真实类名，供写选择器用 ===");
        check("reports className", js.contains("String(n.className)"), "className not reported");
        check("uses aria only for fixed action names", js.contains("getAttribute('aria-label')")
                && !js.contains("aria:"), "raw labels would include file names");
        check("reports rendered size", js.contains("getBoundingClientRect"),
                "size not measured");

        System.out.println("=== 3. 覆盖两类症状 ===");
        check("detects stretched controls", js.contains("'stretched'"), "missing");
        check("detects tab overflow", js.contains("'tab-overflow'"), "missing");
        check("reports deviation", js.contains("Math.abs(r.width-r.height)"), "missing");
        check("prioritises issues", js.contains("a.issue?-1:1"), "missing");

        System.out.println("=== 4. 走既有 console 桥，不开新通道 ===");
        check("uses console.log", js.contains("console.log("), "no console output");
        check("carries the marker", js.contains(LayoutProbe.MARKER), "marker missing");
        check("recognises its own output", LayoutProbe.isResult(LayoutProbe.MARKER + " {}"),
                "result not recognised");
        check("requires exact prefix", !LayoutProbe.isResult("x " + LayoutProbe.MARKER + " {}"),
                "unrelated text was recognised");
        check("ignores unrelated messages", !LayoutProbe.isResult("hello"), "false positive");
        check("null safe", !LayoutProbe.isResult(null), "null not handled");

        System.out.println("=== 5. 出错也要有输出，不能静默 ===");
        check("try/catch reports errors", js.contains("catch(e)") && js.contains("ERR"),
                "errors swallowed silently");
        check("does not log error contents", !js.contains("e.message"), "may contain private data");
        check("watches later controls", js.contains("MutationObserver") && js.contains("'change'"), "missing");
        check("cleans up on pagehide", js.contains("'pagehide',stop") && js.contains("disconnect()"), "missing");

        System.out.println();
        System.out.println("LayoutProbeTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
