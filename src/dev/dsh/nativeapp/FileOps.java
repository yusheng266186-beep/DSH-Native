package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件写操作：**纯 Java（只用 java.io），无 Android 依赖，可离线测试**。
 *
 * <p>加这个功能的前提是**安全**：浏览器可以导航到 {@code /}，
 * 而删除是不可逆的 —— 少一道校验就可能删掉系统目录。
 * 因此这里做两件事：
 *
 * <ol>
 *   <li><b>写入白名单</b>：只允许在应用自己的几个目录内做增删改，
 *       其它位置一律只读。白名单判定用**规范路径**（{@code getCanonicalPath}）
 *       而非绝对路径 —— 否则一个指向 {@code /system} 的符号链接就能绕过去。</li>
 *   <li><b>名称校验</b>：拒绝空名、含分隔符、{@code .} / {@code ..}、
 *       控制字符与超长名称。这些在 Linux 上分别会导致
 *       「写到别的目录」「删掉父目录」「文件名无法显示」等问题。</li>
 * </ol>
 *
 * <p>所有操作返回**错误说明**（成功时返回 null），而不是抛异常 ——
 * 调用方要能把原因显示给用户，而不是笼统地说「失败」。
 */
final class FileOps {

    /** 名称长度上限（ext4 单段上限 255 字节；中文按 UTF-8 三字节算）。 */
    static final int MAX_NAME_BYTES = 255;

    private FileOps() { }

    // ================================================================ 安全边界

    /**
     * 目标是否位于任一允许的根之下。
     *
     * <p>用规范路径比较：{@code /sdcard/app/link -> /system} 这样的符号链接
     * 若只看字面路径就会被判为「在白名单内」，实际却写到系统目录。
     *
     * <p>根自身也算在内（可以在根目录里新建文件）。
     */
    static boolean isWritable(File target, List<File> allowedRoots) {
        if (target == null || allowedRoots == null || allowedRoots.isEmpty()) return false;
        String t = canonical(target);
        if (t == null) return false;
        for (File root : allowedRoots) {
            String r = canonical(root);
            if (r == null) continue;
            if (t.equals(r)) return true;
            // 必须以「根 + 分隔符」开头，否则 /data/app 会被 /data/ap 误判为子路径
            if (t.startsWith(r.endsWith("/") ? r : r + "/")) return true;
        }
        return false;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    // ================================================================ 名称校验

    /**
     * 校验一个新建/重命名用的名称。
     *
     * @return 错误说明；合法时返回 null
     */
    static String validateName(String name) {
        if (name == null) return "名称不能为空";
        String n = name.trim();
        if (n.length() == 0) return "名称不能为空";
        if (n.equals(".") || n.equals("..")) return "名称不能是 “.” 或 “..”";
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return "名称不能包含 “/”";
        if (n.indexOf('\0') >= 0) return "名称包含非法字符";
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7F) return "名称不能包含控制字符";
        }
        try {
            if (n.getBytes("UTF-8").length > MAX_NAME_BYTES) {
                return "名称过长（上限 " + MAX_NAME_BYTES + " 字节）";
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ================================================================ 操作

    /**
     * 新建目录。
     *
     * @return 错误说明；成功返回 null
     */
    static String mkdir(File parent, String name, List<File> allowedRoots) {
        String bad = validateName(name);
        if (bad != null) return bad;
        File target = new File(parent, name.trim());
        if (!isWritable(target, allowedRoots)) return "该位置不允许写入";
        if (target.exists()) return "已存在同名文件或文件夹";
        try {
            return target.mkdirs() ? null : "创建失败（权限或磁盘空间不足）";
        } catch (Throwable t) {
            return "创建失败：" + brief(t);
        }
    }

    /**
     * 重命名。
     *
     * @return 错误说明；成功返回 null
     */
    static String rename(File source, String newName, List<File> allowedRoots) {
        if (source == null || !source.exists()) return "源文件不存在";
        String bad = validateName(newName);
        if (bad != null) return bad;
        // 源与目标都要在白名单内 —— 否则可以把白名单外的文件「搬进来」，
        // 或把白名单内的文件「搬出去」
        if (!isWritable(source, allowedRoots)) return "该位置不允许修改";
        File target = new File(source.getParentFile(), newName.trim());
        if (!isWritable(target, allowedRoots)) return "该位置不允许写入";
        if (target.getAbsolutePath().equals(source.getAbsolutePath())) return null;
        if (target.exists()) return "已存在同名文件或文件夹";
        try {
            return source.renameTo(target) ? null : "重命名失败";
        } catch (Throwable t) {
            return "重命名失败：" + brief(t);
        }
    }

    /**
     * 删除（目录会递归删除）。
     *
     * @return 错误说明；成功返回 null
     */
    static String delete(File target, List<File> allowedRoots) {
        if (target == null || !target.exists()) return "文件不存在";
        // 根目录自身永远不允许删除：删掉「应用目录」等于把整个环境毁掉
        if (isRoot(target, allowedRoots)) return "不能删除该根目录";
        if (!isWritable(target, allowedRoots)) return "该位置不允许删除";
        try {
            if (target.isDirectory()) {
                int n = deleteRecursive(target);
                if (target.exists()) return "部分内容未能删除";
                return n <= 0 ? "删除失败（权限不足）" : null;
            }
            return target.delete() ? null : "删除失败（权限不足）";
        } catch (Throwable t) {
            return "删除失败：" + brief(t);
        }
    }

    /** 递归删除；返回成功删除的条目数。 */
    private static int deleteRecursive(File dir) {
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) n += deleteRecursive(k);
                else if (k.delete()) n++;
            }
        }
        if (dir.delete()) n++;
        return n;
    }

    /** target 是否就是某个允许的根。 */
    static boolean isRoot(File target, List<File> allowedRoots) {
        if (target == null || allowedRoots == null) return false;
        String t = canonical(target);
        if (t == null) return false;
        for (File r : allowedRoots) {
            String rs = canonical(r);
            if (rs != null && rs.equals(t)) return true;
        }
        return false;
    }

    /** 统计目录下的条目数（用于删除前的确认文案）。 */
    static int countEntries(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            n++;
            if (k.isDirectory()) n += countEntries(k);
        }
        return n;
    }

    private static String brief(Throwable t) {
        String m = t.getClass().getSimpleName();
        String msg = t.getMessage();
        if (msg != null && msg.length() > 0) {
            int nl = msg.indexOf('\n');
            m += ": " + (nl > 0 ? msg.substring(0, nl) : msg);
        }
        return m.length() > 60 ? m.substring(0, 60) + "…" : m;
    }

    /**
     * 组装允许写入的根列表。
     *
     * <p>**刻意不包含 {@code /} 与 {@code /sdcard} 整体** ——
     * 那些位置含系统文件与其它应用的数据，误删后果不可逆。
     * 需要访问时仍可浏览，只是不能改。
     */
    static List<File> writableRoots(File appDir) {
        List<File> out = new ArrayList<File>();
        if (appDir != null) out.add(appDir);
        File shared = new File("/sdcard/DSHNative");
        if (shared.isDirectory()) out.add(shared);
        return out;
    }
}
