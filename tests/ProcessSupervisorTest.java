package dev.dsh.nativeapp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** ProcessSupervisor 的离线测试。 */
public class ProcessSupervisorTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    public static void main(String[] args) {
        ProcessSupervisor s = new ProcessSupervisor();
        FakeProcess first = new FakeProcess();
        FakeProcess second = new FakeProcess();

        check("empty supervisor is not alive", !s.isAlive(), "should be false");
        s.adopt(first);
        check("adopt exposes process", s.current() == first, "wrong process");
        check("adopted process is alive", s.isAlive(), "should be alive");

        s.adopt(second);
        check("replacing process stops previous", first.destroyed, "old process still alive");
        check("replacement becomes current", s.current() == second, "wrong current");

        check("stale clear is rejected", !s.clear(first), "should be false");
        check("stale clear cannot remove replacement", s.current() == second, "cleared new process");
        second.finish();
        check("natural exit is detected", !s.isAlive(), "should be stopped");
        check("matching clear succeeds", s.clear(second), "should be true");
        check("matching clear removes process", s.current() == null, "not cleared");

        FakeProcess third = new FakeProcess();
        s.adopt(third);
        check("stop reports managed process", s.stop(), "should report true");
        check("stop destroys process", third.destroyed, "not destroyed");
        check("second stop reports empty", !s.stop(), "should report false");

        boolean rejected = false;
        try { s.adopt(null); } catch (IllegalArgumentException expected) { rejected = true; }
        check("null process rejected", rejected, "should throw");

        System.out.println();
        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static final class FakeProcess extends Process {
        boolean destroyed;
        boolean running = true;

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { running = false; return 0; }
        @Override public int exitValue() {
            if (running) throw new IllegalThreadStateException("running");
            return 0;
        }
        @Override public void destroy() { destroyed = true; running = false; }
        void finish() { running = false; }
    }
}
