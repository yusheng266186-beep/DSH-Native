package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型配置块里找出「声明支持图片输入」的模型 id（纯逻辑，可离线测试）。
 *
 * <p>这段判定原先是 {@code MainActivity.syncProviderConfig} 里的一大段内联正则，
 * 一行测试都跑不了。它出现在启动日志里（"支持图片输入的模型: …"），
 * 是用户核对「这个模型到底能不能看图」的唯一依据，算错了会误导人。
 *
 * <p>注意与 {@link ModelConfig#modelsForProvider} 的区别：那边按**指定 provider
 * 块**解析并处理引号、缩进；这里扫的是 {@code llm-pi-ai} 整块，行为保持原样，
 * 不做改动 —— 那段只用于日志，改它属于行为变更，不在本次范围内。
 */
final class ModelImageSupport {

    /** 模型条目开头是 {@code - id:}；接下来到下一个条目之间的内容里找 input。 */
    private static final Pattern ENTRY = Pattern.compile(
            "(?m)^\\s*-\\s*id:\\s*[\"']?([^\"'\\n]+?)[\"']?\\s*$\n"
            + "((?:(?!^\\s*-\\s*id:)[\\s\\S])*?)"
            + "^\\s*input:\\s*\\[[^\\]]*image[^\\]]*\\]");

    private ModelImageSupport() { }

    /** 按出现顺序返回声明了 {@code input: [... image ...]} 的模型 id。 */
    static List<String> imageCapableModels(String yaml) {
        List<String> out = new ArrayList<String>();
        if (yaml == null) return out;
        Matcher m = ENTRY.matcher(yaml);
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }
}
