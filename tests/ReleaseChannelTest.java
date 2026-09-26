package dev.dsh.nativeapp;

/** ReleaseChannel 的离线回归测试。 */
public class ReleaseChannelTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        check("null defaults stable", "stable".equals(ReleaseChannel.normalize(null)), "wrong");
        check("unknown defaults stable", "stable".equals(ReleaseChannel.normalize("beta")), "wrong");
        check("test normalized", "test".equals(ReleaseChannel.normalize(" TEST ")), "wrong");
        check("stable manifest", "latest.json".equals(ReleaseChannel.manifest("stable")), "wrong");
        check("test manifest", "latest-test.json".equals(ReleaseChannel.manifest("test")), "wrong");
        check("stable tag", "v0.26.0-bootstrap".equals(ReleaseChannel.tag("0.26.0", "stable")), "wrong");
        check("test tag", "v0.26.0-test".equals(ReleaseChannel.tag("0.26.0", "test")), "wrong");
        check("labels", "测试版".equals(ReleaseChannel.label("test"))
                && "稳定版".equals(ReleaseChannel.label("stable")), "wrong");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
