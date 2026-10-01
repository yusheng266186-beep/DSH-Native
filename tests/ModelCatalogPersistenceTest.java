package dev.dsh.nativeapp;

/**
 * ModelCatalogPersistence 的离线测试。
 *
 * 这条规则直接对应用户反馈：「每次打开应用都需要更新模型列表后才能聊天」。
 * 根因是 DSH 会把 settings.yaml 改名成 .imported 一次性导入，而 App 下次启动
 * 又用预置把它重建，用户刚刷新的目录被覆盖。
 */
public class ModelCatalogPersistenceTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static final String IMPORTED_WITH_MODELS =
            "provider: deepseek\n"
            + "llm-pi-ai:\n"
            + "  providers:\n"
            + "    commandcode:\n"
            + "      models:\n"
            + "        - id: gpt-5.6-sol\n"
            + "          input: [ text, image ]\n"
            + "        - id: gpt-5.6-luna\n"
            + "          input: [ text ]\n"
            + "permission:\n"
            + "  preset: danger-full-access\n";

    public static void main(String[] args) {
        System.out.println("=== 1. 识别已导入的模型目录 ===");
        check("imported catalog detected",
                ModelCatalogPersistence.importedCatalogPresent(IMPORTED_WITH_MODELS), "not detected");
        check("models without llm-pi-ai -> false",
                !ModelCatalogPersistence.importedCatalogPresent("permission:\n  preset: x\n"), "false positive");
        check("null -> false",
                !ModelCatalogPersistence.importedCatalogPresent(null), "false positive");
        check("empty -> false",
                !ModelCatalogPersistence.importedCatalogPresent(""), "false positive");

        System.out.println("=== 2. llm-pi-ai 在文件开头也要认 ===");
        check("catalog at file head",
                ModelCatalogPersistence.hasModelCatalog(
                        "llm-pi-ai:\n  models:\n    - id: m1\n"), "not detected");

        System.out.println("=== 3. 空目录不算「已有目录」===");
        // 只有键、没有模型条目时覆盖用户配置同样糟
        check("empty models list is not a catalog",
                !ModelCatalogPersistence.hasModelCatalog(
                        "llm-pi-ai:\n  providers:\n    commandcode:\n      models: []\n"), "accepted");
        check("models key without entries is not a catalog",
                !ModelCatalogPersistence.hasModelCatalog("llm-pi-ai:\n  models:\n"), "accepted");

        System.out.println("=== 4. 跳过注入的判定 ===");
        // 核心场景：settings.yaml 已被 DSH 改名走，且导入内容带模型 -> 必须跳过
        check("skip when already imported",
                ModelCatalogPersistence.shouldSkipPresetInjection(false, IMPORTED_WITH_MODELS),
                "would overwrite user's catalog");
        // settings.yaml 还在时维持原逻辑：合并而不是跳过
        check("do not skip when settings exists",
                !ModelCatalogPersistence.shouldSkipPresetInjection(true, IMPORTED_WITH_MODELS),
                "wrongly skipped");
        check("do not skip on first run",
                !ModelCatalogPersistence.shouldSkipPresetInjection(false, null), "wrongly skipped");
        check("do not skip when imported had no models",
                !ModelCatalogPersistence.shouldSkipPresetInjection(false, "permission:\n  preset: x\n"),
                "wrongly skipped");

        System.out.println("=== 5. 块边界：模型段之后的顶层键不算进来 ===");
        String tricky = "llm-pi-ai:\n  providers:\n    x:\n      models:\n        - id: a\n"
                + "other:\n  models:\n";
        // models 在 llm-pi-ai 块内 -> 仍算有目录
        check("boundary keeps inner catalog", ModelCatalogPersistence.hasModelCatalog(tricky), "wrong");

        System.out.println("=== 6. 诊断导出：内容没变就不写 ===");
        String body = "llm-pi-ai:\n  models:\n    - id: a\n";
        check("first write happens",
                ModelCatalogPersistence.shouldWriteDiagnostics(null, body), "skipped first write");
        check("unchanged content skipped",
                !ModelCatalogPersistence.shouldWriteDiagnostics(body, body), "wrote again");
        check("changed content written",
                ModelCatalogPersistence.shouldWriteDiagnostics(body, body + "    - id: b\n"),
                "skipped a real change");
        check("empty block never written",
                !ModelCatalogPersistence.shouldWriteDiagnostics(body, ""), "wrote empty");
        check("null block never written",
                !ModelCatalogPersistence.shouldWriteDiagnostics(null, null), "wrote null");
        check("empty existing treated as different",
                ModelCatalogPersistence.shouldWriteDiagnostics("", body), "skipped");

        System.out.println();
        System.out.println("ModelCatalogPersistenceTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
