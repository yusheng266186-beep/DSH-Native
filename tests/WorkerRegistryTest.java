package dev.dsh.nativeapp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WorkerRegistry 销毁中断与停止后拒绝启动测试。 */
public class WorkerRegistryTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        WorkerRegistry registry = new WorkerRegistry();
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                waiting.countDown();
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                } finally {
                    finished.countDown();
                }
            }
        }, "registry-probe");

        check("starts active worker", registry.start(worker), "start rejected");
        check("worker registered", registry.size() == 1,
                String.valueOf(registry.size()));
        check("worker reached wait", waiting.await(2, TimeUnit.SECONDS), "not running");

        registry.stop();
        check("registry marked stopped", registry.isStopped(), "still active");
        check("worker interrupted", finished.await(2, TimeUnit.SECONDS)
                && interrupted.get(), "worker survived stop");
        check("registry cleared", registry.size() == 0,
                String.valueOf(registry.size()));

        final AtomicBoolean lateRan = new AtomicBoolean();
        Thread late = new Thread(new Runnable() {
            @Override public void run() { lateRan.set(true); }
        }, "late-probe");
        check("rejects worker after stop", !registry.start(late), "late start accepted");
        Thread.sleep(20L);
        check("rejected worker did not run", !lateRan.get(), "late worker ran");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
