package dev.dsh.nativeapp;

/** A user-requested recording may start once, after a bounded stable foreground. */
final class VoiceStartGate {
    static final int WAIT = 0, START = 1, EXPIRED = 2;
    static final long STABLE_MS = 250L, TIMEOUT_MS = 4000L;
    private long requestedAt, readyAt = -1L;
    private boolean finished = true;

    void reset(long now) { requestedAt = now; readyAt = -1L; finished = false; }

    int poll(long now, boolean foregroundReady) {
        if (finished) return EXPIRED;
        if (now < requestedAt || now - requestedAt >= TIMEOUT_MS) {
            finished = true; return EXPIRED;
        }
        if (!foregroundReady) { readyAt = -1L; return WAIT; }
        if (readyAt < 0L) readyAt = now;
        if (now - readyAt < STABLE_MS) return WAIT;
        finished = true; return START;
    }

    void cancel() { finished = true; readyAt = -1L; }
}
