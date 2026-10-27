package dev.dsh.nativeapp;

import java.util.concurrent.locks.Lock;

/** Owns admission, cancellation, environment exclusion and delivery of startup results. */
final class StartupCoordinator {
    interface Dispatcher { void post(Runnable work); }
    interface Work { void run() throws Exception; }
    interface Failure { void failed(Exception error); }
    private final Lock environment;
    private final Dispatcher dispatcher;
    private final ThreadLocal<Long> context = new ThreadLocal<Long>();
    private long generation;
    private boolean closed;
    private Thread active;

    StartupCoordinator(Lock environment, Dispatcher dispatcher) { this.environment = environment; this.dispatcher = dispatcher; }
    synchronized boolean start(final Work work, final Failure failure) {
        if (closed || active != null) return false;
        final long ticket = ++generation;
        active = new Thread(new Runnable() {
            @Override public void run() {
                context.set(ticket);
                boolean locked = false;
                try {
                    environment.lockInterruptibly(); locked = true;
                    checkpoint(); work.run();
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                } catch (final Exception error) {
                    post(new Runnable() { @Override public void run() { failure.failed(error); } });
                } finally {
                    if (locked) environment.unlock();
                    context.remove();
                    synchronized (StartupCoordinator.this) { if (active == Thread.currentThread()) active = null; }
                }
            }
        }, "dsh-startup-" + ticket);
        active.setDaemon(true);
        active.start();
        return true;
    }
    void checkpoint() throws InterruptedException {
        Long ticket = context.get();
        if (Thread.currentThread().isInterrupted() || !valid(ticket)) throw new InterruptedException("Startup cancelled");
    }
    void post(final Runnable work) {
        final Long ticket = context.get();
        if (!valid(ticket)) return;
        dispatcher.post(new Runnable() { @Override public void run() { if (valid(ticket)) work.run(); } });
    }
    Runnable task(final Runnable work) {
        final Long ticket = context.get();
        return new Runnable() { @Override public void run() {
            if (!valid(ticket)) return;
            Long previous = context.get();
            if (ticket == null) context.remove(); else context.set(ticket);
            try { work.run(); }
            finally { if (previous == null) context.remove(); else context.set(previous); }
        }};
    }
    private synchronized boolean valid(Long ticket) { return !closed && (ticket == null || ticket.longValue() == generation); }
    synchronized boolean busy() { return active != null; }
    synchronized void close() {
        if (closed) return;
        closed = true; generation++;
        if (active != null) active.interrupt();
    }
}
