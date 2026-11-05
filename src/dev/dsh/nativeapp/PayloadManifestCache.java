package dev.dsh.nativeapp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Immutable payload metadata is usable offline only when it matches the APK pin. */
final class PayloadManifestCache {
    static final String BUNDLED = "pinned-manifest.json";
    private PayloadManifestCache() { }

    static File local(File root, String expected) throws IOException {
        File cached = new File(root, "manifest.json");
        if (matches(cached, expected)) return cached;
        File bundled = new File(root, BUNDLED);
        if (!matches(bundled, expected)) return null;
        AssetInstaller.atomic(root, "manifest.json",
                new String(Files.readAllBytes(bundled.toPath()), StandardCharsets.UTF_8));
        if (!matches(cached, expected)) throw new IOException("内置运行包清单写入校验失败");
        return cached;
    }

    private static boolean matches(File file, String expected) throws IOException {
        if (!PayloadUpdate.isSha256(expected) || !file.isFile()
                || PayloadManifest.validateManifestFileSize(file.length()) != null) return false;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count; long total = 0;
            while ((count = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("清单准备已取消");
                total += count;
                if (total > PayloadManifest.MAX_MANIFEST_BYTES) return false;
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        return expected.equalsIgnoreCase(hex.toString());
    }
}
