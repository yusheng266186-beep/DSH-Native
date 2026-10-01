package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 顶层 YAML 块的定位与合并（纯逻辑，可离线测试）。
 *
 * <p>抽出来的原因：原先这几段散在 {@code MainActivity} 的
 * {@code topLevelBlock} / {@code hasTopLevelKey} / {@code mergeTopLevelBlocks} 里，
 * 靠逐行解析判断块边界，改坏时不会报错，只会**静默合并错块或漏合并**——
 * 而目标文件是用户的模型与凭据配置。
 *
 * <p>块边界的判据是「顶格且形如 {@code key:} 的行」。缩进行、注释行、空行
 * 都属于它上面的那个块。
 */
final class YamlBlocks {

    /** 顶层键的行首形态：顶格、合法标识符、紧跟冒号。 */
    private static final Pattern TOP_KEY =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*):");

    private YamlBlocks() { }

    /** 这个文本里是否已有该顶层键（只看顶格行，缩进的同名键不算）。 */
    static boolean hasTopLevelKey(String yaml, String key) {
        if (yaml == null || key == null) return false;
        for (String line : yaml.split("\n", -1)) {
            if (line.length() == 0 || line.charAt(0) == ' '
                    || line.charAt(0) == '\t' || line.charAt(0) == '#') {
                continue;
            }
            int c = line.indexOf(':');
            if (c <= 0) continue;
            if (line.substring(0, c).trim().equals(key)) return true;
        }
        return false;
    }

    /**
     * 取某个顶层键的完整块（到下一个顶层键或文件尾）。
     *
     * @return 键不存在时返回 null
     */
    static String topLevelBlock(String yaml, String key) {
        if (yaml == null) return null;
        String[] lines = yaml.split("\n", -1);
        int start = -1, end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            // 缩进、注释、空行都不算块边界。
            if (l.length() == 0 || l.charAt(0) == ' ' || l.charAt(0) == '\t'
                    || l.charAt(0) == '#') {
                continue;
            }
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim();
            if (start < 0) {
                if (k.equals(key)) start = i;
            } else {
                end = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    /** 按出现顺序切出所有顶层块。 */
    static List<String[]> splitBlocks(String preset) {
        List<String[]> blocks = new ArrayList<String[]>();
        if (preset == null) return blocks;
        String key = null;
        StringBuilder block = new StringBuilder();
        for (String ln : preset.split("\n", -1)) {
            Matcher m = TOP_KEY.matcher(ln);
            if (m.find()) {
                if (key != null) blocks.add(new String[]{key, block.toString()});
                key = m.group(1);
                block = new StringBuilder();
            }
            if (key != null) block.append(ln).append('\n');
        }
        if (key != null) blocks.add(new String[]{key, block.toString()});
        return blocks;
    }

    /**
     * 把 preset 中「目标尚不存在的顶层块」追加到 target 之后。
     *
     * @return {@code content} 为 null 表示无需改动；{@code added} 是新追加的键
     */
    static Result mergeMissingBlocks(String preset, String target) {
        String tgt = target == null ? "" : target;
        List<String> added = new ArrayList<String>();
        StringBuilder append = new StringBuilder();
        for (String[] block : splitBlocks(preset)) {
            if (hasTopLevelKey(tgt, block[0])) continue;
            append.append(block[1]);
            added.add(block[0]);
        }
        if (added.isEmpty()) return new Result(null, added);
        StringBuilder out = new StringBuilder(tgt);
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        out.append('\n').append(append);
        return new Result(out.toString(), added);
    }

    /** 合并结果。 */
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
}
