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
        live.add(new LiveModelCatalog.Entry("fresh/model", "Fresh model", true, true, true,
                true, "reasoningEfforts: { high: high, max: max }\n"));
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
        check("requested max wire is literal", rich.contains("max: max") && !rich.contains("max: ultra"), rich);
        check("per-model transport retained", rich.contains("            supportsReasoningEffort: true"), rich);
        check("upstream text overrides old vision", !ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(0).image && rich.contains("input: [ text ]"), rich);
        check("new vision written", ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(1).image, rich);
        // 上游不返回能力字段（实测 85 个模型 0 个带 input_modalities），
        // 所以「未知」是常态而非例外。若保持未知，DSH 会按「仅文字」处理，
        // 多模态模型直接发不了图。改为：未知一律按支持视觉处理。
        check("unknown vision now defaults to image", ModelConfig.modelsForProvider(rich,
                ModelConfig.COMMAND_CODE).get(2).image
                && ModelConfig.modelsForProvider(rich,
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

        // ---- 视觉能力：未知即默认支持 ----
        // 上游模型接口不返回能力字段（实测 85 个模型 0 个带 input_modalities），
        // 所以刷新后若不补声明，DSH 会按「仅文字」处理、多模态模型发不了图。
        // 现在统一默认写 input: [ text, image ]，由上游决定收不收。
        String visionYml = "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      models:\n"
                + "        - id: plain-model\n"
                + "          name: Plain\n"
                + "          contextWindow: 128000\n";
        java.util.List<LiveModelCatalog.Entry> unknownVision =
                java.util.Arrays.asList(new LiveModelCatalog.Entry(
                        "plain-model", "Plain", true, false, false, false, ""));
        String wrote = ModelCatalogSync.writeLiveCatalog(visionYml, "commandcode", unknownVision);
        check("unknown model defaults to image input",
                wrote.contains("input: [ text, image ]"),
                "no vision declaration written:\n" + wrote);
        check("vision declaration not duplicated",
                occurrences(wrote, "input: [ text, image ]") == 1,
                "declared more than once");

        // 上游若将来明确声明「仅文字」，应以它为准，不被默认值覆盖。
        java.util.List<LiveModelCatalog.Entry> textOnly =
                java.util.Arrays.asList(new LiveModelCatalog.Entry(
                        "text-model", "Text", true, false, false, true, ""));
        String wroteText = ModelCatalogSync.writeLiveCatalog(visionYml, "commandcode", textOnly);
        check("explicit text-only from upstream is respected",
                wroteText.contains("input: [ text ]")
                        && !wroteText.contains("input: [ text, image ]"),
                "upstream declaration ignored:\n" + wroteText);

        // 多个模型都要被写到，不能只补第一个。
        String multiYml = "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      models:\n";
        String multi = ModelCatalogSync.writeLiveCatalog(multiYml, "commandcode",
                java.util.Arrays.asList(
                        new LiveModelCatalog.Entry("m1", "M1", true, false, false, false, ""),
                        new LiveModelCatalog.Entry("m2", "M2", true, false, false, false, ""),
                        new LiveModelCatalog.Entry("m3", "M3", true, false, false, false, "")));
        check("every model gets a declaration",
                occurrences(multi, "input: [ text, image ]") == 3,
                "expected 3, got " + occurrences(multi, "input: [ text, image ]"));

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int occurrences(String text, String part) {
        int count = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) { count++; at += part.length(); }
        return count;
    }
}
