package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可持久化的任务时间线：纯 Java、无 Android 依赖。
 *
 * <p>通知栏只适合回答「现在是什么状态」，不能回答「刚才发生了什么」。
 * 这个状态机保留最近任务的开始、结束、等待批准与断线恢复信息，并把仍在
 * 运行的记录在进程重建后标为 {@link #RECOVERING}，直到权威状态探针重新确认。
 *
 * <p>时间线不会把「回到空闲」写成「成功」。会话列表只能证明任务结束，
 * 无法证明结果是成功、失败还是用户主动停止，因此统一使用 {@link #FINISHED}。
 */
final class TaskTimeline {

    static final int RUNNING = 0;
    static final int AWAITING_APPROVAL = 1;
    static final int RECOVERING = 2;
    static final int FINISHED = 3;
    static final int INTERRUPTED = 4;

    static final int MAX_ENTRIES = 30;
    private static final String FORMAT = "v1";
    private static final int MAX_SERIALIZED_LENGTH = 32 * 1024;

    static final class Entry {
        private final long id;
        private String sessionId;
        private long startedAt;
        private long endedAt;
        private int state;
        private boolean connectionInterrupted;

        private Entry(long id, String sessionId, long startedAt, long endedAt,
                      int state, boolean connectionInterrupted) {
            this.id = id;
            this.sessionId = sanitizeSessionId(sessionId);
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.state = state;
            this.connectionInterrupted = connectionInterrupted;
        }

        long id() { return id; }
        String sessionId() { return sessionId; }
        long startedAt() { return startedAt; }
        long endedAt() { return endedAt; }
        int state() { return state; }
        boolean connectionInterrupted() { return connectionInterrupted; }
        boolean isActive() { return endedAt == 0L && isActiveState(state); }
        long durationAt(long now) {
            long end = endedAt > 0L ? endedAt : now;
            return Math.max(0L, end - startedAt);
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private long nextId = 1L;

    /** 应用一次权威会话状态；返回时间线是否发生变化。 */
    boolean onSessionState(int sessionState, String sessionId, long now) {
        if (now <= 0L) return false;
        if (sessionState == SessionStatus.RUNNING
                || sessionState == SessionStatus.AWAITING_APPROVAL) {
            int target = sessionState == SessionStatus.AWAITING_APPROVAL
                    ? AWAITING_APPROVAL : RUNNING;
            Entry active = active();
            if (active == null) {
                entries.add(new Entry(nextId++, sessionId, now, 0L, target, false));
                trim();
                return true;
            }
            boolean changed = false;
            String clean = sanitizeSessionId(sessionId);
            if (active.sessionId.length() == 0 && clean.length() > 0) {
                active.sessionId = clean;
                changed = true;
            }
            if (active.state != target) {
                active.state = target;
                changed = true;
            }
            return changed;
        }
        if (sessionState == SessionStatus.IDLE) {
            Entry active = active();
            if (active == null) return false;
            active.state = FINISHED;
            active.endedAt = Math.max(now, active.startedAt);
            return true;
        }
        if (sessionState == SessionStatus.UNKNOWN) {
            Entry active = active();
            if (active != null && active.state != RECOVERING) {
                active.state = RECOVERING;
                return true;
            }
        }
        return false;
    }

    /** 兼容显式任务事件；事件与会话探针重复时是幂等的。 */
    boolean onTaskEvent(String kind, String sessionId, long now) {
        if ("start".equals(kind)) {
            return onSessionState(SessionStatus.RUNNING, sessionId, now);
        }
        if (!"done".equals(kind)) return false;
        return onSessionState(SessionStatus.IDLE, sessionId, now);
    }

    /**
     * 标记连接状态。断线不会结束任务；恢复后也不猜测任务是否仍在运行，
     * 必须等下一条会话状态信号来确认。
     */
    boolean onConnectionState(int connectionState) {
        Entry active = active();
        if (active == null) return false;
        if (ConnectionRecovery.isProblem(connectionState)) {
            boolean changed = !active.connectionInterrupted || active.state != RECOVERING;
            active.connectionInterrupted = true;
            active.state = RECOVERING;
            return changed;
        }
        return false;
    }

    Entry active() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.isActive()) return entry;
        }
        return null;
    }

    /** 新到旧的只读快照，供原生任务中心展示。 */
    List<Entry> newestFirst() {
        ArrayList<Entry> copy = new ArrayList<Entry>(entries);
        Collections.reverse(copy);
        return Collections.unmodifiableList(copy);
    }

    int size() { return entries.size(); }

    /** 只清理已结束记录，绝不删除仍在恢复或运行的任务。 */
    boolean clearFinished() {
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!entries.get(i).isActive()) {
                entries.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    String serialize() {
        StringBuilder out = new StringBuilder(FORMAT).append('\n').append(nextId);
        for (Entry entry : entries) {
            out.append('\n')
                    .append(entry.id).append(',')
                    .append(entry.state).append(',')
                    .append(entry.startedAt).append(',')
                    .append(entry.endedAt).append(',')
                    .append(entry.connectionInterrupted ? '1' : '0').append(',')
                    .append(escape(entry.sessionId));
        }
        return out.toString();
    }

    /**
     * 从偏好设置恢复。损坏记录逐条忽略；未结束任务统一进入恢复中，避免把
     * 上次进程的瞬时状态直接当成当前事实。
     */
    static TaskTimeline restore(String raw, long now) {
        TaskTimeline timeline = new TaskTimeline();
        if (raw == null || raw.length() == 0 || raw.length() > MAX_SERIALIZED_LENGTH) {
            return timeline;
        }
        String[] lines = raw.split("\\n", -1);
        if (lines.length < 2 || !FORMAT.equals(lines[0])) return timeline;
        long parsedNext = parseLong(lines[1], 1L);
        long highest = 0L;
        Entry lastActive = null;
        for (int i = 2; i < lines.length; i++) {
            String[] fields = lines[i].split(",", 6);
            if (fields.length != 6) continue;
            long id = parseLong(fields[0], -1L);
            int state = (int) parseLong(fields[1], -1L);
            long started = parseLong(fields[2], -1L);
            long ended = parseLong(fields[3], -1L);
            if (id <= 0L || started <= 0L || ended < 0L || !validState(state)) continue;
            // 设备时间被调到未来时不丢记录，但不能让计时出现负数。
            if (now > 0L && started > now) started = now;
            if (ended > 0L && ended < started) ended = started;
            boolean interrupted = "1".equals(fields[4]);
            if (ended == 0L && !isActiveState(state)) continue;
            if (ended > 0L && isActiveState(state)) state = FINISHED;
            Entry entry = new Entry(id, unescape(fields[5]), started, ended,
                    state, interrupted);
            if (entry.isActive()) {
                // 若偏好设置里意外留下多个活动记录，只保留最新一个；旧记录
                // 标为中断，避免任务中心显示两个任务同时占用同一个计时器。
                if (lastActive != null) {
                    lastActive.state = INTERRUPTED;
                    lastActive.endedAt = Math.max(lastActive.startedAt, started);
                }
                entry.state = RECOVERING;
                lastActive = entry;
            }
            timeline.entries.add(entry);
            highest = Math.max(highest, id);
        }
        timeline.nextId = Math.max(Math.max(1L, parsedNext), highest + 1L);
        timeline.trim();
        return timeline;
    }

    static String label(int state, boolean english) {
        if (english) {
            switch (state) {
                case RUNNING: return "Running";
                case AWAITING_APPROVAL: return "Awaiting approval";
                case RECOVERING: return "Recovering status";
                case FINISHED: return "Finished";
                default: return "Interrupted";
            }
        }
        switch (state) {
            case RUNNING: return "运行中";
            case AWAITING_APPROVAL: return "等待批准";
            case RECOVERING: return "恢复状态中";
            case FINISHED: return "已结束";
            default: return "已中断";
        }
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    private static boolean isActiveState(int state) {
        return state == RUNNING || state == AWAITING_APPROVAL || state == RECOVERING;
    }

    private static boolean validState(int state) {
        return state >= RUNNING && state <= INTERRUPTED;
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static String sanitizeSessionId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() > 64 || clean.startsWith("<")) return "";
        return clean;
    }

    private static String escape(String value) {
        if (value == null || value.length() == 0) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') out.append("%25");
            else if (c == ',') out.append("%2C");
            else if (c == '\n') out.append("%0A");
            else if (c == '\r') out.append("%0D");
            else out.append(c);
        }
        return out.toString();
    }

    private static String unescape(String value) {
        if (value == null || value.length() == 0) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '%' && i + 2 < value.length()) {
                String code = value.substring(i + 1, i + 3);
                if ("25".equalsIgnoreCase(code)) { out.append('%'); i += 2; continue; }
                if ("2C".equalsIgnoreCase(code)) { out.append(','); i += 2; continue; }
                if ("0A".equalsIgnoreCase(code)) { out.append('\n'); i += 2; continue; }
                if ("0D".equalsIgnoreCase(code)) { out.append('\r'); i += 2; continue; }
            }
            out.append(value.charAt(i));
        }
        return out.toString();
    }
}
