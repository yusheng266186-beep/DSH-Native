package dev.dsh.nativeapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 配置备份与恢复：纯 Java，无 Android 依赖，可离线测试。
 *
 * <p>新版备份使用口令派生密钥和 AES-GCM 加密，旧版明文 ZIP 仅保留导入兼容。
 * 所有恢复内容会先完整解密、校验白名单与大小限制，再写入配置目录。
 */
final class ConfigBackup {

    static final String[] FILES = {
        ".credentials.yaml",
        "settings.yaml",
        "settings.yaml.imported",
        "profiles/web/cordis.patch.yml",
        ProjectModelSettings.FILE_NAME,
    };

    private static final String PREFIX = "dsh-config-";
    private static final String ENCRYPTED_SUFFIX = ".dshbak";
    private static final String LEGACY_SUFFIX = ".zip";
    private static final byte[] MAGIC = new byte[]{'D', 'S', 'H', 'B', 'K', '1'};
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KDF_ITERATIONS = 150000;
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;

    private ConfigBackup() { }

    static String stamp(long ms) {
        try {
            return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                    .format(new Date(ms));
        } catch (Throwable t) {
            return String.valueOf(ms);
        }
    }

    /** 新导出一律使用加密格式。 */
    static String fileName(long ms) {
        return PREFIX + stamp(ms) + ENCRYPTED_SUFFIX;
    }

    static boolean isBackupName(String name) {
        return name != null && name.startsWith(PREFIX)
                && (name.endsWith(ENCRYPTED_SUFFIX) || name.endsWith(LEGACY_SUFFIX));
    }

