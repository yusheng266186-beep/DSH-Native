package dev.dsh.nativeapp;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * 进程级未捕获异常记录器：纯 Java，无 Android 依赖。
 *
 * <p>Activity 会因为网页主题切换而重建。如果每次重建都把新的匿名处理器
 * 套在旧处理器外面，整条代理链会永久持有所有旧 Activity，并且一次崩溃会
 * 被重复写入多次。这里把处理器做成进程级单例；再次安装只更新输出文件，
 * 不再增加代理层。</p>
 */
final class CrashReporter implements Thread.UncaughtExceptionHandler {

    private volatile File file;
    private final Thread.UncaughtExceptionHandler delegate;

    private CrashReporter(File file, Thread.UncaughtExceptionHandler delegate) {
        this.file = file;
        this.delegate = delegate;
    }

    /** 安装一次；重复调用不会形成处理器链。 */
    static synchronized Thread.UncaughtExceptionHandler install(File file) {
        Thread.UncaughtExceptionHandler current =
                Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashReporter) {
            ((CrashReporter) current).file = file;
            return current;
        }
        CrashReporter reporter = new CrashReporter(file, current);
        Thread.setDefaultUncaughtExceptionHandler(reporter);
        return reporter;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        File target = file;
        if (target != null && error != null) {
            try {
                PrintWriter out = new PrintWriter(new FileWriter(target, true));
                try {
                    out.println("=== " + new java.util.Date() + " / thread "
                            + (thread == null ? "?" : thread.getName()) + " ===");
                    error.printStackTrace(out);
                    out.flush();
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) { }
        }
        if (delegate != null && delegate != this) {
            delegate.uncaughtException(thread, error);
        }
    }
}
