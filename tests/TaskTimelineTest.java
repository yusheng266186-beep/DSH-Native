package dev.dsh.nativeapp;

import java.util.List;

/** TaskTimeline 的状态转换、持久化与损坏输入回归。 */
public class TaskTimelineTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        long t0 = 1_000_000L;
        TaskTimeline line = new TaskTimeline();
        check("starts running", line.onSessionState(SessionStatus.RUNNING, "s,1", t0), "unchanged");
        TaskTimeline.Entry active = line.active();
        check("active created", active != null && active.state() == TaskTimeline.RUNNING, "missing");
        check("start retained", active != null && active.startedAt() == t0, "wrong start");
        line.onSessionState(SessionStatus.AWAITING_APPROVAL, "s,1", t0 + 5_000L);
        check("approval updates state without reset", line.active().state() == TaskTimeline.AWAITING_APPROVAL
                && line.active().startedAt() == t0, "wrong");

        line.onConnectionState(ConnectionRecovery.RETRYING);
        check("disconnect enters recovering", line.active().state() == TaskTimeline.RECOVERING, "wrong");
        check("disconnect remembered", line.active().connectionInterrupted(), "missing");
        line.onConnectionState(ConnectionRecovery.CONNECTED);
        check("connect does not guess running", line.active().state() == TaskTimeline.RECOVERING, "guessed");
        line.onSessionState(SessionStatus.RUNNING, "s,1", t0 + 9_000L);
        check("authoritative signal restores running", line.active().state() == TaskTimeline.RUNNING, "wrong");
        line.onSessionState(SessionStatus.IDLE, "", t0 + 60_000L);
        check("idle finishes active", line.active() == null, "still active");
        TaskTimeline.Entry done = line.newestFirst().get(0);
        check("finished duration", done.state() == TaskTimeline.FINISHED
                && done.durationAt(t0 + 90_000L) == 60_000L, "wrong");
        check("label does not claim success", "已结束".equals(TaskTimeline.label(done.state(), false)), "wrong");

        line.onSessionState(SessionStatus.RUNNING, "next", t0 + 100_000L);
        String saved = line.serialize();
        TaskTimeline restored = TaskTimeline.restore(saved, t0 + 130_000L);
        check("history round trips", restored.size() == 2, String.valueOf(restored.size()));
        check("active restored as recovering", restored.active() != null
                && restored.active().state() == TaskTimeline.RECOVERING, "wrong");
        check("active start survives process death", restored.active().startedAt() == t0 + 100_000L, "reset");
        check("escaped session id round trips", "s,1".equals(restored.newestFirst().get(1).sessionId()),
                restored.newestFirst().get(1).sessionId());

        restored.clearFinished();
        check("clear keeps active", restored.size() == 1 && restored.active() != null, "removed active");
        check("second clear is idempotent", !restored.clearFinished(), "changed");

        TaskTimeline bad = TaskTimeline.restore("v1\nnot-a-number\nbroken\n1,99,2,0,0,x", t0);
        check("corrupt data ignored", bad.size() == 0, String.valueOf(bad.size()));
        check("wrong version ignored", TaskTimeline.restore("v2\n1", t0).size() == 0, "accepted");
        check("oversized input ignored", TaskTimeline.restore(repeat('x', 40_000), t0).size() == 0, "accepted");

        TaskTimeline many = new TaskTimeline();
        for (int i = 0; i < 40; i++) {
            long start = t0 + i * 2_000L;
            many.onSessionState(SessionStatus.RUNNING, "s" + i, start);
            many.onSessionState(SessionStatus.IDLE, "", start + 1_000L);
        }
        check("history bounded", many.size() == TaskTimeline.MAX_ENTRIES, String.valueOf(many.size()));
        List<TaskTimeline.Entry> recent = many.newestFirst();
        check("newest first", recent.get(0).startedAt() > recent.get(recent.size() - 1).startedAt(), "order");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
