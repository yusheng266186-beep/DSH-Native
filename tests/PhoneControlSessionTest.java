package dev.dsh.nativeapp;

public final class PhoneControlSessionTest {
    interface Action { void run() throws PhoneControlSession.Rejected; }
    private static void reject(String code, Action action) throws Exception {
        try {
            action.run();
        } catch (PhoneControlSession.Rejected error) {
            if (!code.equals(error.code)) throw new AssertionError(error.code, error);
            return;
        }
        throw new AssertionError("Expected " + code);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        PhoneControlSession session = new PhoneControlSession();
        reject("PHONE_CONTROL_DISABLED", () -> session.require(100));
        session.start(100);
        long epoch = session.epoch();
        String first = session.observe(105);
        session.check(first, 106);
        String second = session.observe(110);
        reject("STALE_SCREEN", () -> session.check(first, 111));
        session.invalidate();
        reject("STALE_SCREEN", () -> session.check(second, 112));
        check(session.current(epoch, 113), "Window changes do not cancel accepted gesture completion");
        String third = session.observe(114);
        reject("STALE_SCREEN", () -> session.check(third, 114 + PhoneControlSession.SNAPSHOT_MS + 1));
        session.pause(true);
        reject("PHONE_CONTROL_PAUSED", () -> session.observe(60200));
        check(!session.current(epoch, 60201), "Pause invalidates in-flight result");
        session.pause(false);
        reject("STALE_SCREEN", () -> session.check(third, 60202));
        String fourth = session.observe(60203);
        session.stop("screen-off");
        reject("PHONE_CONTROL_DISABLED", () -> session.check(fourth, 60204));
        session.start(61000);
        reject("STALE_SCREEN", () -> session.check(fourth, 61001));
        reject("PHONE_CONTROL_DISABLED", () -> session.require(61001 + PhoneControlSession.IDLE_MS + 1));
        check("idle-timeout".equals(session.reason()), "Idle stop reason");
        System.out.println("PhoneControlSessionTest passed: opt-in, stale snapshots, pause, stop, restart, idle timeout");
    }
}
