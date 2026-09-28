package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Activity 长生命周期线程登记器：纯 Java，无 Android 依赖。
 *
 * <p>看门狗和日志消费者会一直等待。如果 Activity 重建时不主动中断，
 * 每次重建都会多出两条线程，并通过 Runnable 持有旧 Activity。登记器把
 * 启动与停止收口，销毁时一次中断全部长期线程。</p>
 */
final class WorkerRegistry {

    private final Set<Thread> workers = new HashSet<Thread>();
    private volatile boolean stopped;

    /** 登记并启动线程；注册表已停止时不再启动。 */
    synchronized boolean start(Thread worker) {
        if (worker == null || stopped || workers.contains(worker)) return false;
        workers.add(worker);
        try {
            worker.start();
            return true;
        } catch (Throwable t) {
            workers.remove(worker);
            throw t;
        }
    }

    /** 线程自然结束时移出登记表。 */
    synchronized void finished(Thread worker) {
        workers.remove(worker);
    }

    /** 停止登记表，并中断所有仍在等待的线程。 */
    void stop() {
        java.util.List<Thread> snapshot;
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            snapshot = new ArrayList<Thread>(workers);
            workers.clear();
        }
        for (Thread worker : snapshot) {
            try { worker.interrupt(); } catch (Throwable ignored) { }
        }
    }

    boolean isStopped() {
        return stopped;
    }

    synchronized int size() {
        return workers.size();
    }
}
