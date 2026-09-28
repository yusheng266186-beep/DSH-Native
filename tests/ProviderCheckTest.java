package dev.dsh.nativeapp;

import java.util.List;

public class ProviderCheckTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        check("command endpoint", ProviderCheck.endpoint(ModelConfig.COMMAND_CODE)
                .equals("https://api.commandcode.ai/provider/v1/models"), "wrong");
        check("deepseek endpoint", ProviderCheck.endpoint(ModelConfig.DEEPSEEK)
                .equals("https://api.deepseek.com/models"), "wrong");

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

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
