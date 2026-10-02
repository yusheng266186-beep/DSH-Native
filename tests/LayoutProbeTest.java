package dev.dsh.nativeapp;

/**
 * LayoutProbe 的离线测试。
 *
 * 探针的价值在于**不猜**：CSS Module 会把类名编译成哈希，按源码里的变量名
 * 写选择器在真实页面上根本匹配不到。有了探针，改选择器前先看真实 className。
 */
public class LayoutProbeTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        String js = LayoutProbe.script();

        System.out.println("=== 1. 只读、不改动页面 ===");
        check("no DOM mutation", !js.contains(".style.") && !js.contains(".remove()")
                && !js.contains(".setAttribute"), "script mutates the page");
        check("no fetch or network", !js.contains("fetch(") && !js.contains("XMLHttpRequest"),
                "script performs network calls");
        check("reads only clientWidth", js.contains("document.documentElement.clientWidth"),
                "missing viewport read");

        System.out.println("=== 2. 报告真实类名，供写选择器用 ===");
        check("reports className", js.contains("String(n.className)"), "className not reported");
        check("reports aria-label", js.contains("getAttribute('aria-label')"),
                "aria-label not reported");
        check("reports rendered size", js.contains("getBoundingClientRect"),
                "size not measured");

        System.out.println("=== 3. 覆盖两类症状 ===");
        check("detects square buttons", js.contains("'sq'"), "square-button scan missing");
        check("detects thin elements", js.contains("'thin'"), "thin-element scan missing");
        check("reports deviation", js.contains("Math.abs(b.w-b.h)"),
                "no squareness metric");
        check("sorts smallest first", js.contains("sort("), "results not prioritised");

        System.out.println("=== 4. 走既有 console 桥，不开新通道 ===");
        check("uses console.log", js.contains("console.log("), "no console output");
        check("carries the marker", js.contains(LayoutProbe.MARKER), "marker missing");
        check("recognises its own output", LayoutProbe.isResult("x " + LayoutProbe.MARKER + " {}"),
                "result not recognised");
        check("ignores unrelated messages", !LayoutProbe.isResult("hello"), "false positive");
        check("null safe", !LayoutProbe.isResult(null), "null not handled");

        System.out.println("=== 5. 出错也要有输出，不能静默 ===");
        check("try/catch reports errors", js.contains("catch(e)") && js.contains("ERR"),
                "errors swallowed silently");

        System.out.println();
        System.out.println("LayoutProbeTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
