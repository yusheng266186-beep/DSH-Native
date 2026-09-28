package dev.dsh.nativeapp;

import java.io.File;

public class ProjectModelSettingsTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        ModelConfig.Selection global = new ModelConfig.Selection(
                ModelConfig.COMMAND_CODE, "deepseek/deepseek-v4.1-flash", "max");
        ModelConfig.Selection chinese = new ModelConfig.Selection(
                ModelConfig.DEEPSEEK, "deepseek-v4-pro", "high");
        ProjectModelSettings.State state = new ProjectModelSettings.State();
        ProjectModelSettings.setGlobal(state, global);
        ProjectModelSettings.setOverride(state, "语文 备课", chinese);
        ProjectModelSettings.setOverride(state, "", global);

        check("named override present", ProjectModelSettings.hasOverride(state, "语文 备课"), "missing");
        check("default override present", ProjectModelSettings.hasOverride(state, ""), "missing");
        check("named effective override", chinese.equals(ProjectModelSettings.effective(
                state, "语文 备课", global)), "wrong");
        check("unknown uses global", global.equals(ProjectModelSettings.effective(
                state, "新项目", chinese)), "wrong");

        String text = ProjectModelSettings.serialize(state);
        check("has magic", text.startsWith("DSHPM1\n"), text);
        check("does not expose Chinese name", !text.contains("语文"), text);
        ProjectModelSettings.State parsed = ProjectModelSettings.parse(text);
        check("global round trip", global.equals(parsed.global), "wrong");
        check("unicode project round trip", chinese.equals(parsed.projects.get("语文 备课")), "wrong");
        check("default round trip", global.equals(parsed.projects.get("")), "wrong");

        ProjectModelSettings.clearOverride(parsed, "语文 备课");
        check("clear removes override", !ProjectModelSettings.hasOverride(parsed, "语文 备课"), "still present");
        check("clear falls back global", global.equals(ProjectModelSettings.effective(
                parsed, "语文 备课", chinese)), "wrong");

        ProjectModelSettings.State corrupt = ProjectModelSettings.parse(
                "DSHPM1\nP\tnot-hex\t00\t00\t00\nG\tzz\tzz\tzz\n");
        check("corrupt entries ignored", corrupt.projects.isEmpty() && corrupt.global == null, "accepted");
        check("wrong magic ignored", ProjectModelSettings.parse("OTHER\n").projects.isEmpty(), "accepted");

        File dir = new File("/tmp/dsh-project-model-settings-test");
        dir.mkdirs();
        File stored = new File(dir, ProjectModelSettings.FILE_NAME);
        ProjectModelSettings.writeFileAtomic(stored, text);
        check("atomic file created", stored.isFile() && stored.length() > 0, "missing");
        check("file round trip", text.equals(ProjectModelSettings.readFile(stored)), "mismatch");
        ProjectModelSettings.writeFileAtomic(stored, "DSHPM1\n");
        check("atomic replacement", "DSHPM1\n".equals(ProjectModelSettings.readFile(stored)), "wrong");
        check("temporary file removed", !new File(dir, ProjectModelSettings.FILE_NAME + ".tmp").exists(), "leftover");
        stored.delete();
        dir.delete();

        boolean badProject = false;
        try { ProjectModelSettings.setOverride(state, "../escape", chinese); }
        catch (IllegalArgumentException expected) { badProject = true; }
        check("unsafe project rejected", badProject, "accepted");

        boolean badSelection = false;
        try { ProjectModelSettings.setGlobal(state, new ModelConfig.Selection("bad", "x", "high")); }
        catch (IllegalArgumentException expected) { badSelection = true; }
        check("invalid selection rejected", badSelection, "accepted");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
