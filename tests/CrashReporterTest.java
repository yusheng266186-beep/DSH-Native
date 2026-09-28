package dev.dsh.nativeapp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/** CrashReporter 重复安装与代理行为测试。 */
public class CrashReporterTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        Thread.UncaughtExceptionHandler original =
                Thread.getDefaultUncaughtExceptionHandler();
        File file = File.createTempFile("dsh-crash-", ".log");
        file.delete();
        final AtomicInteger delegated = new AtomicInteger();
        Thread.UncaughtExceptionHandler probe = new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                delegated.incrementAndGet();
            }
        };

        try {
            Thread.setDefaultUncaughtExceptionHandler(probe);
            Thread.UncaughtExceptionHandler first = CrashReporter.install(file);
            Thread.UncaughtExceptionHandler second = CrashReporter.install(file);
            check("reinstall reuses one handler", first == second, "handler was chained");
            check("handler is process default",
                    Thread.getDefaultUncaughtExceptionHandler() == first, "not installed");

            first.uncaughtException(Thread.currentThread(),
                    new IllegalStateException("crash-probe"));
            check("delegates exactly once", delegated.get() == 1,
                    String.valueOf(delegated.get()));
            String text = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            check("writes exception type", text.contains("IllegalStateException"), text);
            check("writes exception message", text.contains("crash-probe"), text);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
            file.delete();
        }

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
