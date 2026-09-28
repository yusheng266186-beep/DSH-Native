package dev.dsh.nativeapp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 项目模型覆盖的可恢复文本格式；不依赖 Android。 */
final class ProjectModelSettings {
    static final String FILE_NAME = ".native-project-models";
    private static final String MAGIC = "DSHPM1";
    private static final int MAX_PROJECTS = 100;

    private ProjectModelSettings() { }

    static final class State {
        ModelConfig.Selection global;
        final Map<String, ModelConfig.Selection> projects =
                new LinkedHashMap<String, ModelConfig.Selection>();
    }

    static State parse(String text) {
        State state = new State();
        if (text == null || text.length() == 0) return state;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) return state;
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\\t", -1);
            try {
                if (parts.length == 4 && "G".equals(parts[0])) {
                    ModelConfig.Selection value = selection(parts, 1);
                    if (value.valid()) state.global = value;
                } else if (parts.length == 5 && "P".equals(parts[0])
                        && state.projects.size() < MAX_PROJECTS) {
                    String project = decode(parts[1]);
                    String normalized = WorkspaceProjects.normalize(project);
                    ModelConfig.Selection value = selection(parts, 2);
                    if (normalized != null && value.valid()) state.projects.put(normalized, value);
                }
            } catch (Throwable ignored) { }
        }
        return state;
    }

    static String serialize(State state) {
        StringBuilder out = new StringBuilder(MAGIC).append('\n');
        if (state != null && state.global != null && state.global.valid()) {
            append(out, "G", null, state.global);
        }
        if (state != null) {
            int count = 0;
            for (Map.Entry<String, ModelConfig.Selection> entry : state.projects.entrySet()) {
                if (count >= MAX_PROJECTS) break;
                String project = WorkspaceProjects.normalize(entry.getKey());
                ModelConfig.Selection selection = entry.getValue();
                if (project == null || selection == null || !selection.valid()) continue;
                append(out, "P", project, selection);
                count++;
            }
        }
        return out.toString();
    }

    static ModelConfig.Selection effective(State state, String project,
                                           ModelConfig.Selection fallback) {
        String normalized = WorkspaceProjects.normalize(project);
        if (state != null && normalized != null) {
            ModelConfig.Selection override = state.projects.get(normalized);
            if (override != null && override.valid()) return override;
            if (state.global != null && state.global.valid()) return state.global;
        }
        return fallback;
    }

    static void setGlobal(State state, ModelConfig.Selection selection) {
        if (state == null || selection == null || !selection.valid()) {
            throw new IllegalArgumentException("invalid global model");
        }
        state.global = selection;
    }

    static void setOverride(State state, String project, ModelConfig.Selection selection) {
        String normalized = WorkspaceProjects.normalize(project);
        if (state == null || normalized == null || selection == null || !selection.valid()) {
            throw new IllegalArgumentException("invalid project model");
        }
        state.projects.put(normalized, selection);
    }

    static void clearOverride(State state, String project) {
        String normalized = WorkspaceProjects.normalize(project);
        if (state != null && normalized != null) state.projects.remove(normalized);
    }

    static boolean hasOverride(State state, String project) {
        String normalized = WorkspaceProjects.normalize(project);
        return state != null && normalized != null && state.projects.containsKey(normalized);
    }

    static String readFile(File file) throws IOException {
        if (file == null || !file.isFile()) return "";
        if (file.length() > 2 * 1024 * 1024) throw new IOException("configuration file too large");
        FileInputStream input = new FileInputStream(file);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        } finally {
            input.close();
        }
        return new String(output.toByteArray(), "UTF-8");
    }

    /** 同目录临时文件提交；正常 Android/Linux 路径使用原子 rename。 */
    static void writeFileAtomic(File target, String text) throws IOException {
        if (target == null) throw new IOException("missing configuration path");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create configuration directory");
        }
        File temp = new File(parent, target.getName() + ".tmp");
        File backup = new File(parent, target.getName() + ".bak");
        FileOutputStream output = new FileOutputStream(temp);
        try {
            output.write((text == null ? "" : text).getBytes("UTF-8"));
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (temp.renameTo(target)) {
            if (backup.exists()) backup.delete();
            return;
        }
        if (backup.exists() && !backup.delete()) {
            temp.delete();
            throw new IOException("cannot clear configuration backup");
        }
        boolean movedOld = !target.exists() || target.renameTo(backup);
        if (!movedOld || !temp.renameTo(target)) {
            if (movedOld && backup.exists()) backup.renameTo(target);
            temp.delete();
            throw new IOException("cannot commit configuration file");
        }
        if (backup.exists()) backup.delete();
    }

    private static ModelConfig.Selection selection(String[] parts, int offset) throws Exception {
        return new ModelConfig.Selection(decode(parts[offset]), decode(parts[offset + 1]),
                decode(parts[offset + 2]));
    }

    private static void append(StringBuilder out, String type, String project,
                               ModelConfig.Selection selection) {
        out.append(type).append('\t');
        if (project != null) out.append(encode(project)).append('\t');
        out.append(encode(selection.provider)).append('\t')
                .append(encode(selection.model)).append('\t')
                .append(encode(selection.effort)).append('\n');
    }

    private static String encode(String value) {
        try {
            byte[] bytes = value.getBytes("UTF-8");
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int n = b & 255;
                out.append(Character.forDigit((n >>> 4) & 15, 16));
                out.append(Character.forDigit(n & 15, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String decode(String value) throws Exception {
        if (value == null || (value.length() & 1) != 0) throw new IllegalArgumentException("bad hex");
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length() / 2);
        for (int i = 0; i < value.length(); i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("bad hex");
            out.write((high << 4) | low);
        }
        return new String(out.toByteArray(), "UTF-8");
    }
}
