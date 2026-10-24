package dev.dsh.nativeapp;

/** Preserve create-only file writes when Android rejects hard links. */
final class FsWritePatch {
    private static final String TAG = "/* DSH-ANDROID-FILE-CREATE-v1 */";
    private FsWritePatch() { }
    static String patch(String source, String helper) {
        if (source == null || helper == null || !helper.startsWith("/* DSH-ANDROID-SESSION-PUBLISH-v1 */")) return null;
        if (source.startsWith(TAG)) return source;
        String anchor = "await linkFile(tempPath, absolutePath);";
        if (source.indexOf(anchor) < 0 || source.indexOf(anchor) != source.lastIndexOf(anchor)) return null;
        String imports = "import { copyFile as androidCopyFile } from 'node:fs/promises';\n"
                + "import { constants as androidFsConstants } from 'node:fs';\n";
        return TAG + "\n" + imports + helper.trim() + "\n" + source.replace(anchor,
                "await __dshPublishLink(tempPath, absolutePath, linkFile, internals.copyFile ?? androidCopyFile);");
    }
}
