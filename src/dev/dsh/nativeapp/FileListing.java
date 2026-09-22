package dev.dsh.nativeapp;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 目录读取与格式化：**纯 Java，不依赖任何 Android 类**。
 *
 * <p>拆出来的目的很实际：这段逻辑最容易出错（排序、边界、格式化、限宽），
 * 而它又完全不需要设备就能验证。抽成纯函数后可以在普通 JVM 上直接跑测试，
 * 不必「改完发版再看截图」。
 *
 * <p>唯一与平台相关的是「判断符号链接」——Android 要用
 * {@code android.system.Os.lstat}（{@code java.io.File} 会跟随链接、
 * 判断不出条目本身是不是链接）。这里通过 {@link LinkResolver} 注入，
 * 使本类保持纯净、可测试。
 */
final class FileListing {

    /** 单目录最多列出多少项。 */
    static final int LIST_CAP = 800;

    /** 排序方式。 */
    static final int SORT_NAME = 0;
    static final int SORT_SIZE = 1;
    static final int SORT_TIME = 2;

    /** 排序方式的可读名称（含方向，避免用户猜）。 */
    static String sortLabel(int mode) {
        if (mode == SORT_SIZE) return "排序: 大小↓";
        if (mode == SORT_TIME) return "排序: 时间↓";
        return "排序: 名称";
    }

    /** 循环切换到下一种排序方式。 */
    static int nextSortMode(int mode) {
        return mode == SORT_NAME ? SORT_SIZE : (mode == SORT_SIZE ? SORT_TIME : SORT_NAME);
    }

    private FileListing() { }

    // ================================================================ 数据模型

    /** 一个目录项的快照。字段在读取时一次算好，避免渲染阶段反复 syscall。 */
    static final class Entry {
        final File file;
        final String name;
        final boolean dir;
        final long size;
        final long mtime;
        /** 软链目标；非软链为 null。 */
        final String linkTarget;

        Entry(File file, String name, boolean dir, long size, long mtime, String linkTarget) {
            this.file = file;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.linkTarget = linkTarget;
        }
    }

    /** 目录内容的快照。{@code error} 非空表示读取失败。 */
    static final class Listing {
        final String error;
        final List<Entry> entries;
        /** 条目总数（可能大于 entries.size()，超过上限时截断）。 */
        final int total;
        final int dirCount;
        final int fileCount;
        /** 本层文件字节数合计（不含子目录内部）。 */
        final long fileBytes;

        Listing(String error, List<Entry> entries, int total,
                int dirCount, int fileCount, long fileBytes) {
            this.error = error;
            this.entries = entries;
            this.total = total;
            this.dirCount = dirCount;
            this.fileCount = fileCount;
            this.fileBytes = fileBytes;
        }

        static Listing failed(String error) {
            return new Listing(error, Collections.<Entry>emptyList(), 0, 0, 0, 0);
        }
    }

    /** 判断符号链接并取目标；由调用方注入平台实现。 */
    interface LinkResolver {
        String linkTargetOf(File f);
    }

    // ================================================================ 读取

    /**
     * 读取目录并生成快照（纯函数，可离线测试）。
     *
     * <p>每个条目只查一次，排序也在本方法内完成 ——
     * 早先把 {@code isDirectory()} 放在比较器里，会产生 O(n log n) 次 syscall。
     */
    static Listing listDirectory(File dir, boolean showHidden, LinkResolver resolver) {
        return listDirectory(dir, showHidden, SORT_NAME, resolver);
    }

