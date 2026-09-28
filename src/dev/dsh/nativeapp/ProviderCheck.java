package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 服务商只读检测的端点、响应分类与模型列表解析。 */
final class ProviderCheck {
    static final int READY = 1;
    static final int KEY_REJECTED = 2;
    static final int RATE_LIMITED = 3;
    static final int SERVICE_ERROR = 4;
    static final int ENDPOINT_CHANGED = 5;
    static final int INVALID_RESPONSE = 6;

    private ProviderCheck() { }

    static final class Result {
        final int state;
        final int httpCode;
        final List<String> models;

        Result(int state, int httpCode, List<String> models) {
            this.state = state;
            this.httpCode = httpCode;
            this.models = models;
        }

        boolean accepted() { return state == READY || state == RATE_LIMITED; }
    }

    static String endpoint(String provider) {
        return ModelConfig.DEEPSEEK.equals(provider)
                ? "https://api.deepseek.com/models"
                : "https://api.commandcode.ai/provider/v1/models";
    }

    static Result classify(int code, String body) {
        List<String> models = modelIds(body);
        if (code >= 200 && code < 300) {
            return new Result(models.isEmpty() ? INVALID_RESPONSE : READY, code, models);
        }
        if (code == 401 || code == 403) return new Result(KEY_REJECTED, code, models);
        if (code == 429) return new Result(RATE_LIMITED, code, models);
        if (code == 404 || code == 405) return new Result(ENDPOINT_CHANGED, code, models);
        return new Result(SERVICE_ERROR, code, models);
    }

    static List<String> modelIds(String json) {
        Set<String> unique = new LinkedHashSet<String>();
        if (json == null || json.length() > 1024 * 1024) return new ArrayList<String>();
        Matcher matcher = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .matcher(json);
        while (matcher.find() && unique.size() < 500) {
            String id = unescape(matcher.group(1));
            if (ModelConfig.normalizeModel(id).length() > 0) unique.add(id);
        }
        return new ArrayList<String>(unique);
    }

    static boolean contains(Result result, String model) {
        return result != null && model != null && result.models.contains(model);
    }

    private static String unescape(String value) {
        return value.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
