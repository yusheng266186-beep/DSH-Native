package dev.dsh.nativeapp;

import java.util.Arrays;

/** ShareTask 的离线回归测试。 */
public class ShareTaskTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        String prompt = ShareTask.prompt("资料整理", Arrays.asList("a.pdf", "b 表格.xlsx"));
        check("project named", prompt.contains("资料整理"), prompt);
        check("all files listed", prompt.contains("a.pdf") && prompt.contains("b 表格.xlsx"), prompt);
        check("newlines removed from name", !ShareTask.prompt("", Arrays.asList("a\nb"))
                .contains("a\nb"), "raw newline");
        String quoted = ShareTask.quote("a\"b\\c\n");
        check("quote escaped", quoted.contains("\\\"") && quoted.contains("\\\\"), quoted);
        check("newline escaped", quoted.contains("\\n"), quoted);
        String js = ShareTask.javascript(prompt);
        check("textarea supported", js.contains("textarea"), "missing");
        check("contenteditable supported", js.contains("contenteditable"), "missing");
        check("send marker present", js.contains("share-task-sent"), "missing");
        check("fallback marker present", js.contains("share-task-prefilled"), "missing");
        check("script carries prompt", js.contains("a.pdf"), "missing");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
