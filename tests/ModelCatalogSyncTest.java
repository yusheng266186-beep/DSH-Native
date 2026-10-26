package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ModelCatalogSyncTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        String base = "agent-default-model:\n"
                + "  provider: codebuddy\n  model: \"fresh/model\"\n"
                + "  reasoningEffort: max\n"
                + "llm-pi-ai:\n  providers:\n    codebuddy:\n"
                + "      displayName: CodeBuddy\n      api: openai-completions\n"
                + "      baseURL: https://www.codebuddy.cn/v2\n"
                + "      apiKeyEnv: CODEBUDDY_API_KEY\n      models:\n"
                + "        - id: old\n          name: Old\n";
        List<LiveModelCatalog.Entry> live = new ArrayList<LiveModelCatalog.Entry>();
        live.add(new LiveModelCatalog.Entry("fresh/model", "Fresh model", true, true, true,
                true, "reasoningEfforts: { high: high, max: max }\n"));
        live.add(new LiveModelCatalog.Entry("text-only", "Text only", true, false, false));
        live.add(new LiveModelCatalog.Entry("fresh/model", "duplicate", true, false, false));

        String synced = ModelCatalogSync.writeLiveCatalog(base, ModelConfig.CODEBUDDY, live);
        String accountSettings = "llm-deepseek-account:\n  baseURL: https://example.test\n"
                + "  modelOverrides:\n    deepseek-flash:\n      maxTokens: 4096\n";
        check("account refresh preserves settings and overrides without a stored catalog",
                accountSettings.equals(ModelCatalogSync.writeLiveCatalog(accountSettings,
                    ModelConfig.DEEPSEEK_ACCOUNT, live)), accountSettings);
        check("account catalog remains owned by core",
                base.equals(ModelCatalogSync.writeLiveCatalog(base, ModelConfig.DEEPSEEK_ACCOUNT, live)), base);
        check("command catalog marked live", synced.contains(
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.CODEBUDDY), synced);
        check("command catalog contains fresh id", synced.contains("id: \"fresh/model\""), synced);
        check("command catalog removes stale id", !synced.contains("id: old"), synced);
        check("command catalog preserves image hint", synced.contains("input: [ text, image ]"), synced);
        check("command catalog preserves reasoning hint", synced.contains("reasoningEfforts:"), synced);
        check("command catalog is parseable", ModelConfig.modelsForProvider(
                synced, ModelConfig.CODEBUDDY).size() == 2, synced);
        String syncedAgain = ModelCatalogSync.writeLiveCatalog(synced,
                ModelConfig.CODEBUDDY, live);
        check("command catalog idempotent", occurrences(syncedAgain, "id: \"fresh/model\"") == 1,
                syncedAgain);
        check("command live marker idempotent", occurrences(syncedAgain,
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.CODEBUDDY) == 1,
                syncedAgain);

        String deepSeek = ModelCatalogSync.writeLiveCatalog(
                "agent-default-model:\n  provider: deepseek-official\n  model: live-ds\n",
                ModelConfig.DEEPSEEK,
                Arrays.asList(new LiveModelCatalog.Entry("live-ds", "Live DS", true, true, true)));
        check("deepseek plugin block written", deepSeek.contains("llm-deepseek-api-key:"), deepSeek);
        check("deepseek live model written", deepSeek.contains("id: \"live-ds\""), deepSeek);
        check("deepseek input uses upstream schema", deepSeek.contains("inputModalities: [ text, image ]"), deepSeek);
        check("deepseek catalog readable", ModelConfig.modelsForProvider(
                deepSeek, ModelConfig.DEEPSEEK).size() == 1, deepSeek);
        check("deepseek marker is provider-specific", ModelCatalogSync.hasLiveCatalog(
                deepSeek, ModelConfig.DEEPSEEK), deepSeek);

        String preset = "llm-pi-ai:\n  providers:\n    codebuddy:\n"
                + "      baseURL: https://www.codebuddy.cn/v2\n      models:\n"
                + "        - id: bundled\n          name: Bundled\n";
        String merged = ModelCatalogSync.mergePresetProviderBlock(
                preset.substring(preset.indexOf("llm-pi-ai:")),
                ModelCatalogSync.topLevelBlock(synced, "llm-pi-ai"));
        String mergedOneLine = merged.replace("\n", "|");
        check("preset merge keeps live model", merged.contains("fresh/model"), mergedOneLine);
        check("preset merge drops bundled model", !merged.contains("bundled"), mergedOneLine);
        check("preset merge keeps live marker", merged.contains(
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.CODEBUDDY), mergedOneLine);

        String custom = "llm-pi-ai:\n  defaultContextWindow: 12345\n  providers:\n    custom:\n      baseURL: https://example.test/v1\n      apiKeyEnv: CUSTOM_KEY\n    codebuddy:\n      models:\n        - id: old\n";
        String preserved = ModelCatalogSync.mergePresetProviderBlock(preset,custom);
        check("preset sync preserves custom providers and global settings",preserved.contains("CUSTOM_KEY")&&preserved.contains("defaultContextWindow: 12345")&&preserved.contains("bundled"),preserved);
        check("preset sync stable with custom provider",preserved.equals(ModelCatalogSync.mergePresetProviderBlock(preset,preserved)),preserved);
        String richBase = base.replace("- id: old\n          name: Old\n",
                "- id: old\n          name: Old\n          contextWindow: 1000000\n"
                + "          maxTokens: 32000\n          input: [ text, image ]\n"
                + "          reasoningEfforts: { off: null, high: high, max: ultra }\n"
                + "          compat:\n            supportsReasoningEffort: true\n");
        ProviderCheck.Result remote = ProviderCheck.classify(200,
                "{\"data\":[{\"id\":\"old\",\"vision\":false,\"context_length\":1048576},"
                + "{\"id\":\"fresh-vision\",\"input_modalities\":[\"text\",\"image\"]},"
                + "{\"id\":\"unknown-new\"}]}");
        List<LiveModelCatalog.Entry> aligned = LiveModelCatalog.reconcile(remote,
                ModelConfig.modelsForProvider(richBase, ModelConfig.CODEBUDDY));
        String rich = ModelCatalogSync.writeLiveCatalog(richBase, ModelConfig.CODEBUDDY, aligned);
        check("local token limit retained", rich.contains("maxTokens: 32000"), rich);
        check("upstream token limit overrides", rich.contains("contextWindow: 1048576")
                && !rich.contains("contextWindow: 1000000"), rich);
        check("requested max wire is literal", rich.contains("max: max") && !rich.contains("max: ultra"), rich);
        check("per-model transport retained", rich.contains("            supportsReasoningEffort: true"), rich);
        check("upstream text overrides old vision", !ModelConfig.modelsForProvider(rich,
                ModelConfig.CODEBUDDY).get(0).image && rich.contains("input: [ text ]"), rich);
        check("new vision written", ModelConfig.modelsForProvider(rich,
                ModelConfig.CODEBUDDY).get(1).image, rich);
        check("unknown vision is not advertised", !ModelConfig.modelsForProvider(rich,
                ModelConfig.CODEBUDDY).get(2).image, rich);
        check("refresh keeps default selection", ModelConfig.readSelection(richBase)
                .equals(ModelConfig.readSelection(rich)), rich);
        check("refresh bytes idempotent", rich.equals(ModelCatalogSync.writeLiveCatalog(rich,
                ModelConfig.CODEBUDDY, aligned)), rich);
        String absent = "llm-pi-ai:\n  providers:\n    other:\n      models:\n        - id: untouched\n";
        String inserted = ModelCatalogSync.writeLiveCatalog(absent, ModelConfig.CODEBUDDY, aligned);
        check("missing route inserted", ModelConfig.containsModel(inserted,
                ModelConfig.CODEBUDDY, "fresh-vision") && inserted.contains("untouched"), inserted);
        String empty = ModelCatalogSync.writeLiveCatalog("llm-pi-ai:\n  providers: {}\n",
                ModelConfig.CODEBUDDY, aligned);
        check("empty provider dictionary expanded", occurrences(empty, "providers:") == 1
                && ModelConfig.containsModel(empty, ModelConfig.CODEBUDDY, "fresh-vision"), empty);
        String noModels = "llm-pi-ai:\n  providers:\n    other:\n      displayName: Other\n"
                + "    codebuddy:\n      api: openai-completions\n";
        String added = ModelCatalogSync.writeLiveCatalog(noModels, ModelConfig.CODEBUDDY, aligned);
        check("models inserted at correct route", ModelConfig.containsModel(added,
                ModelConfig.CODEBUDDY, "fresh-vision") && added.indexOf("other:") < added.indexOf("codebuddy:"), added);
        if (args.length > 0 && "--dump-config".equals(args[0])) {
            System.out.print(ModelCatalogSync.writeLiveCatalog(rich, ModelConfig.DEEPSEEK,
                    Arrays.asList(new LiveModelCatalog.Entry("deepseek-flash", "DeepSeek Flash", true, true, true),
                            new LiveModelCatalog.Entry("deepseek-v4-pro", "DeepSeek V4 Pro", true, false, true, true, ""))));
            return;
        }

        // Unknown modalities do not imply image support.
        String visionYml = "llm-pi-ai:\n  providers:\n    codebuddy:\n"
                + "      models:\n"
                + "        - id: plain-model\n"
                + "          name: Plain\n"
                + "          contextWindow: 128000\n";
        java.util.List<LiveModelCatalog.Entry> unknownVision =
                java.util.Arrays.asList(new LiveModelCatalog.Entry(
                        "plain-model", "Plain", true, false, false, false, ""));
        String wrote = ModelCatalogSync.writeLiveCatalog(visionYml, "codebuddy", unknownVision);
        check("unknown model stays text-only",
                wrote.contains("input: [ text ]"),
                "no vision declaration written:\n" + wrote);
        check("vision declaration not duplicated",
                occurrences(wrote, "input: [ text ]") == 1,
                "declared more than once");

        // 上游若将来明确声明「仅文字」，应以它为准，不被默认值覆盖。
        java.util.List<LiveModelCatalog.Entry> textOnly =
                java.util.Arrays.asList(new LiveModelCatalog.Entry(
                        "text-model", "Text", true, false, false, true, ""));
        String wroteText = ModelCatalogSync.writeLiveCatalog(visionYml, "codebuddy", textOnly);
        check("explicit text-only from upstream is respected",
                wroteText.contains("input: [ text ]")
                        && !wroteText.contains("input: [ text, image ]"),
                "upstream declaration ignored:\n" + wroteText);

        // 多个模型都要被写到，不能只补第一个。
        String multiYml = "llm-pi-ai:\n  providers:\n    codebuddy:\n"
                + "      models:\n";
        String multi = ModelCatalogSync.writeLiveCatalog(multiYml, "codebuddy",
                java.util.Arrays.asList(
                        new LiveModelCatalog.Entry("m1", "M1", true, false, false, false, ""),
                        new LiveModelCatalog.Entry("m2", "M2", true, false, false, false, ""),
                        new LiveModelCatalog.Entry("m3", "M3", true, false, false, false, "")));
        check("every model gets a declaration",
                occurrences(multi, "input: [ text ]") == 3,
                "expected 3, got " + occurrences(multi, "input: [ text ]"));

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int occurrences(String text, String part) {
        int count = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) { count++; at += part.length(); }
        return count;
    }
}
