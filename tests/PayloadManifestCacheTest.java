package dev.dsh.nativeapp;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class PayloadManifestCacheTest {
    static int checks;
    static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); checks++; }
    static String hash(byte[] bytes) throws Exception { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255)); return out.toString(); }
    static void write(Path path, String value) throws Exception { Files.createDirectories(path.getParent()); Files.write(path, value.getBytes("UTF-8")); }
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            if (args[0].equals("--current")) RuntimePreparation.requireCurrentPort(new File(args[1]), new File(args[2]));
            else RuntimePreparation.requirePort(new File(args[1]), new File(args[2]));
            System.out.println("Actual runtime port contract verified: " + args[0]); return;
        }
        Path root = Files.createTempDirectory("dsh-manifest-cache-").toRealPath();
        try {
            Path source = Paths.get("payload/pinned-manifest.json"); if (!Files.exists(source)) source = Paths.get("bootstrap/payload/pinned-manifest.json");
            byte[] bundled = Files.readAllBytes(source); String pin = hash(bundled);
            write(root.resolve("manifest.json"), "previous release metadata");
            check(PayloadManifestCache.local(root.toFile(), pin) == null, "old cache cannot match new pin");
            Files.write(root.resolve(PayloadManifestCache.BUNDLED), bundled);
            File cached = PayloadManifestCache.local(root.toFile(), pin);
            check(Arrays.equals(Files.readAllBytes(cached.toPath()), bundled), "APK upgrade replaces stale metadata without network");
            check(Files.exists(root.resolve(PayloadManifestCache.BUNDLED)), "bundled component remains installed");
            long time = cached.lastModified(); check(PayloadManifestCache.local(root.toFile(), pin).lastModified() == time, "matching cache is not rewritten");
            write(cached.toPath(), "corrupt"); check(PayloadManifestCache.local(root.toFile(), pin).length() == bundled.length, "corrupt cache repaired offline");
            Files.delete(cached.toPath()); Files.write(root.resolve(PayloadManifestCache.BUNDLED), new byte[(int) PayloadManifest.MAX_MANIFEST_BYTES + 1]);
            check(PayloadManifestCache.local(root.toFile(), pin) == null && !cached.exists(), "oversize or wrong bundle is never published");
            Files.write(root.resolve(PayloadManifestCache.BUNDLED), bundled); Thread.currentThread().interrupt();
            try { PayloadManifestCache.local(root.toFile(), pin); throw new AssertionError("interruption ignored"); }
            catch (InterruptedIOException expected) { check(!cached.exists(), "cancelled copy leaves cache absent"); }
            finally { Thread.interrupted(); }
            Path runtime = root.resolve("legacy"), contract = root.resolve("android-port-legacy-0.2.1-rc.3.tsv");
            write(runtime.resolve("package.json"), "{\"version\":\"0.2.1-rc.3\"}"); StringBuilder rows = new StringBuilder();
            for (int n = 0; n < 20; n++) { String name = "node_modules/old-module/part-" + n + ".js"; write(runtime.resolve(name), "old prepared code"); rows.append(name).append('\t').append(hash("old prepared code".getBytes("UTF-8"))).append('\n'); }
            write(contract, rows.toString()); write(root.resolve("android-port-files.tsv"), "new kernel contract");
            RuntimePreparation.requirePort(root.toFile(), runtime.toFile()); check(true, "legacy core uses preserved old contract after APK replaces current contract");
            write(runtime.resolve("node_modules/old-module/part-3.js"), "changed");
            try { RuntimePreparation.requirePort(root.toFile(), runtime.toFile()); throw new AssertionError("modified old runtime accepted"); } catch (IOException expected) { checks++; }
            System.out.println("Payload metadata: " + checks + " offline upgrade, pin, cache repair, interruption and legacy contract checks passed.");
        } finally { try (java.util.stream.Stream<Path> paths = Files.walk(root)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path); } }
    }
}
