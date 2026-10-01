package dev.dsh.nativeapp;

import java.util.List;

/**
 * YamlBlocks 的离线测试。
 *
 * 重点是块边界判对：缩进行、注释行、空行都必须归属到**上一个**顶层键，
 * 否则会把两个块的配置混在一起写回用户的配置文件。
 */
public class YamlBlocksTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static final String YAML =
            "provider: deepseek\n"
            + "models:\n"
            + "  - id: a\n"
            + "  - id: b\n"
            + "temperature: 0.7\n"
            + "# 注释行\n"
            + "credentials:\n"
            + "  key: secret\n";

    public static void main(String[] args) {
        System.out.println("=== 1. 顶层键存在性 ===");
        check("provider exists", YamlBlocks.hasTopLevelKey(YAML, "provider"), "not found");
        check("credentials exists", YamlBlocks.hasTopLevelKey(YAML, "credentials"), "not found");
        check("missing key", !YamlBlocks.hasTopLevelKey(YAML, "nope"), "false positive");
        check("null yaml", !YamlBlocks.hasTopLevelKey(null, "x"), "false positive");
        check("null key", !YamlBlocks.hasTopLevelKey(YAML, null), "false positive");
        // 缩进的同名键不算顶层键 —— 否则会误判「已存在」而漏合并。
        check("indented name is not top-level",
                !YamlBlocks.hasTopLevelKey("root:\n  provider: x\n", "provider"), "false positive");

        System.out.println("=== 2. 取顶层块 ===");
        String models = YamlBlocks.topLevelBlock(YAML, "models");
        check("models block found", models != null, "null");
        check("models holds its list", models.contains("- id: a") && models.contains("- id: b"), models);
        check("models stops before temperature", !models.contains("temperature"), models);
        check("provider block", YamlBlocks.topLevelBlock(YAML, "provider").trim().equals("provider: deepseek"), "wrong");
        String creds = YamlBlocks.topLevelBlock(YAML, "credentials");
        check("last block runs to EOF", creds.contains("key: secret"), creds);
        check("comment belongs to previous block",
                YamlBlocks.topLevelBlock(YAML, "temperature").contains("# 注释行"), "comment lost");
        check("missing key -> null", YamlBlocks.topLevelBlock(YAML, "nope") == null, "not null");
        check("null yaml -> null", YamlBlocks.topLevelBlock(null, "x") == null, "not null");

        System.out.println("=== 3. 切分所有顶层块 ===");
        List<String[]> blocks = YamlBlocks.splitBlocks(YAML);
        // provider / models / temperature / credentials —— temperature 自己就是
        // 一个顶层键（temperature: 0.7），不是 models 的一部分。
        check("four blocks", blocks.size() == 4, "got " + blocks.size());
        check("first key", blocks.get(0)[0].equals("provider"), blocks.get(0)[0]);
        check("second key", blocks.get(1)[0].equals("models"), blocks.get(1)[0]);
        check("third key", blocks.get(2)[0].equals("temperature"), blocks.get(2)[0]);
        check("fourth key", blocks.get(3)[0].equals("credentials"), blocks.get(3)[0]);
        check("block keeps its body", blocks.get(1)[1].contains("- id: b"), blocks.get(1)[1]);
        check("null preset", YamlBlocks.splitBlocks(null).isEmpty(), "not empty");

        System.out.println("=== 4. 合并缺失的顶层块 ===");
        String preset = "provider: x\ntimeout: 30\n";
        YamlBlocks.Result r1 = YamlBlocks.mergeMissingBlocks(preset, "provider: keepme\n");
        check("only missing key added", r1.added.size() == 1 && r1.added.get(0).equals("timeout"),
                "got " + r1.added);
        check("existing value untouched", r1.content.startsWith("provider: keepme"), r1.content);
        check("new block appended", r1.content.contains("timeout: 30"), r1.content);

        System.out.println("=== 5. 什么都不缺时不改动文件 ===");
        YamlBlocks.Result r2 = YamlBlocks.mergeMissingBlocks("provider: x\n", "provider: keepme\n");
        check("nothing added", r2.isEmpty(), "added " + r2.added);
        check("content null means no write", r2.content == null, "content should be null");

        System.out.println("=== 6. 合并的边界输入 ===");
        check("null target -> creates", YamlBlocks.mergeMissingBlocks(preset, null).added.size() == 2,
                "wrong");
        check("empty target -> creates", YamlBlocks.mergeMissingBlocks(preset, "").added.size() == 2,
                "wrong");
        check("null preset -> nothing", YamlBlocks.mergeMissingBlocks(null, "a: 1").isEmpty(), "wrong");
        check("missing trailing newline handled",
                YamlBlocks.mergeMissingBlocks("timeout: 30\n", "provider: p").content.contains("timeout: 30"),
                "wrong");

        System.out.println();
        System.out.println("YamlBlocksTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
