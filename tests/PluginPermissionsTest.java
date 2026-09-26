package dev.dsh.nativeapp;

/** PluginPermissions 的离线回归测试。 */
public class PluginPermissionsTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        check("schedule has task permission",
                PluginPermissions.capabilities("@deepseek-ai/dsh-schedule", true).toString().contains("计划任务"), "missing");
        check("webhook has network permission",
                PluginPermissions.capabilities("@deepseek-ai/dsh-webhook", true).toString().contains("网络"), "missing");
        check("third party discloses commands",
                PluginPermissions.capabilities("dsh-foo", false).toString().contains("本机命令"), "missing");
        check("third party discloses secrets",
                PluginPermissions.capabilities("dsh-foo", false).toString().contains("账号密钥"), "missing");
        String pkg1 = "{\"name\":\"dsh-foo\",\"version\":\"1.0.0\"}";
        String pkg2 = "{\"name\":\"dsh-foo\",\"version\":\"1.1.0\"}";
        check("version parsed", "1.0.0".equals(PluginPermissions.versionOf(pkg1)), "wrong");
        check("missing version safe", "unknown".equals(PluginPermissions.versionOf("{}")), "wrong");
        String key1 = PluginPermissions.grantKey("dsh-foo", pkg1);
        check("grant key stable", key1.equals(PluginPermissions.grantKey("dsh-foo", pkg1)), key1);
        check("version update invalidates grant", !key1.equals(PluginPermissions.grantKey("dsh-foo", pkg2)), "same");
        check("content update invalidates grant", !key1.equals(PluginPermissions.grantKey("dsh-foo", pkg1 + " ")), "same");
        check("disclosure states sandbox boundary",
                PluginPermissions.disclosure("dsh-foo", false).contains("不是操作系统级沙箱"), "missing");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
