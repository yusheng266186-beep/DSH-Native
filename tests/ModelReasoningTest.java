package dev.dsh.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class ModelReasoningTest {
    static int pass, fail;
    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + name); }
    }
    public static void main(String[] args) throws Exception {
        String cc = ModelConfig.COMMAND_CODE;
        String broken = "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      api: openai-completions\n      models:\n"
                + "        - id: Qwen/Qwen3.8-Max\n          input: [ text, image ]\n"
                + "          contextWindow: 999999\n          maxTokens: 32000\n"
                + "          reasoningEfforts: { off: null, low: low, medium: medium, high: high, xhigh: xhigh, max: max }\n";
        ProviderCheck.Result response = ProviderCheck.classify(200, "{\"data\":["
                + "{\"id\":\"Qwen/Qwen3.8-Max\"},{\"id\":\"deepseek/deepseek-v4.1-flash-fast\"},"
                + "{\"id\":\"gpt-6.1-sol\"},{\"id\":\"moonshotai/Kimi-K2.6\"},"
                + "{\"id\":\"unseen-model\"}]}");
        List<LiveModelCatalog.Entry> entries = LiveModelCatalog.reconcile(response,
                ModelConfig.modelsForProvider(broken, cc), cc);
        String repaired = ModelCatalogSync.writeLiveCatalog(broken, cc, entries);
        check("Qwen exact levels replace invalid broad preset", ModelReasoning.choices(cc,
                "Qwen/Qwen3.8-Max", "").equals(Arrays.asList("low", "medium", "xhigh", "max")));
        check("new flash fast gets levels without local ID", ModelReasoning.choices(cc,
                "deepseek/deepseek-v4.1-flash-fast", "").equals(Arrays.asList("low", "high", "max")));
        check("new GPT model gets five levels", ModelReasoning.choices(cc, "gpt-6.1-sol", "").size() == 5);
        check("automatic model gets default and requested max", entries.get(3).reasoning
                && ModelReasoning.choices(cc, entries.get(3).id, entries.get(3).details).equals(Arrays.asList("off", "max")));
        check("unknown model gets requested max without other invented levels", entries.get(4).selectable
                && ModelReasoning.choices(cc, "unseen-model", "").equals(Arrays.asList("off", "max")));
        check("vision and limits survive repair", entries.get(0).image
                && repaired.contains("contextWindow: 999999") && repaired.contains("maxTokens: 32000"));
        check("repeat refresh does not lose efforts", repaired.equals(ModelCatalogSync.writeLiveCatalog(repaired,
                cc, LiveModelCatalog.reconcile(response, ModelConfig.modelsForProvider(repaired, cc), cc))));
        check("upgrade repairs saved live catalog offline", ModelCatalogSync.mergePresetProviderBlock(broken,
                broken.replace("      models:", "      " + ModelCatalogSync.LIVE_MARKER_PREFIX + cc + "\n      models:"))
                .contains("reasoningEfforts: { low: low, medium: medium, xhigh: xhigh, max: max }"));
        check("case variation resolves capabilities without altering ID", ModelReasoning.choices(cc,
                "qwen/qwen3.8-max", "").equals(ModelReasoning.choices(cc, "Qwen/Qwen3.8-Max", "")));
        ProviderCheck.Result explicit = ProviderCheck.classify(200, "{\"data\":["
                + "{\"id\":\"gpt-6.1-sol\",\"effort\":{\"supported_levels\":[\"high\"]}},"
                + "{\"id\":\"new-wire\",\"reasoningEfforts\":{\"low\":\"low\",\"max\":\"ultra\"}},"
                + "{\"id\":\"no-effort\",\"reasoningEfforts\":[]},"
                + "{\"id\":\"boolean-only\",\"reasoning\":true},"
                + "{\"id\":\"bad-wire\",\"reasoningEfforts\":{\"high\":\"bad\\n yaml\"}}]}");
        List<LiveModelCatalog.Entry> live = LiveModelCatalog.reconcile(explicit,
                ModelConfig.modelsForProvider(repaired, cc), cc);
        check("upstream supported_levels wins over official snapshot", ModelReasoning.choices(cc,
                live.get(0).id, live.get(0).details).equals(Arrays.asList("high", "max")));
        String next = ModelCatalogSync.writeLiveCatalog(repaired, cc, live);
        check("upstream authority survives restart and refresh", ModelCatalogSync.mergePresetProviderBlock(broken,
                ModelCatalogSync.topLevelBlock(next, "llm-pi-ai")).contains("reasoningEfforts: { high: high, max: max }"));
        check("max request remains literal even for prior alias", next.contains("low: low, max: max") && !next.contains("max: ultra"));
        check("upstream empty levels keeps default and adds max", ModelReasoning.choices(cc, live.get(2).id, live.get(2).details).equals(Arrays.asList("off", "max")));
        check("reasoning true adds only default and requested max", ModelReasoning.choices(cc, live.get(3).id, live.get(3).details).equals(Arrays.asList("off", "max")));
        check("invalid upstream wire cannot inject YAML", !next.contains("bad\\n yaml"));
        check("direct provider choices differ from Command Code V4", ModelReasoning.choices(ModelConfig.DEEPSEEK,
                "deepseek-v4-pro", "").equals(Arrays.asList("off", "low", "high", "max"))
                && ModelReasoning.choices(cc, "deepseek/deepseek-v4-pro", "").equals(Arrays.asList("high", "max")));
        check("direct catalog thinking levels do not hide adapter Off", ModelReasoning.choices(ModelConfig.DEEPSEEK,
                "deepseek-flash", ModelReasoning.UPSTREAM + "\nreasoningEfforts: { low: low, high: high, max: max }\n")
                .equals(Arrays.asList("off", "low", "high", "max")));
        check("invalid saved effort corrected", ModelReasoning.preferred(Arrays.asList("low", "high", "max"),
                "medium").equals("high"));
        check("valid saved effort kept", ModelReasoning.preferred(Arrays.asList("low", "high", "max"), "max").equals("max"));
        check("default model uses no synthetic effort", ModelReasoning.preferred(Arrays.<String>asList(), "max").equals("off"));
        check("multiline user mapping preserved", ModelReasoning.choices(cc, "custom",
                "reasoningEfforts:\n  high: high\n  max: ultra\ncompat:\n  supportsReasoningEffort: true\n")
                .equals(Arrays.asList("high", "max")));
        check("null off mapping remains supported", ModelReasoning.choices(cc, "custom",
                "reasoningEfforts: { off: null, high: high }\n").equals(Arrays.asList("off", "high", "max")));
        check("Space Bunny preserves original levels and adds max", ModelReasoning.choices(cc,
                "stealth/space-bunny-alpha", "").equals(Arrays.asList("low", "medium", "high", "max")));
        check("Space Bunny official record stays truthful", ModelReasoning.supportedDeclaration(cc,
                "stealth/space-bunny-alpha", "").equals("{ low: low, medium: medium, high: high }"));
        check("max selection survives normalization for unknown model", ModelReasoning.normalizeSelection(repaired,
                new ModelConfig.Selection(cc, "unseen-model", "max")).effort.equals("max"));
        check("other custom wire values survive", ModelReasoning.declaration(cc, "custom",
                "reasoningEfforts: { high: ultra }\n").equals("{ high: ultra, max: max }"));
        if (args.length > 0 && "--dump-config".equals(args[0])) {
            ProviderCheck.Result all = ProviderCheck.classify(200, new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
            String allModels = ModelCatalogSync.writeLiveCatalog(broken, cc,
                    LiveModelCatalog.reconcile(all, ModelConfig.modelsForProvider(broken, cc), cc));
            System.out.print(ModelCatalogSync.writeLiveCatalog(allModels, ModelConfig.DEEPSEEK,
                    Arrays.asList(new LiveModelCatalog.Entry("deepseek-flash", "Flash", true, true, true),
                            new LiveModelCatalog.Entry("deepseek-v4-pro", "Pro", true, false, true))));
            return;
        }
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
