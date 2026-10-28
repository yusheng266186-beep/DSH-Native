package dev.dsh.nativeapp;

import java.util.List;

public class ProviderCheckTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        check("command endpoint", ProviderCheck.endpoint(ModelConfig.CODEBUDDY)
                .equals("https://www.codebuddy.cn/v2/chat/completions"), "wrong");
        check("deepseek endpoint", ProviderCheck.endpoint(ModelConfig.DEEPSEEK)
                .equals("https://api.deepseek.com/models"), "wrong");

        ProviderCheck.Result buddy = CodeBuddyCatalog.probe(400,"{\"code\":11133}");
        check("CodeBuddy parameter probe uses explicit CLI snapshot", buddy.state==ProviderCheck.CLI_METADATA && !buddy.accepted() && buddy.hasCatalog() && buddy.cliSnapshot && buddy.models.size()==21,"invalid snapshot");
        check("CodeBuddy channel rejection remains distinct", CodeBuddyCatalog.probe(400,"{\"code\":11128}").state==ProviderCheck.CHANNEL_REJECTED && !CodeBuddyCatalog.probe(400,"{\"code\":11128}").hasCatalog(),"restriction lost");
        check("CodeBuddy rejected key does not expose models", CodeBuddyCatalog.probe(401,"{}").models.isEmpty() && CodeBuddyCatalog.probe(401,"{}").state==ProviderCheck.KEY_REJECTED,"accepted");
        check("CodeBuddy unknown response does not claim ready", !CodeBuddyCatalog.probe(400,"{}").accepted(),"accepted");
        check("CodeBuddy rate limit remains distinct", CodeBuddyCatalog.probe(429,"{}").state==ProviderCheck.RATE_LIMITED,"lost status");
        check("CLI metadata does not establish chat access", !CodeBuddyCatalog.metadata().accepted() && !CodeBuddyCatalog.metadata().cliChatVerified,"false approval");
        check("DeepSeek CLI metadata selects the declared 1M tier", CodeBuddyCatalog.metadata().metadata.get("deepseek-v4.1-flash").details.contains("contextWindow: 1000000"),"old 300K budget");
        check("official CLI success establishes current model chat check", CodeBuddyCatalog.checkReply("{\"ok\":true}").cliChatVerified,"not verified");
        check("CLI channel refusal wins over an ambiguous ok flag", CodeBuddyCatalog.checkReply("{\"ok\":true,\"channelRejected\":true}").state==ProviderCheck.CHANNEL_REJECTED,"restriction lost");
        check("CLI model access refusal differs from a bad key", CodeBuddyCatalog.checkReply("{\"ok\":false,\"failureCode\":\"CODEBUDDY_MODEL_ACCESS\"}").state==ProviderCheck.MODEL_DENIED,"model refusal lost");
        check("CLI quota stays distinct", CodeBuddyCatalog.checkReply("{\"ok\":false,\"failureCode\":\"QUOTA\"}").state==ProviderCheck.QUOTA_EXHAUSTED,"quota lost");
        check("CLI rate limit stays distinct", CodeBuddyCatalog.checkReply("{\"ok\":false,\"failureCode\":\"CODEBUDDY_RATE_LIMITED\"}").state==ProviderCheck.RATE_LIMITED,"rate limit lost");
        check("unknown CLI failure cannot claim success", !CodeBuddyCatalog.checkReply("{\"ok\":false,\"failureCode\":\"PRIVATE-UNTRUSTED\"}").accepted(),"failure accepted");
        check("CLI rejected key does not expose models", CodeBuddyCatalog.checkReply("{\"keyRejected\":true}").models.isEmpty(),"leaked catalog");
        String json = "{\"data\":[{\"id\":\"alpha/model\"},{\"id\":\"beta\"},"
                + "{\"id\":\"alpha/model\"}]}";
        List<String> ids = ProviderCheck.modelIds(json);
        check("parses ids", ids.size() == 2, ids.toString());
        check("keeps slash", ids.contains("alpha/model"), ids.toString());
        check("deduplicates", ids.indexOf("alpha/model") == ids.lastIndexOf("alpha/model"), ids.toString());

        ProviderCheck.Result ok = ProviderCheck.classify(200, json);
        check("200 ready", ok.state == ProviderCheck.READY && ok.accepted(), String.valueOf(ok.state));
        check("selected present", ProviderCheck.contains(ok, "beta"), "missing");
        check("selected absent", !ProviderCheck.contains(ok, "other"), "found");
        check("empty 200 invalid", ProviderCheck.classify(200, "{}").state
                == ProviderCheck.INVALID_RESPONSE, "wrong");
        check("401 rejected", ProviderCheck.classify(401, "{}").state
                == ProviderCheck.KEY_REJECTED, "wrong");
        check("403 rejected", ProviderCheck.classify(403, "{}").state
                == ProviderCheck.KEY_REJECTED, "wrong");
        check("429 accepted but limited", ProviderCheck.classify(429, "{}").accepted(), "wrong");
        check("404 endpoint changed", ProviderCheck.classify(404, "{}").state
                == ProviderCheck.ENDPOINT_CHANGED, "wrong");
        check("500 service error", ProviderCheck.classify(500, "{}").state
                == ProviderCheck.SERVICE_ERROR, "wrong");
        check("oversize safe", ProviderCheck.modelIds(repeat("x", 1024 * 1024 + 1)).isEmpty(), "parsed");

        ProviderCheck.Result rich = ProviderCheck.classify(200, "{\"data\":["
                + "{\"id\":\"vision-new\",\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}},"
                + "{\"id\":\"text\",\"capabilities\":{\"vision\":false},\"context_length\":262144},"
                + "{\"id\":\"unknown\",\"owner\":{\"id\":\"not-a-model\"}}]}");
        check("nested ids excluded", rich.models.size() == 3 && !rich.models.contains("not-a-model"), rich.models.toString());
        check("vision metadata read", rich.metadata.get("vision-new").imageKnown
                && rich.metadata.get("vision-new").image, "lost vision");
        check("text-only metadata read", rich.metadata.get("text").imageKnown
                && !rich.metadata.get("text").image, "lost negative capability");
        check("unknown stays unknown", !rich.metadata.get("unknown").imageKnown, "guessed capability");
        check("token metadata read", rich.metadata.get("text").details.contains("contextWindow: 262144"), "lost limit");
        check("malformed catalog rejected", ProviderCheck.modelIds("{\"data\":[{\"id\":\"partial\"}").isEmpty(), "partial response accepted");
        check("unicode model decoded", ProviderCheck.modelIds("{\"data\":[{\"id\":\"model-\\u4e2d\"}]}")
                .contains("model-中"), "unicode escape lost");
        check("error id excluded", ProviderCheck.modelIds("{\"error\":{\"id\":\"request-id\"}}").isEmpty(), "request id treated as model");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
