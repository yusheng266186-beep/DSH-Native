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
        check("too long rejected", PluginSpecs.validateSpec(rep("a", 300)) != null, "wrong");
        check("relative path with .. rejected",
                PluginSpecs.validateSpec("/tmp/../../etc/passwd") != null, "wrong");

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
        new File(nm, "@scope/user-thing").mkdirs();         // 第三方 scope，应列出
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

        System.out.println("=== 8. builtin plugin policy ===");
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
