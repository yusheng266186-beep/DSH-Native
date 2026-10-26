package dev.dsh.nativeapp;

public class StartupStateTest {
    private static int checks;
    public static void main(String[] args) {
        if (args.length == 1 && "--dump-script".equals(args[0])) {
            System.out.print(StartupState.script(17)); return;
        }
        StartupState state = new StartupState();
        long first = state.navigate();
        state.connected(true); check(!state.ready(), "handshake alone is not a rendered page");
        check(!state.painted(first - 1) && !state.ready(), "ignore stale paint callback");
        check(state.painted(first) && state.ready(), "both actual signals finish startup");
        state.connected(false); check(!state.ready(), "loss before handoff withdraws readiness");
        state.connected(true); check(state.ready(), "connection may recover without repaint");
        long next = state.navigate(); check(next != first && !state.ready(), "navigation clears both signals");
        check(!state.painted(first), "a previous page cannot complete a new launch");
        state.painted(next); check(!state.ready(), "paint before handshake waits");
        state.connected(true); check(state.ready(), "signal order does not matter");
        check(!StartupState.cycleEnded(1000, 1500, 1719), "no early cut in motion");
        check(StartupState.cycleEnded(1000, 1719, 1720), "finish exactly at next cycle boundary");
        check(!StartupState.cycleEnded(1000, 1720, 1736), "no duplicate boundary");
        check(StartupState.cycleEnded(1000, 1736, 3161), "dropped frames do not stall completion");
        check(!StartupState.cycleEnded(1000, 1300, 900), "clock rollback is not completion");
        String key = StartupReuse.key("0.40.1", "dsh=13", "/private/project");
        check(StartupReuse.eligible(key,key,"0.40.1","0.40.1",true,false,true), "prepared environment can be reused");
        check(!StartupReuse.eligible(key,"","0.40.1","0.40.1",true,false,true), "new installation needs preparation");
        for (String changed : new String[]{StartupReuse.key("0.40.2","dsh=13","/private/project"),
                StartupReuse.key("0.40.1","dsh=14","/private/project"),
                StartupReuse.key("0.40.1","dsh=13","/private/other")}) {
            check(!StartupReuse.eligible(key,changed,"0.40.1","0.40.1",true,false,true), "changed preparation falls through");
        }
        check(!StartupReuse.eligible(key,key,"0.40.1","0.40.0",true,false,true), "old APK assets cannot be skipped");
        check(!StartupReuse.eligible(key,key,"0.40.1","0.40.1",false,false,true), "missing runtime needs repair");
        check(!StartupReuse.eligible(key,key,"0.40.1","0.40.1",true,true,true), "pending slice update cannot be skipped");
        check(!StartupReuse.eligible(key,key,"0.40.1","0.40.1",true,false,false), "untrusted manifest cannot be skipped");
        System.out.println("TOTAL: " + checks + " pass / 0 fail");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); checks++;
    }
}
