package dev.dsh.nativeapp;

/** Labels the requested max override in the actual composer without changing its selection protocol. */
final class ModelEffortUi {
    private static final String TAG = "/* DSH-NATIVE-MAX-REQUEST-v1 */";

    private ModelEffortUi() { }

    static String patch(String source) {
        if (source == null || source.contains(TAG)) return source;
        String[][] replacements = {
            {"label: effort.name", "label: effort.id === \"max\" ? t(\"effort.maxRequest\") : effort.name"},
            {"reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;",
             "effectiveEffort === \"max\" ? t(\"effort.maxRequest\") : reasoning.efforts.find((level) => level.id === effectiveEffort)?.name ?? effectiveEffort;"},
            {"\"aria-checked\": effectiveEffort === level.effort,",
             "\"aria-checked\": effectiveEffort === level.effort,\n                                title: level.effort === \"max\" ? t(\"effort.maxNotice\") : void 0,"},
            {"\"menu.effort\": \"推理等级\",",
             "\"menu.effort\": \"推理等级\",\n            \"effort.maxRequest\": \"Max（请求）\",\n            \"effort.maxNotice\": \"发送 max 参数；是否生效由上游决定，可能被拒绝或忽略。\","},
            {"\"menu.effort\": \"Effort\",",
             "\"menu.effort\": \"Effort\",\n            \"effort.maxRequest\": \"Max (request)\",\n            \"effort.maxNotice\": \"Send max as requested; the provider may reject or ignore it.\","}
        };
        String patched = source;
        for (String[] pair : replacements) {
            int at = patched.indexOf(pair[0]);
            if (at < 0 || patched.indexOf(pair[0], at + pair[0].length()) >= 0) return null;
            patched = patched.substring(0, at) + pair[1] + patched.substring(at + pair[0].length());
        }
        return TAG + "\n" + patched;
    }
}