    /**
     * 读取目录并生成快照（纯函数，可离线测试）。
     *
     * <p>排序规则统一为：**目录永远置顶**，组内按所选方式，
     * 且**始终以自然序文件名作为末级 tiebreaker** ——
     * 否则同名大小/同时间的条目顺序不确定，每次进来都在跳。
     */
    static Listing listDirectory(File dir, boolean showHidden, final int sortMode,
                                 LinkResolver resolver) {
        if (dir == null) return Listing.failed("目录为空");
        if (!dir.isDirectory()) return Listing.failed("不是目录");

        File[] raw = dir.listFiles();
        if (raw == null) {
            // Android 权限不足时 listFiles() 只返回 null，不抛异常，
            // 必须与「空目录」分开说明，否则用户只看到一片空白。
            return Listing.failed(
                    "系统拒绝了访问（EACCES）。\n这是 Android 的沙箱限制，不是应用出错。");
        }

        List<Entry> all = new ArrayList<Entry>(raw.length);
        for (File f : raw) {
            String name = f.getName();
            if (!showHidden && name.startsWith(".")) continue;
            String link = resolver == null ? null : resolver.linkTargetOf(f);
            boolean isDir = f.isDirectory();
            long size = isDir ? 0 : f.length();
            long mtime = f.lastModified();
            all.add(new Entry(f, name, isDir, size, mtime, link));
        }

        Collections.sort(all, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;      // 目录优先
                if (sortMode == SORT_SIZE && a.size != b.size) {
                    return a.size > b.size ? -1 : 1;            // 大文件在前
                }
                if (sortMode == SORT_TIME && a.mtime != b.mtime) {
                    return a.mtime > b.mtime ? -1 : 1;          // 新文件在前
                }
                return naturalCompare(a.name, b.name);          // 末级 tiebreaker
            }
        });

        int dirCount = 0, fileCount = 0;
        long bytes = 0;
        for (Entry e : all) {
            if (e.dir) dirCount++; else { fileCount++; bytes += e.size; }
        }

        int total = all.size();
        List<Entry> shown = total > LIST_CAP
                ? new ArrayList<Entry>(all.subList(0, LIST_CAP)) : all;
        return new Listing(null, shown, total, dirCount, fileCount, bytes);
    }

    // ================================================================ 排序与格式化

    /**
     * 文件名自然序：{@code file2} 排在 {@code file10} 之前。
     *
     * <p>字典序会得到相反的直觉结果。逐段比较，连续数字按**数值**比
     * （先比有效位数，避免超长数字溢出 int）。
     */
    static int naturalCompare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = trimZeros(a.substring(si, i));
                String nb = trimZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String trimZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') k++;
        return digits.substring(k);
    }

    static String humanSize(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", n / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", n / 1073741824.0);
    }

    static String shortTime(long ms) {
        if (ms <= 0) return "";
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 条目右侧的说明文字。
     *
     * <p>软链若指向不存在的路径（运行包解压后残留的断链并不少见）会标记「断链」——
     * 否则它会被当成 0 字节的普通文件，看起来像是文件损坏。
     */
    static String infoText(Entry e) {
        if (e.linkTarget != null) {
            boolean broken = !e.file.exists();
            return "→ " + e.linkTarget + (broken ? "（断链）" : "");
        }
        if (e.dir) return "目录";
        return humanSize(e.size) + "  " + shortTime(e.mtime);
    }

    /** 条目左侧的图标。 */
    static String iconOf(Entry e) {
        if (e.linkTarget != null) return "🔗 ";
        if (e.dir) return "📁 ";
        return iconForName(e.name);
    }

    static String iconForName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".log") || n.endsWith(".txt") || n.endsWith(".md")) return "📄 ";
        if (n.endsWith(".json") || n.endsWith(".yml") || n.endsWith(".yaml")) return "⚙️ ";
        if (n.endsWith(".js") || n.endsWith(".mjs") || n.endsWith(".ts")) return "📜 ";
        if (n.endsWith(".py")) return "🐍 ";
        if (n.endsWith(".sh")) return "🖥 ";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif")) return "🖼 ";
        if (n.endsWith(".so") || n.endsWith(".node")) return "🔧 ";
        if (n.endsWith(".apk") || n.endsWith(".zst") || n.endsWith(".tar")) return "📦 ";
        return "📄 ";
    }

    /** 列表下方的统计文字（也在这里，便于测试措辞与截断提示）。 */
    static String summaryOf(Listing l) {
        StringBuilder sb = new StringBuilder();
        sb.append(l.dirCount).append(" 个目录 · ")
          .append(l.fileCount).append(" 个文件 · ")
          .append(humanSize(l.fileBytes));
        if (l.total > l.entries.size()) {
            sb.append("　（共 ").append(l.total).append(" 项，仅显示前 ")
              .append(l.entries.size()).append(" 项）");
        }
        return sb.toString();
    }
}
