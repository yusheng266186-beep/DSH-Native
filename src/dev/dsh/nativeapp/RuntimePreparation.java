package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Device-specific preparation only. Fixed core compatibility belongs to the payload build. */
final class RuntimePreparation {
    private static final String TOOLS_REVISION = "1";
    private RuntimePreparation() { }
    static void requirePort(File root, File runtime) throws IOException {
        File marker = new File(runtime, "android-runtime.json");
        if (!marker.isFile()) {
            verifyPreparedLegacy(root, runtime);
            return;
        }
        if (marker.length() > 4096) throw new IOException("运行包需要升级为预适配版本，请在更新与维护中更新运行包");
        try {
            Object parsed = JsonValue.parse(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("port contract");
            Map<?, ?> value = (Map<?, ?>) parsed;
            if (!Double.valueOf(1).equals(value.get("schema")) || !Double.valueOf(1).equals(value.get("port"))
                    || !"0.2.1-rc.3".equals(value.get("core"))) throw new IllegalArgumentException("port revision");
        } catch (IllegalArgumentException error) { throw new IOException("运行包 Android 适配版本不匹配，请重新更新运行包", error); }
    }
    /** Offline upgrade/rollback can retain the already adapted 1.0 runtime, without rewriting it. */
    private static void verifyPreparedLegacy(File root, File runtime) throws IOException {
        File contract = new File(root, "android-port-files.tsv");
        if (!contract.isFile() || contract.length() > 16384) throw new IOException("缺少 Android 运行包兼容记录，请更新运行包");
        String source = new String(Files.readAllBytes(contract.toPath()), StandardCharsets.UTF_8);
        int checked = 0;
        for (String line : source.split("\n")) {
            String[] row = line.split("\t", -1);
            if (row.length != 2 || !row[0].startsWith("node_modules/") || !row[0].matches("[A-Za-z0-9_@./-]+") || row[0].contains("..")
                    || !row[1].matches("[0-9a-f]{64}")) throw new IOException("Invalid Android compatibility contract");
            File file = new File(runtime, row[0]);
            if (!file.getCanonicalPath().startsWith(runtime.getCanonicalPath() + File.separator)
                    || !file.isFile() || file.length() > 4 * 1024 * 1024) throw new IOException("运行包需要更新：缺少预适配组件");
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (row[0].endsWith("/dist/index.html")) {
                String normalized = MobileLayout.patchHtml(new String(bytes, StandardCharsets.UTF_8), 480);
                if (normalized == null) throw new IOException("运行包页面需要更新");
                bytes = normalized.getBytes(StandardCharsets.UTF_8);
            }
            bytes = new String(bytes, StandardCharsets.UTF_8).replaceFirst("[ \t\r\n]+\\z", "").getBytes(StandardCharsets.UTF_8);
            StringBuilder hash = new StringBuilder();
            try {
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                    hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
            if (!hash.toString().equals(row[1])) throw new IOException("运行包需要升级为预适配版本，请更新运行包");
            checked++;
        }
        if (checked < 20) throw new IOException("Incomplete Android compatibility contract");
    }
    static boolean toolsPresent(File root) {
        for (String name : new String[]{"node", "npm", "npx", "dsh-android"}) {
            File file = new File(root, ".native-bin/" + name);
            if (!file.isFile() || !file.canExecute()) return false;
        }
        return new File(root, "tmp").isDirectory();
    }
    static int prepareTools(File root, String payloadRevision) throws IOException {
        String key = TOOLS_REVISION + "\n" + payloadRevision + "\n" + root.getCanonicalPath() + "\n" + new File(root, "tools/bin").lastModified() + "\n";
        File receipt = new File(root, ".tool-environment");
        if (receipt.isFile() && receipt.length() <= 8192 && toolsPresent(root)
                && key.equals(new String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8))) return 0;
        int fixed = ToolEnvironment.repair(root);
        if (!toolsPresent(root)) throw new IOException("Device commands were not prepared");
        AssetInstaller.atomic(root, ".tool-environment", key);
        return fixed;
    }
}
