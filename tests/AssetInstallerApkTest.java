package dev.dsh.nativeapp;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** The actual installer consumes the signed APK, not an independently parsed mock index. */
public final class AssetInstallerApkTest {
    private static int checks;
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); checks++; }
    private static byte[] digest(InputStream source) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[262144]; int n;
        try (InputStream stream = source) { while ((n = stream.read(buffer)) != -1) md.update(buffer, 0, n); }
        return md.digest();
    }
    private static final class ApkSource implements AssetInstaller.Source {
        final ZipFile apk;
        int payloadReads;
        ApkSource(ZipFile apk) { this.apk = apk; }
        @Override public InputStream open(String name) throws IOException {
            ZipEntry entry = apk.getEntry("assets/" + name);
            if (entry == null || entry.isDirectory()) throw new FileNotFoundException(name);
            if (name.startsWith("payload/")) payloadReads++;
            return apk.getInputStream(entry);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("signed-apk");
        Path scratch = Files.createTempDirectory("dsh-apk-components-");
        try (ZipFile apk = new ZipFile(args[0])) {
            File root = Files.createDirectory(scratch.resolve("runtime")).toFile();
            Path history = root.toPath().resolve(".dsh/KEEP"), project = root.toPath().resolve("project/KEEP");
            Files.createDirectories(history.getParent()); Files.createDirectories(project.getParent());
            Files.write(history, "synthetic private data".getBytes("UTF-8"));
            Files.write(project, "synthetic project data".getBytes("UTF-8"));
            Map<String, ZipEntry> entries = new TreeMap<String, ZipEntry>();
            Enumeration<? extends ZipEntry> enumeration = apk.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith("assets/payload/"))
                    entries.put(entry.getName().substring("assets/payload/".length()), entry);
            }
            check(entries.containsKey("node") && entries.containsKey("lib/libc++_shared.so"), "signed APK contains the actual Node/C++ bootstrap");
            ApkSource source = new ApkSource(apk);
            AssetInstaller installer = new AssetInstaller(source);
            check(!installer.ready(root), "empty runtime is not marked prepared");
            check(installer.install(root) == entries.size(), "first installation consumes every APK component");
            for (Map.Entry<String, ZipEntry> entry : entries.entrySet()) {
                File installed = new File(root, entry.getKey());
                check(installed.isFile() && installed.length() == entry.getValue().getSize(), "actual component installed: " + entry.getKey());
                check(Arrays.equals(digest(apk.getInputStream(entry.getValue())), digest(new FileInputStream(installed))), "actual installed SHA-256: " + entry.getKey());
            }
            check(installer.ready(root), "actual installed tree passes production admission");
            long nodeTime = new File(root, "node").lastModified();
            source.payloadReads = 0; AssetInstaller reopened = new AssetInstaller(source);
            check(reopened.ready(root) && reopened.install(root) == 0, "reopening the same APK reuses the completed installation");
            check(source.payloadReads == 0 && new File(root, "node").lastModified() == nodeTime, "restart never reopens or rewrites the large APK components");
            Files.delete(root.toPath().resolve(".native-assets")); source.payloadReads = 0;
            check(!reopened.ready(root) && reopened.install(root) == 0 && reopened.ready(root), "upgrade from an installation without a receipt recovers verified disk contents");
            check(source.payloadReads == 0, "legacy receipt migration does not rewrite unchanged components");
            Files.delete(root.toPath().resolve("lib/libc++_shared.so")); source.payloadReads = 0;
            check(!reopened.ready(root) && reopened.install(root) == 1 && reopened.ready(root), "missing C++ library is restored by the actual installer");
            check(source.payloadReads == 1, "repair reads only the missing APK component");
            check(new String(Files.readAllBytes(history), "UTF-8").equals("synthetic private data")
                    && new String(Files.readAllBytes(project), "UTF-8").equals("synthetic project data"), "first install, restart, migration and repair preserve user directories");
            check(new File(root, "node").lastModified() == nodeTime, "C++ library repair leaves the Node binary untouched");
            System.out.println("Signed APK components: " + checks + " production installer checks passed for " + entries.size()
                    + " actual files; fresh install, restart, receipt migration, C++ repair and private-data preservation. No device.");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(scratch)) {
                Iterator<Path> paths = files.sorted(Comparator.reverseOrder()).iterator();
                while (paths.hasNext()) Files.deleteIfExists(paths.next());
            }
        }
    }
}
