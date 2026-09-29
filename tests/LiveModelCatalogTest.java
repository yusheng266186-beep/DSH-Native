package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LiveModelCatalogTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        List<ModelConfig.Model> configured = new ArrayList<ModelConfig.Model>();
        configured.add(new ModelConfig.Model("known", "Known model", true, true));
        configured.add(new ModelConfig.Model("local-only", "Local only", false, false));
        List<LiveModelCatalog.Entry> entries = LiveModelCatalog.reconcile(
                Arrays.asList("known", "new-upstream", "known", "  "), configured);

        check("only upstream ids visible", entries.size() == 2, String.valueOf(entries.size()));
        check("local-only omitted", !LiveModelCatalog.contains(entries, "local-only"), "visible");
        check("known entry selectable", LiveModelCatalog.selectable(entries, "known"), "blocked");
        check("metadata preserved", entries.get(0).image && entries.get(0).reasoning,
                "metadata lost");
        check("upstream-only visible", LiveModelCatalog.contains(entries, "new-upstream"),
                "missing");
        check("upstream-only selectable", LiveModelCatalog.selectable(entries, "new-upstream"),
                "blocked");
        check("all upstream entries selectable", LiveModelCatalog.selectableCount(entries) == 2,
                String.valueOf(LiveModelCatalog.selectableCount(entries)));

        ModelConfig.Selection baseline = new ModelConfig.Selection(
                ModelConfig.COMMAND_CODE, "known", "medium");
        ModelConfig.Selection effortOnly = new ModelConfig.Selection(
                ModelConfig.COMMAND_CODE, "known", "high");
        ModelConfig.Selection unknown = new ModelConfig.Selection(
                ModelConfig.COMMAND_CODE, "new-upstream", "medium");
        check("fresh supported choice saves", LiveModelCatalog.canSave(
                baseline, effortOnly, false, true, entries), "rejected");
        check("unchanged route survives offline", LiveModelCatalog.canSave(
                baseline, effortOnly, false, false, new ArrayList<LiveModelCatalog.Entry>()),
                "rejected");
        check("live removal blocks unchanged route", !LiveModelCatalog.canSave(
                baseline, effortOnly, false, true, new ArrayList<LiveModelCatalog.Entry>()),
                "accepted");
        check("onboarding requires live catalog", !LiveModelCatalog.canSave(
                baseline, effortOnly, true, false, entries), "accepted");
        check("unknown upstream model accepted", LiveModelCatalog.canSave(
                baseline, unknown, false, true, entries), "rejected");
        check("changed route requires live catalog", !LiveModelCatalog.canSave(
                baseline, new ModelConfig.Selection(ModelConfig.DEEPSEEK, "known", "medium"),
                false, false, entries), "accepted");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
