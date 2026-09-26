package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileOutputStream;

/** TransferState 的离线测试。 */
public class TransferStateTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("/tmp/transfer-state-test");
        rmrf(base);
        base.mkdirs();
        File part = new File(base, "asset.part");
        File meta = new File(base, "asset.part.id");

        check("new transfer starts at zero",
                TransferState.prepare(part, meta, "v1", true) == 0, "wrong offset");
        write(part, "abc");
        check("same identity resumes",
                TransferState.prepare(part, meta, "v1", true) == 3, "did not resume");
        check("different identity resets",
                TransferState.prepare(part, meta, "v2", true) == 0 && !part.exists(),
                "stale bytes retained");
        write(part, "xyz");
        check("resume can be disabled",
                TransferState.prepare(part, meta, "v2", false) == 0 && !part.exists(),
                "should restart");

        File target = new File(base, "final.bin");
        write(target, "old");
        TransferState.prepare(part, meta, "v3", true);
        write(part, "new");
        TransferState.commit(part, meta, target);
        check("commit replaces target", "new".equals(read(target)), read(target));
        check("commit removes temporary state", !part.exists() && !meta.exists(), "state remains");

        File staged = new File(base, "manifest.next");
        write(staged, "fresh");
        TransferState.atomicReplace(staged, target);
        check("atomic replace keeps fresh file", "fresh".equals(read(target)), read(target));
        check("backup is cleaned", !new File(target.getAbsolutePath() + ".previous").exists(),
                "backup remains");

        write(part, "partial");
        write(meta, "id");
        TransferState.discard(part, meta);
        check("discard removes both files", !part.exists() && !meta.exists(), "not removed");

        rmrf(base);
        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static void write(File f, String value) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(value.getBytes("UTF-8"));
        out.close();
    }

    static String read(File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int n = in.read(data);
        in.close();
        return new String(data, 0, Math.max(0, n), "UTF-8");
    }

    static void rmrf(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File kid : kids) rmrf(kid);
        }
        f.delete();
    }
}
