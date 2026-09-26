package dev.dsh.nativeapp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ShareTargets 的离线测试。
 *
 * 关键两组：
 *   ① 特殊字符文件的编解码往返 —— 文件名里有空格、中文、# ? % 都很常见，
 *      拼错就会被截断或解析成别的路径；
 *   ② 解码后的白名单校验 —— URI 是外部应用传进来的，对方能自己构造路径。
 */
public class ShareTargetsTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        File base = new File("/tmp/sharetest");
        rmrf(base);
        File root = new File(base, "root");
        root.mkdirs();
        File outside = new File(base, "outside");
        outside.mkdirs();
        List<File> allowed = new ArrayList<File>();
        allowed.add(root);

        System.out.println("=== 1. round-trip on tricky names ===");
        String[] names = {
            "simple.txt", "with space.log", "中文文件名.yaml", "hash#tag.json",
            "question?mark.txt", "percent%20.txt", "plus+sign.txt", "amp&and.txt",
            "quote'single.md", "brackets[1].txt", "equals=sign.txt", "at@sign.txt",
        };
        for (String n : names) {
            File f = new File(root, n);
            write(f, "x");
            String enc = ShareTargets.encode(f);
            File back = ShareTargets.decode(enc);
            boolean ok = back != null && back.getAbsolutePath().equals(f.getAbsolutePath());
            check("round-trip: " + n, ok,
                    enc + " -> " + (back == null ? "null" : back.getAbsolutePath()));
        }

        System.out.println("=== 2. encode never leaves raw separators ambiguous ===");
        File deep = new File(root, "a/b/c.txt");
        deep.getParentFile().mkdirs();
        write(deep, "x");
        String encDeep = ShareTargets.encode(deep);
        check("slashes escaped", encDeep != null && !encDeep.contains("/"), String.valueOf(encDeep));
        check("deep path round-trips",
                deep.getAbsolutePath().equals(ShareTargets.decode(encDeep).getAbsolutePath()),
                String.valueOf(encDeep));

        System.out.println("=== 3. prefix handling ===");
        check("PREFIX constant present", "f/".equals(ShareTargets.PREFIX), ShareTargets.PREFIX);
        File f1 = new File(root, "p.txt"); write(f1, "x");
        check("decode accepts prefixed form",
                ShareTargets.decode("f/" + ShareTargets.encode(f1)) != null, "wrong");
        check("decode accepts bare form",
                ShareTargets.decode(ShareTargets.encode(f1)) != null, "wrong");

        System.out.println("=== 4. malformed input rejected (external input!) ===");
        check("null rejected", ShareTargets.decode(null) == null, "wrong");
        check("empty rejected", ShareTargets.decode("") == null, "wrong");
        check("prefix-only rejected", ShareTargets.decode("f/") == null, "wrong");
        check("relative path rejected", ShareTargets.decode("relative/path.txt") == null, "wrong");
        check("garbage percent rejected", ShareTargets.decode("%ZZ%ZZ") == null, "wrong");
        check("decode does not throw on odd input",
                ShareTargets.decode("f/%E4%B8%AD%ZZ") == null
                || ShareTargets.decode("f/%E4%B8%AD%ZZ") != null, "threw");

        System.out.println("=== 5. whitelist enforced AFTER decode ===");
        File evil = new File(outside, "secret.txt"); write(evil, "secret");
        String evilEnc = ShareTargets.encode(evil);
        File evilBack = ShareTargets.decode(evilEnc);
        check("decode itself succeeds (that is expected)", evilBack != null, "wrong");
        // 关键：解码成功不代表可以分享 —— 必须再过一遍白名单
        check("outside file NOT shareable", !ShareTargets.isShareable(evilBack, allowed),
                "security hole: " + evilBack);
        File okFile = new File(root, "share-me.txt"); write(okFile, "x");
        check("inside file IS shareable", ShareTargets.isShareable(okFile, allowed), "wrong");
        check("system file NOT shareable",
                !ShareTargets.isShareable(new File("/system/build.prop"), allowed), "wrong");
        check("directory NOT shareable", !ShareTargets.isShareable(root, allowed), "wrong");
        check("null NOT shareable", !ShareTargets.isShareable(null, allowed), "wrong");
        check("nonexistent NOT shareable",
                !ShareTargets.isShareable(new File(root, "nope.txt"), allowed), "wrong");

        System.out.println("=== 6. mime mapping ===");
        check("txt", "text/plain".equals(ShareTargets.mimeOf("a.txt")), ShareTargets.mimeOf("a.txt"));
        check("log", "text/plain".equals(ShareTargets.mimeOf("a.LOG")), ShareTargets.mimeOf("a.LOG"));
        check("json", "application/json".equals(ShareTargets.mimeOf("a.json")), "wrong");
        check("yaml", "text/yaml".equals(ShareTargets.mimeOf("a.yaml")), "wrong");
        check("png", "image/png".equals(ShareTargets.mimeOf("a.png")), "wrong");
        check("jpeg", "image/jpeg".equals(ShareTargets.mimeOf("a.jpeg")), "wrong");
        check("apk", "application/vnd.android.package-archive".equals(ShareTargets.mimeOf("a.apk")), "wrong");
        check("zip", "application/zip".equals(ShareTargets.mimeOf("a.zip")), "wrong");
        check("unknown -> octet-stream",
                "application/octet-stream".equals(ShareTargets.mimeOf("a.qqq")), "wrong");
        check("no extension -> octet-stream",
                "application/octet-stream".equals(ShareTargets.mimeOf("Makefile")), "wrong");
        check("null safe", ShareTargets.mimeOf(null) != null, "null");

        System.out.println("=== 7. display name sanitising ===");
        check("normal name kept",
                "report.txt".equals(ShareTargets.displayName(new File("/x/report.txt"))), "wrong");
        check("newline replaced",
                !ShareTargets.displayName(new File("/x/a\nb.txt")).contains("\n"), "raw newline kept");
        check("no empty result", ShareTargets.displayName(new File("/")) != null
                && ShareTargets.displayName(new File("/")).length() > 0, "empty");
        check("null safe", ShareTargets.displayName(null) != null, "null");

        System.out.println("=== 8. incoming share safety ===");
        check("path is reduced to safe basename",
                "passwd".equals(ShareTargets.incomingName("../../etc/passwd", "fallback")),
                ShareTargets.incomingName("../../etc/passwd", "fallback"));
        check("backslash path is reduced",
                "secret.txt".equals(ShareTargets.incomingName("..\\secret.txt", "fallback")),
                ShareTargets.incomingName("..\\secret.txt", "fallback"));
        check("hidden name is made visible",
                ShareTargets.incomingName(".credentials.yaml", "fallback").startsWith("_"),
                ShareTargets.incomingName(".credentials.yaml", "fallback"));
        check("control characters removed",
                !ShareTargets.incomingName("a\nb.txt", "fallback").contains("\n"), "newline remains");
        check("empty incoming name uses fallback",
                "fallback.txt".equals(ShareTargets.incomingName("", "fallback.txt")), "wrong");

        File collision = new File(root, "report.txt"); write(collision, "old");
        File unique = ShareTargets.uniqueDestination(root, "report.txt");
        check("collision creates numbered destination",
                unique != null && unique.getName().equals("report (2).txt"), String.valueOf(unique));
        check("destination remains inside workspace",
                ShareTargets.isContained(root, unique), String.valueOf(unique));
        check("outside destination rejected",
                !ShareTargets.isContained(root, new File(outside, "x")), "escaped");

        File credential = new File(root, ".credentials.yaml"); write(credential, "secret");
        check("credentials cannot be shared",
                !ShareTargets.isShareable(credential, allowed), "secret exposed");
        File key = new File(root, "release.keystore"); write(key, "secret");
        check("keystore cannot be shared",
                !ShareTargets.isShareable(key, allowed), "key exposed");

        rmrf(base);
        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static void write(File f, String s) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.close();
    }

    static void rmrf(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) rmrf(x); }
        f.delete();
    }
}
