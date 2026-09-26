package dev.dsh.nativeapp;

/** MobileLayout 的离线回归测试。 */
public class MobileLayoutTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        check("phone keeps readable width", MobileLayout.viewportWidth(400) == 480, "wrong");
        check("wide phone uses real width", MobileLayout.viewportWidth(600) == 600, "wrong");
        check("landscape not multiplied", MobileLayout.viewportWidth(869) == 869, "wrong");
        check("huge width capped", MobileLayout.viewportWidth(2000) == 1440, "wrong");
        check("invalid width safe", MobileLayout.viewportWidth(0) == 480, "wrong");
        String html = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body></body></html>";
        String once = MobileLayout.patchHtml(html, 480);
        check("viewport patched", once != null && once.contains("width=480"), String.valueOf(once));
        check("style injected", once != null && once.contains("dsh-native-responsive"), "missing");
        String twice = MobileLayout.patchHtml(once, 869);
        check("patch idempotent", twice != null && twice.indexOf("dsh-native-responsive")
                == twice.lastIndexOf("dsh-native-responsive"), String.valueOf(twice));
        check("width can change", twice != null && twice.contains("width=869"), String.valueOf(twice));
        check("missing viewport rejected", MobileLayout.patchHtml("<head></head>", 480) == null, "accepted");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
