package dev.dsh.nativeapp;

import java.util.List;

public class ModelConfigTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        String yaml = "ui-theme:\n  preference: dark\n"
                + "agent-default-model:\n  provider: commandcode\n  model: \"alpha/model\"\n"
                + "  reasoningEffort: high\n"
                + "llm-pi-ai:\n  providers:\n    commandcode:\n"
                + "      displayName: Command Code\n      models:\n"
                + "        - id: \"alpha/model\"\n          name: Alpha\n"
                + "          input: [ text, image ]\n          reasoningEfforts: { low: low }\n"
                + "        - id: beta\n          name: Beta\n";

        ModelConfig.Selection original = ModelConfig.readSelection(yaml);
        check("reads provider", ModelConfig.COMMAND_CODE.equals(original.provider), original.provider);
        check("reads model", "alpha/model".equals(original.model), original.model);
        check("reads effort", "high".equals(original.effort), original.effort);

        ModelConfig.Selection replacement = new ModelConfig.Selection(
                ModelConfig.DEEPSEEK, "deepseek-v4-pro", "max");
        String updated = ModelConfig.updateSelection(yaml, replacement);
        check("preserves unrelated block", updated.contains("ui-theme:"), updated);
        check("replaces provider", updated.contains("provider: deepseek-official"), updated);
        check("quotes model", updated.contains("model: \"deepseek-v4-pro\""), updated);
        check("only one default block", occurrences(updated, "agent-default-model:") == 1, updated);
        check("round trip selection", replacement.equals(ModelConfig.readSelection(updated)), "mismatch");
        String finalBlock = "permission:\n  defaultPreset: auto\nagent-default-model:\n"
                + "  provider: commandcode\n  model: beta\n  reasoningEffort: low";
        String finalUpdated = ModelConfig.updateSelection(finalBlock, replacement);
        check("replaces final block without newline",
                replacement.equals(ModelConfig.readSelection(finalUpdated))
                        && occurrences(finalUpdated, "agent-default-model:") == 1, finalUpdated);

        List<ModelConfig.Model> models = ModelConfig.modelsForProvider(yaml, ModelConfig.COMMAND_CODE);
        check("parses two models", models.size() == 2, String.valueOf(models.size()));
        check("parses display name", "Alpha".equals(models.get(0).name), models.get(0).name);
        check("parses image capability", models.get(0).image, "false");
        check("parses reasoning capability", models.get(0).reasoning, "false");
        check("text model stays text", !models.get(1).image, "true");
        check("contains configured model", ModelConfig.containsModel(yaml, ModelConfig.COMMAND_CODE, "beta"), "missing");
        check("rejects missing model", !ModelConfig.containsModel(yaml, ModelConfig.COMMAND_CODE, "gamma"), "found");
        check("deepseek catalog provided", ModelConfig.modelsForProvider(yaml, ModelConfig.DEEPSEEK).size() == 2, "wrong");

        String creds = "version: 1\nrefs:\n  OTHER: keep\n";
        creds = ModelConfig.updateCredentialRef(creds, "COMMANDCODE_API_KEY", "key:#value");
        check("adds quoted credential", creds.contains("COMMANDCODE_API_KEY: \"key:#value\""), creds);
        check("reads quoted credential", "key:#value".equals(ModelConfig.readCredentialRef(
                creds, "COMMANDCODE_API_KEY")), ModelConfig.readCredentialRef(creds, "COMMANDCODE_API_KEY"));
        String changed = ModelConfig.updateCredentialRef(creds, "COMMANDCODE_API_KEY", "new-key");
        check("updates once", occurrences(changed, "COMMANDCODE_API_KEY") == 1, changed);
        check("preserves other credential", changed.contains("OTHER: keep"), changed);
        String blank = ModelConfig.updateCredentialRef(changed, "DEEPSEEK_API_KEY", "");
        check("blank is explicit", blank.contains("DEEPSEEK_API_KEY: \"\""), blank);

        check("rejects unknown provider", ModelConfig.normalizeProvider("unknown").length() == 0, "accepted");
        check("rejects model newline", ModelConfig.normalizeModel("bad\nmodel").length() == 0, "accepted");
        check("unknown effort falls back", "medium".equals(ModelConfig.normalizeEffort("random")), "wrong");

        boolean badKey = false;
        try { ModelConfig.updateCredentialRef(creds, "EVIL_KEY", "x"); }
        catch (IllegalArgumentException expected) { badKey = true; }
        check("credential key allowlist", badKey, "accepted");

        boolean control = false;
        try { ModelConfig.updateCredentialRef(creds, "DEEPSEEK_API_KEY", "x\ny"); }
        catch (IllegalArgumentException expected) { control = true; }
        check("credential controls rejected", control, "accepted");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static int occurrences(String text, String part) {
        int count = 0, at = 0;
        while ((at = text.indexOf(part, at)) >= 0) { count++; at += part.length(); }
        return count;
    }
}
