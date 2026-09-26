package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ConfigBackup 的离线测试。
 *
 * 重点在**被构造过的备份包**：恢复是覆盖操作，若照单全收压缩包里的路径，
 * 一个恶意包就能往应用目录外写文件。这里用真实构造的恶意 zip 验证防护。
 */
public class ConfigBackupTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("/tmp/cbtest");
        rmrf(base);
        File dsh = new File(base, ".dsh");
        dsh.mkdirs();
        File backups = new File(base, "backup");
        backups.mkdirs();
        write(new File(dsh, ".credentials.yaml"), "COMMANDCODE_API_KEY: user_secret123\n");
        write(new File(dsh, "settings.yaml"), "agent-default-model:\n  provider: commandcode\n");
        write(new File(dsh, "unrelated.txt"), "should not be backed up\n");

        System.out.println("=== 1. export ===");
        File zip = new File(backups, "dsh-config-legacy.zip");
        int n = ConfigBackup.exportTo(dsh, zip);
        check("exports 2 files", n == 2, "got " + n);
        check("zip exists and non-empty", zip.isFile() && zip.length() > 0, "missing");
        check("filename recognised as backup",
                ConfigBackup.isBackupName(zip.getName()), zip.getName());
        check("unrelated file NOT included",
                ConfigBackup.validate(zip) == null, String.valueOf(ConfigBackup.validate(zip)));

        System.out.println("=== 2. validate ===");
        check("valid backup passes", ConfigBackup.validate(zip) == null,
                String.valueOf(ConfigBackup.validate(zip)));
        check("nonexistent rejected", ConfigBackup.validate(new File(base, "nope.zip")) != null, "wrong");
        File empty = new File(base, "empty.zip"); empty.createNewFile();
        check("empty file rejected", ConfigBackup.validate(empty) != null, "wrong");
        File notZip = new File(base, "notzip.zip");
        write(notZip, "this is not a zip at all");
        check("non-zip rejected", ConfigBackup.validate(notZip) != null, "wrong");

        System.out.println("=== 3. restore round-trip ===");
        write(new File(dsh, ".credentials.yaml"), "TAMPERED\n");
        write(new File(dsh, "settings.yaml"), "TAMPERED\n");
        int r = ConfigBackup.restoreFrom(zip, dsh);
        check("restores 2 files", r == 2, "got " + r);
        check("credentials restored",
                read(new File(dsh, ".credentials.yaml")).contains("user_secret123"),
                read(new File(dsh, ".credentials.yaml")));
        check("settings restored",
                read(new File(dsh, "settings.yaml")).contains("commandcode"),
                read(new File(dsh, "settings.yaml")));

        System.out.println("=== 4. encrypted backup ===");
        char[] password = "correct-horse".toCharArray();
        File encrypted = new File(backups, ConfigBackup.fileName(1_700_000_000_123L));
        int encryptedCount = ConfigBackup.exportEncrypted(dsh, encrypted, password);
        check("encrypted export contains 2 files", encryptedCount == 2,
                String.valueOf(encryptedCount));
        check("encrypted format detected", ConfigBackup.isEncrypted(encrypted), "not detected");
        check("encrypted backup validates with password",
                ConfigBackup.validateEncrypted(encrypted, password) == null,
                String.valueOf(ConfigBackup.validateEncrypted(encrypted, password)));
        check("wrong password rejected",
                ConfigBackup.validateEncrypted(encrypted, "wrong-pass".toCharArray()) != null,
                "wrong password accepted");
        check("legacy validator refuses encrypted file",
                ConfigBackup.validate(encrypted) != null, "should request password");

        write(new File(dsh, ".credentials.yaml"), "CHANGED\n");
        int encryptedRestored = ConfigBackup.restoreEncrypted(encrypted, dsh, password);
        check("encrypted restore writes both files", encryptedRestored == 2,
                String.valueOf(encryptedRestored));
        check("encrypted restore recovers credentials",
                read(new File(dsh, ".credentials.yaml")).contains("user_secret123"), "not restored");

        File tampered = new File(backups, "dsh-config-tampered.dshbak");
        copy(encrypted, tampered);
        java.io.RandomAccessFile random = new java.io.RandomAccessFile(tampered, "rw");
        random.seek(random.length() - 1);
        int last = random.read();
        random.seek(random.length() - 1);
        random.write(last ^ 0x01);
        random.close();
        check("tampered encrypted backup rejected",
                ConfigBackup.validateEncrypted(tampered, password) != null, "tamper accepted");

        System.out.println("=== 5. zip-slip and size limits ===");
        File slip = new File(base, "slip.zip");
        makeZip(slip, "../escaped.txt", "pwned");
        check("path traversal entry rejected", ConfigBackup.validate(slip) != null,
                String.valueOf(ConfigBackup.validate(slip)));
        File abs = new File(base, "abs.zip");
        makeZip(abs, "/tmp/absolute-escape.txt", "pwned");
        check("absolute path entry rejected", ConfigBackup.validate(abs) != null, "wrong");
        File evil = new File(base, "evil.zip");
        makeZip(evil, "some-other-file.yaml", "pwned");
        check("non-whitelisted entry rejected", ConfigBackup.validate(evil) != null, "wrong");
        File nested = new File(base, "nested.zip");
        makeZip(nested, "sub/settings.yaml", "pwned");
        check("nested path entry rejected", ConfigBackup.validate(nested) != null, "wrong");

        // 关键：即使绕过 validate 直接调用 restore，也不能写出白名单之外
        boolean threw = false;
        try { ConfigBackup.restoreFrom(slip, dsh); } catch (Exception e) { threw = true; }
        check("restore of malicious zip throws", threw, "should throw");
        check("escape file NOT created outside",
                !new File(base, "escaped.txt").exists(), "zip-slip succeeded!");
        check("absolute escape NOT created",
                !new File("/tmp/absolute-escape.txt").exists(), "zip-slip succeeded!");
        check("original config untouched after failed restore",
                read(new File(dsh, "settings.yaml")).contains("commandcode"), "damaged");
        File bomb = new File(base, "bomb.zip");
        makeLargeZip(bomb, "settings.yaml", 2 * 1024 * 1024 + 1);
        check("oversized expanded entry rejected", ConfigBackup.validate(bomb) != null,
                "zip bomb accepted");

        System.out.println("=== 6. listBackups ===");
        write(new File(backups, "dsh-config-20260101-0000.zip"), "x");
        write(new File(backups, "random.zip"), "x");
        List<File> list = ConfigBackup.listBackups(backups);
        boolean allBackupNames = true;
        for (File f : list) if (!ConfigBackup.isBackupName(f.getName())) allBackupNames = false;
        check("only backup-named files listed", allBackupNames, list.toString());
        boolean hasRandom = false;
        for (File f : list) if (f.getName().equals("random.zip")) hasRandom = true;
        check("non-backup excluded", !hasRandom, list.toString());
        check("newest first", list.get(0).lastModified() >= list.get(list.size()-1).lastModified(),
                "wrong order");
        check("null dir safe", ConfigBackup.listBackups(null).isEmpty(), "should be empty");
        check("nonexistent dir safe", ConfigBackup.listBackups(new File(base,"nodir")).isEmpty(), "wrong");

        System.out.println("=== 7. safety copy ===");
        File sc = ConfigBackup.safetyCopy(dsh, backups, 1_700_000_000_000L, password);
        check("safety copy created", sc.isFile() && sc.length() > 0, "missing");
        check("safety copy is encrypted", ConfigBackup.isEncrypted(sc), "plaintext");
        check("safety copy is restorable", ConfigBackup.validateEncrypted(sc, password) == null,
                String.valueOf(ConfigBackup.validateEncrypted(sc, password)));

        System.out.println("=== 8. filename helpers ===");
        check("name has prefix/suffix",
                ConfigBackup.fileName(0).startsWith("dsh-config-")
                && ConfigBackup.fileName(0).endsWith(".dshbak"), ConfigBackup.fileName(0));
        check("millisecond timestamp avoids overwrite",
                !ConfigBackup.fileName(1000).equals(ConfigBackup.fileName(1001)), "same name");
        check("random name not a backup",
                !ConfigBackup.isBackupName("settings.yaml"), "wrong");
        check("null name safe", !ConfigBackup.isBackupName(null), "wrong");

        rmrf(base);
        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    /** 构造一个含指定条目名的 zip —— 用来模拟被构造过的备份包。 */
    static void makeZip(File zip, String entryName, String content) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(content.getBytes("UTF-8"));
        zos.closeEntry();
        zos.close();
    }

    static void makeLargeZip(File zip, String entryName, int size) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip));
        zos.putNextEntry(new ZipEntry(entryName));
        byte[] block = new byte[8192];
        int written = 0;
        while (written < size) {
            int n = Math.min(block.length, size - written);
            zos.write(block, 0, n);
            written += n;
        }
        zos.closeEntry();
        zos.close();
    }

    static void copy(File source, File target) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(source);
        FileOutputStream out = new FileOutputStream(target);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        in.close();
        out.close();
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.close();
    }

    static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int off = 0, r;
        while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        in.close();
        return new String(b, 0, off, "UTF-8");
    }

    static void rmrf(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) rmrf(x); }
        f.delete();
    }
}
