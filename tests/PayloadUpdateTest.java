package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PayloadUpdate 的离线测试。
 *
 * 三组重点：
 *   ① 「只删文件」这种哨兵发现不了的变化必须被识别（靠分片修订号）；
 *   ② 删除路径的校验 —— 清单从网络下载，被篡改时不能让它删到运行包之外；
 *   ③ 未被改动的分片不该被牵连（这是不用「整包重来」的理由）。
 */
public class PayloadUpdateTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static PayloadUpdate.Part part(String name, int rev, boolean matched, String... remove) {
        return new PayloadUpdate.Part(name, rev, matched, Arrays.asList(remove));
    }

    public static void main(String[] args) {
        System.out.println("=== 1. sentinel mismatch still triggers work ===");
        check("missing file -> work",
                PayloadUpdate.needsWork(part("a", 1, false), 1), "wrong");
        check("intact + same revision -> no work",
                !PayloadUpdate.needsWork(part("a", 1, true), 1), "wrong");

        System.out.println("=== 2. the case sentinels cannot see ===");
        // 关键：哨兵全对，但修订号变高 —— 说明只有删除。必须处理。
        check("intact + higher revision -> work",
                PayloadUpdate.needsWork(part("dsh", 2, true), 1), "removed files would persist");
        check("intact + equal revision -> no work",
                !PayloadUpdate.needsWork(part("dsh", 2, true), 2), "wrong");
        check("intact + LOWER revision -> no work (no downgrade)",
                !PayloadUpdate.needsWork(part("dsh", 1, true), 2), "wrong");

        System.out.println("=== 3. removals only when revision grew ===");
        PayloadUpdate.Part p = part("dsh", 2, true, "node_modules/@img");
        List<String> r = PayloadUpdate.removalsFor(p, 1);
        check("removal returned when revision grew", r.size() == 1 && r.get(0).equals("node_modules/@img"),
                r.toString());
        check("no removal when revision unchanged",
                PayloadUpdate.removalsFor(p, 2).isEmpty(), "wrong");
        // 哨兵不匹配（新增/改动）时不应删除任何东西
        check("no removal when only sentinel mismatched",
                PayloadUpdate.removalsFor(part("dsh", 1, false, "x"), 1).isEmpty(), "wrong");

        System.out.println("=== 4. removal path safety (manifest comes from network) ===");
        String[] safe = { "node_modules/@img", "lib/node_modules/npm/docs", "./share/zsh",
                          "node_modules/@img/sharp-wasm32" };
        for (String s : safe) {
            check("allow: " + s, PayloadUpdate.isSafeRelativePath(s), "too strict");
        }
        String[] unsafe = {
            "/etc/passwd", "..", "../..", "../../.dsh", "node_modules/../../.dsh",
            "a/../../b", "\\windows", "C:\\x", "", "   ", ".dsh", ".dsh/credentials.yaml",
            "cache", "cache/update.apk", null,
        };
        for (String s : unsafe) {
            check("reject: " + String.valueOf(s),
                    !PayloadUpdate.isSafeRelativePath(s), "UNSAFE ACCEPTED");
        }
        // 混合清单：非法项必须被剔除，合法项保留
        PayloadUpdate.Part mixed = part("dsh", 3, true,
                "node_modules/@img", "../.dsh", "lib/x", "/abs");
        List<String> filtered = PayloadUpdate.removalsFor(mixed, 1);
        check("mixed list keeps only safe entries", filtered.size() == 2, filtered.toString());

        System.out.println("=== 5. unaffected parts are not touched ===");
        List<PayloadUpdate.Part> parts = new ArrayList<PayloadUpdate.Part>();
        parts.add(part("dsh", 2, true));            // 只有它变了
        parts.add(part("tools-base", 1, true));
        parts.add(part("tools-libs", 1, true));
        parts.add(part("tools-python", 1, true));
        parts.add(part("tools-npm", 1, true));
        List<Integer> applied = Arrays.asList(1, 1, 1, 1, 1);
        check("only the bumped part needs work",
                PayloadUpdate.countNeedingWork(parts, applied) == 1,
                String.valueOf(PayloadUpdate.countNeedingWork(parts, applied)));
        check("description shows 1/5",
                PayloadUpdate.describe(5, 1).contains("1/5"),
                PayloadUpdate.describe(5, 1));
        check("nothing to do -> reassuring text",
                PayloadUpdate.describe(5, 0).contains("最新"), PayloadUpdate.describe(5, 0));
        check("everything to do -> download text",
                PayloadUpdate.describe(5, 5).contains("下载"), PayloadUpdate.describe(5, 5));

        System.out.println("=== 6. revision storage round-trip ===");
        String key = PayloadUpdate.revisionKey("dsh.tar.zst", 7);
        check("key carries revision", PayloadUpdate.parseRevision(key) == 7, key);
        check("unknown key -> 0", PayloadUpdate.parseRevision("nothing") == 0, "wrong");
        check("null -> 0", PayloadUpdate.parseRevision(null) == 0, "wrong");
        check("malformed -> 0", PayloadUpdate.parseRevision("x=abc") == 0, "wrong");
        check("scoped name with slash survives",
                PayloadUpdate.parseRevision(PayloadUpdate.revisionKey("a/b", 3)) == 3, "wrong");

        System.out.println("=== 7. manifest input validation ===");
        check("normal archive accepted", PayloadUpdate.isSafeAssetName("dsh.tar.zst"), "rejected");
        check("hyphen archive accepted", PayloadUpdate.isSafeAssetName("tools-base.tar.zst"), "rejected");
        String[] badNames = {"../dsh.tar.zst", "/dsh.tar.zst", "a/b.tar.zst",
                ".hidden.tar.zst", "dsh.zip", "dsh tar.zst", "", null};
        for (String name : badNames) {
            check("unsafe archive rejected: " + String.valueOf(name),
                    !PayloadUpdate.isSafeAssetName(name), "accepted");
        }
        check("valid digest accepted",
                PayloadUpdate.isSha256(repeat("a", 64)), "rejected");
        check("short digest rejected", !PayloadUpdate.isSha256("abc"), "accepted");
        check("non-hex digest rejected",
                !PayloadUpdate.isSha256(repeat("g", 64)), "accepted");

        System.out.println("=== 8. disk-space preflight ===");
        long mib = 1024L * 1024L;
        check("uncached archive includes download, extraction and reserve",
                PayloadUpdate.requiredFreeBytes(100L * mib, 0L) == 464L * mib,
                String.valueOf(PayloadUpdate.requiredFreeBytes(100L * mib, 0L)));
        check("cached archive no longer needs download space",
                PayloadUpdate.requiredFreeBytes(100L * mib, 100L * mib) == 364L * mib,
                String.valueOf(PayloadUpdate.requiredFreeBytes(100L * mib, 100L * mib)));
        check("cached bytes are clamped to archive total",
                PayloadUpdate.requiredFreeBytes(10L * mib, 20L * mib) == 94L * mib,
                String.valueOf(PayloadUpdate.requiredFreeBytes(10L * mib, 20L * mib)));
        check("negative input cannot reduce reserve",
                PayloadUpdate.requiredFreeBytes(-1L, -1L) == 64L * mib,
                String.valueOf(PayloadUpdate.requiredFreeBytes(-1L, -1L)));
        check("overflow saturates instead of wrapping",
                PayloadUpdate.requiredFreeBytes(Long.MAX_VALUE, 0L) == Long.MAX_VALUE,
                String.valueOf(PayloadUpdate.requiredFreeBytes(Long.MAX_VALUE, 0L)));

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
