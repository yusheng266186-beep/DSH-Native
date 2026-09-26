package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 下载临时文件与原子替换：纯 Java，无 Android 依赖，可离线测试。
 */
final class TransferState {

    private TransferState() { }

    /**
     * 准备续传文件。来源身份不一致时清空旧分片，避免把不同版本拼在一起。
     */
    static long prepare(File partial, File identityFile, String identity, boolean resume)
            throws IOException {
        if (partial == null || identityFile == null || identity == null) {
            throw new IOException("下载状态参数不完整");
        }
        File parent = partial.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }
        String previous = read(identityFile);
        if (!resume || !identity.equals(previous)) {
            if (partial.exists() && !partial.delete()) {
                throw new IOException("无法清理旧下载分片");
            }
            if (identityFile.exists()) identityFile.delete();
        }
        write(identityFile, identity);
        return partial.isFile() ? partial.length() : 0L;
    }

    /** 下载完成后，把临时文件替换为正式文件。 */
    static void commit(File partial, File identityFile, File target) throws IOException {
        atomicReplace(partial, target);
        if (identityFile != null && identityFile.exists()) identityFile.delete();
    }

    /**
     * 同目录内用 rename 完成替换；失败时把原文件恢复回来。
     */
    static void atomicReplace(File staged, File target) throws IOException {
        if (staged == null || !staged.isFile()) throw new IOException("临时文件不存在");
        if (target == null) throw new IOException("目标文件为空");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目标目录");
        }
        File backup = new File(target.getAbsolutePath() + ".previous");
        if (backup.exists() && !backup.delete()) throw new IOException("无法清理旧备份");

        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IOException("无法暂存原文件");
        }
        if (!staged.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("无法提交新文件");
        }
        if (backup.exists() && !backup.delete()) {
            backup.deleteOnExit();
        }
    }

    static void discard(File partial, File identityFile) {
        if (partial != null && partial.exists()) partial.delete();
        if (identityFile != null && identityFile.exists()) identityFile.delete();
    }

    private static String read(File file) {
        if (file == null || !file.isFile() || file.length() > 4096) return null;
        FileInputStream in = null;
        try {
            byte[] data = new byte[(int) file.length()];
            in = new FileInputStream(file);
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void write(File file, String value) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(value.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
    }
}
