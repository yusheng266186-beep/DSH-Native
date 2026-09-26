package dev.dsh.nativeapp;

/** UiText 的离线回归测试。 */
public class UiTextTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        check("normalizes explicit English", "en".equals(UiText.normalize("EN-us")),
                UiText.normalize("EN-us"));
        check("normalizes explicit Chinese", "zh".equals(UiText.normalize("zh-CN")),
                UiText.normalize("zh-CN"));
        check("unknown preference becomes auto", "auto".equals(UiText.normalize("fr")),
                UiText.normalize("fr"));
        check("auto follows Chinese system", "zh".equals(UiText.resolve("auto", "zh-CN")),
                UiText.resolve("auto", "zh-CN"));
        check("auto falls back to English", "en".equals(UiText.resolve("auto", "de")),
                UiText.resolve("auto", "de"));
        check("explicit choice overrides system", "en".equals(UiText.resolve("en", "zh")),
                UiText.resolve("en", "zh"));

        UiText.configure("en", "zh");
        check("known label translated", "Settings".equals(UiText.text("设置")),
                UiText.text("设置"));
        check("dynamic prefix translated",
                "Current app version 1.2.3".equals(UiText.text("当前 App 版本 1.2.3")),
                UiText.text("当前 App 版本 1.2.3"));
        check("unknown text preserved", "model-x".equals(UiText.text("model-x")),
                UiText.text("model-x"));
        UiText.configure("zh", "en");
        check("Chinese mode preserves label", "设置".equals(UiText.text("设置")),
                UiText.text("设置"));

        check("new install sees guide", UiText.shouldShowFirstRun(false, false), "hidden");
        check("completed guide stays hidden", !UiText.shouldShowFirstRun(true, false), "shown");
        check("upgraded install stays hidden", !UiText.shouldShowFirstRun(false, true), "shown");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
