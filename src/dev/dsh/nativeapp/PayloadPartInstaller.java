package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Install within the caller's durable update/snapshot transaction. */
final class PayloadPartInstaller {
    interface Extractor { void unpack() throws Exception; }
    private PayloadPartInstaller() { }
    static int install(File runtime, List<String> removals, boolean dsh, Extractor extractor) throws Exception {
        int removed = 0;
        if (removals != null) for (String relative : removals) {
            if (!PayloadUpdate.isSafeRelativePath(relative)) throw new IOException("Invalid runtime removal");
            File target = new File(runtime, relative);
            if (!target.exists()) continue;
            String error = FileOps.delete(target, Collections.singletonList(runtime));
            if (error != null || target.exists()) throw new IOException("无法安全移除运行包文件: " + target.getName());
            removed++;
        }
        // Old manifests are cumulative: a path removed years ago may have been
        // reintroduced. Fresh archive contents always win over that old list.
        if (dsh) CoreUpgrade.pruneEmptyModules(runtime);
        extractor.unpack();
        return removed;
    }
}
