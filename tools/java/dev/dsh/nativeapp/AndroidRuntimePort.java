package dev.dsh.nativeapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/** Produces a complete Android runtime before publication. No Activity or device state. */
public final class AndroidRuntimePort {
    private final Path runtime, helpers;
    private int checked;
    private boolean verify;
    private final java.util.Set<String> adapted = new java.util.TreeSet<String>();

    private AndroidRuntimePort(Path runtime, Path helpers) { this.runtime = runtime; this.helpers = helpers; }
    private String helper(String name) throws IOException { return read(helpers.resolve(name)); }
    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private void adapt(String relative, UnaryOperator<String> adapter) throws IOException {
        Path path = runtime.resolve(relative);
        String before = read(path), after = adapter.apply(before);
        if (after == null || after.isEmpty()) throw new IOException("Android port anchor changed: " + relative);
        String repeated = adapter.apply(after);
        if (!after.equals(repeated)) throw new IOException("Android port is not idempotent: " + relative);
        if (!after.equals(before)) {
            if (verify) throw new IOException("Runtime is not pre-adapted: " + relative);
            Files.write(path, after.getBytes(StandardCharsets.UTF_8));
        }
        adapted.add(relative);
        checked++;
    }
    private void module(String name, String file, UnaryOperator<String> adapter) throws IOException {
        adapt("node_modules/@deepseek-ai/" + name + "/lib/" + file, adapter);
    }
    private void build() throws IOException {
        if (CoreUpgrade.legacyRuntime(read(runtime.resolve("package.json"))))
            throw new IOException("Build requires the pinned current core, not a legacy runtime");
        String publish = helper("session-publish.js"), voice = helper("android-voice-ui.js");
        String connection = helper("connection-ui.js"), settings = helper("app-settings-ui.js");
        String sessions = helper("session-tools-core.js");
        if (!verify) CoreUpgrade.pruneEmptyModules(runtime.toFile());
        module("dsh-fs-local", "index.js", source -> FsWritePatch.patch(source, publish));
        module("dsh-experimental-client-ui-voice-input", "client.js", source -> VoiceUi.patch(source, voice));
        module("dsh-llm-pi-ai", "index.js", CoreUpgrade::codeBuddy);
        module("dsh-llm-deepseek-account", "index.js", CoreUpgrade::account);
        module("dsh-experimental-inspector", "index.js", CoreUpgrade::inspector);
        for (String file : new String[]{"client.browser.js", "client.node.js"})
            module("dsh-client-connection", file, source -> ConnectionRecovery.patchClient(source, connection));
        module("dsh-session-persistence-jsonl", "index.js", source -> SessionPersistencePatch.patch(source, publish));
        module("dsh-client-ui-settings-account", "client.js", AccountUi::patchAccount);
        module("dsh-client-ui-settings-general", "client.js", AccountUi::patchSettings);
        module("dsh-client-ui-settings-general", "client.js", source -> AppSettingsUi.patchClient(source, settings));
        module("dsh-api-session-controller", "index.js", source -> AppSettingsUi.patchCore(source, sessions));
        module("dsh-api-session-controller", "client.js", AppSettingsUi::patchSessionClient);
        module("dsh-client-ui-deliverables", "client.js", FileActionsUi::patch);
        module("dsh-client-ui-sidebar-documentpreview", "client.js", FileActionsUi::preview);
        module("dsh-client-ui-layout", "client.js", MobileLayout::patchLayoutModule);
        module("dsh-client-ui-model-selection", "client.js", ModelEffortUi::patch);
        module("dsh-attachment-local", "index.js", AttachmentPort::apply);
        adapt("node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html", source -> MobileLayout.patchHtml(source, 480));
        Path sharp = runtime.resolve("node_modules/sharp");
        if (!Files.isDirectory(sharp)) throw new IOException("Missing sharp module");
        publish(sharp.resolve("index.js"), helper("sharp-android.js"));
        publish(sharp.resolve("pillow_shim.py"), helper("pillow_shim.py"));
        publish(sharp.resolve("package.json"), "{\"name\":\"sharp\",\"version\":\"0.0.0-android-pillow\","
                + "\"main\":\"index.js\",\"description\":\"Android implementation backed by Python/Pillow\"}\n");
        Path legacyBackup = runtime.resolve("node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js.dshorig");
        if (verify && Files.exists(legacyBackup)) throw new IOException("Legacy attachment backup shipped");
        if (!verify) Files.deleteIfExists(legacyBackup);
        adapted.add("node_modules/sharp/index.js"); adapted.add("node_modules/sharp/pillow_shim.py");
        adapted.add("node_modules/sharp/package.json");
        StringBuilder contract = new StringBuilder();
        for (String relative : adapted) {
            byte[] bytes = read(runtime.resolve(relative)).replaceFirst("[ \t\r\n]+\\z", "").getBytes(StandardCharsets.UTF_8);
            StringBuilder hash = new StringBuilder();
            try {
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                    hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
            contract.append(relative).append('\t').append(hash).append('\n');
        }
        publish(runtime.resolve("android-runtime-files.tsv"), contract.toString());
        publish(runtime.resolve("android-runtime.json"), "{\"schema\":1,\"port\":1,\"core\":\"0.2.1-rc.3\",\"adapters\":" + checked + "}\n");
        System.out.println("Android runtime: " + checked + " build-time adapters verified twice; sharp/Pillow installed");
    }
    private void publish(Path path, String content) throws IOException {
        if (verify) {
            if (!read(path).equals(content)) throw new IOException("Runtime build contract differs: " + path.getFileName());
        } else Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && (args.length != 3 || !"--verify".equals(args[2]))) throw new IllegalArgumentException("runtime-dir helper-dir [--verify]");
        AndroidRuntimePort port = new AndroidRuntimePort(Paths.get(args[0]), Paths.get(args[1]));
        port.verify = args.length == 3;
        port.build();
    }
}
