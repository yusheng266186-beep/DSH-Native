package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only discovery: only catalog rows supply model IDs. */
final class ProviderCheck {
    static final int READY = 1, KEY_REJECTED = 2, RATE_LIMITED = 3,
            SERVICE_ERROR = 4, ENDPOINT_CHANGED = 5, INVALID_RESPONSE = 6;
    private ProviderCheck() { }
    static final class Result {
        final int state, httpCode;
        final List<String> models;
        final Map<String, ModelConfig.Model> metadata;
        Result(int state, int code, List<String> models) {
            this(state, code, models, new LinkedHashMap<String, ModelConfig.Model>());
        }
        Result(int state, int code, List<String> models, Map<String, ModelConfig.Model> metadata) {
            this.state = state; this.httpCode = code; this.models = models; this.metadata = metadata;
        }
        boolean accepted() { return state == READY || state == RATE_LIMITED; }
    }
    static String endpoint(String provider) {
        return ModelConfig.DEEPSEEK.equals(provider) ? "https://api.deepseek.com/models"
                : "https://api.commandcode.ai/provider/v1/models";
    }
    static Result classify(int code, String body) {
        Map<String, ModelConfig.Model> metadata = catalog(body);
        List<String> models = new ArrayList<String>(metadata.keySet());
        int state = code >= 200 && code < 300 ? (models.isEmpty() ? INVALID_RESPONSE : READY)
                : code == 401 || code == 403 ? KEY_REJECTED : code == 429 ? RATE_LIMITED
                : code == 404 || code == 405 ? ENDPOINT_CHANGED : SERVICE_ERROR;
        return new Result(state, code, models, metadata);
    }
    static List<String> modelIds(String body) { return new ArrayList<String>(catalog(body).keySet()); }
    static boolean contains(Result result, String model) {
        return result != null && model != null && result.models.contains(model);
    }
    private static Map<String, ModelConfig.Model> catalog(String body) {
        Map<String, ModelConfig.Model> out = new LinkedHashMap<String, ModelConfig.Model>();
        if (body == null || body.length() > 1024 * 1024) return out;
        try {
            Object root = JsonValue.parse(body);
            Object rows = root instanceof Map ? ((Map<?, ?>) root).get("data") : root;
            if (!(rows instanceof List) && root instanceof Map) rows = ((Map<?, ?>) root).get("models");
            if (!(rows instanceof List) || ((List<?>) rows).size() > 500) return out;
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> item = (Map<?, ?>) row;
                String id = item.get("id") instanceof String ? ModelConfig.normalizeModel((String) item.get("id")) : "";
                if (id.length() == 0 || out.containsKey(id)) continue;
                String name = item.get("name") instanceof String ? (String) item.get("name") : id;
                if (ModelConfig.normalizeModel(name).length() == 0) name = id;
                Object modalities = item.get("input_modalities");
                if (!(modalities instanceof List)) modalities = item.get("inputModalities");
                if (!(modalities instanceof List)) modalities = item.get("input");
                Object architecture = item.get("architecture");
                if (!(modalities instanceof List) && architecture instanceof Map)
                    modalities = ((Map<?, ?>) architecture).get("input_modalities");
                Boolean image = null;
                if (modalities instanceof List && ((List<?>) modalities).contains("text"))
                    image = Boolean.valueOf(((List<?>) modalities).contains("image"));
                Object capabilities = item.get("capabilities");
                if (image == null && capabilities instanceof Map) {
                    Object vision = ((Map<?, ?>) capabilities).get("vision");
                    if (vision instanceof Boolean) image = (Boolean) vision;
                }
                if (image == null && item.get("vision") instanceof Boolean) image = (Boolean) item.get("vision");
                String details = tokenField(item, "contextWindow", "context_length")
                        + tokenField(item, "maxTokens", "max_output_tokens");
                if (!item.containsKey("contextWindow") && !item.containsKey("context_length"))
                    details += tokenField(item, "contextWindow", "context_window");
                String reasoning = ModelReasoning.upstreamDeclaration(item);
                if (reasoning != null) details += ModelReasoning.UPSTREAM + "\nreasoningEfforts: " + reasoning + "\n";
                out.put(id, new ModelConfig.Model(id, name, Boolean.TRUE.equals(image),
                        reasoning != null && !"false".equals(reasoning), image != null, details));
            }
        } catch (IllegalArgumentException invalid) { out.clear(); }
        return out;
    }
    private static String tokenField(Map<?, ?> item, String key, String alias) {
        Object value = item.containsKey(key) ? item.get(key) : item.get(alias);
        if (!(value instanceof Number)) return "";
        double n = ((Number) value).doubleValue();
        return n > 0 && n <= Integer.MAX_VALUE && n == Math.floor(n) ? key + ": " + (long) n + "\n" : "";
    }
}
