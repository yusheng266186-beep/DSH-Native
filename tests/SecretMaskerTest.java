package dev.dsh.nativeapp;

/** SecretMasker 的离线回归测试。 */
public class SecretMaskerTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static void hidden(String what, String input, String secret) {
        String masked = SecretMasker.mask(input);
        check(what, !masked.contains(secret) && masked.contains("***"), masked);
    }

    public static void main(String[] args) {
        check("null becomes empty", "".equals(SecretMasker.mask(null)), "wrong");
        check("ordinary text unchanged", "hello world".equals(SecretMasker.mask("hello world")),
                SecretMasker.mask("hello world"));
        hidden("authorization header", "Authorization: Bearer abcdefghijk", "abcdefghijk");
        hidden("quoted password", "{\"password\":\"hunter2-value\"}", "hunter2-value");
        hidden("yaml api key", "COMMANDCODE_API_KEY: key-value-123", "key-value-123");
        hidden("query token", "https://localhost/?token=session-value", "session-value");
        hidden("provider sk key", "using sk-abcdefghijk", "abcdefghijk");
        hidden("command code key", "user_abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz");
        hidden("jwt", "aaaaaaaaaa.bbbbbbbbbb.cccccccccc", "bbbbbbbbbb");
        hidden("long opaque value", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN1234",
                "abcdefghijklmnopqrstuvwxyz");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
