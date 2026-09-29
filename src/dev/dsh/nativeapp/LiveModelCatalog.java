package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 上游实时目录与当前运行环境能力目录的纯逻辑对齐。 */
final class LiveModelCatalog {
    private LiveModelCatalog() { }

    static final class Entry {
        final String id;
        final String name;
        final boolean selectable;
        final boolean image;
        final boolean reasoning;

        Entry(String id, String name, boolean selectable, boolean image, boolean reasoning) {
            this.id = id;
            this.name = name;
            this.selectable = selectable;
            this.image = image;
            this.reasoning = reasoning;
        }
    }

    /**
     * 只以上游返回的 ID 决定可见列表；本地目录只补充能力和兼容状态，
     * 绝不能把上游没有返回的预置项混进界面。
     */
    static List<Entry> reconcile(List<String> upstream, List<ModelConfig.Model> configured) {
        Map<String, ModelConfig.Model> supported = new LinkedHashMap<String, ModelConfig.Model>();
        if (configured != null) {
            for (ModelConfig.Model model : configured) {
                if (model != null && ModelConfig.normalizeModel(model.id).length() > 0) {
                    supported.put(model.id, model);
                }
            }
        }
        Map<String, Entry> result = new LinkedHashMap<String, Entry>();
        if (upstream != null) {
            for (String raw : upstream) {
                String id = ModelConfig.normalizeModel(raw);
                if (id.length() == 0 || result.containsKey(id)) continue;
                ModelConfig.Model known = supported.get(id);
                result.put(id, new Entry(id, known == null ? id : known.name,
                        known != null, known != null && known.image,
                        known != null && known.reasoning));
            }
        }
        return new ArrayList<Entry>(result.values());
    }

    static boolean contains(List<Entry> entries, String model) {
        if (entries == null || model == null) return false;
        for (Entry entry : entries) {
            if (entry != null && entry.id.equals(model)) return true;
        }
        return false;
    }

    static boolean selectable(List<Entry> entries, String model) {
        if (entries == null || model == null) return false;
        for (Entry entry : entries) {
            if (entry != null && entry.selectable && entry.id.equals(model)) return true;
        }
        return false;
    }

    static int selectableCount(List<Entry> entries) {
        int count = 0;
        if (entries == null) return count;
        for (Entry entry : entries) if (entry != null && entry.selectable) count++;
        return count;
    }

    /**
     * 新选择必须来自本次凭据拉到的实时目录并受当前运行环境支持。
     * 非首次配置允许原样保存旧选择，避免临时断网把既有配置锁死。
     */
    static boolean canSave(ModelConfig.Selection baseline,
                           ModelConfig.Selection candidate,
                           boolean onboarding,
                           boolean catalogLoaded,
                           List<Entry> liveEntries) {
        if (candidate == null || !candidate.valid()) return false;
        if (catalogLoaded && selectable(liveEntries, candidate.model)) return true;
        return !onboarding && !catalogLoaded && sameRoute(baseline, candidate);
    }

    private static boolean sameRoute(ModelConfig.Selection left,
                                     ModelConfig.Selection right) {
        return left != null && right != null
                && left.provider.equals(right.provider)
                && left.model.equals(right.model);
    }
}
