package dev.dsh.nativeapp;
import java.util.Map;
/** Public model metadata from the installed official CLI snapshot; contains no credentials. */
final class CodeBuddyCatalog {
    static final String VERSION = "2.174.0";
    static final String ENDPOINT = "https://www.codebuddy.cn/v2/chat/completions";
    static ProviderCheck.Result metadata() {
        ProviderCheck.Result data = ProviderCheck.classify(200, JSON);
        return new ProviderCheck.Result(ProviderCheck.CLI_METADATA,0,data.models,data.metadata,true);
    }
    static ProviderCheck.Result checked() {
        ProviderCheck.Result data = metadata();
        return new ProviderCheck.Result(ProviderCheck.READY,0,data.models,data.metadata,true,true);
    }
    static ProviderCheck.Result checkReply(String json) {
        Object parsed = JsonValue.parse(json);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Invalid CLI reply");
        Map<?,?> result = (Map<?,?>)parsed;
        if (Boolean.TRUE.equals(result.get("channelRejected")))
            return new ProviderCheck.Result(ProviderCheck.CHANNEL_REJECTED,400,new java.util.ArrayList<String>());
        if (Boolean.TRUE.equals(result.get("keyRejected"))) return ProviderCheck.classify(401,"");
        Object failure = result.get("failureCode");
        if ("CODEBUDDY_RATE_LIMITED".equals(failure)) return ProviderCheck.classify(429, "");
        if ("CODEBUDDY_MODEL_ACCESS".equals(failure))
            return new ProviderCheck.Result(ProviderCheck.MODEL_DENIED,403,new java.util.ArrayList<String>());
        if ("QUOTA".equals(failure))
            return new ProviderCheck.Result(ProviderCheck.QUOTA_EXHAUSTED,402,new java.util.ArrayList<String>());
        return Boolean.TRUE.equals(result.get("ok")) ? checked() : ProviderCheck.classify(500,"");
    }
    static final String JSON = "{\"data\":[{\"id\":\"default-model\",\"name\":\"Auto\",\"contextWindow\":176000,\"maxTokens\":24000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":[]},{\"id\":\"fast-model\",\"name\":\"Fast\",\"contextWindow\":200000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"medium\":\"medium\"}},{\"id\":\"balanced-model\",\"name\":\"Balanced\",\"contextWindow\":256000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"medium\":\"medium\"}},{\"id\":\"primary-model\",\"name\":\"Primary\",\"contextWindow\":272000,\"maxTokens\":72000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"high\":\"high\"}},{\"id\":\"deep-model\",\"name\":\"Deep\",\"contextWindow\":176000,\"maxTokens\":24000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":[]},{\"id\":\"deepseek-v4.1-flash\",\"name\":\"Deepseek-V4.1-Flash\",\"contextWindow\":1000000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"high\":\"high\"}},{\"id\":\"gpt-6-astra\",\"name\":\"GPT-6-Astra\",\"contextWindow\":400000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\",\"max\":\"max\"}},{\"id\":\"hy4-preview\",\"name\":\"Hy4 preview\",\"contextWindow\":200000,\"maxTokens\":64000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"high\":\"high\"}},{\"id\":\"hy3\",\"name\":\"Hy3\",\"contextWindow\":192000,\"maxTokens\":64000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"high\":\"high\"}},{\"id\":\"kimi-k2.8-preview\",\"name\":\"Kimi-K2.8-Preview\",\"contextWindow\":300000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"high\":\"high\",\"max\":\"max\"}},{\"id\":\"gpt-5.6-sol\",\"name\":\"GPT-5.6-Sol\",\"contextWindow\":1000000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\",\"max\":\"max\"}},{\"id\":\"gpt-5.6-terra\",\"name\":\"GPT-5.6-Terra\",\"contextWindow\":1000000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\",\"max\":\"max\"}},{\"id\":\"gpt-5.6-luna\",\"name\":\"GPT-5.6-Luna\",\"contextWindow\":1000000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\",\"max\":\"max\"}},{\"id\":\"gpt-5.5\",\"name\":\"GPT-5.5\",\"contextWindow\":1000000,\"maxTokens\":128000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\"}},{\"id\":\"gpt-5.4\",\"name\":\"GPT-5.4\",\"contextWindow\":272000,\"maxTokens\":72000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"medium\":\"medium\",\"high\":\"high\",\"xhigh\":\"xhigh\"}},{\"id\":\"gemini-3.5-flash\",\"name\":\"Gemini-3.5-Flash\",\"contextWindow\":1000000,\"maxTokens\":65536,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"medium\":\"medium\"}},{\"id\":\"glm-5.3-flash\",\"name\":\"GLM-5.3-Flash\",\"contextWindow\":300000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"high\":\"high\",\"max\":\"max\"}},{\"id\":\"glm-5.3\",\"name\":\"GLM-5.3\",\"contextWindow\":1000000,\"maxTokens\":48000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"low\":\"low\",\"high\":\"high\",\"max\":\"max\"}},{\"id\":\"glm-5.2\",\"name\":\"GLM-5.2\",\"contextWindow\":1000000,\"maxTokens\":48000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"high\":\"high\",\"xhigh\":\"xhigh\"}},{\"id\":\"kimi-k3\",\"name\":\"Kimi-K3\",\"contextWindow\":1000000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"medium\":\"medium\"}},{\"id\":\"kimi-k2.6\",\"name\":\"Kimi-K2.6\",\"contextWindow\":256000,\"maxTokens\":32000,\"input\":[\"text\",\"image\"],\"reasoningEfforts\":{\"medium\":\"medium\"}}]}";
    static ProviderCheck.Result probe(int http, String body) {
        try {
            Object parsed=JsonValue.parse(body);
            if(http==400 && parsed instanceof Map && ((Map<?,?>)parsed).get("code") instanceof Number && ((Number)((Map<?,?>)parsed).get("code")).intValue()==11128)
                return new ProviderCheck.Result(ProviderCheck.CHANNEL_REJECTED,http,new java.util.ArrayList<String>());
            if(http==400 && parsed instanceof Map && ((Map<?,?>)parsed).get("code") instanceof Number && ((Number)((Map<?,?>)parsed).get("code")).intValue()==11133)
            {
                ProviderCheck.Result result = ProviderCheck.classify(200, JSON);
                return new ProviderCheck.Result(ProviderCheck.CLI_METADATA,http,result.models,result.metadata,true);
            }
        } catch(IllegalArgumentException malformed) { /* A non-protocol response is not an authenticated probe. */ }
        return ProviderCheck.classify(http==401||http==403?401:http==429||http==404||http==405?http:500, "");
    }
}
