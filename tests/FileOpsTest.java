package dev.dsh.nativeapp;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FileOps 的离线测试。
 *
 * 重点在**安全边界**而不是功能本身：删除不可逆，
 * 少一道校验就可能删掉系统目录。这里逐条钉死：
 * 白名单外一律拒绝、符号链接不能逃逸、根目录不能删。
 */
public class FileOpsTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("/tmp/fileopstest");
        rmrf(base);
        File root = new File(base, "root");
        root.mkdirs();
        File outside = new File(base, "outside");
        outside.mkdirs();
        File sibling = new File(base, "roo");     // 前缀相似但不同目录
        sibling.mkdirs();

        List<File> allowed = new ArrayList<File>();
        allowed.add(root);

        System.out.println("=== 1. name validation ===");
        check("empty rejected", FileOps.validateName("") != null, "should reject");
        check("whitespace-only rejected", FileOps.validateName("   ") != null, "should reject");
        check("null rejected", FileOps.validateName(null) != null, "should reject");
        check("dot rejected", FileOps.validateName(".") != null, "should reject");
        check("dotdot rejected", FileOps.validateName("..") != null, "should reject");
        check("slash rejected", FileOps.validateName("a/b") != null, "should reject");
        check("backslash rejected", FileOps.validateName("a\\b") != null, "should reject");
        check("NUL rejected", FileOps.validateName("a\u0000b") != null, "should reject");
        check("control char rejected", FileOps.validateName("a\u0007b") != null, "should reject");
        check("newline rejected", FileOps.validateName("a\nb") != null, "should reject");
        check("normal accepted", FileOps.validateName("日志.txt") == null,
                String.valueOf(FileOps.validateName("日志.txt")));
        check("spaces accepted", FileOps.validateName("my file.txt") == null,
                String.valueOf(FileOps.validateName("my file.txt")));
        check("very long rejected",
                FileOps.validateName(repeat("a", 300)) != null, "should reject");
        check("255 bytes accepted",
                FileOps.validateName(repeat("a", 255)) == null,
                String.valueOf(FileOps.validateName(repeat("a", 255))));
        check("85 CJK chars (255 bytes) accepted",
                FileOps.validateName(repeat("中", 85)) == null, "should accept");

        System.out.println("=== 2. writable boundary ===");
        check("root itself writable", FileOps.isWritable(root, allowed), "should allow");
        check("inside root writable", FileOps.isWritable(new File(root, "a/b"), allowed), "should allow");
        check("outside rejected", !FileOps.isWritable(outside, allowed), "should reject");
        check("system dir rejected", !FileOps.isWritable(new File("/system"), allowed), "should reject");
        check("filesystem root rejected", !FileOps.isWritable(new File("/"), allowed), "should reject");
        // 前缀陷阱：/tmp/fileopstest/roo 与 /tmp/fileopstest/root 只差一个字符，
        // 若用 startsWith 直接比字符串就会把它误判为子路径
        check("sibling prefix NOT writable", !FileOps.isWritable(sibling, allowed),
                "prefix trap: " + sibling.getAbsolutePath());
        check("null target rejected", !FileOps.isWritable(null, allowed), "should reject");
        check("empty whitelist rejects all", !FileOps.isWritable(root, new ArrayList<File>()),
                "should reject");

        System.out.println("=== 3. symlink escape (the critical one) ===");
        File link = new File(root, "escape");
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        check("symlink pointing outside NOT writable",
                !FileOps.isWritable(link, allowed),
                "escape via symlink: " + link.getAbsolutePath());
        check("file under escaping symlink NOT writable",
                !FileOps.isWritable(new File(link, "x"), allowed), "should reject");
        // 指向白名单内部的软链应仍然允许
        File innerLink = new File(root, "inner");
        Files.createSymbolicLink(innerLink.toPath(), new File(root, "sub").toPath());
        new File(root, "sub").mkdirs();
        check("symlink staying inside IS writable",
                FileOps.isWritable(innerLink, allowed), "should allow");

        System.out.println("=== 4. mkdir ===");
        check("mkdir ok", FileOps.mkdir(root, "newdir", allowed) == null, "should succeed");
        check("created on disk", new File(root, "newdir").isDirectory(), "missing");
        check("duplicate rejected", FileOps.mkdir(root, "newdir", allowed) != null, "should reject");
        check("bad name rejected", FileOps.mkdir(root, "a/b", allowed) != null, "should reject");
        check("outside rejected", FileOps.mkdir(outside, "x", allowed) != null, "should reject");

        System.out.println("=== 5. rename ===");
        File src = new File(root, "old.txt");
        write(src, "hi");
        check("rename ok", FileOps.rename(src, "new.txt", allowed) == null, "should succeed");
        check("renamed on disk", new File(root, "new.txt").isFile(), "missing");
        check("source gone", !src.exists(), "should be gone");
        check("rename to existing rejected",
                FileOps.rename(new File(root, "new.txt"), "newdir", allowed) != null, "should reject");
        check("rename outside rejected", FileOps.rename(outside, "z.txt", allowed) != null, "should reject");

        System.out.println("=== 6. delete ===");
        File d = new File(root, "tree");
        new File(d, "a/b").mkdirs();
        write(new File(d, "a/b/f.txt"), "x");
        check("countEntries counts nested", FileOps.countEntries(d) == 3,
                String.valueOf(FileOps.countEntries(d)));
        check("delete dir ok", FileOps.delete(d, allowed) == null, "should succeed");
        check("dir removed", !d.exists(), "still exists");
        check("delete nonexistent rejected", FileOps.delete(new File(root, "nope"), allowed) != null,
                "should reject");
        check("delete outside rejected", FileOps.delete(outside, allowed) != null, "should reject");
        check("delete filesystem root rejected",
                FileOps.delete(new File("/"), allowed) != null, "should reject");
        // 根目录自身永远不能删：删掉「应用目录」等于毁掉整个环境
        check("delete allowed-root itself rejected",
                FileOps.delete(root, allowed) != null, "should reject");
        check("root still exists after rejected delete", root.isDirectory(), "gone!");

        File outsideKeep = new File(outside, "must-stay.txt");
        write(outsideKeep, "keep");
        File linkedTree = new File(root, "tree-with-link");
        linkedTree.mkdirs();
        File outwardChild = new File(linkedTree, "outside-link");
        Files.createSymbolicLink(outwardChild.toPath(), outside.toPath());
        check("count treats nested symlink as leaf",
                FileOps.countEntries(linkedTree) == 1,
                String.valueOf(FileOps.countEntries(linkedTree)));
        check("delete tree containing outward symlink succeeds",
                FileOps.delete(linkedTree, allowed) == null, "delete failed");
        check("outward symlink target survives recursive delete",
                outsideKeep.isFile() && "keep".equals(read(outsideKeep)), "outside was damaged");

        System.out.println("=== 7. writableRoots never includes system paths ===");
        List<File> wr = FileOps.writableRoots(new File("/data/user/0/pkg/files/dsh"));
        boolean hasRoot = false, hasSdcard = false;
        for (File f : wr) {
            if ("/".equals(f.getAbsolutePath())) hasRoot = true;
            if ("/sdcard".equals(f.getAbsolutePath())) hasSdcard = true;
        }
        check("does not include filesystem root", !hasRoot, "dangerous");
        check("does not include whole /sdcard", !hasSdcard, "dangerous");
        check("app runtime root stays read-only",
                !wr.contains(new File("/data/user/0/pkg/files/dsh")), wr.toString());
        check("includes app configuration directory",
                wr.contains(new File("/data/user/0/pkg/files/dsh/.dsh")), wr.toString());
        check("includes private fallback workspace",
                wr.contains(new File("/data/user/0/pkg/files/dsh/workspace")), wr.toString());

        rmrf(base);
        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static void write(File f, String s) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.close();
    }

    static String read(File f) throws Exception {
        byte[] data = Files.readAllBytes(f.toPath());
        return new String(data, "UTF-8");
    }

    static void rmrf(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) rmrf(x); }
        f.delete();
    }
}
