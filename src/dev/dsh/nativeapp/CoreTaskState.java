package dev.dsh.nativeapp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Read the authenticated core directly; never infer idle from missing evidence. */
final class CoreTaskState {
    static final long FRESH_MS = 15000L;
    private CoreTaskState() { }

    static int read(CoreRpcClient client) throws IOException {
        CoreRpcClient.Reply reply = client.call("session/list", "{\"_request\":{}}");
        return parse(reply.body, reply.id);
    }

    static int parse(String json, String id) throws IOException {
        try {
            Map<?, ?> envelope = (Map<?, ?>) JsonValue.parse(json);
            if (!"server-response".equals(envelope.get("type")) || !id.equals(envelope.get("rpcId")))
                throw new IOException("invalid task reply");
            Map<?, ?> result = (Map<?, ?>) envelope.get("result");
            if (!Boolean.TRUE.equals(result.get("ok"))) throw new IOException("task state unavailable");
            Map<?, ?> value = (Map<?, ?>) result.get("value");
            List<?> items = (List<?>) value.get("items");
            if (items == null) throw new IOException("task rows unavailable");
            boolean active = false;
            for (Object item : items) {
                Object running = ((Map<?, ?>) item).get("running");
                if (!(running instanceof Boolean)) throw new IOException("incomplete task state");
                active |= Boolean.TRUE.equals(running);
            }
            return active ? SessionStatus.RUNNING : SessionStatus.IDLE;
        } catch (RuntimeException invalid) { throw new IOException("invalid task state"); }
    }

    static int effective(int nativeState, long nativeAt, int pageState, long now) {
        if (nativeAt > 0 && now >= nativeAt && now - nativeAt <= FRESH_MS) {
            if (nativeState == SessionStatus.RUNNING && pageState == SessionStatus.AWAITING_APPROVAL)
                return SessionStatus.AWAITING_APPROVAL;
            return nativeState;
        }
        return SessionStatus.UNKNOWN;
    }
}
