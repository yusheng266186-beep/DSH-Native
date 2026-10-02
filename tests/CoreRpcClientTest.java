package dev.dsh.nativeapp;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class CoreRpcClientTest {
    static int pass;
    static void check(boolean condition) { if (!condition) throw new AssertionError("native RPC boundary"); pass++; }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger authentication = new AtomicInteger(), requests = new AtomicInteger(), foreign = new AtomicInteger();
        server.createContext("/", e -> {
            String query = e.getRequestURI().getRawQuery();
            authentication.incrementAndGet();
            e.getResponseHeaders().add("Location", "token=foreign".equals(query)
                    ? "http://localhost:" + server.getAddress().getPort() + "/foreign" : "/");
            e.getResponseHeaders().add("Set-Cookie", "dsh_session=test-only; HttpOnly; SameSite=Strict");
            e.sendResponseHeaders(303, -1); e.close();
        });
        server.createContext("/foreign", e -> { foreign.incrementAndGet(); send(e, 200, "unexpected"); });
        server.createContext("/api/account/getState", e -> {
            requests.incrementAndGet();
            if (!"POST".equals(e.getRequestMethod()) || !"dsh_session=test-only".equals(e.getRequestHeaders().getFirst("Cookie"))) {
                send(e, 401, "PRIVATE-RESPONSE"); return;
            }
            String raw = new String(e.getRequestBody().readAllBytes(), "UTF-8");
            Map<?, ?> request = (Map<?, ?>) JsonValue.parse(raw);
            check("client-request".equals(request.get("type")) && "account/getState".equals(request.get("method")));
            check(((Map<?, ?>) request.get("payload")).get("args") instanceof Map);
            send(e, 200, "{\"type\":\"server-response\",\"rpcId\":\"" + request.get("rpcId")
                    + "\",\"result\":{\"ok\":true,\"value\":{\"status\":\"signed-out\"}}}");
        });
        server.createContext("/api/account/signOut", e -> send(e, 500, "PRIVATE-RESPONSE"));
        server.createContext("/api/account/getBalance", e -> send(e, 200, "x".repeat(2 * 1024 * 1024 + 1)));
        server.start();
        CoreRpcClient client = new CoreRpcClient(server.getAddress().getPort(), "test-token");
        try {
            CoreRpcClient.Reply first = client.call("account/getState", "{}");
            check(first.body.contains(first.id) && first.body.contains("signed-out"));
            client.call("account/getState", "{}");
            check(authentication.get() == 1 && requests.get() == 2);
            reject(() -> client.call("../account/getState", "{}"));
            check(requests.get() == 2);
            reject(() -> client.call("account/signOut", "{}"));
            reject(() -> client.call("account/getBalance", "{}"));
            CoreRpcClient other = new CoreRpcClient(server.getAddress().getPort(), "foreign");
            try { reject(() -> other.call("account/getState", "{}")); check(foreign.get() == 0); }
            finally { other.close(); }
            client.close(); reject(() -> client.call("account/getState", "{}"));
            check(requests.get() == 2);
        } finally { client.close(); server.stop(0); }
        System.out.println("TOTAL: " + pass + " pass / 0 fail");
    }
    interface Call { void run() throws Exception; }
    static void reject(Call call) throws Exception {
        try { call.run(); throw new AssertionError("unsafe request accepted"); }
        catch (IOException expected) {
            check(!expected.toString().contains("test-token") && !expected.toString().contains("PRIVATE-RESPONSE")
                    && !expected.toString().contains("Cookie") && expected.getCause() == null);
        }
    }
    static void send(HttpExchange exchange, int status, String text) throws IOException {
        try { byte[] bytes = text.getBytes("UTF-8"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
        finally { exchange.close(); }
    }
}
