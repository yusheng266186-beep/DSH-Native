package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provider-scoped effort declarations; model availability still comes from /models. */
final class ModelReasoning {
    static final String UPSTREAM = "# dsh-native-reasoning-source: upstream";
    static final String[] LEVELS = {"off", "minimal", "low", "medium", "high", "xhigh", "max"};
    private static final Map<String, String> BUDDY = new LinkedHashMap<String, String>();

    static {
        // Declared model metadata from CodeBuddy CLI 2.174.0; max remains an explicit request.
        known("default-model", "");
        known("fast-model", "medium");
        known("balanced-model", "medium");
        known("primary-model", "high");
        known("deep-model", "");
        known("deepseek-v4.1-flash", "high");
        known("gpt-6-astra", "low,medium,high,xhigh,max");
        known("hy4-preview", "high");
        known("hy3", "low,high");
        known("kimi-k2.8-preview", "low,high,max");
        known("gpt-5.6-sol", "low,medium,high,xhigh,max");
        known("gpt-5.6-terra", "low,medium,high,xhigh,max");
        known("gpt-5.6-luna", "low,medium,high,xhigh,max");
        known("gpt-5.5", "low,medium,high,xhigh");
        known("gpt-5.4", "low,medium,high,xhigh");
        known("gemini-3.5-flash", "medium");
        known("glm-5.3-flash", "low,high,max");
        known("glm-5.3", "low,high,max");
        known("glm-5.2", "high,xhigh");
        known("kimi-k3", "medium");
        known("kimi-k2.6", "medium");
    }

    private ModelReasoning() { }

    private static void known(String id, String levels) {
        BUDDY.put(id.toLowerCase(Locale.ROOT), fromLevels(Arrays.asList(levels.split(","))));
    }

    /** User-requested override: every model offers literal max, including unknown/automatic models. */
    static String declaration(String provider, String id, String details) {
        String declared = supportedDeclaration(provider, id, details);
        Map<String, String> map = declared == null || "false".equals(declared)
                ? new LinkedHashMap<String, String>() : parseMap(declared);
        // Keep the provider-default path for models that previously had no selector.
        if (map.isEmpty()) map.put("off", "null");
        map.put("max", "max");
        return mapDeclaration(map);
    }

    /** Actual declarations remain separate from the user's forced request option. */
    static String supportedDeclaration(String provider, String id, String details) {
        if (ModelConfig.DEEPSEEK.equals(provider) || ModelConfig.DEEPSEEK_ACCOUNT.equals(provider)) {
            // The official /models lists thinking levels, while this adapter also provides Off.
            return "{ off: off, low: low, high: high, max: max }";
        }
        String local = field(details);
        if (details != null && details.contains(UPSTREAM) && local != null) return local;
        if (ModelConfig.CODEBUDDY.equals(provider)) {
            String official = BUDDY.get(id.toLowerCase(Locale.ROOT));
            if (official != null) return official;
        }
        return local;
    }

    static LiveModelCatalog.Entry enrich(String provider, LiveModelCatalog.Entry entry) {
        String declared = declaration(provider, entry.id, entry.details);
        if (declared == null) return entry;
        return new LiveModelCatalog.Entry(entry.id, entry.name, entry.selectable, entry.image,
                !"false".equals(declared), entry.imageKnown, replaceField(entry.details, declared));
    }

    static List<String> choices(String provider, String id, String details) {
        String declared = declaration(provider, id, details);
        List<String> result = new ArrayList<String>();
        if (declared == null || "false".equals(declared)) return result;
        Map<String, String> map = parseMap(declared);
        for (String level : LEVELS) if (map.containsKey(level)) result.add(level);
        return result;
    }

    /** Keep valid user choices; an unsupported old choice falls back to a supported daily level. */
    static String preferred(List<String> choices, String requested) {
        if (choices.contains(requested)) return requested;
        if (choices.contains("high")) return "high";
        if (choices.contains("medium")) return "medium";
        return choices.isEmpty() ? "off" : choices.get(0);
    }

    static ModelConfig.Selection normalizeSelection(String yaml, ModelConfig.Selection selected) {
        String details = "";
        for (ModelConfig.Model model : ModelConfig.modelsForProvider(yaml, selected.provider))
            if (model.id.equals(selected.model)) { details = model.details; break; }
        List<String> levels = choices(selected.provider, selected.model, details);
        return new ModelConfig.Selection(selected.provider, selected.model, preferred(levels, selected.effort));
    }

