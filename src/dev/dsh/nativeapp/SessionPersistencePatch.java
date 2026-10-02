package dev.dsh.nativeapp;

/** Reentrant compatibility patch for both first materialization and migration. */
final class SessionPersistencePatch {
    private static final String TAG = "/* DSH-ANDROID-SESSION-PUBLISH-v1 */";
    private static final String END = "/* DSH-ANDROID-SESSION-PUBLISH-END */";
    private static final String LEGACY = "try { await internals.fs.link(staged, currentPath); } catch (error) {\n"
            + "            if (!['EPERM', 'EXDEV', 'ENOSYS', 'EOPNOTSUPP'].includes(error?.code)) throw error;\n"
            + "            await androidCopyFile(staged, currentPath, androidFsConstants.COPYFILE_EXCL);\n"
            + "        }";

    private SessionPersistencePatch() { }

    static String patch(String source, String helper) {
        if (source == null || helper == null || !helper.startsWith(TAG) || !helper.contains(END)) return null;
        String out = source;
        if (out.startsWith(TAG)) {
            int end = out.indexOf(END);
            if (end < 0) return null;
            out = out.substring(end + END.length());
            if (out.startsWith("\n")) out = out.substring(1);
            out = out.replace("await __dshPublishLink(staged, currentPath, internals.fs.link, internals.fs.copyFile);",
                    "await internals.fs.link(staged, currentPath);");
            out = out.replace("await __dshPublishLink(tmp, finalPath, link);", "await link(tmp, finalPath);");
        }
        out = out.replace(LEGACY, "await internals.fs.link(staged, currentPath);");
        if (!out.contains("copyFile as androidCopyFile"))
            out = once(out, "import { link, lstat,", "import { copyFile as androidCopyFile, link, lstat,");
        if (out == null) return null;
        if (!out.contains("constants as androidFsConstants"))
            out = once(out, "import { readdirSync } from \"node:fs\";",
                    "import { readdirSync, constants as androidFsConstants } from \"node:fs\";");
        out = once(out, "await internals.fs.link(staged, currentPath);",
                "await __dshPublishLink(staged, currentPath, internals.fs.link, internals.fs.copyFile);");
        out = once(out, "await link(tmp, finalPath);", "await __dshPublishLink(tmp, finalPath, link);");
        return out == null ? null : helper.trim() + "\n" + out;
    }

    private static String once(String source, String before, String after) {
        if (source == null) return null;
        int at = source.indexOf(before);
        if (at < 0 || source.indexOf(before, at + before.length()) >= 0) return null;
        return source.substring(0, at) + after + source.substring(at + before.length());
    }
}
