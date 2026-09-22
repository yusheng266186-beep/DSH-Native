package dev.dsh.nativeapp;

/**
 * Version 的离线测试。
 *
 * 版本比较错了**不会报错**，只会表现为「提示没有新版本」——
 * 用户永远收不到更新，而且看不出哪里不对。所以边界必须逐条钉死。
 */
public class VersionTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. basic ordering ===");
        check("0.19.6 > 0.19.5", Version.isNewer("0.19.6", "0.19.5"), "wrong");
        check("0.19.5 is not newer than 0.19.6", !Version.isNewer("0.19.5", "0.19.6"), "wrong");
        check("equal is not newer", !Version.isNewer("0.19.6", "0.19.6"), "wrong");
        check("compare equal == 0", Version.compare("1.2.3", "1.2.3") == 0, "wrong");

        System.out.println("=== 2. numeric, not lexicographic ===");
        // 字典序会把 0.9.10 判成小于 0.9.9 —— 这正是分数字段比较的意义
        check("0.9.10 > 0.9.9", Version.isNewer("0.9.10", "0.9.9"), "wrong");
        check("0.13.1 < 0.19.6 (jsDelivr stale case)",
                !Version.isNewer("0.13.1", "0.19.6"), "wrong");
        check("1.10.0 > 1.9.0", Version.isNewer("1.10.0", "1.9.0"), "wrong");

        System.out.println("=== 3. variable length ===");
        check("1.0 == 1.0.0", Version.compare("1.0", "1.0.0") == 0, "wrong");
        check("1.0.1 > 1.0", Version.isNewer("1.0.1", "1.0"), "wrong");
        check("2 > 1.9.9.9", Version.isNewer("2", "1.9.9.9"), "wrong");

        System.out.println("=== 4. tolerate suffixes ===");
        check("0.19.6-beta parsed as 0.19.6",
                Version.compare("0.19.6-beta", "0.19.6") == 0, "wrong");
        check("0.20.0-alpha > 0.19.6", Version.isNewer("0.20.0-alpha", "0.19.6"), "wrong");

        System.out.println("=== 5. null and garbage safety ===");
        check("null < anything", Version.compare(null, "1.0") < 0, "wrong");
        check("anything > null", Version.compare("1.0", null) > 0, "wrong");
        check("both null == 0", Version.compare(null, null) == 0, "wrong");
        check("garbage does not throw", Version.compare("abc", "1.0") <= 0, "wrong");
        check("empty string safe", Version.compare("", "1.0") <= 0, "wrong");
        check("huge number does not throw",
                Version.compare("99999999999999.0.0", "1.0.0") >= 0, "wrong");

        System.out.println("=== 6. max across sources ===");
        check("max picks highest",
                "0.19.6".equals(Version.max("0.13.1", "0.19.6", "0.19.5")), "wrong");
        check("max ignores null",
                "0.19.6".equals(Version.max(null, "0.19.6", null)), "wrong");
        check("max of all null is null", Version.max(null, null) == null, "wrong");
        check("max of empty is null", Version.max() == null, "wrong");
        check("max ignores empty strings",
                "1.0".equals(Version.max("", "1.0")), "wrong");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
