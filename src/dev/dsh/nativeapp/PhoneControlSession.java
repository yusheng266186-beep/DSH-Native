package dev.dsh.nativeapp;

import java.util.UUID;

/** A user-started, expiring control session. No model operation can enable it. */
final class PhoneControlSession {
    static final long IDLE_MS = 180000L, SNAPSHOT_MS = 60000L;
    static final class Rejected extends Exception {
        final String code;
        Rejected(String code, String message) { super(message); this.code = code; }
    }
    private boolean enabled, paused;
    private long epoch, touched, observed;
    private String snapshot = "", reason = "not-started";
    synchronized void start(long now) { enabled = true; paused = false; touched = now; reason = ""; epoch++; invalidate(); }
    synchronized long stop(String why) { enabled = false; paused = false; reason = why; epoch++; invalidate(); return epoch; }
    synchronized void pause(boolean value) { if (enabled) { paused = value; epoch++; invalidate(); } }
    synchronized void invalidate() { snapshot = ""; }
    synchronized long epoch() { return epoch; }
    synchronized boolean enabled(long now) { expire(now); return enabled; }
    synchronized boolean paused() { return paused; }
    synchronized String reason() { return reason; }
    private void expire(long now) { if (enabled && (now < touched || now - touched > IDLE_MS)) stop("idle-timeout"); }
    synchronized void require(long now) throws Rejected {
        expire(now);
        if (!enabled) throw new Rejected("PHONE_CONTROL_DISABLED", "Start Phone control in DSH Settings > Data & extensions");
        if (paused) throw new Rejected("PHONE_CONTROL_PAUSED", "Resume using the floating control or DSH settings");
        touched = now;
    }
    synchronized String observe(long now) throws Rejected {
        require(now); invalidate(); observed = now; snapshot = UUID.randomUUID().toString().replace("-", "").substring(0, 16); return snapshot;
    }
    synchronized void check(String value, long now) throws Rejected {
        require(now);
        if (snapshot.isEmpty() || !snapshot.equals(value) || now < observed || now - observed > SNAPSHOT_MS)
            throw new Rejected("STALE_SCREEN", "Read the current screen again before acting");
    }
    synchronized boolean current(long value, long now) { expire(now); return enabled && !paused && value == epoch; }
}
