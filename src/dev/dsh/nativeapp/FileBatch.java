package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 安全的批量复制与移动逻辑。纯 Java，无 Android 依赖。 */
final class FileBatch {

    static final class Result {
        final int succeeded;
        final int failed;
        final List<String> errors;

        Result(int succeeded, int failed, List<String> errors) {
            this.succeeded = succeeded;
            this.failed = failed;
            this.errors = errors;
        }
    }

    private FileBatch() { }

    static Result transfer(List<File> sources, File destination, boolean move,
                           List<File> allowedRoots) {
        List<String> errors = new ArrayList<String>();
        int ok = 0;
        if (sources == null || sources.isEmpty()) {
            errors.add("没有选择文件");
            return new Result(0, 1, errors);
        }
        if (destination == null || !destination.isDirectory()
                || !FileOps.isWritable(destination, allowedRoots)) {
            errors.add("目标目录不可写");
            return new Result(0, sources.size(), errors);
        }
        for (File source : sources) {
            String err = transferOne(source, destination, move, allowedRoots);
            if (err == null) ok++;
            else errors.add(display(source) + "：" + err);
        }
        return new Result(ok, sources.size() - ok, errors);
    }

    private static String transferOne(File source, File destination, boolean move,
                                      List<File> roots) {
        if (source == null || !source.exists()) return "源文件不存在";
        if (!FileOps.isWritable(source, roots)) return "源位置不允许批量操作";
        try {
            if (isSymbolicLink(source)) return "暂不支持复制符号链接";
            String src = source.getCanonicalPath();
            String dst = destination.getCanonicalPath();
            if (source.isDirectory() && (dst.equals(src) || dst.startsWith(src + "/"))) {
                return "不能复制到自身目录内";
            }
            if (move && source.getParentFile() != null
                    && source.getParentFile().getCanonicalPath().equals(dst)) {
                return "文件已经位于目标目录";
            }
            File target = uniqueTarget(destination, source.getName());
            if (!FileOps.isWritable(target, roots)) return "目标位置不允许写入";

            if (move && source.renameTo(target)) return null;

            File stage = uniqueStage(destination, source.getName());
            String copyError = copyTree(source, stage, roots);
            if (copyError != null) {
                deleteQuietly(stage);
                return copyError;
            }
            if (!stage.renameTo(target)) {
                deleteQuietly(stage);
                return "无法提交复制结果";
            }
            if (move) {
                String deleteError = FileOps.delete(source, roots);
                if (deleteError != null) {
                    return "已复制，但无法移除原文件（" + deleteError + "）";
                }
            }
            return null;
        } catch (Throwable t) {
            return "操作失败：" + brief(t);
        }
    }

    private static String copyTree(File source, File target, List<File> roots) {
        try {
            if (isSymbolicLink(source)) return "目录中包含符号链接";
            if (source.isDirectory()) {
                if (!target.mkdirs() && !target.isDirectory()) return "无法创建目标目录";
                File[] children = source.listFiles();
                if (children == null) return "无法读取源目录";
                for (File child : children) {
                    String err = copyTree(child, new File(target, child.getName()), roots);
                    if (err != null) return err;
                }
                target.setLastModified(source.lastModified());
                return null;
            }
            if (!source.isFile()) return "不是常规文件";
            FileInputStream in = new FileInputStream(source);
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(target);
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n > 0) out.write(buffer, 0, n);
                }
                out.flush();
                out.getFD().sync();
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                if (out != null) try { out.close(); } catch (Throwable ignored) { }
            }
            target.setLastModified(source.lastModified());
            return null;
        } catch (Throwable t) {
            return "复制失败：" + brief(t);
        }
    }

    static File uniqueTarget(File parent, String name) {
        File first = new File(parent, name);
        if (!first.exists()) return first;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            File candidate = new File(parent, base + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(parent, base + "-副本-" + System.currentTimeMillis() + ext);
    }

    private static File uniqueStage(File parent, String name) {
        for (int i = 0; i < 1000; i++) {
            File f = new File(parent, ".dsh-copy-" + System.currentTimeMillis()
                    + "-" + i + "-" + name);
            if (!f.exists()) return f;
        }
        return new File(parent, ".dsh-copy-fallback-" + name);
    }

    private static boolean isSymbolicLink(File file) throws IOException {
        File parent = file.getParentFile();
        File normalized = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
        return !normalized.getCanonicalFile().equals(normalized.getAbsoluteFile());
    }

    private static void deleteQuietly(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File child : kids) deleteQuietly(child);
        }
        try { f.delete(); } catch (Throwable ignored) { }
    }

    private static String display(File f) {
        return f == null ? "未知文件" : f.getName();
    }

    private static String brief(Throwable t) {
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null || message.length() == 0 ? name : name + ": " + message;
    }
}
