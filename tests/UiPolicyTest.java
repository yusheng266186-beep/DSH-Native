package dev.dsh.nativeapp;

/** UiPolicy 的离线回归测试。 */
public class UiPolicyTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) {
        check("light success", UiPolicy.success(false) == 0xFF1A7F37, "color");
        check("dark success", UiPolicy.success(true) == 0xFF22C55E, "color");
        check("light warning", UiPolicy.warning(false) == 0xFFB26A00, "color");
        check("dark warning", UiPolicy.warning(true) == 0xFFF59E0B, "color");
        check("light error", UiPolicy.error(false) == 0xFFD93025, "color");
        check("dark error", UiPolicy.error(true) == 0xFFF25A5A, "color");

        check("alpha replaces", UiPolicy.withAlpha(0xFFF25A5A, 0x14)
                == 0x14F25A5A, "alpha");
        check("alpha clamps low", UiPolicy.withAlpha(0xFF123456, -2)
                == 0x00123456, "low");
        check("alpha clamps high", UiPolicy.withAlpha(0x00123456, 999)
                == 0xFF123456, "high");

        check("normal animations", UiPolicy.animationsEnabled(1f), "disabled");
        check("slow animations", UiPolicy.animationsEnabled(0.5f), "disabled");
        check("zero animations", !UiPolicy.animationsEnabled(0f), "enabled");
        check("negative animations", !UiPolicy.animationsEnabled(-1f), "enabled");
        check("nan animations", !UiPolicy.animationsEnabled(Float.NaN), "enabled");
        check("infinite animations", !UiPolicy.animationsEnabled(
                Float.POSITIVE_INFINITY), "enabled");

        check("inset keyboard", UiPolicy.imeFromInsets(720, 120) == 600, "value");
        check("inset nav only", UiPolicy.imeFromInsets(120, 120) == 0, "value");
        check("inset clamps", UiPolicy.imeFromInsets(10, 30) == 0, "value");
        check("frame keyboard", UiPolicy.imeFromVisibleFrame(2000, 1200, 100)
                == 700, "value");
        check("frame noise", UiPolicy.imeFromVisibleFrame(2000, 1750, 100)
                == 0, "noise");
        check("frame invalid", UiPolicy.imeFromVisibleFrame(0, 0, 0) == 0, "value");
        check("merged inset", UiPolicy.mergedIme(600, 0) == 600, "value");
        check("merged frame", UiPolicy.mergedIme(0, 700) == 700, "value");
        check("merged clamps", UiPolicy.mergedIme(-1, -2) == 0, "value");
        check("safe side system", UiPolicy.safeSide(44, 20) == 44, "value");
        check("safe side cutout", UiPolicy.safeSide(10, 60) == 60, "value");
        check("safe side clamps", UiPolicy.safeSide(-1, -2) == 0, "value");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
