package dev.dsh.nativeapp;

/** DiagnosticReport 的离线回归测试。 */
public class DiagnosticReportTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        String report = new DiagnosticReport.Builder()
                .title("DSH Native diagnostics")
                .section("App")
                .add("Version", "0.30.0")
                .add("Token", "token=private-value")
                .add("Control", "a\u0001b\r\nc")
                .build();
        check("title retained", report.startsWith("DSH Native diagnostics"), report);
        check("section retained", report.contains("[App]"), report);
        check("ordinary value retained", report.contains("Version: 0.30.0"), report);
        check("secret masked", !report.contains("private-value") && report.contains("***"), report);
        check("control character replaced", report.contains("a?b\nc"), report);
        check("carriage return removed", report.indexOf('\r') < 0, report);

        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < DiagnosticReport.MAX_VALUE_CHARS + 20; i++) longValue.append('x');
        String cleaned = DiagnosticReport.clean(longValue.toString());
        check("long value truncated", cleaned.endsWith("...[truncated]"), "not truncated");
        check("null safe", "".equals(DiagnosticReport.clean(null)), "wrong");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
