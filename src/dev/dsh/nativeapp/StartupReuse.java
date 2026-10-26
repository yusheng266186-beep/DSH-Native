package dev.dsh.nativeapp;

/** Reuse only an environment previously prepared by this exact App and payload. */
final class StartupReuse {
    private StartupReuse() { }

    static String key(String version, String revisions, String workspace) {
        return version + "\n" + revisions + "\n" + workspace;
    }

    static boolean eligible(String expected, String prepared, String version, String assets,
                            boolean complete, boolean updatePending, boolean manifestTrusted) {
        return expected != null && !expected.isEmpty() && expected.equals(prepared)
                && version != null && !version.isEmpty() && version.equals(assets)
                && complete && !updatePending && manifestTrusted;
    }
}
