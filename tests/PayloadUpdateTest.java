package dev.dsh.nativeapp;

/**
 * PayloadUpdate 的离线测试。
 *
 * 核心用例只有一个，但很关键：
 * **哨兵全部匹配、修订号却变了 → 必须整体重来**。
 * 这是「只删文件的更新」唯一能被发现的途径，漏掉它那些文件会永远留在设备上。
 */
public class PayloadUpdateTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. the case that used to be missed ===");
        check("revision changed + NO sentinel mismatch -> FULL",
                PayloadUpdate.decide(7, 6, 0) == PayloadUpdate.ACTION_FULL,
                String.valueOf(PayloadUpdate.decide(7, 6, 0)));
        check("  -> and it requires a wipe",
                PayloadUpdate.needsWipe(PayloadUpdate.ACTION_FULL), "removed files would persist");
        check("first install (applied=0) -> FULL",
                PayloadUpdate.decide(1, 0, 0) == PayloadUpdate.ACTION_FULL, "wrong");
        check("big jump -> FULL",
                PayloadUpdate.decide(9, 3, 0) == PayloadUpdate.ACTION_FULL, "wrong");

        System.out.println("=== 2. incremental still works ===");
        check("same revision + missing -> INCREMENTAL",
                PayloadUpdate.decide(7, 7, 2) == PayloadUpdate.ACTION_INCREMENTAL, "wrong");
        check("  -> no wipe needed",
                !PayloadUpdate.needsWipe(PayloadUpdate.ACTION_INCREMENTAL), "should not wipe");
        check("same revision + nothing missing -> UPTODATE",
                PayloadUpdate.decide(7, 7, 0) == PayloadUpdate.ACTION_UPTODATE, "wrong");

        System.out.println("=== 3. revision regression (server rolled back) ===");
        // 远端修订号比本机小：不降级，但若哨兵不匹配仍补齐
        check("remote older + nothing missing -> UPTODATE",
                PayloadUpdate.decide(5, 7, 0) == PayloadUpdate.ACTION_UPTODATE, "wrong");
        check("remote older + missing -> INCREMENTAL",
                PayloadUpdate.decide(5, 7, 1) == PayloadUpdate.ACTION_INCREMENTAL, "wrong");

        System.out.println("=== 4. per-part download decision ===");
        check("FULL downloads even matched parts",
                PayloadUpdate.needsDownload(PayloadUpdate.ACTION_FULL, true), "wrong");
        check("INCREMENTAL skips matched parts",
                !PayloadUpdate.needsDownload(PayloadUpdate.ACTION_INCREMENTAL, true), "wrong");
        check("INCREMENTAL downloads unmatched",
                PayloadUpdate.needsDownload(PayloadUpdate.ACTION_INCREMENTAL, false), "wrong");
        check("UPTODATE downloads nothing",
                !PayloadUpdate.needsDownload(PayloadUpdate.ACTION_UPTODATE, false), "wrong");

        System.out.println("=== 5. descriptions mention the action ===");
        check("full mentions re-download",
                PayloadUpdate.describe(PayloadUpdate.ACTION_FULL, 5, 0).contains("重新下载"),
                PayloadUpdate.describe(PayloadUpdate.ACTION_FULL, 5, 0));
        check("incremental shows counts",
                PayloadUpdate.describe(PayloadUpdate.ACTION_INCREMENTAL, 5, 2).contains("2/5"),
                PayloadUpdate.describe(PayloadUpdate.ACTION_INCREMENTAL, 5, 2));
        check("uptodate is reassuring",
                PayloadUpdate.describe(PayloadUpdate.ACTION_UPTODATE, 5, 0).contains("最新"),
                PayloadUpdate.describe(PayloadUpdate.ACTION_UPTODATE, 5, 0));

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
