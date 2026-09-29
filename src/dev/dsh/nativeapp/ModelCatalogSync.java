package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一次成功的上游模型目录写入 DSH 的 provider catalog。
 *
 * <p>模型选择器只读实时上游响应，但 DSH WebUI 还需要在 settings.yaml
 * 里有对应的模型条目才能显示和解析它。这个类只处理纯文本/纯逻辑，便于
 * 在普通 JVM 上覆盖幂等、重复和升级预设保留等边界。</p>
 */
final class ModelCatalogSync {
    static final String LIVE_MARKER_PREFIX = "# dsh-native-live-catalog: ";
    private static final String COMMAND_PROVIDER = "commandcode";
    private static final String COMMAND_BLOCK = "llm-pi-ai";
    private static final String DEEPSEEK_BLOCK = "llm-deepseek-api-key";
    private static final String DEEPSEEK_LEGACY_BLOCK = "llm-deepseek";

    private ModelCatalogSync() { }

    /**
     * 用本次上游响应替换指定 provider 的 DSH catalog。
     *
     * <p>只写模型 ID、名称和 App 已知的能力提示；不会把 API key 或网络响应
     * 原文写入配置。实时目录为空时拒绝写入，避免一次失败请求清空可用目录。</p>
     */
    static String writeLiveCatalog(String yaml, String provider,
                                    List<LiveModelCatalog.Entry> upstream) {
        String normalized = ModelConfig.normalizeProvider(provider);
        if (normalized.length() == 0) throw new IllegalArgumentException("invalid provider");
        List<LiveModelCatalog.Entry> entries = clean(upstream);
        if (entries.isEmpty()) throw new IllegalArgumentException("empty live catalog");
        String source = yaml == null ? "" : yaml;
        if (ModelConfig.COMMAND_CODE.equals(normalized)) {
            String block = topLevelBlock(source, COMMAND_BLOCK);
            if (block == null) {
                block = commandBlock(entries);
                return appendTopLevel(source, block);
            }
            String updated = replaceModels(block, COMMAND_PROVIDER, entries, true);
            return replaceTopLevelBlock(source, COMMAND_BLOCK, updated);
        }

        String blockName = topLevelBlock(source, DEEPSEEK_BLOCK) != null
                ? DEEPSEEK_BLOCK : DEEPSEEK_LEGACY_BLOCK;
        String block = topLevelBlock(source, blockName);
        if (block == null) {
            blockName = DEEPSEEK_BLOCK;
            block = deepSeekBlock(entries);
            return appendTopLevel(source, block);
        }
        String updated = replaceModels(block, null, entries, false);
        return replaceTopLevelBlock(source, blockName, updated);
    }

    /** Returns whether a provider catalog was written from a live upstream response. */
    static boolean hasLiveCatalog(String yaml, String provider) {
        String normalized = ModelConfig.normalizeProvider(provider);
        if (normalized.length() == 0) return false;
        String blockName = ModelConfig.COMMAND_CODE.equals(normalized)
                ? COMMAND_BLOCK
                : (topLevelBlock(yaml, DEEPSEEK_BLOCK) != null
                    ? DEEPSEEK_BLOCK : DEEPSEEK_LEGACY_BLOCK);
        String block = topLevelBlock(yaml, blockName);
        return block != null && block.contains(LIVE_MARKER_PREFIX + normalized);
    }

    /**
     * Merge the static provider preset while retaining a previously written
     * live commandcode model list. App upgrades must not silently restore an
     * old bundled list after the user has selected a newer upstream model.
     */
    static String mergePresetProviderBlock(String presetBlock, String currentBlock) {
        if (presetBlock == null || presetBlock.length() == 0) return presetBlock;
        if (currentBlock == null
                || !currentBlock.contains(LIVE_MARKER_PREFIX + COMMAND_PROVIDER)) {
            return presetBlock;
        }
        String currentSection = modelsSection(currentBlock, COMMAND_PROVIDER);
        if (currentSection == null) return presetBlock;
        return replaceModelsSection(presetBlock, COMMAND_PROVIDER, currentSection);
    }