    static boolean isEncrypted(File file) {
        if (file == null || !file.isFile() || file.length() < MAGIC.length) return false;
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] head = new byte[MAGIC.length];
            int n = in.read(head);
            return n == MAGIC.length && Arrays.equals(head, MAGIC);
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    static int exportEncrypted(File dshHome, File target, char[] password) throws IOException {
        requirePassword(password);
        ZipPayload payload = buildZip(dshHome);
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(MAGIC);
            byte[] encrypted = cipher.doFinal(payload.bytes);

            ByteArrayOutputStream raw = new ByteArrayOutputStream(
                    MAGIC.length + salt.length + iv.length + encrypted.length + 8);
            DataOutputStream out = new DataOutputStream(raw);
            out.write(MAGIC);
            out.writeByte(salt.length);
            out.write(salt);
            out.writeByte(iv.length);
            out.write(iv);
            out.writeInt(encrypted.length);
            out.write(encrypted);
            out.close();
            writeAtomic(target, raw.toByteArray());
            return payload.count;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("无法加密备份：" + t.getClass().getSimpleName(), t);
        } finally {
            Arrays.fill(payload.bytes, (byte) 0);
        }
    }

    /** 旧版明文 ZIP 导出仅供兼容测试与迁移，不应再由界面调用。 */
    static int exportTo(File dshHome, File zipFile) throws IOException {
        ZipPayload payload = buildZip(dshHome);
        try {
            writeAtomic(zipFile, payload.bytes);
            return payload.count;
        } finally {
            Arrays.fill(payload.bytes, (byte) 0);
        }
    }

    static String validate(File backup) {
        if (backup == null || !backup.isFile()) return "备份文件不存在";
        if (backup.length() == 0) return "备份文件为空";
        if (isEncrypted(backup)) return "这是加密备份，需要输入口令";
        Map<String, byte[]> entries = null;
        try {
            entries = readLegacyZip(backup);
            return null;
        } catch (Throwable t) {
            return message(t, "无法读取备份");
        } finally {
            wipeEntries(entries);
        }
    }

    static String validateEncrypted(File backup, char[] password) {
        Map<String, byte[]> entries = null;
        try {
            entries = readEncryptedEntries(backup, password);
            return null;
        } catch (Throwable t) {
            return message(t, "无法解密备份");
        } finally {
            wipeEntries(entries);
        }
    }

    static int restoreFrom(File backup, File dshHome) throws IOException {
        if (isEncrypted(backup)) throw new IOException("加密备份需要口令");
        return restoreEntries(readLegacyZip(backup), dshHome);
    }

    static int restoreEncrypted(File backup, File dshHome, char[] password) throws IOException {
        return restoreEntries(readEncryptedEntries(backup, password), dshHome);
    }

    static List<File> listBackups(File dir) {
        List<File> out = new ArrayList<File>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File file : all) {
            if (file.isFile() && isBackupName(file.getName())) out.add(file);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    /** 恢复前用同一口令保存当前配置，安全副本同样不会暴露密钥。 */
    static File safetyCopy(File dshHome, File dir, long now, char[] password)
            throws IOException {
        File file = new File(dir, PREFIX + "before-restore-" + stamp(now)
                + ENCRYPTED_SUFFIX);
        exportEncrypted(dshHome, file, password);
        return file;
    }

    static String describe(File file) {
        if (file == null) return "";
        String when;
        try {
            when = new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
                    .format(new Date(file.lastModified()));
        } catch (Throwable t) {
            when = "";
        }
        return (isEncrypted(file) ? "已加密  " : "旧版明文  ")
                + FileListing.humanSize(file.length()) + "  " + when;
    }

    private static ZipPayload buildZip(File dshHome) throws IOException {
        if (dshHome == null || !dshHome.isDirectory()) throw new IOException("配置目录不存在");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        int count = 0;
        int total = 0;
        try {
            for (String name : FILES) {
                File file = configFile(dshHome, name);
                if (!file.isFile()) continue;
                if (file.length() > MAX_ENTRY_BYTES) throw new IOException(name + " 超过大小限制");
                zip.putNextEntry(new ZipEntry(name));
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[8192];
                    int entryBytes = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            throw new IOException("配置大小超过限制");
                        }
                        zip.write(buffer, 0, n);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
                count++;
            }
        } finally {
            zip.close();
        }
        if (count == 0) throw new IOException("没有可备份的配置文件");
        return new ZipPayload(bytes.toByteArray(), count);
    }

    private static Map<String, byte[]> readLegacyZip(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("备份文件不存在");
        if (file.length() <= 0 || file.length() > MAX_BACKUP_BYTES) {
            throw new IOException("备份文件大小异常");
        }
        FileInputStream in = new FileInputStream(file);
        try {
            return readZip(in);
        } finally {
            in.close();
        }
    }

    private static Map<String, byte[]> readEncryptedEntries(File file, char[] password)
            throws IOException {
        requirePassword(password);
        if (file == null || !file.isFile()) throw new IOException("备份文件不存在");
        if (file.length() <= MAGIC.length || file.length() > MAX_BACKUP_BYTES) {
            throw new IOException("备份文件大小异常");
        }
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        try {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("不是加密备份格式");
            int saltLength = in.readUnsignedByte();
            if (saltLength != SALT_BYTES) throw new IOException("盐值长度异常");
            byte[] salt = new byte[saltLength];
            in.readFully(salt);
            int ivLength = in.readUnsignedByte();
            if (ivLength != IV_BYTES) throw new IOException("随机向量长度异常");
            byte[] iv = new byte[ivLength];
            in.readFully(iv);
            int cipherLength = in.readInt();
            if (cipherLength <= 16 || cipherLength > MAX_BACKUP_BYTES) {
                throw new IOException("密文长度异常");
            }
            byte[] encrypted = new byte[cipherLength];
            in.readFully(encrypted);
            if (in.read() != -1) throw new IOException("备份尾部存在多余数据");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(MAGIC);
            byte[] plain = cipher.doFinal(encrypted);
            try {
                return readZip(new ByteArrayInputStream(plain));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("口令错误或备份已损坏", t);
        } finally {
            in.close();
        }
    }

    private static Map<String, byte[]> readZip(InputStream source) throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        ZipInputStream zip = new ZipInputStream(source);
        int total = 0;
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name == null || name.length() == 0) {
                    throw new IOException("备份包含无效条目");
                }
                if (name.indexOf('\\') >= 0 || name.contains("..")) {
                    throw new IOException("备份内容不合法");
                }
                if (!Arrays.asList(FILES).contains(name)) {
                    throw new IOException("备份内容不在允许范围内：" + name);
                }
                if (entries.containsKey(name)) throw new IOException("备份包含重复条目：" + name);

                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int entryBytes = 0;
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    entryBytes += n;
                    total += n;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw new IOException("备份解压后超过大小限制");
                    }
                    data.write(buffer, 0, n);
                }
                entries.put(name, data.toByteArray());
                zip.closeEntry();
            }
        } finally {
            zip.close();
        }
        if (entries.isEmpty()) throw new IOException("备份里没有任何配置文件");
        return entries;
    }

    private static synchronized int restoreEntries(Map<String, byte[]> entries, File dshHome)
            throws IOException {
        if (dshHome == null) throw new IOException("配置目录为空");
        if (!dshHome.isDirectory() && !dshHome.mkdirs()) throw new IOException("无法创建配置目录");
        List<File> staged = new ArrayList<File>();
        Map<String, byte[]> originals = new LinkedHashMap<String, byte[]>();
        boolean journalReady = false;
        try {
            recoverInterruptedRestore(dshHome);
            // 校验全部目标和父目录后才覆盖任何文件，允许的嵌套路径仍由 FILES 精确限定。
            List<File> targets = new ArrayList<File>();
            for (String name : entries.keySet()) {
                File target = configFile(dshHome, name);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建配置子目录");
                }
                configFile(dshHome, name);
                if (target.exists() && !target.isFile()) {
                    throw new IOException("配置目标不是文件");
                }
                targets.add(target);
                originals.put(name, target.exists() ? readConfigBytes(target) : null);
            }
            int index = 0;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                File temp = new File(dshHome, ".restore-" + index + "-" + System.nanoTime());
                staged.add(temp);
                FileOutputStream out = new FileOutputStream(temp);
                try {
                    out.write(entry.getValue());
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                index++;
            }
            writeRestoreJournal(dshHome, originals);
            journalReady = true;
            int restored = 0;
            index = 0;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                configFile(dshHome, entry.getKey());
                TransferState.atomicReplace(staged.get(index), targets.get(index));
                restored++;
                index++;
            }
            if (!restoreJournal(dshHome).delete()) throw new IOException("无法提交配置恢复记录");
            journalReady = false;
            return restored;
        } catch (IOException failed) {
            if (journalReady) {
                try { recoverInterruptedRestore(dshHome); }
                catch (IOException rollbackFailed) {
                    failed.addSuppressed(rollbackFailed);
                    throw new IOException("配置恢复未完成，原配置恢复记录已保留；请重试启动或导出诊断", failed);
                }
            }
            throw failed;
        } finally {
            for (File file : staged) if (file.exists()) file.delete();
            wipeEntries(entries);
            wipeEntries(originals);
        }
    }

    private static final String RESTORE_JOURNAL = ".native-config-restore";
    private static final String RESTORE_MAGIC = "DSH-CONFIG-RESTORE-1";

    static boolean hasInterruptedRestore(File dshHome) {
        return new File(dshHome, RESTORE_JOURNAL).exists();
    }

    private static File restoreJournal(File home) throws IOException {
        File file = new File(home.getCanonicalFile(), RESTORE_JOURNAL);
        if (!file.equals(file.getCanonicalFile())) throw new IOException("配置恢复记录不能是符号链接");
        return file;
    }

    /** Private, bounded undo record is durable before any live configuration changes. */
    static void writeRestoreJournal(File home, Map<String, byte[]> originals) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(RESTORE_MAGIC);
        if (originals.isEmpty() || originals.size() > FILES.length) throw new IOException("配置恢复记录无效");
        out.writeInt(originals.size());
        int total = 0;
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
            configFile(home, entry.getKey());
            byte[] value = entry.getValue();
            int length = value == null ? -1 : value.length;
            if (length > MAX_ENTRY_BYTES || (total += Math.max(0, length)) > MAX_TOTAL_BYTES)
                throw new IOException("原配置超过恢复保护大小限制");
            out.writeUTF(entry.getKey()); out.writeInt(length);
            if (value != null) out.write(value);
        }
        out.flush();
        byte[] raw = bytes.toByteArray();
        byte[] digest = digest(raw);
        File journal = restoreJournal(home);
        if (journal.exists()) throw new IOException("存在尚未完成的配置恢复");
        File temp = new File(home.getCanonicalFile(), RESTORE_JOURNAL + ".next");
        if (!temp.equals(temp.getCanonicalFile())) throw new IOException("配置恢复暂存路径无效");
        try {
            try (FileOutputStream stream = new FileOutputStream(temp)) {
                stream.write(raw); stream.write(digest); stream.getFD().sync();
            }
            if (!temp.renameTo(journal)) throw new IOException("无法保存配置恢复保护记录");
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (temp.exists()) temp.delete();
        }
    }

    /** Replays undo idempotently before the core is allowed to read configuration. */
    static synchronized boolean recoverInterruptedRestore(File home) throws IOException {
        File journal = restoreJournal(home);
        if (!journal.exists()) return false;
        if (!journal.isFile() || journal.length() < 40 || journal.length() > MAX_TOTAL_BYTES + 4096)
            throw new IOException("配置恢复保护记录损坏，已保留现场");
        Map<String, byte[]> originals = new LinkedHashMap<String, byte[]>();
        byte[] raw = new byte[(int) journal.length()];
        try {
            try (DataInputStream file = new DataInputStream(new FileInputStream(journal))) { file.readFully(raw); }
            byte[] body = Arrays.copyOf(raw, raw.length - 32);
            try {
                if (!java.security.MessageDigest.isEqual(digest(body), Arrays.copyOfRange(raw, raw.length - 32, raw.length)))
                    throw new IOException("配置恢复保护记录摘要不一致，已保留现场");
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
                if (!RESTORE_MAGIC.equals(in.readUTF())) throw new IOException("配置恢复保护记录格式无效");
                int count = in.readInt(), total = 0;
                if (count < 1 || count > FILES.length) throw new IOException("配置恢复保护记录条目异常");
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    File target = configFile(home, name);
                    if (originals.containsKey(name) || (target.exists() && !target.isFile()))
                        throw new IOException("配置恢复保护目标异常");
                    int length = in.readInt();
                    if (length < -1 || length > MAX_ENTRY_BYTES || (total += Math.max(0, length)) > MAX_TOTAL_BYTES)
                        throw new IOException("配置恢复保护记录大小异常");
                    byte[] value = length < 0 ? null : new byte[length];
                    if (value != null) in.readFully(value);
                    originals.put(name, value);
                }
                if (in.read() != -1) throw new IOException("配置恢复保护记录尾部异常");
            } finally { Arrays.fill(body, (byte) 0); }
            for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
                File target = configFile(home, entry.getKey());
                byte[] old = entry.getValue();
                if (old == null) {
                    if (target.exists() && !target.delete()) throw new IOException("无法撤销新增配置");
                } else {
                    byte[] current = target.isFile() ? readConfigBytes(target) : null;
                    boolean same = Arrays.equals(old, current);
                    if (current != null) Arrays.fill(current, (byte) 0);
                    if (!same) writeAtomic(target, old);
                }
            }
            if (!journal.delete()) throw new IOException("无法清理已恢复的配置保护记录");
            return true;
        } finally {
            Arrays.fill(raw, (byte) 0);
            wipeEntries(originals);
        }
    }

    private static byte[] readConfigBytes(File file) throws IOException {
        if (file.length() > MAX_ENTRY_BYTES) throw new IOException("原配置超过大小限制");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_ENTRY_BYTES) throw new IOException("原配置超过大小限制");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static byte[] digest(byte[] raw) throws IOException {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(raw); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("缺少 SHA-256", impossible); }
    }

    private static File configFile(File dshHome, String name) throws IOException {
        if (!Arrays.asList(FILES).contains(name)) throw new IOException("配置文件不在允许范围内");
        File target = new File(dshHome.getCanonicalFile(), name);
        String root = dshHome.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(root) || !target.getAbsoluteFile().equals(target.getCanonicalFile())) {
            throw new IOException("配置路径超出目录范围");
        }
        return target;
    }

    private static void wipeEntries(Map<String, byte[]> entries) {
        if (entries == null) return;
        for (byte[] value : entries.values()) {
            if (value != null) Arrays.fill(value, (byte) 0);
        }
        entries.clear();
    }

    private static SecretKeySpec deriveKey(char[] password, byte[] salt) throws Exception {
        // Android 7 的标准 Provider 没有 PBKDF2WithHmacSHA256。导出时按设备能力
        // 选择算法会制造无法跨设备恢复的备份，所以格式 v1 固定使用各支持版本
        // 都具备的 PBKDF2WithHmacSHA1；AES-GCM 仍负责机密性与完整性。
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, 256);
        try {
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static void requirePassword(char[] password) throws IOException {
        if (password == null || password.length < 8) throw new IOException("备份口令至少 8 位");
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        if (target == null) throw new IOException("备份目标为空");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建备份目录");
        }
        File temp = new File(target.getAbsolutePath() + ".tmp-" + System.nanoTime());
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(data);
                out.getFD().sync();
            } finally {
                out.close();
            }
            TransferState.atomicReplace(temp, target);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private static String message(Throwable t, String fallback) {
        String text = t == null ? null : t.getMessage();
        return text == null || text.length() == 0 ? fallback : text;
    }

    private static final class ZipPayload {
        final byte[] bytes;
        final int count;

        ZipPayload(byte[] bytes, int count) {
            this.bytes = bytes;
            this.count = count;
        }
    }
}
