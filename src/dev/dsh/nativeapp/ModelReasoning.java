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
    private static final Map<String, String> COMMAND = new LinkedHashMap<String, String>();

    static {
        // Generated from the Provider API effort map and known IDs in command-code@1.72.4.
        // BEGIN OFFICIAL SNAPSHOT
        known("claude-sonnet-5-5", "low,medium,high,xhigh,max");
        known("claude-sonnet-5", "low,medium,high,xhigh,max");
        known("claude-sonnet-4-6", "low,medium,high,xhigh,max");
        known("claude-fable-5-1", "low,medium,high,xhigh,max");
        known("claude-fable-5", "low,medium,high,xhigh,max");
        known("claude-opus-5-5", "low,medium,high,xhigh,max");
        known("claude-opus-5", "low,medium,high,xhigh,max");
        known("claude-opus-4-8", "low,medium,high,xhigh,max");
        known("claude-opus-4-7", "low,medium,high,xhigh,max");
        known("claude-haiku-4-5-20251001", "");
        known("gpt-6-astra", "low,medium,high,xhigh,max");
        known("gpt-6.1-sol", "low,medium,high,xhigh,max");
        known("gpt-6-sol", "low,medium,high,xhigh,max");
        known("gpt-6-luna", "low,medium,high,xhigh,max");
        known("gpt-5.6-sol", "low,medium,high,xhigh,max");
        known("gpt-5.6-terra", "low,medium,high,xhigh,max");
        known("gpt-5.6-luna", "low,medium,high,xhigh,max");
        known("gpt-5.5", "low,medium,high,xhigh");
        known("gpt-5.4", "low,medium,high,xhigh");
        known("gpt-5.3-codex", "low,medium,high,xhigh");
        known("gpt-5.4-mini", "low,medium,high");
        known("MiniMaxAI/MiniMax-M3-Free", "low,medium,high");
        known("moonshotai/Kimi-K3", "low,high,max");
        known("thinkingmachines/inkling", "");
        known("thinkingmachines/inkling-small", "");
        known("deepseek/deepseek-v4-pro", "high,max");
        known("deepseek/deepseek-v4-flash", "high,max");
        known("deepseek/deepseek-v4-flash-vision-exp", "high,max");
        known("deepseek/deepseek-v4-flash-fast", "low,high,max");
        known("deepseek/deepseek-v4.1-flash", "low,high,max");
        known("deepseek/deepseek-v4.1-flash-fast", "low,high,max");
        known("moonshotai/Kimi-K2.7-Code", "");
        known("moonshotai/Kimi-K2.7-Code-Highspeed", "");
        known("moonshotai/Kimi-K2.6", "");
        known("moonshotai/Kimi-K2.5", "");
        known("zai-org/GLM-5.3", "low,high,max");
        known("z-ai/glm-5.3-flash", "low,high,max");
        known("z-ai/glm-5.3-flashx", "low,high,max");
        known("zai-org/GLM-5.2", "high,max");
        known("zai-org/GLM-5.2-Fast", "");
        known("zai-org/GLM-5.1", "");
        known("zai-org/GLM-5", "");
        known("MiniMaxAI/MiniMax-M3", "low,medium,high");
        known("MiniMaxAI/MiniMax-M2.7", "");
        known("minimax/minimax-m3-free", "low,medium,high");
        known("minimax/minimax-m2.7-free", "");
        known("MiniMaxAI/MiniMax-M2.5", "");
        known("xiaomi/mimo-v2.6-pro", "");
        known("xiaomi/mimo-v2.6-pro-ultraspeed", "");
        known("xiaomi/mimo-v2.6-flash", "");
        known("xiaomi/mimo-v2.5-pro", "");
        known("xiaomi/mimo-v2.5", "");
        known("Qwen/Qwen3.6-Max-Preview", "");
        known("Qwen/Qwen3.6-Plus", "");
        known("Qwen/Qwen3.7-Max", "");
        known("Qwen/Qwen3.7-Plus", "");
        known("Qwen/Qwen3.8-Omni-Flash", "low,medium,xhigh");
        known("Qwen/Qwen3.8-Max-0902", "low,medium,xhigh");
        known("Qwen/Qwen3.8-Max", "low,medium,xhigh");
        known("Qwen/Qwen3.8-27B", "low,medium,xhigh");
        known("Qwen/Qwen3.8-Flash", "low,medium,xhigh");
        known("Qwen/Qwen3.7-Flash", "");
        known("meituan/LongCat-2.0", "");
        known("meituan/LongCat-2.0:free", "");
        known("stepfun/Step-5-Preview", "low,medium,high");
        known("stepfun/Step-3.7-Flash", "");
        known("stepfun/Step-3.5-Flash", "");
        known("tencent/hy4-preview", "low,medium,high");
        known("tencent/hy3-paid", "");
        known("tencent/Hy3", "");
        known("google/gemini-3.8-flash", "low,medium,high");
        known("google/gemini-3.7-flash", "low,medium,high");
        known("google/gemini-3.6-flash", "low,medium,high");
        known("google/gemini-3.5-flash", "low,medium,high");
        known("google/gemini-3.5-flash-lite", "low,medium,high");
        known("google/gemini-3.1-flash-lite", "low,medium,high");
        known("sakana/fugu-ultra", "high,xhigh");
        known("xai/grok-4.5", "low,medium,high");
        known("xai/grok-4.6", "low,medium,high,xhigh");
        known("xai/grok-4.7", "low,medium,high,xhigh");
        known("meta/muse-spark-1.1", "low,medium,high,xhigh");
        known("meta/muse-spark-1.2", "low,medium,high,xhigh");
        known("meta/muse-spark-1.2-contributor", "low,medium,high,xhigh");
        known("meta/muse-spark-1.3", "low,medium,high,xhigh,max");
        known("meta/muse-spark-1.3-contributor", "low,medium,high,xhigh");
        known("nvidia/nemotron-3-ultra-550b-a55b", "");
        known("poolside/laguna-s-2.1-free", "");
        known("inclusionai/ling-3.0-flash-free", "");
        known("inclusionai/ling-3.0-flash-sante:free", "");
        known("inclusionai/ling-3.1-flash:free", "low,medium,high");
        known("stealth/space-bunny-alpha", "low,medium,high");
        known("stealth/pixel-canary", "low,medium,xhigh");
        // END OFFICIAL SNAPSHOT
    }

    private ModelReasoning() { }

    private static void known(String id, String levels) {
        COMMAND.put(id.toLowerCase(Locale.ROOT), fromLevels(Arrays.asList(levels.split(","))));
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
        if (ModelConfig.COMMAND_CODE.equals(provider)) {
            String official = COMMAND.get(id.toLowerCase(Locale.ROOT));
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
