package dev.dsh.nativeapp;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class ModelCatalogRefreshTest {
    static int pass, fail;
    static final String BASE = "agent-default-model:\n  provider: commandcode\n"
            + "  model: old\n  reasoningEffort: max\n"
            + "llm-pi-ai:\n  providers:\n    commandcode:\n"
            + "      baseURL: https://api.commandcode.ai/provider/v1\n"
            + "      apiKeyEnv: COMMANDCODE_API_KEY\n      models:\n"
            + "        - id: old\n          name: Old\n"
            + "llm-deepseek-api-key:\n  models:\n    - id: deepseek-chat\n";

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static final class Backend implements ModelCatalogRefresh.Backend {
        String key = "synthetic-key", fetchedProvider, written;
        int reads, fetches, settingsReads, writes;
        boolean changed, closedAfterFetch;
        Exception fetchFailure, writeFailure;
        ProviderCheck.Result response = ProviderCheck.classify(200,
                "{\"data\":[{\"id\":\"fresh/model\"},{\"id\":\"old\"}]}");

        @Override public void checkOpen() throws IOException {
            if (closedAfterFetch && fetches > 0) throw new IOException("cancelled");
        }
        @Override public String credential(String provider) {
            reads++;
            return changed && reads > 1 ? "different-synthetic-key" : key;
        }
        @Override public ProviderCheck.Result fetch(String provider, String credential) throws Exception {
            fetches++;
            fetchedProvider = provider;
            if (fetchFailure != null) throw fetchFailure;
            return response;
        }
        @Override public String settings() { settingsReads++; return BASE; }
        @Override public void write(String settings) throws Exception {
            if (writeFailure != null) throw writeFailure;
            written = settings; writes++;
        }
    }

    static boolean fails(Backend backend, String key) {
        try { ModelCatalogRefresh.run(ModelConfig.COMMAND_CODE, key, backend); return false; }
        catch (Exception expected) { return true; }
    }

    public static void main(String[] args) throws Exception {
        Backend ok = new Backend();
        ModelCatalogRefresh.Result result = ModelCatalogRefresh.run(ModelConfig.COMMAND_CODE, ok.key, ok);
        check("successful refresh returns current rows", result.saved() && result.entries.size() == 2);
        check("only selected provider fetched", ok.fetches == 1 && ModelConfig.COMMAND_CODE.equals(ok.fetchedProvider));
        check("catalog written once", ok.writes == 1);
        check("latest settings read after successful fetch", ok.settingsReads == 1);
        check("native catalog and persisted IDs agree", ModelConfig.modelsForProvider(ok.written, ModelConfig.COMMAND_CODE).size() == result.entries.size());
        check("default model is preserved", ModelConfig.readSelection(BASE).equals(ModelConfig.readSelection(ok.written)));
        check("other provider is preserved", ModelCatalogSync.topLevelBlock(BASE, "llm-deepseek-api-key")
                .equals(ModelCatalogSync.topLevelBlock(ok.written, "llm-deepseek-api-key")));

        for (int status : new int[]{401, 403, 429, 500, 404, 200}) {
            Backend bad = new Backend();
            bad.response = ProviderCheck.classify(status, "{}");
            ModelCatalogRefresh.Result rejected = ModelCatalogRefresh.run(ModelConfig.COMMAND_CODE, bad.key, bad);
            check("HTTP " + status + " retains response classification", !rejected.saved() && rejected.upstream.httpCode == status);
            check("HTTP " + status + " does not overwrite catalog", bad.writes == 0 && bad.settingsReads == 0);
        }

        Backend missing = new Backend(); missing.key = "";
        check("missing key fails before network", fails(missing, "") && missing.fetches == 0 && missing.writes == 0);
        Backend unsaved = new Backend();
        check("unsaved key is not silently substituted", fails(unsaved, "new-synthetic-key") && unsaved.fetches == 0);
        Backend changed = new Backend(); changed.changed = true;
        check("changed credential discards fetched catalog", fails(changed, changed.key) && changed.fetches == 1 && changed.writes == 0);
        Backend cancelled = new Backend(); cancelled.closedAfterFetch = true;
        check("cancelled client cannot write", fails(cancelled, cancelled.key) && cancelled.writes == 0);
        Backend timeout = new Backend(); timeout.fetchFailure = new SocketTimeoutException();
        check("network timeout reaches caller without writes", fails(timeout, timeout.key) && timeout.writes == 0);
        Backend disk = new Backend(); disk.writeFailure = new IOException("synthetic disk failure");
        check("failed disk write is not reported as saved", fails(disk, disk.key) && disk.writes == 0);
        Backend account = new Backend(); account.key = "";
        check("account provider does not require an API key", ModelCatalogRefresh.run(ModelConfig.DEEPSEEK_ACCOUNT, "", account).saved());
        Backend invalid = new Backend();
        try { ModelCatalogRefresh.run("unrecognized", invalid.key, invalid); check("invalid provider rejected", false); }
        catch (IOException expected) { check("invalid provider rejected", invalid.fetches == 0); }
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
