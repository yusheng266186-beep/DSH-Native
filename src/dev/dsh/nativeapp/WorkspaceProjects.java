package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 命名项目与工作区路径规则；不依赖 Android，便于离线验证。 */
final class WorkspaceProjects {
    static final String DEFAULT = "";
    static final String PROJECTS_DIR = "projects";

    private WorkspaceProjects() { }

    /** 返回安全的项目名；不合法时返回 null。空串代表兼容旧用户的默认工作区。 */
    static String normalize(String raw) {
        if (raw == null) return DEFAULT;
        String value = raw.trim().replaceAll("\\s+", " ");
        if (value.length() == 0) return DEFAULT;
        if (value.length() > 48 || ".".equals(value) || "..".equals(value)) return null;
        if (value.startsWith(".")) return null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\' || c == ':') return null;
        }
        return value;
    }

    static String displayName(String normalized) {
        return normalized == null || normalized.length() == 0 ? "默认工作区" : normalized;
    }

    /** 命名项目统一放在 root/projects 下；默认项目继续使用 root，避免迁移旧文件。 */
    static File directory(File root, String normalized) {
        if (root == null) return null;
        String name = normalize(normalized);
        if (name == null) return null;
        if (name.length() == 0) return root;
        File candidate = new File(new File(root, PROJECTS_DIR), name);
        return contained(root, candidate) ? candidate : null;
    }

    static boolean create(File root, String rawName) {
        String name = normalize(rawName);
        if (name == null || name.length() == 0) return false;
        File dir = directory(root, name);
        return dir != null && (dir.isDirectory() || dir.mkdirs());
    }

    static List<String> list(File root) {
        List<String> out = new ArrayList<String>();
        if (root == null) return out;
        File base = new File(root, PROJECTS_DIR);
        File[] children = base.listFiles();
        if (children == null) return out;
        for (File child : children) {
            String name = normalize(child.getName());
            if (child.isDirectory() && name != null && name.length() > 0
                    && contained(base, child)) {
                out.add(name);
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static boolean contained(File root, File candidate) {
        if (root == null || candidate == null) return false;
        try {
            String base = root.getCanonicalPath();
            String child = candidate.getCanonicalPath();
            return child.equals(base) || child.startsWith(base + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
