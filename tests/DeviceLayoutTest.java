package dev.dsh.nativeapp;

/** DeviceLayout 的离线回归测试。 */
public class DeviceLayoutTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }

    public static void main(String[] args) {
        check("phone dialog keeps total 24dp gutter",
                DeviceLayout.dialogWidthPx(1080, 3f) == 1008, "wrong");
        check("tablet dialog width capped",
                DeviceLayout.dialogWidthPx(2560, 2f) == 1440, "wrong");
        check("tiny width remains positive",
                DeviceLayout.dialogWidthPx(10, 3f) == 1, "wrong");
        check("dialog requested height respected",
                DeviceLayout.dialogHeightPx(2400, 600, 3f) == 1800, "wrong");
        check("dialog height capped to screen",
                DeviceLayout.dialogHeightPx(1000, 900, 2f) == 860, "wrong");
        check("single action stays horizontal",
                !DeviceLayout.stackFooter(280, 2f, 1), "stacked");
        check("three phone actions stack",
                DeviceLayout.stackFooter(400, 1f, 3), "not stacked");
        check("three tablet actions stay horizontal",
                !DeviceLayout.stackFooter(700, 1f, 3), "stacked");
        check("large font stacks two actions",
                DeviceLayout.stackFooter(400, 1.6f, 2), "not stacked");
        check("four actions always stack",
                DeviceLayout.stackFooter(1200, 1f, 4), "not stacked");
        check("phone profile", "phone/portrait/font=1.00".equals(
                DeviceLayout.profile(400, 800, 1f)), "wrong");
        check("tablet landscape profile", "tablet/landscape/font=1.30".equals(
                DeviceLayout.profile(700, 500, 1.3f)), "wrong");
        check("touch target is 48dp", DeviceLayout.MIN_TOUCH_TARGET_DP == 48, "wrong");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
