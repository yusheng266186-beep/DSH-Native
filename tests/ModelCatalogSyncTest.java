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
                + "  provider: commandcode\n  model: \"fresh/model\"\n"
                + "  reasoningEffort: max\n"
                + "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      displayName: Command Code\n      api: openai-completions\n"
                + "      baseURL: https://api.commandcode.ai/provider/v1\n"
                + "      apiKeyEnv: COMMANDCODE_API_KEY\n      models:\n"
                + "        - id: old\n          name: Old\n";
        List<LiveModelCatalog.Entry> live = new ArrayList<LiveModelCatalog.Entry>();
        live.add(new LiveModelCatalog.Entry("fresh/model", "Fresh model", true, true, true));
        live.add(new LiveModelCatalog.Entry("text-only", "Text only", true, false, false));
        live.add(new LiveModelCatalog.Entry("fresh/model", "duplicate", true, false, false));

        String synced = ModelCatalogSync.writeLiveCatalog(base, ModelConfig.COMMAND_CODE, live);
        check("command catalog marked live", synced.contains(
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.COMMAND_CODE), synced);
        check("command catalog contains fresh id", synced.contains("id: \"fresh/model\""), synced);
        check("command catalog removes stale id", !synced.contains("id: old"), synced);
        check("command catalog preserves image hint", synced.contains("input: [ text, image ]"), synced);
        check("command catalog preserves reasoning hint", synced.contains("reasoningEfforts:"), synced);
        check("command catalog is parseable", ModelConfig.modelsForProvider(
                synced, ModelConfig.COMMAND_CODE).size() == 2, synced);
        String syncedAgain = ModelCatalogSync.writeLiveCatalog(synced,
                ModelConfig.COMMAND_CODE, live);
        check("command catalog idempotent", occurrences(syncedAgain, "id: \"fresh/model\"") == 1,
                syncedAgain);
        check("command live marker idempotent", occurrences(syncedAgain,
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.COMMAND_CODE) == 1,
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

        String preset = "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      baseURL: https://api.commandcode.ai/provider/v1\n      models:\n"
                + "        - id: bundled\n          name: Bundled\n";
        String merged = ModelCatalogSync.mergePresetProviderBlock(
                preset.substring(preset.indexOf("llm-pi-ai:")),
                ModelCatalogSync.topLevelBlock(synced, "llm-pi-ai"));
        String mergedOneLine = merged.replace("\n", "|");
        check("preset merge keeps live model", merged.contains("fresh/model"), mergedOneLine);
        check("preset merge drops bundled model", !merged.contains("bundled"), mergedOneLine);
        check("preset merge keeps live marker", merged.contains(
                ModelCatalogSync.LIVE_MARKER_PREFIX + ModelConfig.COMMAND_CODE), mergedOneLine);

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
                ModelConfig.modelsForProvider(richBase, ModelConfig.COMMAND_CODE));
        String rich = ModelCatalogSync.writeLiveCatalog(richBase, ModelConfig.COMMAND_CODE, aligned);
        check("local token limit retained", rich.contains("maxTokens: 32000"), rich);
        check("upstream token limit overrides", rich.contains("contextWindow: 1048576")
                && !rich.contains("contextWindow: 1000000"), rich);
        check("reasoning wire mapping retained", rich.contains("max: ultra"), rich);
        check("per-model transport retained", rich.contains("            supportsReasoningEffort: true"), rich);
        check("upstream text overrides old vision", !ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(0).image && rich.contains("input: [ text ]"), rich);
        check("new vision written", ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(1).image, rich);
        check("unknown vision stays unknown", !ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(2).imageKnown, rich);
        check("refresh keeps default selection", ModelConfig.readSelection(richBase)
                .equals(ModelConfig.readSelection(rich)), rich);
        check("refresh bytes idempotent", rich.equals(ModelCatalogSync.writeLiveCatalog(rich,
                ModelConfig.COMMAND_CODE, aligned)), rich);
        String absent = "llm-pi-ai:\n  providers:\n    other:\n      models:\n        - id: untouched\n";
        String inserted = ModelCatalogSync.writeLiveCatalog(absent, ModelConfig.COMMAND_CODE, aligned);
        check("missing route inserted", ModelConfig.containsModel(inserted,
                ModelConfig.COMMAND_CODE, "fresh-vision") && inserted.contains("untouched"), inserted);
        String empty = ModelCatalogSync.writeLiveCatalog("llm-pi-ai:\n  providers: {}\n",
                ModelConfig.COMMAND_CODE, aligned);
        check("empty provider dictionary expanded", occurrences(empty, "providers:") == 1
                && ModelConfig.containsModel(empty, ModelConfig.COMMAND_CODE, "fresh-vision"), empty);
        String noModels = "llm-pi-ai:\n  providers:\n    other:\n      displayName: Other\n"
                + "    commandcode:\n      api: openai-completions\n";
        String added = ModelCatalogSync.writeLiveCatalog(noModels, ModelConfig.COMMAND_CODE, aligned);
        check("models inserted at correct route", ModelConfig.containsModel(added,
                ModelConfig.COMMAND_CODE, "fresh-vision") && added.indexOf("other:") < added.indexOf("commandcode:"), added);
        if (args.length > 0 && "--dump-config".equals(args[0])) {
            System.out.print(ModelCatalogSync.writeLiveCatalog(rich, ModelConfig.DEEPSEEK,
                    Arrays.asList(new LiveModelCatalog.Entry("deepseek-flash", "DeepSeek Flash", true, true, true),
                            new LiveModelCatalog.Entry("deepseek-v4-pro", "DeepSeek V4 Pro", true, false, true, true, ""))));
            return;
        }

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int occurrences(String text, String part) {
        int count = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) { count++; at += part.length(); }
        return count;
    }
}
