package dev.dsh.nativeapp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DSH 插件的安装规格与清单：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <h3>机制（已端到端验证）</h3>
 * <pre>
 * npm install --prefix &lt;DSH_HOME&gt;/profiles/web &lt;spec&gt;
 *   → 落到 profiles/web/node_modules/&lt;name&gt;      ← DSH 正是从这里解析插件
 * 再在 --patch 里 insert 这个包名
 *   → 启动时加载并 apply（实测打印出 [PLUGIN-LOADED]）
 * </pre>
 *
 * <p>注意：DSH 自带的 {@code dsh plugin add} 子命令依赖 <b>pnpm</b>，
 * 而运行包里没有（也不值得为它多带 46MB）。我们直接用已有的 npm ——
 * profile 用的是 {@code nodeLinker: hoisted}，npm 默认也是提升安装，落点一致。
 *
 * <h3>为什么校验要单独抽出来</h3>
 * 插件规格是**用户输入**，会被拼进命令行。不做校验就等于把 shell 交给用户 ——
 * 分号、反引号、空格都可能变成额外命令。
 */
final class PluginSpecs {

    /** 官方包由 profile 的 bundles 管理，不作为「可卸载的插件」列出。 */
    static final String OFFICIAL_SCOPE = "@deepseek-ai/";

    private PluginSpecs() { }

    /**
     * 校验用户输入的插件规格。
     *
     * @return 错误说明；合法时返回 null
     */
    static String validateSpec(String spec) {
        if (spec == null) return "请填写插件名或路径";
        String s = spec.trim();
        if (s.length() == 0) return "请填写插件名或路径";
        if (s.length() > 200) return "名称过长";

        // 命令行安全：这些都是「在 shell 里另有含义」的字符。
        // 即便我们用数组形式传参（不经过 shell），也仍然拒绝 ——
        // 因为未来的调用方式可能变化，而这里是唯一一道闸。
        String dangerous = ";&|`$><\n\r\t\"'\\(){}[]!*?~";
        for (int i = 0; i < s.length(); i++) {
            if (dangerous.indexOf(s.charAt(i)) >= 0) {
                return "不能包含特殊字符：" + s.charAt(i);
            }
        }
        if (s.contains(" ")) return "不能包含空格";

        // 绝对路径（本地插件）：以 / 开头且不含 ..
        if (s.startsWith("/")) {
            if (s.contains("..")) return "路径不能包含 ..";
            return null;
        }
        // npm 包名：可选 @scope/，小写字母数字与 . - _ ，允许 @版本
        String pattern = "^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*"
                       + "(@[a-zA-Z0-9._^~-]+)?$";
        if (!s.matches(pattern)) {
            return "不是有效的包名（应以小写字母或数字开头）";
        }
        return null;
    }

    /** 规格里去掉版本号后的包名。 */
    static String baseName(String spec) {
        if (spec == null) return "";
        String s = spec.trim();
        int at = s.lastIndexOf('@');
        // 开头的 @ 是 scope 标记，不是版本分隔符
        if (at > 0) s = s.substring(0, at);
        // 本地路径取最后一段
        if (s.startsWith("/")) {
            int slash = s.lastIndexOf('/');
            if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        }
        return s;
    }

    /**
     * 扫描已安装的插件。
     *
     * <p>只列**用户装的**（跳过 {@code @deepseek-ai/} 官方包与 {@code .} 开头的目录）——
     * 官方包由 profile 的 bundles 管理，列出来只会让人以为可以卸载。
     */
    static List<String> installedPlugins(File profileDir) {
        List<String> out = new ArrayList<String>();
        File nm = new File(profileDir, "node_modules");
        if (!nm.isDirectory()) return out;

        File[] direct = nm.listFiles();
        if (direct == null) return out;
        for (File f : direct) {
            String n = f.getName();
            if (!f.isDirectory()) continue;
            if (n.startsWith(".")) continue;
            if (n.startsWith("@")) {
                // scope 目录：再往下一层
                File[] inner = f.listFiles();
                if (inner == null) continue;
                for (File g : inner) {
                    if (!g.isDirectory()) continue;
                    String full = n + "/" + g.getName();
                    if (full.startsWith(OFFICIAL_SCOPE)) continue;
                    out.add(full);
                }
                continue;
            }
            out.add(n);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** 从 package.json 里读 name 字段；读不到返回 null。 */
    static String packageNameOf(File pluginDir) {
        if (pluginDir == null) return null;
        File pkg = new File(pluginDir, "package.json");
        if (!pkg.isFile()) return null;
        try {
            byte[] b = new byte[(int) Math.min(pkg.length(), 65536)];
            java.io.FileInputStream in = new java.io.FileInputStream(pkg);
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            in.close();
            String text = new String(b, 0, off, "UTF-8");
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 生成 {@code --patch} 覆盖层的内容。
     *
     * <p>每个插件一行 {@code id} + {@code name}。id 用包名派生（把 {@code /} 换成 {@code -}），
     * 保证唯一且稳定 —— 同一插件反复启停不会产生重复条目。
     */
    static String buildPatchYaml(List<String> pluginNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 由 App 自动生成的插件覆盖层。\n")
          .append("# --patch 是启动器级覆盖层（最后应用、优先级最高），\n")
          .append("# 因此启用插件无需改动用户的 profile 文件。\n")
          .append("- insert:\n");
        if (pluginNames == null || pluginNames.isEmpty()) return sb.toString();
        for (String raw : pluginNames) {
            String name = raw == null ? "" : raw.trim();
            if (name.length() == 0) continue;
            String id = name.replace("/", "-").replace("@", "").toLowerCase(Locale.ROOT);
            sb.append("    - id: ").append(id).append("\n")
              .append("      name: '").append(name).append("'\n");
        }
        return sb.toString();
    }

    /** 内置可选插件（运行包里已有，无需安装）。 */
    static String[] builtinPlugins() {
        return new String[]{
            "@deepseek-ai/dsh-schedule",
            "@deepseek-ai/dsh-webhook",
        };
    }

    /**
     * 是否允许启用某个内置插件。
     *
     * <p>{@code dsh-mcp-client} 被**刻意排除**：实测在没有配置任何 server 时
     * 它会让 DSH 整个启动失败（{@code Cannot read properties of undefined
     * (reading 'reconnect')}）。
     */
    static boolean isEnableableBuiltin(String name) {
        if (name == null) return false;
        for (String b : builtinPlugins()) if (b.equals(name)) return true;
        return false;
    }
}
