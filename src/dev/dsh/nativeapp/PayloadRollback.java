package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 运行环境回滚记录的纯逻辑格式与边界检查。 */
final class PayloadRollback {
    static final String MAGIC = "DSH-PAYLOAD-ROLLBACK-V1";
    static final String JOURNAL_FILE = "journal.txt";
    static final long RESERVE_BYTES = 32L * 1024L * 1024L;

    private PayloadRollback() { }

    static final class Target {
        final String name;
        final String archive;
        final long size;
        final String sha256;

        Target(String name, String archive, long size, String sha256) {
            this.name = name;
            this.archive = archive;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    static final class Journal {
        final long createdAt;
        final List<Target> targets;
        final Set<String> revisions;

        Journal(long createdAt, List<Target> targets, Set<String> revisions) {
            this.createdAt = createdAt;
            this.targets = targets == null
                    ? Collections.<Target>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Target>(targets));
            this.revisions = revisions == null
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new HashSet<String>(revisions));
        }
    }

    static boolean isSafeTarget(String name) {
        return "dsh".equals(name) || "tools".equals(name);
    }

    static String snapshotName(String target) {
        return isSafeTarget(target) ? target + ".tar.zst" : null;
    }

    static boolean isSafeRevision(String value) {
        if (value == null || value.length() == 0 || value.length() > 180) return false;
        int eq = value.lastIndexOf('=');
        if (eq <= 0 || eq == value.length() - 1) return false;
        String name = value.substring(0, eq);
        if (!PayloadUpdate.isSafeAssetName(name)) return false;
        try {
            int revision = Integer.parseInt(value.substring(eq + 1));
            return revision >= 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String serialize(Journal journal) {
        if (journal == null || journal.createdAt <= 0L || journal.targets.isEmpty()) return null;
        StringBuilder out = new StringBuilder(MAGIC).append('\n');
        out.append("created=").append(journal.createdAt).append('\n');
        HashSet<String> seen = new HashSet<String>();
        for (Target target : journal.targets) {
            if (target == null || !isSafeTarget(target.name)
                    || !snapshotName(target.name).equals(target.archive)
                    || target.size <= 0L || !PayloadUpdate.isSha256(target.sha256)
                    || !seen.add(target.name)) return null;
            out.append("target=").append(target.name).append('|')
                    .append(target.archive).append('|').append(target.size).append('|')
                    .append(target.sha256.toLowerCase(java.util.Locale.ROOT)).append('\n');
        }
        ArrayList<String> revisions = new ArrayList<String>();
        for (String revision : journal.revisions) {
            if (!isSafeRevision(revision)) return null;
            revisions.add(revision);
        }
        Collections.sort(revisions);
        for (String revision : revisions) out.append("revision=").append(revision).append('\n');
        return out.toString();
    }

    static Journal parse(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > 64 * 1024) return null;
        String[] lines = raw.replace("\r", "").split("\n");
        if (lines.length < 3 || !MAGIC.equals(lines[0])) return null;
        long created = 0L;
        ArrayList<Target> targets = new ArrayList<Target>();
        HashSet<String> names = new HashSet<String>();
        HashSet<String> revisions = new HashSet<String>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            if (line.startsWith("created=")) {
                if (created != 0L) return null;
                try { created = Long.parseLong(line.substring(8)); }
                catch (Throwable ignored) { return null; }
                if (created <= 0L) return null;
            } else if (line.startsWith("target=")) {
                String[] fields = line.substring(7).split("\\|", -1);
                if (fields.length != 4 || !isSafeTarget(fields[0])
                        || !snapshotName(fields[0]).equals(fields[1])
                        || !names.add(fields[0]) || !PayloadUpdate.isSha256(fields[3])) {
                    return null;
                }
                long size;
                try { size = Long.parseLong(fields[2]); }
                catch (Throwable ignored) { return null; }
                if (size <= 0L) return null;
                targets.add(new Target(fields[0], fields[1], size, fields[3]));
            } else if (line.startsWith("revision=")) {
                String value = line.substring(9);
                if (!isSafeRevision(value) || !revisions.add(value)) return null;
            } else {
                return null;
            }
        }
        if (created <= 0L || targets.isEmpty()) return null;
        return new Journal(created, targets, revisions);
    }

    static long requiredFreeBytes(long updateBytes, long liveTargetBytes) {
        return saturatedAdd(saturatedAdd(Math.max(0L, updateBytes),
                Math.max(0L, liveTargetBytes)), RESERVE_BYTES);
    }

    private static long saturatedAdd(long a, long b) {
        if (a >= Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
