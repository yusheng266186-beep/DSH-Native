package dev.dsh.nativeapp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CoreUpgradeTest {
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("CoreUpgrade check " + (checks + 1));
        checks++;
    }
    private static void patch(File file, boolean account) throws Exception {
        String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        String next = account ? CoreUpgrade.account(source) : CoreUpgrade.inspector(source);
        if (next == null) throw new AssertionError("Runtime patch anchor changed");
        Files.write(file.toPath(), next.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--apply".equals(args[0])) {
            File runtime = new File(args[1]);
            int removed = CoreUpgrade.pruneEmptyModules(runtime);
            patch(new File(runtime, "node_modules/@deepseek-ai/dsh-llm-deepseek-account/lib/index.js"), true);
            patch(new File(runtime, "node_modules/@deepseek-ai/dsh-experimental-inspector/lib/index.js"), false);
            System.out.println("Applied production upgrade compatibility; empty directories removed: " + removed);
            return;
        }
        check(CoreUpgrade.account("unknown upstream") == null);
        check(CoreUpgrade.legacyRuntime("{\"version\": \"0.2.0-rc.2\"}"));
        check(!CoreUpgrade.legacyRuntime("{\"version\": \"0.2.1-rc.3\"}"));
        check(CoreUpgrade.inspector("unknown upstream") == null);
        String patched = CoreUpgrade.inspector("const options = {execArgv: []};");
        check(patched.contains("--expose-internals"));
        check(CoreUpgrade.inspector(patched).equals(patched));
        check(CoreUpgrade.inspector("execArgv: []\nexecArgv: []") == null);
        Path root = Files.createTempDirectory("dsh-upgrade-");
        Path modules = Files.createDirectories(root.resolve("dsh/node_modules"));
        Path empty = Files.createDirectories(modules.resolve("old/node_modules/empty/lib"));
        Path live = Files.createDirectories(modules.resolve("live"));
        Files.write(live.resolve("index.js"), "unchanged".getBytes(StandardCharsets.UTF_8));
        Path outside = Files.createDirectories(root.resolve(".dsh/empty-must-survive"));
        Path linked = modules.resolve("linked");
        Files.createSymbolicLink(linked, outside);
        try {
            check(CoreUpgrade.pruneEmptyModules(root.resolve("dsh").toFile()) == 4);
            check(!Files.exists(empty));
            check(Files.isDirectory(modules));
            check(new String(Files.readAllBytes(live.resolve("index.js")), StandardCharsets.UTF_8).equals("unchanged"));
            check(Files.isSymbolicLink(linked));
            check(Files.isDirectory(outside));
            check(CoreUpgrade.pruneEmptyModules(root.resolve("dsh").toFile()) == 0);
        } finally {
            Files.deleteIfExists(linked);
            Files.deleteIfExists(live.resolve("index.js"));
            Files.deleteIfExists(live);
            Files.deleteIfExists(modules);
            Files.deleteIfExists(modules.getParent());
            Files.deleteIfExists(outside);
            Files.deleteIfExists(outside.getParent());
            Files.deleteIfExists(root);
        }
        System.out.println("TOTAL: " + checks + " pass / 0 fail");
    }
}
