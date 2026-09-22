package dev.dsh.nativeapp;

/*
 * FileListing 的离线测试。
 *
 * FileListing 是纯 Java（无 Android 依赖），因此可以在普通 JVM 上直接运行。
 * 构建脚本会执行它 —— 排序、边界、格式化一旦改坏，构建即失败，
 * 不必等到装到手机上才发现。
 */
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FileListingTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    /** 测试用软链解析器：普通 JVM 用 Files.isSymbolicLink。 */
    static final FileListing.LinkResolver LINKS = new FileListing.LinkResolver() {
        public String linkTargetOf(File f) {
            try {
                if (!Files.isSymbolicLink(f.toPath())) return null;
                return Files.readSymbolicLink(f.toPath()).toString();
            } catch (Throwable t) { return null; }
        }
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. natural sort ===");
        List<String> names = new ArrayList<String>();
        for (String n : new String[]{"file10","file2","file1","File1","a.txt","b.txt","9","10","x2y10","x2y9","无标题"})
            names.add(n);
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            public int compare(String a, String b) { return FileListing.naturalCompare(a, b); }
        });
        System.out.println("     结果: " + names);
        check("file2 在 file10 之前", names.indexOf("file2") < names.indexOf("file10"), names.toString());
        check("9 在 10 之前", names.indexOf("9") < names.indexOf("10"), names.toString());
        check("x2y9 在 x2y10 之前", names.indexOf("x2y9") < names.indexOf("x2y10"), names.toString());

        System.out.println("=== 2. size format ===");
        check("512 → 512 B", "512 B".equals(FileListing.humanSize(512)), FileListing.humanSize(512));
        check("2048 → 2.0 KB", "2.0 KB".equals(FileListing.humanSize(2048)), FileListing.humanSize(2048));
        check("5MB → 5.0 MB", "5.0 MB".equals(FileListing.humanSize(5L*1024*1024)), FileListing.humanSize(5L*1024*1024));
        check("负数不崩", FileListing.humanSize(-1) != null, "null");
        check("0 字节", "0 B".equals(FileListing.humanSize(0)), FileListing.humanSize(0));

        System.out.println("=== 3. time format ===");
        check("0 返回空串（不可读时不应显示 1970）", "".equals(FileListing.shortTime(0)), FileListing.shortTime(0));

        System.out.println("=== 4. real dir listing ===");
        File tmp = new File("/tmp/fltest/fixture");
        rmrf(tmp); tmp.mkdirs();
        new File(tmp, "file1.txt").createNewFile();
        new File(tmp, "file2.txt").createNewFile();
        new File(tmp, "file10.txt").createNewFile();
        new File(tmp, ".hidden").createNewFile();
        new File(tmp, "subdir").mkdirs();
        Files.createSymbolicLink(new File(tmp, "link-to-sub").toPath(),
                new File(tmp, "subdir").toPath());
        Files.createSymbolicLink(new File(tmp, "long-link-name").toPath(),
                new File("/very/long/target/path/that/keeps/going/and/going/and/going/forever").toPath());

        FileListing.Listing l = FileListing.listDirectory(tmp, false, LINKS);
        check("读取无错误", l.error == null, String.valueOf(l.error));
        check("目录优先（第一项是目录）", l.entries.get(0).dir, l.entries.get(0).name);
        System.out.println("     条目顺序: " + namesOf(l));
        check("自然序 file2 在 file10 前",
                idx(l,"file2.txt") < idx(l,"file10.txt"), namesOf(l));
        check("隐藏文件被过滤", idx(l,".hidden") < 0, namesOf(l));
        // 夹具实为：subdir + 指向目录的软链 = 2 个目录；
        // 3 个普通文件 + 1 个悬空软链（isDirectory 跟随软链为 false）= 4 个文件
        check("stats: 2 dirs / 4 files", l.dirCount == 2 && l.fileCount == 4,
                l.dirCount + " dirs / " + l.fileCount + " files");

        FileListing.Listing lh = FileListing.listDirectory(tmp, true, LINKS);
        check("显示隐藏文件后含 .hidden", idx(lh,".hidden") >= 0, namesOf(lh));

        System.out.println("=== 5. symlink ===");
        FileListing.Entry link = find(lh, "link-to-sub");
        check("软链被识别（linkTarget 非空）", link != null && link.linkTarget != null,
                link == null ? "条目未找到" : String.valueOf(link.linkTarget));
        check("软链图标为 🔗", link != null && FileListing.iconOf(link).startsWith("🔗"),
                link == null ? "-" : FileListing.iconOf(link));
        check("软链信息列以 → 开头", link != null && FileListing.infoText(link).startsWith("→ "),
                link == null ? "-" : FileListing.infoText(link));
        FileListing.Entry plain = find(lh, "file1.txt");
        check("普通文件 linkTarget 为 null", plain != null && plain.linkTarget == null, "-");

        System.out.println("=== 6. long symlink target (overflow risk) ===");
        FileListing.Entry longLink = find(lh, "long-link-name");
        String info = longLink == null ? "" : FileListing.infoText(longLink);
        System.out.println("     信息列长度: " + info.length() + " 字符");
        check("超长目标不崩溃且内容完整（截断交给 UI 层限宽省略）",
                info.length() > 40 && info.startsWith("→ "), info);
        check("悬空软链被标记为断链", info.contains("断链"), info);

        System.out.println("=== 7. error branches ===");
        check("不存在的目录 → 报错", FileListing.listDirectory(new File("/no/such/dir"), false, LINKS).error != null, "-");
        check("文件而非目录 → 报错", FileListing.listDirectory(new File(tmp,"file1.txt"), false, LINKS).error != null, "-");
        check("null → 报错", FileListing.listDirectory(null, false, LINKS).error != null, "-");
        File empty = new File("/tmp/fltest/empty"); rmrf(empty); empty.mkdirs();
        FileListing.Listing le = FileListing.listDirectory(empty, false, LINKS);
        check("空目录 → 无错误且 0 项", le.error == null && le.total == 0, String.valueOf(le.error));

        System.out.println("=== 8. cap truncation ===");
        File many = new File("/tmp/fltest/many"); rmrf(many); many.mkdirs();
        for (int i = 0; i < FileListing.LIST_CAP + 50; i++) new File(many, "f" + i).createNewFile();
        FileListing.Listing lm = FileListing.listDirectory(many, false, LINKS);
        check("total = " + (FileListing.LIST_CAP+50), lm.total == FileListing.LIST_CAP + 50, String.valueOf(lm.total));
        check("entries 截断到上限 " + FileListing.LIST_CAP, lm.entries.size() == FileListing.LIST_CAP, String.valueOf(lm.entries.size()));
        String sum = FileListing.summaryOf(lm);
        System.out.println("     统计文字: " + sum);
        check("统计文字含截断提示", sum.contains("仅显示前"), sum);

        System.out.println("=== 10. sort modes ===");
        File sortDir = new File("/tmp/fltest/sortdir"); rmrf(sortDir); sortDir.mkdirs();
        new File(sortDir, "small.txt").createNewFile();                       // 0 B
        write(new File(sortDir, "big.txt"), 3000);                            // 3 KB
        write(new File(sortDir, "mid.txt"), 500);                             // 500 B
        new File(sortDir, "subdir").mkdirs();
        new File(sortDir, "big.txt").setLastModified(1000000000000L);
        new File(sortDir, "mid.txt").setLastModified(1500000000000L);
        new File(sortDir, "small.txt").setLastModified(1700000000000L);

        FileListing.Listing byName = FileListing.listDirectory(sortDir, false,
                FileListing.SORT_NAME, LINKS);
        check("SORT_NAME: dirs first", byName.entries.get(0).dir, namesOf(byName));
        check("SORT_NAME: subdir before big.txt (dir first)",
                idx(byName,"subdir") < idx(byName,"big.txt"), namesOf(byName));
        check("SORT_NAME: big/mid/small alphabetical",
                idx(byName,"big.txt") < idx(byName,"mid.txt")
                && idx(byName,"mid.txt") < idx(byName,"small.txt"), namesOf(byName));

        FileListing.Listing bySize = FileListing.listDirectory(sortDir, false,
                FileListing.SORT_SIZE, LINKS);
        System.out.println("     by size: " + namesOf(bySize));
        check("SORT_SIZE: dirs still first", bySize.entries.get(0).dir, namesOf(bySize));
        check("SORT_SIZE: big > mid > small",
                idx(bySize,"big.txt") < idx(bySize,"mid.txt")
                && idx(bySize,"mid.txt") < idx(bySize,"small.txt"), namesOf(bySize));

        FileListing.Listing byTime = FileListing.listDirectory(sortDir, false,
                FileListing.SORT_TIME, LINKS);
        System.out.println("     by time: " + namesOf(byTime));
        check("SORT_TIME: dirs still first", byTime.entries.get(0).dir, namesOf(byTime));
        check("SORT_TIME: newest first (small > mid > big)",
                idx(byTime,"small.txt") < idx(byTime,"mid.txt")
                && idx(byTime,"mid.txt") < idx(byTime,"big.txt"), namesOf(byTime));

        check("SORT_SIZE is deterministic across calls",
                namesOf(FileListing.listDirectory(sortDir,false,FileListing.SORT_SIZE,LINKS))
                    .equals(namesOf(bySize)), "unstable");

        System.out.println("=== 11. sort label cycling ===");
        check("label name", FileListing.sortLabel(FileListing.SORT_NAME).contains("名称"),
                FileListing.sortLabel(FileListing.SORT_NAME));
        check("label size shows direction",
                FileListing.sortLabel(FileListing.SORT_SIZE).contains("大小")
                && FileListing.sortLabel(FileListing.SORT_SIZE).contains("↓"),
                FileListing.sortLabel(FileListing.SORT_SIZE));
        check("cycle name->size->time->name",
                FileListing.nextSortMode(FileListing.SORT_NAME) == FileListing.SORT_SIZE
                && FileListing.nextSortMode(FileListing.SORT_SIZE) == FileListing.SORT_TIME
                && FileListing.nextSortMode(FileListing.SORT_TIME) == FileListing.SORT_NAME, "broken");

        System.out.println("=== 9. summary wording ===");
        String s2 = FileListing.summaryOf(l);
        System.out.println("     " + s2);
        check("不含截断提示时不应出现「仅显示」", !s2.contains("仅显示"), s2);

        System.out.println();
        // 统一用 ASCII 汇总行：构建脚本据此判断成败，不受终端编码影响
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String namesOf(FileListing.Listing l) {
        StringBuilder sb = new StringBuilder();
        for (FileListing.Entry e : l.entries) { if (sb.length()>0) sb.append(", "); sb.append(e.name); }
        return sb.toString();
    }
    static int idx(FileListing.Listing l, String name) {
        for (int i = 0; i < l.entries.size(); i++) if (l.entries.get(i).name.equals(name)) return i;
        return -1;
    }
    static FileListing.Entry find(FileListing.Listing l, String name) {
        int i = idx(l, name); return i < 0 ? null : l.entries.get(i);
    }
    static void write(File f, int bytes) throws Exception {
        byte[] b = new byte[bytes];
        java.util.Arrays.fill(b, (byte) 'x');
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        os.write(b); os.close();
    }
    static void rmrf(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) rmrf(x); }
        f.delete();
    }
}
