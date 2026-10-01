package dev.dsh.nativeapp;

/**
 * 判断「本次启动是否还会用预置覆盖用户的模型目录」（纯逻辑，可离线测试）。
 *
 * <p><b>为什么需要它</b>
 *
 * <p>DSH v0.2.0 把扁平 {@code settings.yaml} 当作**一次性遗留文件**：
 * {@code dsh-settings} 的 {@code importLegacyDocument()} 会在启动时把它**改名**
 * 成 {@code settings.yaml.imported}，并把各段导入 profile 存储。之后
 * {@code settings.yaml} 就不存在了。
 *
 * <p>而 App 原先在每次启动都「文件不存在就用预置重建」。两者叠加就成了：
 *
 * <pre>
 *   刷新模型 → 写入 .dsh/settings.yaml（87 个模型）
 *   下次启动 → DSH 改名成 .imported 并导入（用户目录生效）
 *   再下次启动 → App 发现 settings.yaml 不在，用预置重建（56 个）
 *              → DSH 再次导入，用户刚刷新的目录被覆盖
 * </pre>
 *
 * 表现就是<b>每次打开应用都要重新更新一次模型列表才能聊天</b>。
 *
 * <p>因此：只要检测到「已经导入过、且导入内容里带着模型目录」，
 * 本次启动就<b>不得</b>再用预置重建那个文件。
 */
final class ModelCatalogPersistence {

    private ModelCatalogPersistence() { }

    /**
     * 已导入的遗留文件里是否带有模型目录。
     *
     * @param importedText {@code settings.yaml.imported} 的内容；可为 null
     */
    static boolean importedCatalogPresent(String importedText) {
        return hasModelCatalog(importedText);
    }

    /**
     * 文本里是否含非空的模型目录（顶层 {@code llm-pi-ai} 段下有 {@code models:}）。
     *
     * <p>要求<b>真的有模型条目</b>，而不是只看键存在 —— 空目录覆盖掉用户配置
     * 和没覆盖一样糟。
     */
    static boolean hasModelCatalog(String text) {
        if (text == null || text.length() == 0) return false;
        int start = text.indexOf("\nllm-pi-ai:");
        if (start < 0) {
            // 也允许文件以 llm-pi-ai: 开头
            if (!text.startsWith("llm-pi-ai:")) return false;
            start = -1;
        }
        StringBuilder block = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inside = false;
        int indent = 0;
        for (String line : lines) {
            if (!inside) {
                if (line.startsWith("llm-pi-ai:")) {
                    inside = true;
                    indent = 0;
                }
                continue;
            }
            // 顶格且形如 key: 的行表示块结束
            if (line.length() > 0 && !Character.isWhitespace(line.charAt(0))
                    && line.indexOf(':') > 0) {
                break;
            }
            block.append(line).append('\n');
        }
        String body = block.toString();
        if (body.indexOf("models:") < 0) return false;
        // 至少要有一个 "- id:" 条目
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- id:") || trimmed.startsWith("-id:")) return true;
        }
        return false;
    }

    /**
     * 诊断导出是否需要真的写盘。
     *
     * <p>原来每次启动都把模型配置写一遍到共享存储（{@code /sdcard}，FUSE，很慢），
     * 而内容通常一字未变。这里只在**内容真的不同**时才写，省掉一次启动路径上的
     * 共享存储写入；顺带避免无意义的 FUSE 抖动。
     *
     * @param existing 已存在文件的内容，可为 null（不存在）
     * @param block    本次要写出的内容
     */
    static boolean shouldWriteDiagnostics(String existing, String block) {
        if (block == null || block.length() == 0) return false;
        return !block.equals(existing == null ? "" : existing);
    }

    /**
     * 本次启动是否应当跳过预置注入。
     *
     * @param settingsExists {@code settings.yaml} 当前是否存在
     * @param importedText   {@code settings.yaml.imported} 的内容
     */
    static boolean shouldSkipPresetInjection(boolean settingsExists, String importedText) {
        // settings.yaml 还在：老逻辑按「已存在则合并」处理，不动。
        if (settingsExists) return false;
        // 已被 DSH 导入过且里面确实有模型：绝不能再用预置覆盖回去。
        return importedCatalogPresent(importedText);
    }
}
