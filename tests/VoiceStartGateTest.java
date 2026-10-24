package dev.dsh.nativeapp;

public class VoiceStartGateTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("voice start gate " + checks); checks++; }
    public static void main(String[] args) {
        VoiceStartGate gate = new VoiceStartGate();
        check(gate.poll(0L, true) == VoiceStartGate.EXPIRED);
        gate.reset(0L);
        check(gate.poll(0L, true) == VoiceStartGate.WAIT);
        check(gate.poll(249L, true) == VoiceStartGate.WAIT);
        check(gate.poll(250L, true) == VoiceStartGate.START);
        check(gate.poll(251L, true) == VoiceStartGate.EXPIRED);
        gate.reset(1000L);
        check(gate.poll(1000L, false) == VoiceStartGate.WAIT);
        check(gate.poll(2000L, true) == VoiceStartGate.WAIT);
        check(gate.poll(2249L, false) == VoiceStartGate.WAIT);
        check(gate.poll(2300L, true) == VoiceStartGate.WAIT);
        check(gate.poll(2549L, true) == VoiceStartGate.WAIT);
        check(gate.poll(2550L, true) == VoiceStartGate.START);
        gate.reset(0L);
        check(gate.poll(3999L, false) == VoiceStartGate.WAIT);
        check(gate.poll(4000L, true) == VoiceStartGate.EXPIRED);
        gate.reset(900L);check(gate.poll(800L, true) == VoiceStartGate.EXPIRED);
        gate.reset(0L);gate.cancel();check(gate.poll(250L, true) == VoiceStartGate.EXPIRED);
        gate.reset(0L);gate.poll(0L,true);gate.reset(200L);
        check(gate.poll(250L, true) == VoiceStartGate.WAIT);
        check(gate.poll(500L, true) == VoiceStartGate.START);
        System.out.println("VoiceStartGate: " + checks + " checks passed");
    }
}
