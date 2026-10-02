package dev.dsh.nativeapp;

import java.util.List;
import java.util.Map;

/** Project the live core's public model settings into the native YAML readers. */
final class ModelSettingsSnapshot {
    private ModelSettingsSnapshot() { }

    static String fromDescription(String json) {
        return fromDescriptionObject(JsonValue.parse(json));
    }

    static String fromDescriptionObject(Object root) {
        if (!(root instanceof Map)) throw new IllegalArgumentException("invalid settings description");
        Object namespaces = ((Map<?, ?>) root).get("namespaces");
        if (!(namespaces instanceof List)) throw new IllegalArgumentException("missing settings namespaces");
        StringBuilder out = new StringBuilder();
        for (Object entry : (List<?>) namespaces) {
            if (!(entry instanceof Map)) throw new IllegalArgumentException("invalid namespace");
            Map<?, ?> ns = (Map<?, ?>) entry;
            String name = String.valueOf(ns.get("ns"));
            if (!"agent-default-model".equals(name) && !"llm-pi-ai".equals(name)
                    && !"llm-deepseek".equals(name) && !"llm-deepseek-api-key".equals(name)
                    && !"llm-deepseek-account".equals(name)) continue;
            if (!(ns.get("value") instanceof Map)) throw new IllegalArgumentException("invalid model settings");
            out.append(name).append(":\n");
            object(out, (Map<?, ?>) ns.get("value"), 2);
        }
        if (!ModelConfig.readSelection(out.toString()).valid())
            throw new IllegalArgumentException("default model unavailable");
        return out.toString();
    }

    private static void object(StringBuilder out, Map<?, ?> map, int indent) {
        for (Map.Entry<?, ?> field : map.entrySet()) {
            String key = String.valueOf(field.getKey());
            if (!key.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("invalid settings key");
            Object value = field.getValue();
            pad(out, indent); out.append(key).append(':');
            if (value instanceof Map && !((Map<?, ?>) value).isEmpty() && !"reasoningEfforts".equals(key)) {
                out.append('\n'); object(out, (Map<?, ?>) value, indent + 2);
            } else if ("models".equals(key) && value instanceof List && !((List<?>) value).isEmpty()) {
                out.append('\n');
                for (Object item : (List<?>) value) {
                    if (!(item instanceof Map)) throw new IllegalArgumentException("invalid model entry");
                    Map<?, ?> model = (Map<?, ?>) item;
                    pad(out, indent + 2); out.append("- id: ").append(flow(model.get("id"))).append('\n');
                    for (Map.Entry<?, ?> detail : model.entrySet()) {
                        if ("id".equals(detail.getKey())) continue;
                        pad(out, indent + 4); out.append(detail.getKey()).append(": ").append(flow(detail.getValue())).append('\n');
                    }
                }
            } else out.append(' ').append(flow(value)).append('\n');
        }
    }

    private static String flow(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("invalid settings number");
            return number == Math.rint(number) && Math.abs(number) < 9e15 ? String.valueOf((long) number) : value.toString();
        }
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> field : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(", ");
                String key = String.valueOf(field.getKey());
                if (!key.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("invalid settings key");
                out.append(key).append(": ").append(flow(field.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object item : (List<?>) value) {
                if (out.length() > 1) out.append(", "); out.append(flow(item));
            }
            return out.append(']').toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\');
            if (c < 32 || c == 127) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static void pad(StringBuilder out, int indent) { for (int i = 0; i < indent; i++) out.append(' '); }
}
