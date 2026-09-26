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
                File file = new File(dshHome, name);
                if (!file.isFile()) continue;
                if (file.length() > MAX_ENTRY_BYTES) throw new IOException(name + " 超过大小限制");
                zip.putNextEntry(new ZipEntry(name));
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) throw new IOException("配置总大小超过限制");
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
                if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
                    throw new IOException("备份内容不合法（含路径分隔符）");
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

    private static int restoreEntries(Map<String, byte[]> entries, File dshHome)
            throws IOException {
        if (dshHome == null) throw new IOException("配置目录为空");
        if (!dshHome.isDirectory() && !dshHome.mkdirs()) throw new IOException("无法创建配置目录");
        List<File> staged = new ArrayList<File>();
        try {
            int index = 0;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                File temp = new File(dshHome, ".restore-" + index + "-" + System.nanoTime());
                FileOutputStream out = new FileOutputStream(temp);
                try {
                    out.write(entry.getValue());
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                staged.add(temp);
                index++;
            }
            int restored = 0;
            index = 0;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                TransferState.atomicReplace(staged.get(index), new File(dshHome, entry.getKey()));
                restored++;
                index++;
            }
            return restored;
        } finally {
            for (File file : staged) if (file.exists()) file.delete();
            wipeEntries(entries);
        }
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
