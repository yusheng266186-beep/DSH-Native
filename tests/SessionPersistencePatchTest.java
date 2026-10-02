package dev.dsh.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SessionPersistencePatchTest {
    static String read(String path) throws Exception { return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--dump-module".equals(args[0])) {
            String patched = SessionPersistencePatch.patch(read(args[1]), read(args[2]));
            if (patched == null || !patched.equals(SessionPersistencePatch.patch(patched, read(args[2]))))
                throw new AssertionError("Actual persistence must patch idempotently");
            System.out.print(patched); return;
        }
        String helper = "/* DSH-ANDROID-SESSION-PUBLISH-v1 */\nasync function __dshPublishLink() {}\n/* DSH-ANDROID-SESSION-PUBLISH-END */\n";
        String pristine = "import { link, lstat, open } from \"node:fs/promises\";\nimport { readdirSync } from \"node:fs\";\n"
                + "await internals.fs.link(staged, currentPath);\nawait link(tmp, finalPath);\n";
        String patched = SessionPersistencePatch.patch(pristine, helper);
        check(patched != null, "pristine rejected");
        check(patched.contains("__dshPublishLink(tmp, finalPath, link)"), "first materialization omitted");
        check(patched.contains("internals.fs.copyFile"), "migration injection lost");
        check(patched.equals(SessionPersistencePatch.patch(patched, helper)), "not idempotent");
        check(SessionPersistencePatch.patch(pristine + "await link(tmp, finalPath);", helper) == null, "duplicate accepted");
        check(SessionPersistencePatch.patch(pristine.replace("await link(tmp, finalPath);", ""), helper) == null, "missing materialization accepted");
        check(SessionPersistencePatch.patch(pristine, "invalid helper") == null, "invalid helper accepted");
        check(SessionPersistencePatch.patch(null, helper) == null, "null accepted");
        System.out.println("TOTAL: 8 pass / 0 fail");
    }
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
}
