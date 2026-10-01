package dev.dsh.nativeapp;

import java.util.Arrays;
import java.util.List;

/**
 * ModelImageSupport 的离线测试。
 *
 * 这段判定出现在启动日志里，是用户核对「这个模型到底能不能看图」的唯一依据，
 * 算错了会直接误导人。此前是 MainActivity 里的一段内联正则，无法测试。
 */
public class ModelImageSupportTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static boolean same(List<String> got, String... want) {
        return got.equals(Arrays.asList(want));
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 基本识别 ===");
        check("single image model", same(ModelImageSupport.imageCapableModels(
                "      - id: m1\n        input: [ text, image ]\n"), "m1"), "wrong");
        check("only the image one", same(ModelImageSupport.imageCapableModels(
                "      - id: m1\n        input: [ text ]\n"
              + "      - id: m2\n        input: [ text, image ]\n"), "m2"), "wrong");
        check("none declared", ModelImageSupport.imageCapableModels(
                "      - id: m1\n        input: [ text ]\n").isEmpty(), "not empty");
        check("image alone", same(ModelImageSupport.imageCapableModels(
                "      - id: m6\n        input: [ image ]\n"), "m6"), "wrong");
        check("image in the middle", same(ModelImageSupport.imageCapableModels(
                "      - id: m7\n        input: [ text, image, audio ]\n"), "m7"), "wrong");

        System.out.println("=== 2. id 带引号 ===");
        check("double quotes", same(ModelImageSupport.imageCapableModels(
                "      - id: \"m3\"\n        input: [ image ]\n"), "m3"), "wrong");
        check("single quotes", same(ModelImageSupport.imageCapableModels(
                "      - id: 'm4'\n        input: [ image ]\n"), "m4"), "wrong");

        System.out.println("=== 3. input 前面有别的字段 ===");
        check("name before input", same(ModelImageSupport.imageCapableModels(
                "      - id: m5\n        name: Foo\n        input: [ text, image ]\n"), "m5"),
                "wrong");

        System.out.println("=== 4. 多行夹杂内容 ===");
        check("long block", same(ModelImageSupport.imageCapableModels(
                "      - id: m8\n"
              + "        name: A rather long model name\n"
              + "        reasoningEfforts:\n"
              + "          low: low\n"
              + "          high: high\n"
              + "        input: [ text, image ]\n"), "m8"), "wrong");

        System.out.println("=== 5. 没有 input 的条目不算 ===");
        check("missing input skipped", ModelImageSupport.imageCapableModels(
                "      - id: m9\n        name: Foo\n").isEmpty(), "not empty");

        System.out.println("=== 6. 边界 ===");
        check("null yaml", ModelImageSupport.imageCapableModels(null).isEmpty(), "not empty");
        check("empty string", ModelImageSupport.imageCapableModels("").isEmpty(), "not empty");
        check("dash without id", ModelImageSupport.imageCapableModels(
                "      - \n        input: [ image ]\n").isEmpty(), "not empty");
        check("unrelated text", ModelImageSupport.imageCapableModels(
                "just some text\nwithout any model\n").isEmpty(), "not empty");

        System.out.println("=== 7. 条目边界：input 属于紧随其后的那个条目 ===");
        // m9 没有 input、m10 有 —— 只有 m10 该被计入，且不能把 m9 的内容算给 m10。
        check("belongs to its own entry", same(ModelImageSupport.imageCapableModels(
                "      - id: m9\n        name: A\n"
              + "      - id: m10\n        input: [ image ]\n"), "m10"), "wrong");

        System.out.println("=== 8. 顺序保持 ===");
        List<String> several = ModelImageSupport.imageCapableModels(
                "      - id: a\n        input: [ image ]\n"
              + "      - id: b\n        input: [ text ]\n"
              + "      - id: c\n        input: [ image ]\n"
              + "      - id: d\n        input: [ text, image ]\n");
        check("order preserved", same(several, "a", "c", "d"), "wrong");

        System.out.println();
        System.out.println("ModelImageSupportTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
