package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;

/** Compatibility for an existing installation upgraded to the rc.3 runtime. */
final class CoreUpgrade {
    private CoreUpgrade() { }

    static boolean legacyRuntime(String packageJson) {
        return packageJson.matches("(?s).*\"version\"\\s*:\\s*\"0\\.2\\.0-rc\\.2\".*");
    }

    static String account(String source) {
        String marker = "// dsh-native-account-live-catalog-v1";
        if (source.contains(marker)) return source;
        String before = "if (Object.hasOwn(options, \"models\")) throw new Error(\"llm-deepseek-account: models is no longer configurable; the account /models listing supplies the catalog. Remove models from this entry and adjust single models with modelOverrides.<model id>\");";
        // Older App versions persisted a cached catalog here. Keep the stored
        // document for downgrade, but let the account's authenticated listing
        // supply models. Explicit modelOverrides and connection settings survive.
        String after = marker + "\n\tconst { models: _legacyCatalog, ...currentOptions } = options;\n\toptions = currentOptions;";
        return once(source, before, after);
    }

    static String inspector(String source) {
        if (source.contains("/* dsh-native-inspector-internals-v1 */")) return source;
        // The inspector deliberately clears execArgv. The Android JS shim still
        // needs this single flag in its Worker; do not inherit unrelated flags.
        return once(source, "execArgv: []", "execArgv: [\"--expose-internals\"] /* dsh-native-inspector-internals-v1 */");
    }

    /** Use the official CLI agent transport in the App; keep standard HTTP elsewhere. */
    static String codeBuddy(String source) {
        String marker = "/* dsh-native-codebuddy-standard-v2 */";
        String previous = "headers: requestHeaders(options.provider === \"codebuddy\" && profile.baseURL === \"https://www.codebuddy.cn/v2\" ? { ...profile.headers, \"X-API-Key\": apiKey, \"X-Product\": \"codebuddy\", \"X-IDE-Name\": \"DSH Native\" } : profile.headers) /* dsh-native-codebuddy-auth-v1 */";
        String out = source;
        if (out.contains("/* dsh-native-codebuddy-auth-v1 */"))
            out = once(out, previous, "headers: requestHeaders(profile.headers) " + marker);
        else if (!out.contains(marker)) out = once(out, "headers: requestHeaders(profile.headers)",
                "headers: requestHeaders(profile.headers) " + marker);
        if (out == null || out.contains("/* dsh-native-codebuddy-cli-v1 */")) return out;
        String anchor = "const iterator = toStreamChunks(snapshot.models.streamSimple(model, context, {";
        return once(out,anchor,"/* dsh-native-codebuddy-cli-v1 */\n"
                + "if (options.provider === \"codebuddy\" && profile.baseURL === \"https://www.codebuddy.cn/v2\" && process.env.DSH_NATIVE_APP_ROOT) {\n"
                + "const root = process.env.DSH_NATIVE_APP_ROOT;\n"
                + "const { default: cli } = await import((await import(\"node:url\")).pathToFileURL(resolve(root, \"codebuddy-cli.cjs\")).href);\n"
                + "yield* cli.stream(options, context, {apiKey, root, idleTimeoutMs: streamIdleTimeoutMs}); return;\n}\n" + anchor);
    }

    private static String once(String source, String before, String after) {
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0) return null;
        return source.substring(0, at) + after + source.substring(at + before.length());
    }

    /** Remove only empty directories, never files or symlinks, inside node_modules. */
    static int pruneEmptyModules(File runtime) throws IOException {
        File modules = new File(runtime.getCanonicalFile(), "node_modules");
        if (!modules.getCanonicalFile().equals(modules))
            throw new IOException("Refusing linked node_modules");
        return prune(modules, 0, true);
    }

    private static int prune(File directory, int depth, boolean keep) throws IOException {
        if (depth > 48) throw new IOException("Module directory nesting exceeds limit");
        File[] children = directory.listFiles();
        if (children == null) {
            if (!directory.exists()) return 0;
            throw new IOException("Cannot inspect module directory");
        }
        int count = 0;
        for (File child : children) {
            // Work below a canonical root; unequal paths identify symlinks,
            // including links to another package inside this same tree.
            if (child.isDirectory() && child.getCanonicalFile().equals(child.getAbsoluteFile()))
                count += prune(child, depth + 1, false);
        }
        // File.delete is an atomic rmdir for a directory. A file appearing
        // concurrently makes it fail safely; never fall back to recursive delete.
        if (!keep && directory.delete()) count++;
        return count;
    }
}
