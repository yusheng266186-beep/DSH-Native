package dev.dsh.nativeapp;
import java.io.IOException;
public class CoreTaskStateTest {
    private static int checks;
    private static void check(boolean value) { if (!value) throw new AssertionError("task state " + checks); checks++; }
    private static String reply(String result) { return "{\"type\":\"server-response\",\"rpcId\":\"fixture\",\"result\":" + result + "}"; }
    public static void main(String[] args) throws Exception {
        check(CoreTaskState.parse(reply("{\"ok\":true,\"value\":{\"items\":[]}}"), "fixture") == SessionStatus.IDLE);
        check(CoreTaskState.parse(reply("{\"ok\":true,\"value\":{\"items\":[{\"running\":false},{\"running\":true}]}}"), "fixture") == SessionStatus.RUNNING);
        StringBuilder rows = new StringBuilder();
        for (int index=0; index<1000; index++) rows.append(index==0?"":",").append("{\"running\":false}");
        rows.append(",{\"running\":true}");
        check(CoreTaskState.parse(reply("{\"ok\":true,\"value\":{\"items\":["+rows+"]}}"), "fixture") == SessionStatus.RUNNING);
        for (String invalid : new String[]{"{}", reply("{\"ok\":false}"), reply("{\"ok\":true,\"value\":{}}"),
                reply("{\"ok\":true,\"value\":{\"items\":[{}]}}"), reply("{\"ok\":true,\"value\":{\"items\":[{\"running\":\"true\"}]}}"),
                reply("{\"ok\":true,\"value\":{\"items\":[]}}").replace("fixture", "stale")}) {
            try { CoreTaskState.parse(invalid, "fixture"); throw new AssertionError("invalid reply accepted"); }
            catch (IOException expected) { checks++; }
        }
        check(CoreTaskState.effective(SessionStatus.RUNNING, 1000, SessionStatus.IDLE, 1001) == SessionStatus.RUNNING);
        check(CoreTaskState.effective(SessionStatus.IDLE, 1000, SessionStatus.RUNNING, 1001) == SessionStatus.IDLE);
        check(CoreTaskState.effective(SessionStatus.RUNNING, 1000, SessionStatus.AWAITING_APPROVAL, 1001) == SessionStatus.AWAITING_APPROVAL);
        check(CoreTaskState.effective(SessionStatus.UNKNOWN, 1000, SessionStatus.IDLE, 1001) == SessionStatus.UNKNOWN);
        check(CoreTaskState.effective(SessionStatus.RUNNING, 1000, SessionStatus.IDLE, 17000) == SessionStatus.UNKNOWN);
        check(CoreTaskState.effective(SessionStatus.RUNNING, 2000, SessionStatus.IDLE, 1000) == SessionStatus.UNKNOWN);
        System.out.println("TOTAL: " + checks + " pass / 0 fail");
    }
}
