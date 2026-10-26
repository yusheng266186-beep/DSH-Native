package dev.dsh.nativeapp;

import java.util.List;
public class ModelSettingsSnapshotTest {
    static int pass;
    static void check(boolean condition) { if (!condition) throw new AssertionError("live settings projection"); pass++; }
    public static void main(String[] args) {
        String description = "{\"namespaces\":[{\"ns\":\"agent-default-model\",\"value\":{\"provider\":\"deepseek-account\",\"model\":\"deepseek-flash\",\"reasoningEffort\":\"max\"}},"
                + "{\"ns\":\"llm-pi-ai\",\"value\":{\"providers\":{\"codebuddy\":{\"apiKeyEnv\":\"CODEBUDDY_API_KEY\",\"compat\":{},\"models\":[{\"name\":\"A: model\",\"id\":\"new/model\",\"input\":[\"text\",\"image\"],\"contextWindow\":1000000,\"reasoningEfforts\":{\"low\":\"low\",\"max\":\"max\"}}]}}}},"
                + "{\"ns\":\"llm-deepseek-account\",\"value\":{\"models\":[{\"id\":\"obsolete-cached\"}],\"modelOverrides\":{\"deepseek-flash\":{\"maxTokens\":4096}}}},"
                + "{\"ns\":\"ui-settings-account\",\"value\":{\"purpose\":\"private\"}}]}";
        String yaml = ModelSettingsSnapshot.fromDescription(description);
        check(ModelConfig.readSelection(yaml).provider.equals(ModelConfig.DEEPSEEK_ACCOUNT));
        List<ModelConfig.Model> models = ModelConfig.modelsForProvider(yaml, ModelConfig.CODEBUDDY);
        check(models.size() == 1 && models.get(0).id.equals("new/model"));
        check(models.get(0).name.equals("A: model") && models.get(0).image);
        check(yaml.contains("contextWindow: 1000000\n") && yaml.contains("compat: {}"));
        check(ModelReasoning.choices(ModelConfig.CODEBUDDY, "new/model", models.get(0).details).contains("low"));
        check(!yaml.contains("private") && !yaml.contains("purpose"));
        String account = ModelCatalogSync.writeLiveCatalog(yaml, ModelConfig.DEEPSEEK_ACCOUNT,
                LiveModelCatalog.reconcile(ProviderCheck.classify(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"),
                        ModelConfig.modelsForProvider(yaml, ModelConfig.DEEPSEEK_ACCOUNT), ModelConfig.DEEPSEEK_ACCOUNT));
        check(account.equals(yaml));
        check(!account.contains("obsolete-cached") && account.contains("maxTokens: 4096"));
        check(!ModelCatalogSync.hasLiveCatalog(account, ModelConfig.DEEPSEEK));
        check(!ModelCatalogSync.topLevelBlock(account, "llm-deepseek-account").contains("models:"));
        String again = ModelCatalogSync.writeLiveCatalog(account, ModelConfig.DEEPSEEK_ACCOUNT,
                LiveModelCatalog.reconcile(ProviderCheck.classify(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"),
                        ModelConfig.modelsForProvider(account, ModelConfig.DEEPSEEK_ACCOUNT), ModelConfig.DEEPSEEK_ACCOUNT));
        check(again.equals(account));
        for (String invalid : new String[]{"{}", "{\"namespaces\":[]}", "{\"namespaces\":[{\"ns\":\"agent-default-model\",\"value\":null}]}",
                "{\"namespaces\":[],\"namespaces\":[]}"}) {
            try { ModelSettingsSnapshot.fromDescription(invalid); throw new AssertionError("invalid snapshot accepted"); }
            catch (IllegalArgumentException expected) { pass++; }
        }
        System.out.println("TOTAL: " + pass + " pass / 0 fail");
    }
}
