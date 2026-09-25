package dev.dsh.nativeapp;

/** SessionRecovery 的离线测试。 */
public class SessionRecoveryTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + name); }
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        String screenshot = "resume failed for session session-1: "
                + "SessionPersistenceCorruptionError: stored log is corrupt: "
                + "TypeError: internals.fs.rename is not a function";
        check("recognizes screenshot error", SessionRecovery.isFailure(screenshot), screenshot);
        check("recognizes lower-case variant",
                SessionRecovery.isFailure("stored log is corrupt in session.v3.jsonl.zstd"), "not recognized");
        check("recognizes resume wording",
                SessionRecovery.isFailure("Failed to resume session session-2"), "not recognized");
        check("ignores ordinary session log",
                !SessionRecovery.isFailure("session list loaded: 3 items"), "false positive");
        check("title is user-facing", SessionRecovery.title().contains("恢复"), SessionRecovery.title());
        check("detail avoids implementation error", !SessionRecovery.detail().contains("internals.fs"),
                SessionRecovery.detail());
        check("overlay is idempotent", SessionRecovery.overlayScript().contains("__dshRecovery"), "missing guard");
        check("overlay has retry action", SessionRecovery.overlayScript().contains("dsh-recovery://retry"), "missing");
        check("overlay has new session action", SessionRecovery.overlayScript().contains("dsh-recovery://new"), "missing");
        check("overlay has diagnostics action", SessionRecovery.overlayScript().contains("dsh-recovery://logs"), "missing");
        check("new session script has fallback signal",
                SessionRecovery.newSessionScript().contains("new-session-not-found"), "missing");
        check("dismiss script removes overlay", SessionRecovery.dismissScript().contains("__dshRecovery"), "missing");
        check("DOM watcher has recovery marker",
                SessionRecovery.domWatcherScript().contains("dsh-session-recovery-dom"), "missing");
        System.out.println("TOTAL pass=" + pass + " fail=" + fail);
        if (fail > 0) throw new AssertionError("SessionRecoveryTest failed");
    }
}
