package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 在实际界面里标注「按上游要求发送 max」这件事，但**不改变选择协议本身**。
 *
 * <p><b>为什么必须可重入</b>
 *
 * <p>第一版是 {@code if (source.contains(TAG)) return source;} —— 见到自己打的
 * 标记就直接返回。结果是：文案从「Max（请求）」改成「Max」之后，运行包里那份
 * 文件仍带着旧标记与旧文案，补丁**再也不会执行**，改动永远不生效。
 *
 * <p>运行包里的文件是会跨版本留存的（升级只换变化的那些），所以补丁必须
 * <b>每次都能重跑并把文件收敛到目标状态</b>，而不是「打过就跳过」。
 * 现在改成：先撤掉上一轮打过的补丁，再按当前规则重新打。
 */
final class ModelEffortUi {
    private static final String TAG = "/* DSH-NATIVE-MAX-REQUEST-v1 */";

    private ModelEffortUi() { }

    /** 撤掉历史补丁留下的改动，把文件还原成未打过补丁的形态。 */
    static String unpatch(String source) {
        if (source == null || !source.contains(TAG)) return source;
        List<String> lines = new ArrayList<String>(
                Arrays.asList(source.substring(source.indexOf(TAG) + TAG.length()).split("\n", -1)));
        List<String> out = new ArrayList<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            // 第一行是空串（TAG 后的换行），去掉即可还原。
            if (trimmed.startsWith("title: level.effort === \"max\"")) {
                // 上一行原本就带尾逗号（对象里还有后续字段），**原样保留**。
                // 早先误以为逗号是补丁加的而去掉它，结果锚点
                // 「"aria-checked": effectiveEffort === level.effort,」再也匹配不上，
                // 补丁从此再也打不进去。
                continue;
            }
            if (trimmed.startsWith("\"effort.maxRequest\":")
                    || trimmed.startsWith("\"effort.maxNotice\":")) {
                continue;
            }
            if (trimmed.startsWith("label: effort.id === \"max\"")) {
                out.add(line.replace("label: effort.id === \"max\" ? t(\"effort.maxRequest\") : ",
                        "label: "));
                continue;
            }
            if (trimmed.startsWith("effectiveEffort === \"max\" ? t(\"effort.maxRequest\") : ")) {
                out.add(line.replace("effectiveEffort === \"max\" ? t(\"effort.maxRequest\") : ", ""));
                continue;
            }
            if (out.isEmpty() && trimmed.length() == 0) continue;
            out.add(line);
        }
        return String.join("\n", out);
    }

    static String patch(String source) {
        if (source == null) return null;
        // 幂等：先还原，再重新应用。这样文案改动才能真正到达运行包里的文件。
        String pristine = unpatch(source);
        String[][] replacements = {
            {"label: effort.name", "label: effort.id === \"max\" ? t(\"effort.maxRequest\") : effort.name"},
            {"reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;",
             "effectiveEffort === \"max\" ? t(\"effort.maxRequest\") : reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;"},
            {"\"aria-checked\": effectiveEffort === level.effort,",
             "\"aria-checked\": effectiveEffort === level.effort,\n                                title: level.effort === \"max\" ? t(\"effort.maxNotice\") : void 0,"},
            {"\"menu.effort\": \"推理等级\",",
             "\"menu.effort\": \"推理等级\",\n            \"effort.maxRequest\": \"Max\",\n            \"effort.maxNotice\": \"发送 max 参数；是否生效由上游决定，可能被拒绝或忽略。\","},
            {"\"menu.effort\": \"Effort\",",
             "\"menu.effort\": \"Effort\",\n            \"effort.maxRequest\": \"Max\",\n            \"effort.maxNotice\": \"Send max as requested; the provider may reject or ignore it.\","}
        };
        String patched = pristine;
        for (String[] pair : replacements) {
            int at = patched.indexOf(pair[0]);
            if (at < 0 || patched.indexOf(pair[0], at + pair[0].length()) >= 0) return null;
            patched = patched.substring(0, at) + pair[1] + patched.substring(at + pair[0].length());
        }
        return TAG + "\n" + patched;
    }
}
