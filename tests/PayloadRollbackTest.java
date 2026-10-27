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

    public static void main(String[] args) throws Exception {
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

        java.io.File root = java.nio.file.Files.createTempDirectory("dsh-runtime-journal-").toFile();
        check("no pending change initially", PayloadRollback.interruptedMode(root) == null, "present");
        PayloadRollback.beginChange(root, "fresh");
        check("first install interruption retained", "fresh".equals(PayloadRollback.interruptedMode(root)), "lost");
        PayloadRollback.beginChange(root, "fresh");
        check("retry preserves same journal", "fresh".equals(PayloadRollback.interruptedMode(root)), "changed");
        PayloadRollback.completeChange(root);
        PayloadRollback.beginChange(root, "update");
        check("update interruption retained", "update".equals(PayloadRollback.interruptedMode(root)), "lost");
        PayloadRollback.beginChange(root, "restore");
        check("recovery keeps original durable interruption", "update".equals(PayloadRollback.interruptedMode(root)), "lost");
        PayloadRollback.completeChange(root);
        check("commit clears interruption", PayloadRollback.interruptedMode(root) == null, "retained");
        java.io.File marker = new java.io.File(root, PayloadRollback.INTERRUPTION_FILE);
        java.nio.file.Files.write(marker.toPath(), "truncated".getBytes("UTF-8"));
        boolean rejected = false;
        try { PayloadRollback.interruptedMode(root); } catch (java.io.IOException expected) { rejected = true; }
        check("corrupt journal is rejected and preserved", rejected && marker.exists(), "ignored");
        marker.delete();
        java.io.File outside = java.nio.file.Files.createTempFile("dsh-journal-outside-", ".txt").toFile();
        java.nio.file.Files.createSymbolicLink(marker.toPath(), outside.toPath());
        rejected = false;
        try { PayloadRollback.beginChange(root, "update"); } catch (java.io.IOException expected) { rejected = true; }
        check("journal cannot write through a symlink", rejected && outside.length() == 0, "escaped");
        marker.delete(); outside.delete(); root.delete();

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
