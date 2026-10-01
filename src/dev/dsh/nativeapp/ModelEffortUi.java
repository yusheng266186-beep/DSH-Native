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

    /** 替换表：每对是 {未打补丁的原文, 注入后的文本}。 */
    private static final String[][] REPLACEMENTS = {
        {"label: effort.name",
         "label: effort.id === \"max\" ? t(\"effort.maxRequest\") : effort.name"},
        {"reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;",
         "effectiveEffort === \"max\" ? t(\"effort.maxRequest\") : reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;"},
        {"\"aria-checked\": effectiveEffort === level.effort,",
         "\"aria-checked\": effectiveEffort === level.effort,\n                                title: level.effort === \"max\" ? t(\"effort.maxNotice\") : void 0,"},
        {"\"menu.effort\": \"推理等级\",",
         "\"menu.effort\": \"推理等级\",\n            \"effort.maxRequest\": \"Max\",\n            \"effort.maxNotice\": \"发送 max 参数；是否生效由上游决定，可能被拒绝或忽略。\","},
        {"\"menu.effort\": \"Effort\",",
         "\"menu.effort\": \"Effort\",\n            \"effort.maxRequest\": \"Max\",\n            \"effort.maxNotice\": \"Send max as requested; the provider may reject or ignore it.\","}
    };

    /**
     * 认得**历史上注入过的** maxRequest 文案。
     *
     * <p>运行包里的文件会跨版本留存，而文案改过（「Max（请求）」-&gt;「Max」），
     * 所以还原不能只认当前值 —— 否则旧文件永远还原不出来，补丁就再也打不进去。
     */
    private static final java.util.regex.Pattern LEGACY_LABEL =
            java.util.regex.Pattern.compile(
                    "\\n *\"effort\\.maxRequest\\": \"[^\"]*\","
                  + "\\n *\"effort\\.maxNotice\\": \"[^\"]*\",");

    /**
     * 撤掉历史补丁留下的改动，把文件还原成未打过补丁的形态。
     *
     * <p>反向复用 {@link #REPLACEMENTS}，不另写一份还原规则：两份规则只要有一处
     * 不一致（缩进、空格、字面量），还原就会静默失败，而症状是「补丁再也打不进
     * 去」，很难从日志看出来。
     *
     * @return 还原后的源码；匹配不到或匹配多次时返回 null，交由 {@link #patch}
     *         判定为结构不匹配而**放弃修改**（宁可不改，也不能改坏）
     */
    static String unpatch(String source) {
        if (source == null || !source.contains(TAG)) return source;
        String out = source.substring(source.indexOf(TAG) + TAG.length());
        if (out.startsWith("\n")) out = out.substring(1);
        // 中英文两段文案可能被历史版本写成别的样子，用正则整体去掉。
        // 中英文各一段，正常就是 2 段；多于 2 说明文件结构异常，宁可放弃。
        java.util.regex.Matcher legacy = LEGACY_LABEL.matcher(out);
        int found = 0;
        while (legacy.find()) found++;
        if (found > 2) return null;
        out = LEGACY_LABEL.matcher(out).replaceAll("");
        // 文案段已被上面整体去掉，这里只还原**代码形态**的三条。
        // 菜单项那两条（REPLACEMENTS[3]/[4]）在打补丁时会重新写入，
        // 因此还原阶段找不到它们是正常的，不能据此判定失败。
        for (int i = 0; i < REPLACEMENTS.length - 2; i++) {
            String injected = REPLACEMENTS[i][1];
            int index = out.indexOf(injected);
            if (index < 0 || out.indexOf(injected, index + injected.length()) >= 0) return null;
            out = out.substring(0, index) + REPLACEMENTS[i][0]
                    + out.substring(index + injected.length());
        }
        return out;
    }

    static String patch(String source) {
        if (source == null) return null;
        // 幂等：先还原，再重新应用。这样文案改动才能真正到达运行包里的文件。
        String pristine = unpatch(source);
        // unpatch 还原不出来（结构已变、或带着本补丁认不出的旧改动）时，
        // 必须放弃而不是继续 —— 半还原的文件再打补丁只会写坏。
        if (pristine == null) return null;
        String patched = pristine;
        for (String[] pair : REPLACEMENTS) {
            int at = patched.indexOf(pair[0]);
            if (at < 0 || patched.indexOf(pair[0], at + pair[0].length()) >= 0) return null;
            patched = patched.substring(0, at) + pair[1] + patched.substring(at + pair[0].length());
        }
        return TAG + "\n" + patched;
    }
}
