package dev.dsh.nativeapp;

import java.util.List;
public class ModelSettingsSnapshotTest {
    static int pass;
    static void check(boolean condition) { if (!condition) throw new AssertionError("live settings projection"); pass++; }
    public static void main(String[] args) {
        String description = "{\"namespaces\":[{\"ns\":\"agent-default-model\",\"value\":{\"provider\":\"deepseek-account\",\"model\":\"deepseek-flash\",\"reasoningEffort\":\"max\"}},"
                + "{\"ns\":\"llm-pi-ai\",\"value\":{\"providers\":{\"commandcode\":{\"apiKeyEnv\":\"COMMANDCODE_API_KEY\",\"compat\":{},\"models\":[{\"name\":\"A: model\",\"id\":\"new/model\",\"input\":[\"text\",\"image\"],\"contextWindow\":1000000,\"reasoningEfforts\":{\"low\":\"low\",\"max\":\"max\"}}]}}}},"
                + "{\"ns\":\"ui-settings-account\",\"value\":{\"purpose\":\"private\"}}]}";
        String yaml = ModelSettingsSnapshot.fromDescription(description);
        check(ModelConfig.readSelection(yaml).provider.equals(ModelConfig.DEEPSEEK_ACCOUNT));
        List<ModelConfig.Model> models = ModelConfig.modelsForProvider(yaml, ModelConfig.COMMAND_CODE);
        check(models.size() == 1 && models.get(0).id.equals("new/model"));
        check(models.get(0).name.equals("A: model") && models.get(0).image);
        check(yaml.contains("contextWindow: 1000000\n") && yaml.contains("compat: {}"));
        check(ModelReasoning.choices(ModelConfig.COMMAND_CODE, "new/model", models.get(0).details).contains("low"));
        check(!yaml.contains("private") && !yaml.contains("purpose"));
        String account = ModelCatalogSync.writeLiveCatalog(yaml, ModelConfig.DEEPSEEK_ACCOUNT,
                LiveModelCatalog.reconcile(ProviderCheck.classify(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"),
                        ModelConfig.modelsForProvider(yaml, ModelConfig.DEEPSEEK_ACCOUNT), ModelConfig.DEEPSEEK_ACCOUNT));
        check(ModelCatalogSync.hasLiveCatalog(account, ModelConfig.DEEPSEEK_ACCOUNT));
        check(!ModelCatalogSync.hasLiveCatalog(account, ModelConfig.DEEPSEEK));
        check(ModelConfig.modelsForProvider(account, ModelConfig.DEEPSEEK_ACCOUNT).size() == 1);
        check(ModelConfig.modelsForProvider(account, "DeepSeek-Account").size() == 1);
        String again = ModelCatalogSync.writeLiveCatalog(account, ModelConfig.DEEPSEEK_ACCOUNT,
                LiveModelCatalog.reconcile(ProviderCheck.classify(200, "{\"data\":[{\"id\":\"deepseek-flash\"}]}"),
                        ModelConfig.modelsForProvider(account, ModelConfig.DEEPSEEK_ACCOUNT), ModelConfig.DEEPSEEK_ACCOUNT));
        check(ModelCatalogSync.hasLiveCatalog(again, ModelConfig.DEEPSEEK_ACCOUNT));
        for (String invalid : new String[]{"{}", "{\"namespaces\":[]}", "{\"namespaces\":[{\"ns\":\"agent-default-model\",\"value\":null}]}",
                "{\"namespaces\":[],\"namespaces\":[]}"}) {
            try { ModelSettingsSnapshot.fromDescription(invalid); throw new AssertionError("invalid snapshot accepted"); }
            catch (IllegalArgumentException expected) { pass++; }
        }
        System.out.println("TOTAL: " + pass + " pass / 0 fail");
    }
}