    /** Parse model entries from a top-level plugin block's models list. */
    static List<ModelConfig.Model> readModels(String block) {
        List<ModelConfig.Model> result = new ArrayList<ModelConfig.Model>();
        if (block == null) return result;
        String[] lines = block.split("\\n", -1);
        int models = -1;
        int modelsIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            if ("models:".equals(lines[i].trim())) {
                models = i;
                modelsIndent = indent(lines[i]);
                break;
            }
        }
        if (models < 0) return result;
        for (int i = models + 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int in = indent(line);
            if (trimmed.length() > 0 && in <= modelsIndent) break;
            if (!trimmed.startsWith("- id:")) continue;
            String id = unquote(trimmed.substring(5).trim());
            if (ModelConfig.normalizeModel(id).length() == 0) continue;
            int itemIndent = in;
            String name = id;
            boolean image = false;
            boolean reasoning = false;
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j];
                String nt = next.trim();
                int ni = indent(next);
                if (nt.length() > 0 && ni <= modelsIndent) break;
                if (ni == itemIndent && nt.startsWith("- id:")) break;
                if (nt.startsWith("name:")) name = unquote(nt.substring(5).trim());
                if ((nt.startsWith("input:") || nt.startsWith("inputModalities:"))
                        && nt.toLowerCase(Locale.ROOT).contains("image")) image = true;
                if (nt.startsWith("reasoningEfforts:") && !nt.endsWith("false")) reasoning = true;
            }
            result.add(new ModelConfig.Model(id, name, image, reasoning));
        }
        return result;
    }

    private static List<LiveModelCatalog.Entry> clean(List<LiveModelCatalog.Entry> upstream) {
        Map<String, LiveModelCatalog.Entry> unique =
                new LinkedHashMap<String, LiveModelCatalog.Entry>();
        if (upstream != null) {
            for (LiveModelCatalog.Entry entry : upstream) {
                if (entry == null) continue;
                String id = ModelConfig.normalizeModel(entry.id);
                if (id.length() == 0 || unique.containsKey(id)) continue;
                unique.put(id, new LiveModelCatalog.Entry(id,
                        entry.name == null || entry.name.length() == 0 ? id : entry.name,
                        true, entry.image, entry.reasoning));
            }
        }
        return new ArrayList<LiveModelCatalog.Entry>(unique.values());
    }

    private static String commandBlock(List<LiveModelCatalog.Entry> entries) {
        StringBuilder out = new StringBuilder();
        out.append("llm-pi-ai:\n")
                .append("  providers:\n")
                .append("    commandcode:\n")
                .append("      displayName: Command Code\n")
                .append("      api: openai-completions\n")
                .append("      baseURL: https://api.commandcode.ai/provider/v1\n")
                .append("      apiKeyEnv: COMMANDCODE_API_KEY\n")
                .append("      compat:\n")
                .append("        supportsReasoningEffort: true\n")
                .append(modelSection(entries, 6, COMMAND_PROVIDER, false));
        return out.toString();
    }

    private static String deepSeekBlock(List<LiveModelCatalog.Entry> entries) {
        return "llm-deepseek-api-key:\n"
                + "  apiKeyEnv: DEEPSEEK_API_KEY\n"
                + modelSection(entries, 2, ModelConfig.DEEPSEEK, true);
    }

    private static String modelSection(List<LiveModelCatalog.Entry> entries,
                                       int modelsIndent, String provider,
                                       boolean deepSeek) {
        StringBuilder out = new StringBuilder();
        out.append(spaces(modelsIndent)).append(LIVE_MARKER_PREFIX).append(provider).append('\n');
        out.append(spaces(modelsIndent)).append("models:\n");
        for (LiveModelCatalog.Entry entry : entries) {
            out.append(spaces(modelsIndent + 2)).append("- id: ")
                    .append(quote(entry.id)).append('\n');
            out.append(spaces(modelsIndent + 4)).append("name: ")
                    .append(quote(entry.name)).append('\n');
            if (entry.image) {
                out.append(spaces(modelsIndent + 4))
                        .append(deepSeek ? "inputModalities: [ text, image ]\n"
                                : "input: [ text, image ]\n");
            }
            if (entry.reasoning && !deepSeek) {
                out.append(spaces(modelsIndent + 4))
                        .append("reasoningEfforts: { off: null, low: low, medium: medium, "
                                + "high: high, xhigh: xhigh, max: max }\n");
            }
        }
        return out.toString();
    }

    private static String replaceModels(String block, String provider,
                                        List<LiveModelCatalog.Entry> entries,
                                        boolean command) {
        String section = modelsSection(block, provider);
        if (section == null) {
            String[] lines = block.split("\\n", -1);
            int parentIndent = provider == null ? 0 : findProviderIndent(lines, provider);
            if (parentIndent < 0) return block;
            int insert = provider == null ? lines.length : providerEnd(lines, parentIndent);
            int modelsIndent = provider == null ? 2 : parentIndent + 2;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < insert; i++) out.append(lines[i]).append('\n');
            out.append(modelSection(entries, modelsIndent, provider == null
                    ? ModelConfig.DEEPSEEK : provider, !command));
            for (int i = insert; i < lines.length; i++) out.append(lines[i]).append('\n');
            return out.toString();
        }
        return replaceModelsSection(block, provider, modelSection(entries,
                sectionModelsIndent(section), provider == null ? ModelConfig.DEEPSEEK : provider,
                !command));
    }

    private static String modelsSection(String block, String provider) {
        if (block == null) return null;
        String[] lines = block.split("\\n", -1);
        int parentIndent = provider == null ? 0 : findProviderIndent(lines, provider);
        if (provider != null && parentIndent < 0) return null;
        int from = provider == null ? 0 : providerLine(lines, provider) + 1;
        int models = -1;
        int modelsIndent = -1;
        for (int i = from; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int in = indent(lines[i]);
            if (trimmed.length() > 0 && provider != null && in <= parentIndent) break;
            if ("models:".equals(trimmed)) {
                models = i;
                modelsIndent = in;
                break;
            }
        }
        if (models < 0) return null;
        int end = models + 1;
        while (end < lines.length) {
            String trimmed = lines[end].trim();
            int in = indent(lines[end]);
            if (trimmed.length() > 0 && in <= modelsIndent) break;
            end++;
        }
        int sectionStart = models;
        if (models > 0 && lines[models - 1].trim().startsWith(LIVE_MARKER_PREFIX)) {
            sectionStart = models - 1;
        }
        StringBuilder section = new StringBuilder();
        for (int i = sectionStart; i < end; i++) section.append(lines[i]).append('\n');
        return section.toString();
    }

    private static int sectionModelsIndent(String section) {
        if (section == null) return 0;
        for (String line : section.split("\\n")) {
            if ("models:".equals(line.trim())) return indent(line);
        }
        return 0;
    }

    private static String replaceModelsSection(String block, String provider, String replacement) {
        String[] lines = block.split("\\n", -1);
        int parentIndent = provider == null ? 0 : findProviderIndent(lines, provider);
        if (provider != null && parentIndent < 0) return block;
        int from = provider == null ? 0 : providerLine(lines, provider) + 1;
        int models = -1;
        int modelsIndent = -1;
        for (int i = from; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            int in = indent(lines[i]);
            if (trimmed.length() > 0 && provider != null && in <= parentIndent) break;
            if ("models:".equals(trimmed)) {
                models = i;
                modelsIndent = in;
                break;
            }
        }
        if (models < 0) return block;
        int end = models + 1;
        while (end < lines.length) {
            String trimmed = lines[end].trim();
            int in = indent(lines[end]);
            if (trimmed.length() > 0 && in <= modelsIndent) break;
            end++;
        }
        int sectionStart = models;
        if (models > 0 && lines[models - 1].trim().startsWith(LIVE_MARKER_PREFIX)) {
            sectionStart = models - 1;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sectionStart; i++) out.append(lines[i]).append('\n');
        out.append(replacement);
        for (int i = end; i < lines.length; i++) out.append(lines[i]).append('\n');
        return out.toString();
    }

    private static int providerLine(String[] lines, String provider) {
        for (int i = 0; i < lines.length; i++) {
            if ((provider + ":").equals(lines[i].trim())) return i;
        }
        return -1;
    }

    private static int findProviderIndent(String[] lines, String provider) {
        int line = providerLine(lines, provider);
        return line < 0 ? -1 : indent(lines[line]);
    }

    private static int providerEnd(String[] lines, int providerIndent) {
        for (int i = 0; i < lines.length; i++) {
            if (indent(lines[i]) == providerIndent && lines[i].trim().endsWith(":")) {
                int j = i + 1;
                while (j < lines.length) {
                    String trimmed = lines[j].trim();
                    if (trimmed.length() > 0 && indent(lines[j]) <= providerIndent) return j;
                    j++;
                }
                return lines.length;
            }
        }
        return lines.length;
    }

    static String topLevelBlock(String yaml, String key) {
        if (yaml == null) return null;
        String[] lines = yaml.split("\\n", -1);
        int start = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0 || line.charAt(0) == ' ' || line.charAt(0) == '\t'
                    || line.charAt(0) == '#') continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String found = line.substring(0, colon).trim();
            if (start < 0) {
                if (key.equals(found)) start = i;
            } else {
                end = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) out.append(lines[i]).append('\n');
        return out.toString();
    }

    private static String replaceTopLevelBlock(String yaml, String key, String replacement) {
        String old = topLevelBlock(yaml, key);
        if (old == null) return appendTopLevel(yaml, replacement);
        String matched = old;
        int at = yaml.indexOf(matched);
        if (at < 0 && matched.endsWith("\n")) {
            matched = matched.substring(0, matched.length() - 1);
            at = yaml.indexOf(matched);
        }
        return at < 0 ? yaml : yaml.substring(0, at) + replacement
                + yaml.substring(at + matched.length());
    }

    private static String appendTopLevel(String yaml, String block) {
        String source = yaml == null ? "" : yaml;
        if (source.length() == 0) return block;
        return source + (source.endsWith("\n") ? "" : "\n") + block;
    }

    private static int indent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static String spaces(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(' ');
        return out.toString();
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")) + "\"";
    }

    private static String unquote(String value) {
        if (value == null) return "";
        String out = value.trim();
        if (out.length() >= 2 && ((out.charAt(0) == '"' && out.charAt(out.length() - 1) == '"')
                || (out.charAt(0) == '\'' && out.charAt(out.length() - 1) == '\''))) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
