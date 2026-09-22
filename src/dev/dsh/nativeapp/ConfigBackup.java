package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 配置备份与恢复：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>要解决的问题：账户密钥与模型配置都在应用私有目录里，
 * **其它文件管理器进不去**。一旦卸载重装或换机，这些内容就全没了。
 *
 * <h3>两条必须守住的安全线</h3>
 * <ol>
 *   <li><b>只允许白名单内的文件进出。</b> 恢复时若照单全收压缩包里的任意路径，
 *       一个构造过的包就能往应用目录里写任何文件。这里只认固定的文件名，
 *       并拒绝含 {@code /}、{@code ..} 的条目（即经典的 zip-slip）。</li>
 *   <li><b>恢复前先备份当前配置。</b> 恢复是覆盖操作，若包本身有问题，
 *       没有回退路径就等于把还能用的配置也毁了。</li>
 * </ol>
 *
 * <p>另外：备份包含**明文密钥**。导出的位置由调用方决定，
 * 界面上必须明确提示这一点。
 */
final class ConfigBackup {

    /** 备份/恢复涉及的文件（相对 DSH_HOME）。 */
    static final String[] FILES = {
        ".credentials.yaml",
        "settings.yaml",
    };

    /** 备份文件名的前缀与后缀。 */
    private static final String PREFIX = "dsh-config-";
    private static final String SUFFIX = ".zip";

    private ConfigBackup() { }

    /** 备份文件的时间戳格式。 */
    static String stamp(long ms) {
        try {
            return new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(ms));
        } catch (Throwable t) {
            return String.valueOf(ms);
        }
    }

    /** 生成一个备份文件名。 */
    static String fileName(long ms) {
        return PREFIX + stamp(ms) + SUFFIX;
    }

    /** 这个文件名是否是本应用生成的备份。 */
    static boolean isBackupName(String name) {
        return name != null && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    /**
     * 打包配置。
     *
     * @return 写入的条目数（0 表示一个文件都不存在，属于异常，调用方应提示）
     */
    static int exportTo(File dshHome, File zipFile) throws IOException {
        if (dshHome == null || !dshHome.isDirectory()) {
            throw new IOException("配置目录不存在");
        }
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建备份目录");
        }
        int count = 0;
        FileOutputStream fos = new FileOutputStream(zipFile);
        ZipOutputStream zos = new ZipOutputStream(fos);
        try {
            for (String name : FILES) {
                File f = new File(dshHome, name);
                if (!f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(name));
                FileInputStream in = new FileInputStream(f);
                try {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) zos.write(buf, 0, r);
                } finally {
                    in.close();
                }
                zos.closeEntry();
                count++;
            }
        } finally {
            zos.close();
            fos.close();
        }
        return count;
    }

    /**
     * 校验一个文件是否是本应用的有效备份。
     *
     * @return 错误说明；有效时返回 null
     */
    static String validate(File zipFile) {
        if (zipFile == null || !zipFile.isFile()) return "备份文件不存在";
        if (zipFile.length() == 0) return "备份文件为空";
        List<String> names = new ArrayList<String>();
        ZipInputStream zin = null;
        try {
            zin = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n == null || n.length() == 0) continue;
                // zip-slip：条目名带路径或上跳的一律拒绝，不等到解包时才判
                if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0 || n.contains("..")) {
                    return "备份内容不合法（含路径分隔符）";
                }
                if (!Arrays.asList(FILES).contains(n)) {
                    return "备份内容不在允许范围内：" + n;
                }
                names.add(n);
            }
        } catch (Throwable t) {
            return "无法读取备份：" + t.getClass().getSimpleName();
        } finally {
            if (zin != null) try { zin.close(); } catch (Throwable ignored) { }
        }
        if (names.isEmpty()) return "备份里没有任何配置文件";
        return null;
    }

    /**
     * 恢复配置。
     *
     * <p>只会写入 {@link #FILES} 中列出的文件名 —— 不解析压缩包里的路径。
     *
     * @return 恢复的文件数；包无效时抛异常（调用方先做过 {@link #validate}）
     */
    static int restoreFrom(File zipFile, File dshHome) throws IOException {
        String bad = validate(zipFile);
        if (bad != null) throw new IOException(bad);
        if (!dshHome.isDirectory() && !dshHome.mkdirs()) {
            throw new IOException("无法创建配置目录");
        }
        int count = 0;
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (n == null || !Arrays.asList(FILES).contains(n)) continue;
                // 目标名取自白名单常量，而不是条目名 —— 即使条目名被构造过也写不出去
                File out = new File(dshHome, n);
                FileOutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = zin.read(buf)) > 0) fos.write(buf, 0, r);
                } finally {
                    fos.close();
                }
                count++;
            }
        } finally {
            zin.close();
        }
        return count;
    }

    /** 列出现有备份，按修改时间倒序（最新的在前）。 */
    static List<File> listBackups(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            if (f.isFile() && isBackupName(f.getName())) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    /** 恢复前把当前配置另存一份，出问题还能退回。 */
    static File safetyCopy(File dshHome, File dir, long now) throws IOException {
        File f = new File(dir, "before-restore-" + stamp(now) + SUFFIX);
        exportTo(dshHome, f);
        return f;
    }

    /** 备份文件的可读大小与时间，用于列表显示。 */
    static String describe(File f) {
        if (f == null) return "";
        String when;
        try {
            when = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                    .format(new Date(f.lastModified()));
        } catch (Throwable t) {
            when = "";
        }
        return FileListing.humanSize(f.length()) + "  " + when;
    }
}
