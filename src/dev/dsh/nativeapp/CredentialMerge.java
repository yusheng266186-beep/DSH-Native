package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 凭据文件 {@code refs:} 段的解析与合并（纯逻辑，可离线测试）。
 *
 * <p>抽出来的原因：这段代码原先整块写在 {@code MainActivity.mergeCredentials} 里，
 * 操作的是**用户的 API 密钥文件**，却一行测试都跑不了。它有两个容易出错的点：
 *
 * <ul>
 *   <li><b>键匹配必须是整行匹配</b>。原来用 {@code target.indexOf(k + ":") >= 0}
 *       判断「键已存在」，那么 {@code FOO} 会被 {@code FOOBAR: ...} 命中，
 *       导致本该导入的密钥被静默跳过 —— 用户看到的是「导入了 0 个」。</li>
 *   <li><b>YAML 的 {@code refs:} 段边界</b>。缩进的续行属于本段，
 *       遇到第一个非空白且非缩进的行就结束。</li>
 * </ul>
 *
 * <p>不碰 {@code org.json} 与 Android API，因此可在普通 JVM 上直接测试。
 */
final class CredentialMerge {

    /** {@code refs:} 段头。 */
    private static final Pattern REFS_HEADER = Pattern.compile("(?m)^refs\\s*:.*$");
    /** 段内条目要求「非空白开头」且含冒号。 */
    private static final Pattern REFS_ENTRY = Pattern.compile("^refs\\s*:.*");

    private CredentialMerge() { }

    /** 合并结果：{@code content} 是写回目标文件的内容，{@code added} 是新导入的键。 */
    static final class Result {
        final String content;
        final List<String> added;

        Result(String content, List<String> added) {
            this.content = content;
            this.added = added;
        }

        boolean isEmpty() {
            return added.isEmpty();
        }
    }

    /** 取出 {@code text} 的 {@code refs:} 段里的条目（已 trim，形如 {@code KEY: value}）。 */
    static List<String> readRefs(String text) {
        List<String> refs = new ArrayList<String>();
        if (text == null) return refs;
        boolean inRefs = false;
        for (String ln : text.split("\n", -1)) {
            if (REFS_ENTRY.matcher(ln).matches()) { inRefs = true; continue; }
            if (!inRefs) continue;
            // 段内允许缩进续行；遇到顶格非空行说明本段结束。
            if (ln.length() > 0 && !Character.isWhitespace(ln.charAt(0))) break;
            String t = ln.trim();
            if (t.length() > 0 && t.indexOf(':') > 0) refs.add(t);
        }
        return refs;
    }

    /** 条目里的键（冒号之前的部分）。 */
    static String keyOf(String entry) {
        int idx = entry.indexOf(':');
        return idx <= 0 ? "" : entry.substring(0, idx).trim();
    }

    /**
     * 目标文件里是否已经有这个键。
     *
     * <p>必须**整行匹配并跳过注释行**。原来用 {@code target.indexOf(k + ":") >= 0}
     * 判断，于是被注释掉的 {@code # DEEPSEEK_API_KEY: ...} 也会被当成「已存在」，
     * 结果是**导入被静默跳过、界面却显示成功**，用户的密钥其实从没写进去。
     * 这是实打实影响使用的一种失败，故按整行键判定。
     */
    static boolean hasKey(String targetText, String key) {
        if (targetText == null || key == null || key.length() == 0) return false;
        for (String ln : targetText.split("\n", -1)) {
            String t = ln.trim();
            if (t.startsWith("#")) continue;                 // 注释不算已存在
            if (keyOf(t).equals(key)) return true;
        }
        return false;
    }

    /**
     * 把 {@code srcText} 的 refs 条目合并进 {@code targetText}，跳过已存在的键。
     *
     * @return 新导入的键；{@code content} 为 null 表示无需改动目标文件
     */
    static Result merge(String srcText, String targetText) {
        String target = targetText == null ? "" : targetText;
        List<String> added = new ArrayList<String>();
        List<String> addedLines = new ArrayList<String>();
        for (String entry : readRefs(srcText)) {
            String key = keyOf(entry);
            if (key.length() == 0) continue;
            if (hasKey(target, key)) continue;
            addedLines.add(entry);
            added.add(key);
        }
        if (added.isEmpty()) return new Result(null, added);

        StringBuilder out = new StringBuilder(target);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        Matcher m = REFS_HEADER.matcher(out);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            sb.append(out, 0, m.end());
            for (String entry : addedLines) sb.append("\n  ").append(entry);
            sb.append(out.substring(m.end()));
            return new Result(sb.toString(), added);
        }
        out.append("refs:\n");
        for (String entry : addedLines) out.append("  ").append(entry).append('\n');
        return new Result(out.toString(), added);
    }
}
