package dev.dsh.nativeapp;

/** UpdateProvider 路由白名单测试。 */
public class ProviderRouteTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        String authority = ProviderRoute.AUTHORITY;
        check("exact update path accepted",
                ProviderRoute.classify(authority, "/update.apk") == ProviderRoute.UPDATE_APK,
                "rejected");
        check("share path accepted",
                ProviderRoute.classify(authority, "/f/%2Fsdcard%2Fnote.txt")
                        == ProviderRoute.SHARED_FILE, "rejected");
        check("wrong authority rejected",
                ProviderRoute.classify("other.app", "/update.apk") == ProviderRoute.INVALID,
                "accepted");
        check("null authority rejected",
                ProviderRoute.classify(null, "/update.apk") == ProviderRoute.INVALID,
                "accepted");
        check("null path rejected",
                ProviderRoute.classify(authority, null) == ProviderRoute.INVALID,
                "accepted");
        check("extra update segment rejected",
                ProviderRoute.classify(authority, "/update.apk/extra") == ProviderRoute.INVALID,
                "accepted");
        check("similar update name rejected",
                ProviderRoute.classify(authority, "/UPDATE.apk") == ProviderRoute.INVALID,
                "accepted");
        check("empty share target rejected",
                ProviderRoute.classify(authority, "/f/") == ProviderRoute.INVALID,
                "accepted");
        check("similar share prefix rejected",
                ProviderRoute.classify(authority, "/file/x") == ProviderRoute.INVALID,
                "accepted");
        check("root rejected",
                ProviderRoute.classify(authority, "/") == ProviderRoute.INVALID,
                "accepted");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
