package dev.dsh.nativeapp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** OperationGate 的离线回归测试。 */
public class OperationGateTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        OperationGate gate = new OperationGate();
        check("starts empty", gate.active() == null, "active");
        check("rejects null", !gate.tryStart(null), "accepted");
        check("rejects blank", !gate.tryStart("  "), "accepted");
        check("starts app update", gate.tryStart(OperationGate.APP_UPDATE), "rejected");
        check("reports active", OperationGate.APP_UPDATE.equals(gate.active()), "wrong");
        check("matches active", gate.isActive(OperationGate.APP_UPDATE), "false");
        check("rejects duplicate", !gate.tryStart(OperationGate.APP_UPDATE), "accepted");
        check("rejects competing", !gate.tryStart(OperationGate.PAYLOAD_UPDATE), "accepted");
        gate.finish(OperationGate.PAYLOAD_UPDATE);
        check("wrong finish ignored", gate.isActive(OperationGate.APP_UPDATE), "released");
        gate.finish(OperationGate.APP_UPDATE);
        check("matching finish releases", gate.active() == null, "active");
        check("next operation starts", gate.tryStart(OperationGate.PAYLOAD_UPDATE), "rejected");
        gate.finish(OperationGate.PAYLOAD_UPDATE);

        final OperationGate concurrent = new OperationGate();
        final AtomicInteger winners = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(12);
        final CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[12];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) { return; }
                    if (concurrent.tryStart("并发任务")) winners.incrementAndGet();
                }
            });
            threads[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread thread : threads) thread.join();
        check("one concurrent winner", winners.get() == 1,
                "winners=" + winners.get());

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
