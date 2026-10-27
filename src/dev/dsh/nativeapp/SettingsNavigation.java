package dev.dsh.nativeapp;

import java.util.LinkedHashSet;

/** One navigation owner for WebUI settings, native fallback and focus hand-off. */
final class SettingsNavigation {
    interface Result { void complete(boolean accepted); }
    interface Host {
        long document();
        boolean ready();
        void web(String page, Result result);
        void acknowledge(String id, Result result);
        void device(AppSettingsCommands command);
        void fallback();
    }
    private final Host host;
    private final LinkedHashSet<String> requests = new LinkedHashSet<String>();
    private final LinkedHashSet<String> commands = new LinkedHashSet<String>();
    private long navigation;
    private String returnPage;
    private boolean closed;
    SettingsNavigation(Host host) { this.host = host; }
    private static boolean first(LinkedHashSet<String> seen, String id) {
        if (!seen.add(id)) return false;
        while (seen.size() > 256) seen.remove(seen.iterator().next());
        return true;
    }
    boolean accept(String id) { return !closed && first(requests, id); }
    void open() {
        if (closed) return;
        String page = returnPage == null ? "home" : returnPage; returnPage = null;
        final long ticket = ++navigation, document = host.document();
        if (!host.ready()) { host.fallback(); return; }
        host.web(page, new Result() { @Override public void complete(boolean accepted) {
            if (current(ticket, document) && !accepted) host.fallback();
        }});
    }
    void device(final AppSettingsCommands command) {
        if (closed || !host.ready() || !first(commands, command.id)) return;
        returnPage = command.action;
        final long ticket = ++navigation, document = host.document();
        host.acknowledge(command.id, new Result() { @Override public void complete(boolean accepted) {
            if (accepted && current(ticket, document)) host.device(command);
        }});
    }
    private boolean current(long ticket, long document) { return !closed && ticket == navigation && document == host.document(); }
    void documentChanged() { navigation++; returnPage = null; requests.clear(); commands.clear(); }
    void close() { closed = true; documentChanged(); }
}
