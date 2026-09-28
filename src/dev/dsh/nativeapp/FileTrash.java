package dev.dsh.nativeapp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 同卷回收站。移动和恢复都不覆盖现有文件，纯 Java、可离线测试。 */
final class FileTrash {

    static final String DIR = ".dsh-native-trash";
    private static final int MAGIC = 0x44534854;
    private static final int VERSION = 1;
    private static final int LIST_CAP = 300;

    static final class Entry {
        final String id;
        final File payload;
        final File metadata;
        final File root;
        final String originalPath;
        final long deletedAt;
        final boolean directory;

        Entry(String id, File payload, File metadata, File root,
              String originalPath, long deletedAt, boolean directory) {
            this.id = id;
            this.payload = payload;
            this.metadata = metadata;
            this.root = root;
            this.originalPath = originalPath;
            this.deletedAt = deletedAt;
            this.directory = directory;
        }

        String name() {
            String n = new File(originalPath).getName();
            return n.length() == 0 ? originalPath : n;
        }
    }

    private FileTrash() { }

    static String move(File target, List<File> roots) {
        if (target == null || !target.exists()) return "文件不存在";
        if (FileOps.isRoot(target, roots)) return "不能移动根目录到回收站";
        File root = FileOps.containingRootFile(target, roots);
        if (root == null) return "该位置不支持回收站";
        try {
            if (isSymbolicLink(target)) return "暂不支持把符号链接移入回收站";
            File trash = new File(root, DIR);
            if (within(target.getCanonicalPath(), trash.getCanonicalPath())) {
                return "回收站内容请使用永久删除";
            }
            File files = new File(trash, "files");
            File meta = new File(trash, "meta");
            if ((!files.isDirectory() && !files.mkdirs())
                    || (!meta.isDirectory() && !meta.mkdirs())) {
                return "无法创建回收站";
            }
            long now = System.currentTimeMillis();
            String id = createId(target.getName(), files, now);
            File payload = new File(files, id);
            if (!target.renameTo(payload)) return "无法移动到回收站";
            File record = new File(meta, id + ".bin");
            String error = writeRecord(record, id, root, target.getCanonicalPath(), now,
                    payload.isDirectory());
            if (error != null) {
                if (!payload.renameTo(target)) {
                    return "已移入回收站，但索引写入失败；文件保留在 " + payload.getAbsolutePath();
                }
                return error;
            }
            return null;
        } catch (Throwable t) {
            return "移入回收站失败：" + brief(t);
        }
    }

    static List<Entry> list(List<File> roots) {
        List<Entry> out = new ArrayList<Entry>();
        if (roots == null) return out;
        for (File root : roots) {
            File meta = new File(new File(root, DIR), "meta");
            File[] records = meta.listFiles();
            if (records == null) continue;
            for (File record : records) {
                if (out.size() >= LIST_CAP) break;
                Entry e = readRecord(record, root);
                if (e != null && e.payload.exists()) out.add(e);
            }
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.deletedAt == b.deletedAt ? a.id.compareTo(b.id)
                        : (a.deletedAt > b.deletedAt ? -1 : 1);
            }
        });
        return out;
    }

    static String restore(Entry entry, List<File> roots) {
        if (entry == null || !entry.payload.exists()) return "回收站文件不存在";
        if (!FileOps.isWritable(entry.payload, roots)) return "回收站位置不可写";
        try {
            File wanted = new File(entry.originalPath);
            File parent = wanted.getParentFile();
            if (parent == null || !FileOps.isWritable(parent, roots)) return "原位置不再可写";
            if (!parent.isDirectory() && !parent.mkdirs()) return "无法恢复原目录";
            File target = FileBatch.uniqueTarget(parent, wanted.getName());
            if (!entry.payload.renameTo(target)) return "恢复失败";
            if (!entry.metadata.delete()) return "文件已恢复，但回收站索引清理失败";
            cleanupEmpty(entry.root);
            return null;
        } catch (Throwable t) {
            return "恢复失败：" + brief(t);
        }
    }

    static String purge(Entry entry, List<File> roots) {
        if (entry == null) return "回收站记录不存在";
        String error = entry.payload.exists() ? FileOps.delete(entry.payload, roots) : null;
        if (error != null) return error;
        if (entry.metadata.exists() && !entry.metadata.delete()) return "无法删除回收站索引";
        cleanupEmpty(entry.root);
        return null;
    }

    private static String writeRecord(File record, String id, File root, String path,
                                      long time, boolean directory) {
        File temp = new File(record.getParentFile(), record.getName() + ".tmp");
        try {
            DataOutputStream out = new DataOutputStream(new FileOutputStream(temp));
            try {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(id);
                out.writeUTF(root.getCanonicalPath());
                out.writeUTF(path);
                out.writeLong(time);
                out.writeBoolean(directory);
                out.flush();
            } finally { out.close(); }
            if (!temp.renameTo(record)) return "无法提交回收站索引";
            return null;
        } catch (Throwable t) {
            try { temp.delete(); } catch (Throwable ignored) { }
            return "无法写入回收站索引：" + brief(t);
        }
    }

    private static Entry readRecord(File record, File expectedRoot) {
        try {
            if (!record.isFile() || record.length() > 64 * 1024) return null;
            DataInputStream in = new DataInputStream(new FileInputStream(record));
            try {
                if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
                String id = in.readUTF();
                String rootPath = in.readUTF();
                String original = in.readUTF();
                long time = in.readLong();
                boolean directory = in.readBoolean();
                if (id.length() == 0 || id.indexOf('/') >= 0 || original.length() == 0) return null;
                if (!expectedRoot.getCanonicalPath().equals(new File(rootPath).getCanonicalPath())) return null;
                if (!within(new File(original).getCanonicalPath(), expectedRoot.getCanonicalPath())) return null;
                File payload = new File(new File(new File(expectedRoot, DIR), "files"), id);
                if (!within(payload.getCanonicalPath(), new File(expectedRoot, DIR).getCanonicalPath())) return null;
                return new Entry(id, payload, record, expectedRoot, original, time, directory);
            } finally { in.close(); }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String createId(String name, File files, long now) {
        String safe = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() == 0) safe = "file";
        if (safe.length() > 48) safe = safe.substring(0, 48);
        for (int i = 0; i < 1000; i++) {
            String id = now + "-" + i + "-" + safe;
            if (!new File(files, id).exists()) return id;
        }
        return now + "-fallback-" + safe;
    }

    private static void cleanupEmpty(File root) {
        try {
            File trash = new File(root, DIR);
            File files = new File(trash, "files");
            File meta = new File(trash, "meta");
            File[] a = files.listFiles(), b = meta.listFiles();
            if (a != null && a.length == 0) files.delete();
            if (b != null && b.length == 0) meta.delete();
            File[] left = trash.listFiles();
            if (left != null && left.length == 0) trash.delete();
        } catch (Throwable ignored) { }
    }

    private static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static boolean isSymbolicLink(File file) throws IOException {
        File parent = file.getParentFile();
        File normalized = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
        return !normalized.getCanonicalFile().equals(normalized.getAbsoluteFile());
    }

    private static String brief(Throwable t) {
        String m = t.getClass().getSimpleName();
        return t.getMessage() == null ? m : m + ": " + t.getMessage();
    }
}
