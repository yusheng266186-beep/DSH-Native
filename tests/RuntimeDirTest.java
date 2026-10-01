package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;

/**
 * RuntimeDir 的离线测试 —— 运行目录删除守卫。
 *
 * 这是**唯一挡在「递归删除」与用户数据之间的那道门**，所以测试直接用真实的
 * 临时目录验证行为，而不是只测字符串判断：真正建出 `.dsh`、`credentials.yaml`
 * 这类目录，确认守卫确实拒绝删除它们。
 */
public class RuntimeDirTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static File mkdirs(File root, String rel) throws IOException {
        File f = new File(root, rel);
        if (!f.mkdirs() && !f.isDirectory()) throw new IOException("mkdir " + rel);
        return f;
    }

    public static void main(String[] args) throws Exception {
        File root = File.createTempFile("dsh-rt", "root");
        if (!root.delete() || !root.mkdirs()) throw new IOException("temp root");
        root.deleteOnExit();

        System.out.println("=== 1. 白名单内的名字 ===");
        check("dsh allowed", RuntimeDir.isDeletableName("dsh"), "rejected");
        check("tools allowed", RuntimeDir.isDeletableName("tools"), "rejected");
        check("payload-rollback allowed", RuntimeDir.isDeletableName("payload-rollback"), "rejected");
        check(".rollback-current-dsh allowed",
                RuntimeDir.isDeletableName(".rollback-current-dsh"), "rejected");
        check("whitelist has 7 names", RuntimeDir.deletableNames().size() == 7,
                "" + RuntimeDir.deletableNames().size());

        System.out.println("=== 2. 用户数据绝不在白名单 ===");
        String[] forbidden = {".dsh", "cache", "credentials.yaml", "settings.yaml",
                "shared.log", ".assets-version", "node", "sessions", ""};
        for (String name : forbidden) {
            check("not deletable: " + (name.isEmpty() ? "<empty>" : name),
                    !RuntimeDir.isDeletableName(name), "ACCEPTED");
        }
        check("null name rejected", !RuntimeDir.isDeletableName(null), "accepted");

        System.out.println("=== 3. 真实目录：白名单内且直属 root 时可删 ===");
        File dsh = mkdirs(root, "dsh");
        new File(dsh, "lib").mkdirs();
        RuntimeDir.delete(dsh, root);
        check("dsh directory actually removed", !dsh.exists(), "still there");

        File tools = mkdirs(root, "tools");
        RuntimeDir.delete(tools, root);
        check("tools removed", !tools.exists(), "still there");

        System.out.println("=== 4. 真实目录：用户数据被拒绝且仍在原处 ===");
        File userData = mkdirs(root, ".dsh");
        new File(userData, "sessions").mkdirs();
        try {
            RuntimeDir.delete(userData, root);
            check(".dsh must be refused", false, "it was deleted");
        } catch (IOException e) {
            check(".dsh refused", true, "");
            check(".dsh still exists after refusal", userData.exists(), "data gone");
        }

        File cache = mkdirs(root, "cache");
        try {
            RuntimeDir.delete(cache, root);
            check("cache must be refused", false, "it was deleted");
        } catch (IOException e) {
            check("cache refused and intact", cache.exists(), "data gone");
        }

        System.out.println("=== 5. 边界：非直属子目录（孙级）被拒绝 ===");
        // 故意用**白名单里的名字**放在孙级：否则会因「名字不在白名单」被拒，
        // 根本走不到「父目录不是 root」这条规则，断言就变成假通过。
        File deep = mkdirs(root, "tools/sub/tools");
        check("name is whitelisted", RuntimeDir.isDeletableName(deep.getName()), "not whitelisted");
        String refusal = RuntimeDir.checkDeletable(deep, root);
        check("grandchild refused by parent rule", refusal != null, "allowed");

        System.out.println("=== 6. 边界：root 自身不可删 ===");
        check("root itself refused", RuntimeDir.checkDeletable(root, root) != null, "allowed");

        System.out.println("=== 7. 边界：root 之外的同名目录被拒绝 ===");
        File outside = File.createTempFile("dsh-rt-out", "x");
        outside.delete();
        outside.mkdirs();
        outside.deleteOnExit();
        File foreign = new File(outside, "dsh");
        foreign.mkdirs();
        // 名字在白名单里，但父目录不是 root —— 必须拒绝
        check("foreign dsh refused", RuntimeDir.checkDeletable(foreign, root) != null, "allowed");
        check("foreign dsh still exists", foreign.exists(), "deleted");
        RuntimeDir.delete(foreign, outside);
        outside.delete();

        System.out.println("=== 8. 空输入不炸 ===");
        check("null target check", RuntimeDir.checkDeletable(null, root) != null, "allowed");
        check("null root check", RuntimeDir.checkDeletable(dsh, null) != null, "allowed");
        RuntimeDir.delete(null, root);      // 不抛异常即为通过
        RuntimeDir.delete(dsh, null);
        check("null inputs tolerated", true, "");

        System.out.println("=== 9. 删除不存在的目录是幂等的 ===");
        RuntimeDir.delete(new File(root, "payload-rollback"), root);
        check("missing dir no-op", true, "");

        root.delete();
        System.out.println();
        System.out.println("RuntimeDirTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
