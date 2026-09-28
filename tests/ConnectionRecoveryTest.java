package dev.dsh.nativeapp;

/** ConnectionRecovery 的解析、恢复边界与脚本契约。 */
public class ConnectionRecoveryTest {
    static int pass, fail;
    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL " + name + " -> " + detail); }
    }
    public static void main(String[] args) {
        if (args.length > 0 && "--dump-script".equals(args[0])) {
            System.out.print(ConnectionRecovery.script());
            return;
        }
        check("connecting parsed", ConnectionRecovery.parseConsole("[dsh-conn] connecting")
                == ConnectionRecovery.CONNECTING, "wrong");
        check("open parsed", ConnectionRecovery.parseConsole("x [dsh-conn] open")
                == ConnectionRecovery.CONNECTED, "wrong");
        check("close parsed", ConnectionRecovery.parseConsole("[dsh-conn] close code=1006")
                == ConnectionRecovery.RETRYING, "wrong");
        check("error parsed", ConnectionRecovery.parseConsole("[dsh-conn] error")
                == ConnectionRecovery.ERROR, "wrong");
        check("junk ignored", ConnectionRecovery.parseConsole("open") == -1, "wrong");
        check("null safe", ConnectionRecovery.parseConsole(null) == -1, "wrong");
        check("short disconnect does not reload", !ConnectionRecovery.shouldReload(false, 29_999L, 0), "wrong");
        check("active task never reloads", !ConnectionRecovery.shouldReload(true, 60_000L, 0), "wrong");
        check("idle stuck connection reloads", ConnectionRecovery.shouldReload(false, 30_000L, 0), "wrong");
        check("attempt limit enforced", !ConnectionRecovery.shouldReload(false, 60_000L, 2), "wrong");
        check("negative attempts rejected", !ConnectionRecovery.shouldReload(false, 60_000L, -1), "wrong");
        check("problem states", ConnectionRecovery.isProblem(ConnectionRecovery.RETRYING)
                && ConnectionRecovery.isProblem(ConnectionRecovery.ERROR)
                && !ConnectionRecovery.isProblem(ConnectionRecovery.CONNECTED), "wrong");
        String js = ConnectionRecovery.script();
        check("idempotent", js.contains("__dshConnectionWatch"), "missing");
        check("does not log url or messages", !js.contains("console.log(url")
                && !js.contains("message.data"), "leaks data");
        check("preserves prototype", js.contains("Wrapped.prototype=Native.prototype"), "missing");
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
