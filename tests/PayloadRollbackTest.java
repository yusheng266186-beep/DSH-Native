package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.HashSet;

/** PayloadRollback 的离线回归测试。 */
public class PayloadRollbackTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    static String sha() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }

    public static void main(String[] args) {
        check("dsh target allowed", PayloadRollback.isSafeTarget("dsh"), "rejected");
        check("tools target allowed", PayloadRollback.isSafeTarget("tools"), "rejected");
        check("config target rejected", !PayloadRollback.isSafeTarget(".dsh"), "accepted");
        check("traversal target rejected", !PayloadRollback.isSafeTarget("../dsh"), "accepted");
        check("snapshot name", "dsh.tar.zst".equals(PayloadRollback.snapshotName("dsh")), "wrong");
        check("unsafe snapshot name absent", PayloadRollback.snapshotName("cache") == null, "present");
        check("revision accepted", PayloadRollback.isSafeRevision("dsh.tar.zst=12"), "rejected");
        check("negative revision rejected", !PayloadRollback.isSafeRevision("dsh.tar.zst=-1"), "accepted");
        check("unsafe revision name rejected", !PayloadRollback.isSafeRevision("../dsh=1"), "accepted");

        ArrayList<PayloadRollback.Target> targets = new ArrayList<PayloadRollback.Target>();
        targets.add(new PayloadRollback.Target("dsh", "dsh.tar.zst", 123L, sha()));
        targets.add(new PayloadRollback.Target("tools", "tools.tar.zst", 456L, sha()));
        HashSet<String> revisions = new HashSet<String>();
        revisions.add("tools-base.tar.zst=3");
        revisions.add("dsh.tar.zst=5");
        PayloadRollback.Journal journal = new PayloadRollback.Journal(123456L, targets, revisions);
        String encoded = PayloadRollback.serialize(journal);
        PayloadRollback.Journal decoded = PayloadRollback.parse(encoded);
        check("journal serialized", encoded != null && encoded.startsWith(PayloadRollback.MAGIC), encoded);
        check("journal parsed", decoded != null, "null");
        check("timestamp retained", decoded != null && decoded.createdAt == 123456L, "wrong");
        check("targets retained", decoded != null && decoded.targets.size() == 2, "wrong");
        check("revisions retained", decoded != null && decoded.revisions.size() == 2, "wrong");
        check("serialization stable", decoded != null
                && encoded.equals(PayloadRollback.serialize(decoded)), "changed");
        check("bad magic rejected", PayloadRollback.parse(encoded.replace(
                PayloadRollback.MAGIC, "BAD")) == null, "accepted");
        check("duplicate target rejected", PayloadRollback.parse(encoded
                + "target=dsh|dsh.tar.zst|1|" + sha() + "\n") == null, "accepted");
        check("unsafe archive rejected", PayloadRollback.parse(encoded.replace(
                "dsh.tar.zst|123", "../dsh.tar.zst|123")) == null, "accepted");
        check("bad sha rejected", PayloadRollback.parse(encoded.replace(sha(), "abc")) == null,
                "accepted");
        check("unknown field rejected", PayloadRollback.parse(encoded + "other=x\n") == null,
                "accepted");

        long mib = 1024L * 1024L;
        check("space includes update snapshot reserve",
                PayloadRollback.requiredFreeBytes(100L * mib, 300L * mib)
                        == 432L * mib, "wrong");
        check("negative sizes clamped",
                PayloadRollback.requiredFreeBytes(-1L, -1L)
                        == PayloadRollback.RESERVE_BYTES, "wrong");
        check("space calculation saturates",
                PayloadRollback.requiredFreeBytes(Long.MAX_VALUE, Long.MAX_VALUE)
                        == Long.MAX_VALUE, "overflow");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
