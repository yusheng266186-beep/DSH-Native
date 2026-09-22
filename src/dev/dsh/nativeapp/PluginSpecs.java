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
        if (s.length() > 300) return "过长";

        // ── 命令行安全 ──
        // 虽然我们用**数组形式**传参（不经过 shell），这些字符本身是惰性的，
        // 但仍然拒绝 —— 这是唯一一道闸，将来调用方式变化时不该出现缺口。
        // 注意：: / # @ . - _ 是规格本身需要的（git 地址、scope、版本、分支），
        // 因此**不在**拒绝列表里。
        String dangerous = ";&|`$><\n\r\t\"'\\(){}[]!*?~ ";
        for (int i = 0; i < s.length(); i++) {
            if (dangerous.indexOf(s.charAt(i)) >= 0) {
                return "不能包含特殊字符：" + s.charAt(i);
            }
        }

        // ── 绝对路径（本地插件）──
        if (s.startsWith("/")) {
            if (s.contains("..")) return "路径不能包含 ..";
            return null;
        }

        // ── git / GitHub ──
        // 这是 DSH 插件的主要分发形式：官方 CLI 明确支持「Git address」。
        // 实测 github:owner/repo 可以安装成功。
        if (s.startsWith("github:") || s.startsWith("gitlab:")
                || s.startsWith("bitbucket:") || s.startsWith("git+")
                || s.startsWith("https://") || s.startsWith("http://")) {
            int slash = s.indexOf('/');
            // 至少要形如 <prefix><owner>/<repo>
            String rest = s;
            if (s.startsWith("https://") || s.startsWith("http://")) {
                int host = s.indexOf("//") + 2;
                int first = s.indexOf('/', host);
                if (first < 0 || first >= s.length() - 1) return "不是有效的仓库地址";
                rest = s.substring(first + 1);
            } else {
                // git+https://… 之类：先剥掉 git+ 前缀再按 URL 处理，
                // 否则会把 https: 的冒号误当分隔符
                String u = s.startsWith("git+") ? s.substring(4) : s;
                if (u.startsWith("https://") || u.startsWith("http://")
                        || u.startsWith("ssh://") || u.startsWith("git://")) {
                    int host = u.indexOf("//") + 2;
                    int first = u.indexOf('/', host);
                    if (first < 0 || first >= u.length() - 1) return "不是有效的仓库地址";
                    rest = u.substring(first + 1);
                } else {
                    int colon = s.indexOf(':');
                    rest = colon >= 0 ? s.substring(colon + 1) : s;
                }
            }
            String body = rest;
            int hash = body.indexOf('#');
            if (hash >= 0) body = body.substring(0, hash);   // 去掉分支/标签
            if (body.endsWith(".git")) body = body.substring(0, body.length() - 4);
            int sl = body.indexOf('/');
            if (sl <= 0 || sl >= body.length() - 1) return "不是有效的仓库地址";
            String owner = body.substring(0, sl);
            String repo = body.substring(sl + 1);
            if (owner.length() == 0 || repo.length() == 0) return "不是有效的仓库地址";
            return null;
        }

        // ── GitHub 简写 owner/repo ──
        if (s.indexOf('/') > 0 && !s.startsWith("@")) {
            String body = s;
            int hash = body.indexOf('#');
            if (hash >= 0) body = body.substring(0, hash);
            if (body.endsWith(".git")) body = body.substring(0, body.length() - 4);
            if (body.indexOf('/') != body.lastIndexOf('/')) return "仓库简写只支持 owner/repo";
            int sl = body.indexOf('/');
            if (sl <= 0 || sl >= body.length() - 1) return "不是有效的插件规格";
            return null;
        }

        // ── npm 包名 ──
        String pattern = "^(@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*"
                       + "(@[a-zA-Z0-9._^~-]+)?$";
        if (!s.matches(pattern)) {
            return "不是有效的插件规格（可用 npm 包名、owner/repo 或绝对路径）";
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
                File[] inner = f.listFiles();
                if (inner == null) continue;
                for (File g : inner) {
                    if (!g.isDirectory()) continue;
                    String full = n + "/" + g.getName();
                    if (full.startsWith(OFFICIAL_SCOPE)) continue;
                    if (looksLikePlugin(g, g.getName())) out.add(full);
                }
                continue;
            }
            if (looksLikePlugin(f, n)) out.add(n);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * 这个包里装的是不是 DSH 插件（而不是被一起装进来的依赖）。
     *
     * <p><b>实测踩过</b>：装 {@code dsh-about} 会连带装上 react、js-tokens、
     * loose-envify —— 它们和插件躺在同一个 node_modules 里，
     * 不区分的话插件列表会被这些依赖淹没，用户根本找不到自己装的那个。
     *
     * <p>两条判据（满足其一）：
     * <ul>
     *   <li>package.json 里有 {@code dsh} 字段 —— DSH 插件的声明位；</li>
     *   <li>包名以 {@code dsh-} 开头 —— 少数插件不带声明位，但遵循命名约定。</li>
     * </ul>
     */
    static boolean looksLikePlugin(File dir, String name) {
        if (dir == null) return false;
        if (name != null && name.startsWith("dsh-")) return true;
        String text = readPackageJson(dir);
        if (text == null) return false;
        return Pattern.compile("\"dsh\"\\s*:").matcher(text).find();
    }

    /** 从 package.json 里读 name 字段；读不到返回 null。 */
    static String packageNameOf(File pluginDir) {
        String text = readPackageJson(pluginDir);
        if (text == null) return null;
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        return m.find() ? m.group(1) : null;
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

    // ================================================================ 插件类型

    /** 普通插件：直接写进 {@code --patch} 的 insert 列表即可。 */
    static final int KIND_PLAIN = 0;
    /** bundle 插件：必须追加到 profile 的 {@code dsh.profile.bundles}。 */
    static final int KIND_BUNDLE = 1;

    /**
     * 判定插件的类型。
     *
     * <p><b>两种类型的启用方式不同，弄错插件就不会生效：</b>
     * <ul>
     *   <li>普通插件 —— 写进 {@code --patch} 的 insert 列表；</li>
     *   <li><b>bundle 插件</b>（package.json 里有 {@code dsh.bundle}）——
     *       必须追加到 profile 的 {@code dsh.profile.bundles}。
     *       实测 {@code dsh-about} 就是这种：它的 {@code cordis.patch.yml}
     *       本身是「bundle 声明的组合层」，只有把包名加进 bundles，
     *       那份 patch 才会被合并。</li>
     * </ul>
     *
     * <p>判定依据读插件自己的 package.json，而不是按名字猜。
     */
    static int pluginKind(File pluginDir) {
        String text = readPackageJson(pluginDir);
        if (text == null) return KIND_PLAIN;
        // 只要出现 dsh.bundle 就按 bundle 处理
        Matcher m = Pattern.compile("\"dsh\"\\s*:\\s*\\{[^}]*\"bundle\"", Pattern.DOTALL)
                .matcher(text);
        return m.find() ? KIND_BUNDLE : KIND_PLAIN;
    }

    /**
     * 把插件名追加进 profile 的 {@code dsh.profile.bundles}。
     *
     * <p>这个文件是 JSON，不能靠字符串拼接改 —— 拼坏了 profile 就起不来。
     * 这里只做「读取 → 在 bundles 数组里加一项 → 原样写回结构」，
     * 且**保留其余字段**。
     *
     * @return 是否发生了变化
     */
    static String addToBundlesJson(String packageJson, String pluginName) {
        if (packageJson == null || pluginName == null || pluginName.length() == 0) {
            return packageJson;
        }
        String key = "\"bundles\"";
        int ki = packageJson.indexOf(key);
        if (ki < 0) return packageJson;
        int open = packageJson.indexOf('[', ki);
        int close = packageJson.indexOf(']', open);
        if (open < 0 || close < 0 || close < open) return packageJson;

        String inside = packageJson.substring(open + 1, close);
        // 已有的不重复添加
        if (inside.contains("\"" + pluginName + "\"")) return packageJson;

        String trimmed = inside.trim();
        String add = "\"" + pluginName + "\"";
        String merged = trimmed.length() == 0 ? add : trimmed + ", " + add;
        return packageJson.substring(0, open + 1) + merged + packageJson.substring(close);
    }

    /** 从 profile 的 package.json 文本里读出 bundles 列表（用于界面显示）。 */
    static List<String> readBundles(String packageJson) {
        List<String> out = new ArrayList<String>();
        if (packageJson == null) return out;
        int ki = packageJson.indexOf("\"bundles\"");
        if (ki < 0) return out;
        int open = packageJson.indexOf('[', ki);
        int close = packageJson.indexOf(']', open);
        if (open < 0 || close < 0) return out;
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(
                packageJson.substring(open + 1, close));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 读插件目录下的 package.json 全文；读不到返回 null。 */
    static String readPackageJson(File pluginDir) {
        if (pluginDir == null) return null;
        File pkg = new File(pluginDir, "package.json");
        if (!pkg.isFile()) return null;
        try {
            byte[] b = new byte[(int) Math.min(pkg.length(), 262144)];
            java.io.FileInputStream in = new java.io.FileInputStream(pkg);
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一个推荐插件。 */
    static final class Recommended {
        final String spec;       // 安装规格（GitHub 简写）
        final String title;      // 显示名
        final String desc;       // 一句话说明
        Recommended(String spec, String title, String desc) {
            this.spec = spec; this.title = title; this.desc = desc;
        }
    }

    /**
     * 推荐插件。
     *
     * <p>**只收录在容器里实测能装上、且（至少）能被 npm 正确解析的**。
     * 这里不放「看起来不错但没验证过」的东西 —— 装不上比不推荐更糟。
     *
     * <p>实测记录（2026-09-22）：
     * <ul>
     *   <li>{@code dsh-about} —— 安装成功，加入 bundles 后出现在首页 ✓</li>
     *   <li>{@code dsh-session-diff} —— 安装成功（bundle 类型）✓</li>
     *   <li>{@code dsh-workspace-menu} —— **npm 404，仓库名有误**，故不收录</li>
     * </ul>
     */
    static Recommended[] recommended() {
        return new Recommended[]{
            new Recommended("1010n111/dsh-about", "关于",
                    "在设置里显示 DSH 版本与更新日志"),
            new Recommended("2002XiaoYu/dsh-session-diff", "会话改动",
                    "在右侧栏查看本次会话改过哪些文件，带增删行高亮"),
        };
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
