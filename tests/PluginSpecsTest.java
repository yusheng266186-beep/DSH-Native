package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PluginSpecs 的离线测试。
 *
 * 两组重点：
 *   ① 规格是用户输入，会被用来调用 npm —— 必须挡住命令注入；
 *   ② 生成的 patch YAML 若不合法，DSH 会直接启动失败，
 *      所以要在测试里就把它解析一遍（见配套脚本）。
 */
public class PluginSpecsTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. valid specs accepted ===");
        String[] good = {
            "dsh-foo", "dsh-foo-bar", "my.plugin", "my_plugin", "plugin1",
            "@scope/plugin", "@a/b", "dsh-foo@1.2.3", "@scope/pkg@^2.0.0",
            "/data/user/0/dev.dsh.native/files/dsh/plugins/local-one",
            // GitHub 规格：DSH 插件的主要分发形式（实测可安装）
            "github:1010n111/dsh-about", "1010n111/dsh-about",
            "bycall/dsh-answer-reviewer", "user/repo#main",
            "https://github.com/user/repo", "git+https://github.com/u/r.git",
            "gitlab:owner/proj",
        };
        for (String g : good) {
            String err = PluginSpecs.validateSpec(g);
            check("accept: " + g, err == null, String.valueOf(err));
        }

        System.out.println("=== 2. shell injection rejected ===");
        String[] bad = {
            "foo; rm -rf /", "foo && reboot", "foo | cat /etc/passwd",
            "foo`id`", "foo$(id)", "foo > /tmp/x", "foo < /tmp/x",
            "foo\nrm -rf /", "foo\\bar", "foo\"bar", "foo'bar",
            "foo(1)", "foo{a}", "foo[x]", "foo!x", "foo*x", "foo?x",
            "foo~x", "foo\tbar", "foo bar",
            // 保留了 git 规格所需的 : / # @，但这些仍必须被拒
            "a/b/c", "github:", "github:/", "owner/", "https://github.com",
        };
        for (String b : bad) {
            String err = PluginSpecs.validateSpec(b);
            check("reject: " + b.replace("\n", "\\n").replace("\t", "\\t"),
                    err != null, "ACCEPTED — injection risk!");
        }

        System.out.println("=== 3. malformed specs rejected ===");
        check("null rejected", PluginSpecs.validateSpec(null) != null, "wrong");
        check("empty rejected", PluginSpecs.validateSpec("") != null, "wrong");
        check("whitespace rejected", PluginSpecs.validateSpec("   ") != null, "wrong");
        check("uppercase start rejected", PluginSpecs.validateSpec("Foo") != null, "wrong");
        check("dot start rejected", PluginSpecs.validateSpec(".hidden") != null, "wrong");
        check("dash start rejected", PluginSpecs.validateSpec("-foo") != null, "wrong");
        check("too long rejected", PluginSpecs.validateSpec(rep("a", 400)) != null, "wrong");
        check("relative path with .. rejected",
                PluginSpecs.validateSpec("/tmp/../../etc/passwd") != null, "wrong");
        // 绝对路径本身合法（存在性由后续检查负责，不在校验阶段判断）
        check("short absolute path accepted",
                PluginSpecs.validateSpec("/x") == null, "should accept");

        System.out.println("=== 4. baseName ===");
        check("plain", "dsh-foo".equals(PluginSpecs.baseName("dsh-foo")),
                PluginSpecs.baseName("dsh-foo"));
        check("version stripped", "dsh-foo".equals(PluginSpecs.baseName("dsh-foo@1.2.3")),
                PluginSpecs.baseName("dsh-foo@1.2.3"));
        check("scope kept", "@scope/pkg".equals(PluginSpecs.baseName("@scope/pkg")),
                PluginSpecs.baseName("@scope/pkg"));
        check("scope with version", "@scope/pkg".equals(PluginSpecs.baseName("@scope/pkg@2.0.0")),
                PluginSpecs.baseName("@scope/pkg@2.0.0"));
        check("local path last segment",
                "local-one".equals(PluginSpecs.baseName("/a/b/local-one")),
                PluginSpecs.baseName("/a/b/local-one"));
        check("null safe", PluginSpecs.baseName(null) != null, "null");

        System.out.println("=== 5. installed plugin scanning ===");
        File profile = new File("/tmp/plugscantest/profile");
        rmrf(profile);
        File nm = new File(profile, "node_modules");
        new File(nm, "dsh-user-plugin").mkdirs();
        new File(nm, "@deepseek-ai/dsh-base").mkdirs();     // 官方，应跳过
        File scoped = new File(nm, "@scope/user-thing");    // 第三方 scope，应列出
        scoped.mkdirs();
        // 必须带插件声明（dsh 字段）或遵循 dsh- 命名 —— 空目录不算插件
        write(new File(scoped, "package.json"),
                "{\"name\":\"@scope/user-thing\",\"dsh\":{\"client\":{\"platform\":\"web\"}}}");
        new File(nm, ".bin").mkdirs();                      // 隐藏，应跳过
        write(new File(nm, "dsh-user-plugin/package.json"),
                "{ \"name\": \"dsh-user-plugin\", \"version\": \"1.0.0\" }");

        List<String> found = PluginSpecs.installedPlugins(profile);
        System.out.println("     found: " + found);
        check("user plugin listed", found.contains("dsh-user-plugin"), found.toString());
        check("third-party scope listed", found.contains("@scope/user-thing"), found.toString());
        check("official scope skipped", !found.contains("@deepseek-ai/dsh-base"), found.toString());
        check("dot dir skipped", !found.contains(".bin"), found.toString());
        check("missing dir safe", PluginSpecs.installedPlugins(new File("/no/such")).isEmpty(), "wrong");
        check("null profile safe", PluginSpecs.installedPlugins(null) == null
                || PluginSpecs.installedPlugins(null).isEmpty(), "wrong");

        System.out.println("=== 5.1 dependencies are NOT listed as plugins ===");
        // 实测：装 dsh-about 会连带装上 react / js-tokens / loose-envify，
        // 它们和插件躺在同一个 node_modules 里。不区分的话插件列表会被淹没。
        new File(nm, "react").mkdirs();
        write(new File(nm, "react/package.json"), "{\"name\":\"react\",\"version\":\"18.3.1\"}");
        new File(nm, "js-tokens").mkdirs();
        write(new File(nm, "js-tokens/package.json"), "{\"name\":\"js-tokens\",\"version\":\"4.0.0\"}");
        new File(nm, "loose-envify").mkdirs();
        write(new File(nm, "loose-envify/package.json"), "{\"name\":\"loose-envify\",\"version\":\"1.4.0\"}");
        // 一个不带 dsh 字段、但遵循 dsh- 命名约定的插件
        new File(nm, "dsh-naming-only").mkdirs();
        write(new File(nm, "dsh-naming-only/package.json"),
                "{\"name\":\"dsh-naming-only\",\"version\":\"1.0.0\"}");
        // 一个带 dsh 字段但名字不含 dsh- 的（例如官方风格的包名）
        new File(nm, "plugin-with-decl").mkdirs();
        write(new File(nm, "plugin-with-decl/package.json"),
                "{\"name\":\"plugin-with-decl\",\"dsh\":{\"client\":{\"platform\":\"web\"}}}");

        List<String> filtered = PluginSpecs.installedPlugins(profile);
        System.out.println("     filtered: " + filtered);
        check("react excluded", !filtered.contains("react"), "dependency leaked into list");
        check("js-tokens excluded", !filtered.contains("js-tokens"), "dependency leaked into list");
        check("loose-envify excluded", !filtered.contains("loose-envify"), "dependency leaked into list");
        check("dsh- naming convention kept", filtered.contains("dsh-naming-only"), "missed");
        check("dsh field declaration kept", filtered.contains("plugin-with-decl"), "missed");
        check("real plugin still listed", filtered.contains("dsh-user-plugin"), filtered.toString());

        System.out.println("=== 6. package.json name extraction ===");
        File pd = new File(nm, "dsh-user-plugin");
        check("name read", "dsh-user-plugin".equals(PluginSpecs.packageNameOf(pd)),
                String.valueOf(PluginSpecs.packageNameOf(pd)));
        File empty = new File(nm, "no-pkg"); empty.mkdirs();
        check("missing package.json -> null", PluginSpecs.packageNameOf(empty) == null, "wrong");
        check("null dir safe", PluginSpecs.packageNameOf(null) == null, "wrong");

        System.out.println("=== 7. patch yaml generation ===");
        List<String> plugins = new ArrayList<String>();
        plugins.add("dsh-alpha");
        plugins.add("@scope/beta");
        String yaml = PluginSpecs.buildPatchYaml(plugins);
        System.out.println("----- generated -----");
        for (String l : yaml.split("\n")) System.out.println("     " + l);
        System.out.println("---------------------");
        check("has insert key", yaml.contains("- insert:"), "missing");
        check("alpha entry present", yaml.contains("name: 'dsh-alpha'"), "missing");
        check("scoped entry present", yaml.contains("name: '@scope/beta'"), "missing");
        check("scoped id flattened", yaml.contains("id: scope-beta"), "id with slash would break YAML");
        check("empty list still valid", PluginSpecs.buildPatchYaml(new ArrayList<String>())
                .contains("- insert:"), "missing");
        check("null list safe", PluginSpecs.buildPatchYaml(null) != null, "null");
        // 生成的 YAML 交给外部校验（见 build 脚本里的 python 解析）
        java.io.FileOutputStream fo = new java.io.FileOutputStream("/tmp/patch-generated.yml");
        fo.write(yaml.getBytes("UTF-8")); fo.close();
        System.out.println("     (YAML 已写入 /tmp/patch-generated.yml，由外部解析器验证)");

        System.out.println("=== 8. plugin kind detection ===");
        // bundle 插件（有 dsh.bundle）与普通插件的启用方式不同，判错就不生效
        File bundleDir = new File(nm, "dsh-bundle-type"); bundleDir.mkdirs();
        write(new File(bundleDir, "package.json"),
                "{\n  \"name\": \"dsh-about\",\n  \"version\": \"0.0.4\",\n"
              + "  \"dsh\": {\n    \"bundle\": { \"patch\": \"./cordis.patch.yml\" },\n"
              + "    \"client\": { \"platform\": \"web\" }\n  }\n}");
        check("bundle plugin detected",
                PluginSpecs.pluginKind(bundleDir) == PluginSpecs.KIND_BUNDLE,
                String.valueOf(PluginSpecs.pluginKind(bundleDir)));

        File plainDir = new File(nm, "dsh-plain-type"); plainDir.mkdirs();
        write(new File(plainDir, "package.json"),
                "{\"name\":\"dsh-plain\",\"version\":\"1.0.0\",\"main\":\"index.js\"}");
        check("plain plugin detected",
                PluginSpecs.pluginKind(plainDir) == PluginSpecs.KIND_PLAIN,
                String.valueOf(PluginSpecs.pluginKind(plainDir)));
        check("missing package.json -> plain",
                PluginSpecs.pluginKind(new File(nm, "no-pkg")) == PluginSpecs.KIND_PLAIN, "wrong");
        check("null -> plain", PluginSpecs.pluginKind(null) == PluginSpecs.KIND_PLAIN, "wrong");
        // 有 dsh 字段但没有 bundle 子字段的，应按普通处理
        File dshOnly = new File(nm, "dsh-client-only"); dshOnly.mkdirs();
        write(new File(dshOnly, "package.json"),
                "{\"name\":\"x\",\"dsh\":{\"client\":{\"platform\":\"web\"}}}");
        check("dsh without bundle -> plain",
                PluginSpecs.pluginKind(dshOnly) == PluginSpecs.KIND_PLAIN, "wrong");

        System.out.println("=== 9. bundles json editing ===");
        // 用真实的 profile package.json 结构
        String pkg = "{\n  \"name\": \"dsh-profile-web\",\n  \"private\": true,\n"
                   + "  \"dependencies\": {},\n  \"dsh\": {\n    \"profile\": {\n"
                   + "      \"bundles\": [\n        \"@deepseek-ai/dsh-base\",\n"
                   + "        \"@deepseek-ai/dsh-web-app\"\n      ]\n    }\n  }\n}";
        String edited = PluginSpecs.addToBundlesJson(pkg, "dsh-about");
        check("bundle appended", edited.contains("\"dsh-about\""), "missing");
        check("existing bundles kept",
                edited.contains("@deepseek-ai/dsh-base")
                && edited.contains("@deepseek-ai/dsh-web-app"), "lost entries");
        check("other fields preserved",
                edited.contains("\"private\": true") && edited.contains("\"dependencies\""), "lost fields");
        check("idempotent (no duplicate)",
                PluginSpecs.addToBundlesJson(edited, "dsh-about").equals(edited), "duplicated");
        check("readBundles sees all three",
                PluginSpecs.readBundles(edited).size() == 3, PluginSpecs.readBundles(edited).toString());
        check("readBundles on original sees two",
                PluginSpecs.readBundles(pkg).size() == 2, PluginSpecs.readBundles(pkg).toString());
        check("empty bundles handled",
                PluginSpecs.addToBundlesJson("{\"dsh\":{\"profile\":{\"bundles\":[]}}}", "x")
                        .contains("\"x\""), "wrong");
        check("missing bundles key returns unchanged",
                "{}".equals(PluginSpecs.addToBundlesJson("{}", "x")), "wrong");
        check("null json safe", PluginSpecs.addToBundlesJson(null, "x") == null, "wrong");
        check("null name safe", PluginSpecs.addToBundlesJson(pkg, null).equals(pkg), "wrong");
        // 生成的 JSON 必须还能被解析（这是硬要求：拼坏了 profile 起不来）
        java.io.FileOutputStream fo2 = new java.io.FileOutputStream("/tmp/bundles-edited.json");
        fo2.write(edited.getBytes("UTF-8")); fo2.close();
        System.out.println("     (编辑后的 JSON 已写入 /tmp/bundles-edited.json，由外部解析器验证)");

        System.out.println("=== 10. builtin plugin policy ===");
        check("schedule enableable", PluginSpecs.isEnableableBuiltin("@deepseek-ai/dsh-schedule"), "wrong");
        check("webhook enableable", PluginSpecs.isEnableableBuiltin("@deepseek-ai/dsh-webhook"), "wrong");
        check("mcp-client NOT enableable (breaks startup)",
                !PluginSpecs.isEnableableBuiltin("@deepseek-ai/dsh-mcp-client"), "must be excluded");
        check("unknown not enableable", !PluginSpecs.isEnableableBuiltin("whatever"), "wrong");
        check("null safe", !PluginSpecs.isEnableableBuiltin(null), "wrong");

        rmrf(profile);
        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
    static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes("UTF-8")); os.close();
    }
    static void rmrf(File f) {
        if (f.isDirectory()) { File[] c = f.listFiles(); if (c != null) for (File x : c) rmrf(x); }
        f.delete();
    }
}
