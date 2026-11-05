package dev.dsh.nativeapp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class PayloadPartInstallerTest {
    static int checks;
    static void check(boolean yes, String why) { if (!yes) throw new AssertionError(why); checks++; }
    static void write(Path file, String text) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, text.getBytes("UTF-8")); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0) { published(args); return; }
        Path root = Files.createTempDirectory("dsh-part-install-").toRealPath();
        try {
            Path runtime = root.resolve("runtime"), file = runtime.resolve("node_modules/package/index.js");
            write(file, "old"); write(root.resolve(".dsh/KEEP"), "private");
            Path empty = runtime.resolve("node_modules/consumer/node_modules/chokidar"); Files.createDirectories(empty);
            PayloadPartInstaller.install(runtime.toFile(), Arrays.asList("node_modules/package/index.js"), true, () -> {
                check(!Files.exists(file), "obsolete file removed before extraction");
                check(!Files.exists(empty), "empty nested dependency removed before extraction");
                write(file, "new upstream dependency");
            });
            check(new String(Files.readAllBytes(file), "UTF-8").equals("new upstream dependency"), "new contents win over cumulative removals");
            check(Files.readAllBytes(root.resolve(".dsh/KEEP")).length == 7, "private data retained");
            try { PayloadPartInstaller.install(runtime.toFile(), Arrays.asList("../.dsh/KEEP"), true, () -> { throw new AssertionError("unsafe removal reached extraction"); }); throw new AssertionError("unsafe removal accepted"); }
            catch (IOException expected) { checks++; }
            try { PayloadPartInstaller.install(runtime.toFile(), Collections.emptyList(), true, () -> { throw new IOException("unpack failure"); }); throw new AssertionError("unpack failure hidden"); }
            catch (IOException expected) { check(expected.getMessage().equals("unpack failure"), "failure reaches snapshot transaction"); }
            System.out.println("Payload part installation: " + checks + " ordering, empty dependency, failure and data-preservation checks passed.");
        } finally { try (java.util.stream.Stream<Path> paths = Files.walk(root)) { for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path); } }
    }
    static void published(String[] args) throws Exception {
        File runtime = new File(args[4]);
        Map<?, ?> manifest = (Map<?, ?>) JsonValue.parse(new String(Files.readAllBytes(Paths.get(args[5])), "UTF-8"));
        Map<?, ?> part = (Map<?, ?>) ((List<?>) manifest.get("parts")).get(0);
        List<String> removals = new ArrayList<>(); for (Object value : (List<?>) part.get("remove")) removals.add((String) value);
        String collision = "node_modules/@aws-sdk/credential-provider-http/node_modules/@smithy/node-http-handler/dist-cjs/index.js";
        if (removals.contains(collision)) write(runtime.toPath().resolve(collision), "obsolete fixture");
        Path empty = runtime.toPath().resolve("node_modules/@deepseek-ai/dsh-skill-filesystem/node_modules/chokidar"); Files.createDirectories(empty);
        PayloadPartInstaller.install(runtime, removals, true, () -> {
            check(!Files.exists(empty), "real update prunes empty dependency before unpack");
            Process process = new ProcessBuilder(args[1], args[2], args[3], args[4]).inheritIO().start();
            if (process.waitFor() != 0) throw new IOException("actual archive extraction failed");
        });
        if (removals.contains(collision)) check(Files.size(runtime.toPath().resolve(collision)) > 100, "published archive restores reintroduced dependency");
        System.out.println("Published payload: production removal/pruning/extraction path preserves reintroduced dependencies.");
    }
}
