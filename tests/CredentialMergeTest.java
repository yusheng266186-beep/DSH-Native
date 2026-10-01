package dev.dsh.nativeapp;

import java.util.Arrays;
import java.util.List;

/**
 * CredentialMerge 的离线测试。
 *
 * 重点是三类历史上真会出错的点：
 *   ① 键必须整行匹配 —— 原来的 indexOf(k + ":") 会让 FOO 命中 FOOBAR，
 *      导致用户密钥被静默跳过（表现为「导入了 0 个」）；
 *   ② YAML refs: 段的边界 —— 缩进续行属于本段，顶格非空行结束；
 *   ③ 不重复写回 —— 没有新键时不应改动目标文件。
 */
public class CredentialMergeTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static String refs(String... entries) {
        StringBuilder sb = new StringBuilder("provider: deepseek\nrefs:\n");
        for (String e : entries) sb.append("  ").append(e).append('\n');
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 读取 refs 段 ===");
        List<String> got = CredentialMerge.readRefs(refs("DEEPSEEK_API_KEY: aaa"));
        check("one entry read", got.size() == 1 && got.get(0).equals("DEEPSEEK_API_KEY: aaa"),
                "got " + got);
        check("no refs section -> empty",
                CredentialMerge.readRefs("provider: x\nother: y\n").isEmpty(), "not empty");
        check("null text -> empty", CredentialMerge.readRefs(null).isEmpty(), "not empty");
        check("two entries read",
                CredentialMerge.readRefs(refs("A: 1", "B: 2")).size() == 2, "wrong count");
        check("entry without colon skipped",
                CredentialMerge.readRefs("refs:\n  justtext\n").isEmpty(), "accepted junk");

        System.out.println("=== 2. refs 段在顶格非空行处结束 ===");
        String twoSections = "refs:\n  A: 1\nnext:\n  B: 2\n";
        List<String> bounded = CredentialMerge.readRefs(twoSections);
        check("stops at top-level key", bounded.size() == 1 && bounded.get(0).equals("A: 1"),
                "got " + bounded);
        check("blank line inside section is not an end",
                CredentialMerge.readRefs("refs:\n  A: 1\n\n  B: 2\n").size() == 2, "wrong");

        System.out.println("=== 3. 键提取 ===");
        check("key before colon", CredentialMerge.keyOf("A: 1").equals("A"), "wrong");
        check("no colon -> empty", CredentialMerge.keyOf("abc").equals(""), "wrong");
        check("leading colon -> empty", CredentialMerge.keyOf(": v").equals(""), "wrong");

        System.out.println("=== 4. 键存在性必须整行匹配（真实缺陷）===");
        check("exact key found", CredentialMerge.hasKey("FOO: bar\n", "FOO"), "not found");
        // 旧实现用 indexOf("FOO:")，会把下面这行误判为已存在。
        check("FOO must not match FOOBAR", !CredentialMerge.hasKey("FOOBAR: bar\n", "FOO"),
                "FALSE POSITIVE");
        check("prefix key still found",
                CredentialMerge.hasKey("FOOBAR: bar\n", "FOOBAR"), "not found");
        check("commented key is not 'present'",
                !CredentialMerge.hasKey("# FOO: bar\n", "FOO"), "comment counted");
        check("key without value still counts",
                CredentialMerge.hasKey("FOO:\n", "FOO"), "not found");
        check("null target", !CredentialMerge.hasKey(null, "FOO"), "wrong");
        check("empty key", !CredentialMerge.hasKey("FOO: bar\n", ""), "wrong");

        System.out.println("=== 5. 合并：新键写入 ===");
        CredentialMerge.Result r1 = CredentialMerge.merge(refs("NEW_KEY: v1"), "");
        check("one key added", r1.added.size() == 1 && r1.added.get(0).equals("NEW_KEY"),
                "got " + r1.added);
        check("content creates refs section", r1.content.startsWith("refs:"), r1.content);
        check("entry indented under refs", r1.content.contains("\n  NEW_KEY: v1"), r1.content);

        System.out.println("=== 6. 合并：跳过已存在的键 ===");
        CredentialMerge.Result r2 = CredentialMerge.merge(
                refs("OLD: 1"), "refs:\n  OLD: 1\n");
        check("nothing added", r2.isEmpty(), "added " + r2.added);
        check("no write when nothing added", r2.content == null, "content should be null");

        System.out.println("=== 7. 合并：FOO 不应被 FOOBAR 挡住 ===");
        CredentialMerge.Result r3 = CredentialMerge.merge(
                refs("FOO: aaa"), "refs:\n  FOOBAR: bbb\n");
        check("FOO imported despite FOOBAR", r3.added.size() == 1, "got " + r3.added);
        check("FOO present in result", r3.content.contains("FOO: aaa"), r3.content);

        System.out.println("=== 8. 合并：部分新增 ===");
        CredentialMerge.Result r4 = CredentialMerge.merge(
                refs("KEEP: old", "ADD: new"), "refs:\n  KEEP: old\n");
        check("only the new key added",
                r4.added.size() == 1 && r4.added.get(0).equals("ADD"), "got " + r4.added);
        check("existing entry preserved", r4.content.contains("KEEP: old"), r4.content);
        check("new entry present", r4.content.contains("ADD: new"), r4.content);

        System.out.println("=== 9. 合并：目标没有 refs 段时新建 ===");
        CredentialMerge.Result r5 = CredentialMerge.merge(refs("K: v"), "provider: deepseek\n");
        check("appended refs section", r5.content.startsWith("provider: deepseek"), r5.content);
        check("refs appended at end", r5.content.trim().endsWith("K: v"), r5.content);

        System.out.println("=== 10. 合并：边界输入 ===");
        check("no source refs -> empty",
                CredentialMerge.merge("provider: x\n", "refs:\n").isEmpty(), "wrong");
        check("null source -> empty", CredentialMerge.merge(null, "refs:\n").isEmpty(), "wrong");
        check("null target treated as empty",
                CredentialMerge.merge(refs("Z: 1"), null).added.size() == 1, "wrong");
        check("missing trailing newline handled",
                CredentialMerge.merge(refs("Q: 1"), "refs:\n  P: 0").content.contains("Q: 1"),
                "wrong");

        System.out.println();
        System.out.println("CredentialMergeTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
