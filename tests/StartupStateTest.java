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
        check(StartupState.swimPhase(0L) == 0f && StartupState.swimPhase(-20L) == 0f, "motion starts at its resting pose");
        check(StartupState.swimPhase(600L) == .25f, "swimming is deliberately slower than a spinner");
        check(StartupState.swimPhase(2400L) == 0f, "swim loops without a discontinuous pose");
        check(StartupState.handoffProgress(-1L) == 0f, "clock reversal cannot start a fade");
        check(StartupState.handoffProgress(90L) == .5f, "handoff has a smooth midpoint");
        check(StartupState.handoffProgress(180L) == 1f && StartupState.handoffProgress(9000L) == 1f,
                "handoff completes within a bounded interval even if frames drop");
        for (int ms = 1; ms <= 180; ms++) {
            check(StartupState.handoffProgress(ms) >= StartupState.handoffProgress(ms - 1), "handoff never reverses");
        }
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
        StartupState.Timing timing = new StartupState.Timing(1000L);
        check("pending".equals(timing.summary()), "timing starts without invented zero measurements");
        check(timing.mark("assets", 1012L) && timing.mark("paint", 1800L), "numeric milestones recorded");
        check(!timing.mark("assets", 1400L) && timing.summary().contains("assets=12ms"), "later callbacks cannot replace the original milestone");
        check(!timing.mark("PRIVATE-PATH", 1900L) && !timing.summary().contains("PRIVATE"), "only fixed diagnostic labels accepted");
        check(!timing.mark("runtime", 900L) && !timing.summary().contains("runtime"), "clock reversal does not fabricate negative time");
        System.out.println("TOTAL: " + checks + " pass / 0 fail");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); checks++;
    }
}
