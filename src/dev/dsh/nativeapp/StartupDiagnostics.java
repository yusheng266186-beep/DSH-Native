package dev.dsh.nativeapp;

/** Optional capability inspection never controls core reuse or first-frame readiness. */
final class StartupDiagnostics {
    static final long RETRY_MS = 60L * 60 * 1000;
    private StartupDiagnostics() { }
    static boolean due(String expected, String passed, String attempted, long attemptedAt, long now) {
        if (expected == null || expected.isEmpty() || expected.equals(passed)) return false;
        if (!expected.equals(attempted) || attemptedAt <= 0 || now < attemptedAt) return true;
        return now - attemptedAt >= RETRY_MS;
    }
}
