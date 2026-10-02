package dev.dsh.nativeapp;

public class DeepSeekAccountTest {
    static int pass;
    static void check(boolean condition) { if (!condition) throw new AssertionError("account state or URL boundary"); pass++; }
    public static void main(String[] args) {
        for (String phase : new String[]{"initializing", "waiting-browser", "exchanging", "committing"}) check(DeepSeekAccount.active(phase));
        for (String phase : new String[]{"succeeded", "cancelled", "expired", "failed", "unknown", ""}) check(!DeepSeekAccount.active(phase));
        check(DeepSeekAccount.canCancel("waiting-browser") && !DeepSeekAccount.canCancel("committing"));
        check(DeepSeekAccount.browserUrl("https://platform.deepseek.com/dsh/authorize?state=test", "/dsh/authorize"));
        for (String url : new String[]{"http://platform.deepseek.com/dsh/authorize", "https://platform.deepseek.com.evil/dsh/authorize",
                "https://platform.deepseek.com@evil.test/dsh/authorize", "https://user@platform.deepseek.com/dsh/authorize",
                "https://platform.deepseek.com:444/dsh/authorize", "https://platform.deepseek.com/dsh/%61uthorize",
                "https://platform.deepseek.com/dsh/authorize#fragment", "https://platform.deepseek.com/usage", "javascript:alert(1)"})
            check(!DeepSeekAccount.browserUrl(url, "/dsh/authorize"));
        check(DeepSeekAccount.status("signed-out", "failed", "network", true).contains("connection"));
        check(DeepSeekAccount.status("signed-out", "failed", "storage", false).contains("空间"));
        check(DeepSeekAccount.status("credential-stored", "succeeded", "", true).contains("saved"));
        check(DeepSeekAccount.status("signed-out", "expired", "", false).contains("超时"));
        check(DeepSeekAccount.status("signed-out", "cancelled", "", true).contains("cancelled"));
        check(DeepSeekAccount.status("signed-out", "committing", "", false).contains("保存"));
        check(ModelConfig.DEEPSEEK_ACCOUNT.equals(ModelConfig.normalizeProvider("DeepSeek-Account")));
        check(ModelConfig.credentialKey(ModelConfig.DEEPSEEK_ACCOUNT).isEmpty());
        ModelConfig.Selection selection = new ModelConfig.Selection(ModelConfig.DEEPSEEK_ACCOUNT, "deepseek-flash", "max");
        check(ModelConfig.readSelection(ModelConfig.updateSelection("", selection)).equals(selection));
        check(ModelReasoning.choices(selection.provider, selection.model, "").contains("max"));
        System.out.println("TOTAL: " + pass + " pass / 0 fail");
    }
}
