package dev.dsh.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ModelEffortUiTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--dump-client".equals(args[0])) {
            String source = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            String patched = ModelEffortUi.patch(source);
            if (patched == null || !patched.equals(ModelEffortUi.patch(patched)))
                throw new AssertionError("Published composer patch must apply idempotently");
            System.out.print(patched);
            return;
        }
        if (ModelEffortUi.patch("changed upstream source") != null)
            throw new AssertionError("Unrecognized source must remain untouched");

        // 补丁必须**可重入**：运行包里的文件跨版本留存，若打过一次就永远跳过，
        // 文案改动就再也到不了设备上。真实文件里仍留着旧的「Max（请求）」，
        // 就是这个原因。用一份「旧补丁形态」的输入验证能被纠正过来。
        String pristine =
                "x();\n"
              + "  label: effort.name\n"
              + "  const v = reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;\n"
              + "  \"aria-checked\": effectiveEffort === level.effort,\n"
              + "  \"menu.effort\": \"推理等级\",\n"
              + "  \"menu.effort\": \"Effort\",\n"
              + "y();\n";
        String first = ModelEffortUi.patch(pristine);
        if (first == null) throw new AssertionError("pristine source must patch");
        if (!first.contains("\"effort.maxRequest\": \"Max\""))
            throw new AssertionError("label must be plain Max, got: " + first);

        // 模拟「上一版打好的补丁留在运行包里」
        String legacy = first
                .replace("\"effort.maxRequest\": \"Max\"", "\"effort.maxRequest\": \"Max（请求）\"");
        String second = ModelEffortUi.patch(legacy);
        if (second == null) throw new AssertionError("legacy patched source must be re-patched");
        if (!second.contains("\"effort.maxRequest\": \"Max\"")
                || second.contains("（请求）"))
            throw new AssertionError("stale label must be corrected, got: " + second);
        if (!second.equals(first))
            throw new AssertionError("re-patching must converge to the same result");

        // 真实场景：运行包里那份文件已经被上一版补丁打过（带 TAG、旧文案、
        // 注入的 title 行）。若 unpatch 还原得不对，锚点会匹配不上，
        // 补丁从此再也打不进去 —— 而设备上那份文件正是这个样子。
        String legacyBody =
                "  ref: itemRef(),\n"
              + "  type: \"button\",\n"
              + "  \"aria-checked\": effectiveEffort === level.effort,\n"
              + "      title: level.effort === \"max\" ? t(\"effort.maxNotice\") : void 0,\n"
              + "  className: clsx(x),\n"
              + "  label: effort.id === \"max\" ? t(\"effort.maxRequest\") : effort.name\n"
              + "}));\n"
              + "  const v = reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;\n"
              + "  const menu = {\n"
              + "    \"menu.effort\": \"推理等级\",\n"
              + "    \"effort.maxRequest\": \"Max（请求）\",\n"
              + "    \"effort.maxNotice\": \"发送 max 参数；是否生效由上游决定，可能被拒绝或忽略。\",\n"
              + "    \"menu.effort\": \"Effort\",\n"
              + "    \"effort.maxRequest\": \"Max (request)\",\n"
              + "    \"effort.maxNotice\": \"Send max as requested; the provider may reject or ignore it.\"\n"
              + "  };\n";
        String legacyTagged = "/* DSH-NATIVE-MAX-REQUEST-v1 */\n" + legacyBody;
        String repatched = ModelEffortUi.patch(legacyTagged);
        if (repatched == null)
            throw new AssertionError("legacy patched file must be re-patchable");
        if (repatched.contains("（请求）") || repatched.contains("Max (request)"))
            throw new AssertionError("stale label survived: " + repatched);
        if (!repatched.contains("\"effort.maxRequest\": \"Max\""))
            throw new AssertionError("label not corrected: " + repatched);
        if (!repatched.contains("\"aria-checked\": effectiveEffort === level.effort,"))
            throw new AssertionError("anchor line lost its comma: " + repatched);
        // 再打一次结果必须稳定（幂等）。
        if (!ModelEffortUi.patch(repatched).equals(repatched))
            throw new AssertionError("re-patching must be idempotent");

        System.out.println("TOTAL: 5 pass / 0 fail");
    }
}
