package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.List;

public class CoreReadinessTest {
    private static int pass;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("core configuration readiness");
        pass++;
    }
    public static void main(String[] args) {
        List<LiveModelCatalog.Entry> entries = new ArrayList<LiveModelCatalog.Entry>();
        entries.add(new LiveModelCatalog.Entry("new-model", "New model", true, false, true, false, ""));
        String expected = ModelConfig.updateSelection("", new ModelConfig.Selection(ModelConfig.COMMAND_CODE, "new-model", "max"));
        expected = ModelCatalogSync.writeLiveCatalog(expected, ModelConfig.COMMAND_CODE, entries);
        check(!CoreReadiness.matches(expected, ""));
        check(!CoreReadiness.matches(expected, ModelConfig.updateSelection("", ModelConfig.readSelection(expected))));
        check(CoreReadiness.matches(expected, expected));
        String changed = ModelConfig.updateSelection(expected, new ModelConfig.Selection(ModelConfig.COMMAND_CODE, "new-model", "high"));
        check(!CoreReadiness.matches(expected, changed));
        entries.add(new LiveModelCatalog.Entry("another-model", "Another", true, false, true, false, ""));
        String expanded = ModelCatalogSync.writeLiveCatalog(expected, ModelConfig.COMMAND_CODE, entries);
        check(!CoreReadiness.matches(expanded, expected));
        check(CoreReadiness.matches(expected, expanded));
        String account = ModelConfig.updateSelection(expanded, new ModelConfig.Selection(ModelConfig.DEEPSEEK_ACCOUNT, "account-model", "max"));
        check(!CoreReadiness.matches(expected, account));
        check(CoreReadiness.matches(account, account));
        check(!CoreReadiness.matches("", account));
        System.out.println("TOTAL: " + pass + " pass / 0 fail");
    }
}
