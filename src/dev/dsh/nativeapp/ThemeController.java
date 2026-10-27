package dev.dsh.nativeapp;

/** Single owner of theme requests, cancellation and document lifetime. Calls run on the UI dispatcher. */
final class ThemeController {
    interface Dispatcher {
        void post(Runnable work, long delayMs);
        void cancel(Runnable work);
    }
    interface Host {
        boolean current(Object page, long generation);
        boolean dark();
        void remember(boolean dark);
        void apply(boolean dark);
        void failed(RuntimeException error);
    }
    private final Host host;
    private final Dispatcher dispatcher;
    private Runnable pending;
    private boolean closed;

    ThemeController(Host host, Dispatcher dispatcher) { this.host = host; this.dispatcher = dispatcher; }
    void rendered(Object page, long generation, boolean dark) { request(page, generation, dark, true); }
    void system(Object page, long generation, boolean dark) { request(page, generation, dark, false); }
    private void request(final Object page, final long generation, final boolean dark, final boolean remember) {
        if (closed || !host.current(page, generation)) return;
        if (pending != null) dispatcher.cancel(pending);
        pending = new Runnable() {
            @Override public void run() {
                if (pending != this) return;
                pending = null;
                if (closed || !host.current(page, generation)) return;
                if (remember) {
                    try { host.remember(dark); } catch (RuntimeException error) { host.failed(error); }
                }
                try { if (host.dark() != dark) host.apply(dark); }
                catch (RuntimeException error) { host.failed(error); }
            }
        };
        dispatcher.post(pending, 80L);
    }
    void close() {
        closed = true;
        if (pending != null) dispatcher.cancel(pending);
        pending = null;
    }
}
