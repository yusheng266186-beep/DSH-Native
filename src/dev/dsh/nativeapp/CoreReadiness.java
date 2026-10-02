package dev.dsh.nativeapp;

import java.io.IOException;
import java.util.List;

/** Confirm the expected model configuration through the real core after legacy import. */
final class CoreReadiness {
    private CoreReadiness() { }

    static boolean matches(String expected, String actual) {
        ModelConfig.Selection selection = ModelConfig.readSelection(expected);
        if (!selection.valid() || !selection.equals(ModelConfig.readSelection(actual))) return false;
        for (String provider : new String[]{ModelConfig.COMMAND_CODE, ModelConfig.DEEPSEEK, ModelConfig.DEEPSEEK_ACCOUNT}) {
            String block = ModelConfig.COMMAND_CODE.equals(provider) ? "llm-pi-ai"
                    : ModelConfig.DEEPSEEK_ACCOUNT.equals(provider) ? "llm-deepseek-account"
                    : YamlBlocks.hasTopLevelKey(expected, "llm-deepseek-api-key") ? "llm-deepseek-api-key" : "llm-deepseek";
            if (!YamlBlocks.hasTopLevelKey(expected, block)) continue;
            List<ModelConfig.Model> wanted = ModelConfig.modelsForProvider(expected, provider);
            List<ModelConfig.Model> current = ModelConfig.modelsForProvider(actual, provider);
            for (ModelConfig.Model model : wanted) {
                boolean present = false;
                for (ModelConfig.Model candidate : current) {
                    if (model.id.equals(candidate.id)
                            && (!model.imageKnown || candidate.imageKnown && model.image == candidate.image)
                            && ModelReasoning.choices(provider, candidate.id, candidate.details).containsAll(
                                    ModelReasoning.choices(provider, model.id, model.details))) {
                        present = true;
                        break;
                    }
                }
                if (!present) return false;
            }
        }
        return true;
    }

    static void awaitModelSettings(CoreRpcClient client, String expected, long timeoutMs) throws IOException, InterruptedException {
        if (expected == null || expected.length() == 0) return;
        long deadline = System.nanoTime() + timeoutMs * 1000000L;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("core readiness cancelled");
            try { if (matches(expected, client.modelSettings())) return; }
            catch (IOException pending) { /* The settings service can reload during namespace import. */ }
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IOException("core model configuration not ready");
    }
}
