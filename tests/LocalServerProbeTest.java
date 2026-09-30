package dev.dsh.nativeapp;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalServerProbeTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger externalCalls = new AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                String query = exchange.getRequestURI().getQuery();
                if ("token=good".equals(query)) {
                    exchange.getResponseHeaders().add("Location", "./");
                    exchange.getResponseHeaders().add("Set-Cookie", "dsh_session=signed; HttpOnly; SameSite=Strict");
                    exchange.sendResponseHeaders(303, -1);
                } else if ("dsh_session=signed".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                    send(exchange, 200, "<html>DeepSeek Harness</html>");
                } else send(exchange, 401, "authentication required");
            } finally { exchange.close(); }
        });
        server.createContext("/foreign", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:" + server.getAddress().getPort() + "/target");
            exchange.getResponseHeaders().add("Set-Cookie", "secret=private");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            externalCalls.incrementAndGet(); send(exchange, 200, "unexpected"); exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.getResponseHeaders().add("Set-Cookie", "session=x");
            exchange.sendResponseHeaders(303, -1); exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            check(LocalServerProbe.readAuthenticatedIndex(origin + "/?token=good", 1000).contains("DeepSeek"));
            check(LocalServerProbe.readAuthenticatedIndex(origin + "/?token=bad", 1000) == null);
            check(LocalServerProbe.readAuthenticatedIndex(origin + "/foreign", 1000) == null && externalCalls.get() == 0);
            check(LocalServerProbe.readAuthenticatedIndex(origin + "/loop", 1000) == null);
            check(LocalServerProbe.readAuthenticatedIndex("http://example.invalid/", 1000) == null);
            System.out.println("TOTAL: 5 pass / 0 fail");
        } finally { server.stop(0); }
    }
    static void send(HttpExchange exchange, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes("UTF-8"); exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    static void check(boolean condition) { if (!condition) throw new AssertionError("local authentication probe"); }
}