    /** Structured upstream lists/maps, including DeepSeek effort.supported_levels. */
    static String upstreamDeclaration(Map<?, ?> item) {
        Object value = item.get("reasoningEfforts");
        if (value == null) value = item.get("reasoning_efforts");
        if (value == null) value = item.get("supported_reasoning_efforts");
        if (value == null && item.get("effort") instanceof Map)
            value = ((Map<?, ?>) item.get("effort")).get("supported_levels");
        if (value == null && item.get("reasoning") instanceof Map) {
            Map<?, ?> reasoning = (Map<?, ?>) item.get("reasoning");
            value = reasoning.get("efforts");
            if (value == null) value = reasoning.get("supported_levels");
        }
        if (value == null && item.get("capabilities") instanceof Map) {
            Map<?, ?> caps = (Map<?, ?>) item.get("capabilities");
            value = caps.get("reasoningEfforts");
            if (value == null) value = caps.get("reasoning_efforts");
        }
        if (value instanceof Boolean) return Boolean.FALSE.equals(value) ? "false" : null;
        if (value instanceof List) {
            List<String> levels = new ArrayList<String>();
            for (Object raw : (List<?>) value) {
                Object level = raw instanceof Map ? ((Map<?, ?>) raw).get("id") : raw;
                if (!(level instanceof String) || !validLevel((String) level)) return null;
                if (!levels.contains(level)) levels.add((String) level);
            }
            return fromLevels(levels);
        }
        if (value instanceof Map) {
            Map<String, String> map = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String) || !validLevel((String) entry.getKey())) return null;
                String key = (String) entry.getKey();
                Object wire = entry.getValue();
                if (wire == null && !"off".equals(key)) return null;
                if (wire != null && (!(wire instanceof String) || !((String) wire).matches("[a-z][a-z0-9_-]{0,31}"))) return null;
                map.put(key, wire == null ? "null" : (String) wire);
            }
            return mapDeclaration(map);
        }
        if (Boolean.FALSE.equals(item.get("reasoning"))) return "false";
        return null;
    }

    private static boolean validLevel(String value) {
        return Arrays.asList(LEVELS).contains(value);
    }

    private static String fromLevels(List<String> levels) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String level : levels) if (validLevel(level)) map.put(level, level);
        return mapDeclaration(map);
    }

    private static String mapDeclaration(Map<String, String> map) {
        boolean thinking = false;
        for (String key : map.keySet()) if (!"off".equals(key)) thinking = true;
        if (!thinking) return "false";
        StringBuilder out = new StringBuilder("{ ");
        for (String level : LEVELS) if (map.containsKey(level)) {
            if (out.length() > 2) out.append(", ");
            out.append(level).append(": ").append(map.get(level));
        }
        return out.append(" }").toString();
    }

    /** Reads only the reasoning field; later structured metadata overrides an older field. */
    static String field(String details) {
        if (details == null) return null;
        String field = null;
        boolean nested = false;
        for (String line : details.split("\n")) {
            if (line.startsWith("reasoningEfforts:")) {
                field = line.substring("reasoningEfforts:".length()).trim();
                nested = field.length() == 0;
            } else if (nested && line.startsWith(" ") && line.trim().length() > 0) {
                field += line.trim() + ", ";
            } else if (line.length() > 0 && !line.startsWith(" ")) nested = false;
        }
        if ("false".equals(field)) return field;
        if (field == null || field.length() == 0) return null;
        Map<String, String> map = parseMap(field);
        return map.isEmpty() ? null : mapDeclaration(map);
    }

    private static Map<String, String> parseMap(String declaration) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        Matcher matcher = Pattern.compile("(?:^|[,{\\s])(?:[\"']?)(off|minimal|low|medium|high|xhigh|max)(?:[\"']?)\\s*:\\s*(?:[\"']?)([a-z][a-z0-9_-]*)(?:[\"']?)").matcher(declaration);
        while (matcher.find()) map.put(matcher.group(1), matcher.group(2));
        return map;
    }

    static String replaceField(String details, String declaration) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : (details == null ? "" : details).split("\n")) {
            if (line.startsWith("reasoningEfforts:")) { skipping = true; continue; }
            if (skipping && line.startsWith(" ")) continue;
            skipping = false;
            if (line.length() > 0) out.append(line).append('\n');
        }
        if (declaration != null) out.append("reasoningEfforts: ").append(declaration).append('\n');
        return out.toString();
    }
}
