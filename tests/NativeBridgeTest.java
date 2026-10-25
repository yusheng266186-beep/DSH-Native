package dev.dsh.nativeapp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NativeBridgeTest {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void invalid(String json) { try { NativeBridgePolicy.request(json); throw new AssertionError(json); } catch (IllegalArgumentException expected) { checks++; } }
    private static String request(int port, String token, String body, String extra) {
        return "POST /native HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + token
                + "\r\nContent-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" + extra + "\r\n" + body;
    }
    private static String call(int port, String raw) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(4000); socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            try { while ((count = socket.getInputStream().read(buffer)) != -1) result.write(buffer, 0, count); }
            catch (SocketException rejected) { /* A rejected request with unread input may close via TCP reset. */ }
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--serve".equals(args[0])) {
            final NativeBridgeServer server = new NativeBridgeServer(new File(args[1]), new NativeBridgeServer.Host() {
                public Object execute(Map<?, ?> command) throws Exception {
                    if ("clipboard.get".equals(command.get("method"))) return NativeBridgePolicy.object("text", "synthetic clipboard", "hasText", true);
                    if ("notification.post".equals(command.get("method"))) throw new NativeBridgeServer.Failure("NOTIFICATIONS_DISABLED", "Synthetic notifications disabled");
                    return NativeBridgePolicy.object("method", command.get("method"), "foregroundReady", true);
                }
            });
            System.out.println("READY"); System.out.flush();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() { public void run() { server.close(); } }));
            Thread.sleep(120000); server.close(); return;
        }
        invalid("null"); invalid("[]"); invalid("{\"method\":\"arbitrary\"}");
        invalid("{\"method\":\"clipboard.set\",\"text\":4}"); invalid("{\"method\":\"clipboard.get\",\"text\":\"unexpected\"}");
        invalid("{\"method\":\"clipboard.get\",\"method\":\"screenshot\"}");
        for (String url : new String[] {"intent://app", "javascript:alert(1)", "file:///data/private", "https://user:pass@example.com/", "https:///missing"})
            invalid(NativeBridgePolicy.json(NativeBridgePolicy.object("method", "url.open", "url", url)));
        invalid("{\"method\":\"app.open\",\"package\":\"x;rm -rf\"}");
        for (String method : NativeBridgePolicy.METHODS) {
            Map<String, Object> command = NativeBridgePolicy.object("method", method);
            if (method.endsWith(".set") || "share.text".equals(method)) command.put("text", "");
            if (method.endsWith(".file") || "file.open".equals(method)) command.put("path", "/tmp/synthetic.txt");
            if ("url.open".equals(method)) command.put("url", "https://example.com/a?q=one%20two");
            if ("app.open".equals(method)) command.put("package", "com.example.app");
            if ("notification.post".equals(method)) { command.put("title", "title"); command.put("text", "body"); }
            check(method.equals(NativeBridgePolicy.request(NativeBridgePolicy.json(command)).get("method")), method);
        }
        StringBuilder longText = new StringBuilder(); for (int i=0;i<16001;i++) longText.append('x');
        invalid(NativeBridgePolicy.json(NativeBridgePolicy.object("method", "clipboard.set", "text", longText.toString())));
        String escaped = "\"\\\n\r\t\0中文";
        check(escaped.equals(JsonValue.parse(NativeBridgePolicy.json(escaped))), "JSON escaping");
        for (int[] size : new int[][] {{320,800},{440,956},{956,440},{4096,4096},{16384,32768}}) {
            int[] actual = NativeBridgePolicy.captureSize(size[0],size[1]);
            check(actual[0]>0&&actual[1]>0&&Math.max(actual[0],actual[1])<=2048, "capture bitmap bound");
            check(Math.abs((double)actual[0]/actual[1]-(double)size[0]/size[1])<.01, "capture ratio");
        }
        try { NativeBridgePolicy.captureSize(0,100); throw new AssertionError(); } catch (IllegalArgumentException expected) { checks++; }
        Path root = Files.createTempDirectory("dsh-native-test-").toRealPath();
        try {
            Path workspace = Files.createDirectory(root.resolve("workspace"));
            Path file=workspace.resolve("report.txt"); Files.write(file,"synthetic".getBytes(StandardCharsets.UTF_8));
            List<File> roots = Collections.singletonList(workspace.toFile());
            check(NativeBridgePolicy.exportFile(file.toString(), roots).equals(file.toFile()), "workspace file allowed");
            Path outside=root.resolve("outside.txt");Files.write(outside,new byte[]{1});
            Files.createSymbolicLink(workspace.resolve("external"),outside);
            for (String path : new String[]{outside.toString(), workspace.resolve("external").toString(), workspace.toString(), "relative.txt"}) {
                try { NativeBridgePolicy.exportFile(path,roots);throw new AssertionError("unsafe export"); } catch(IOException expected){checks++;}
            }
            for (String name : new String[]{".env", ".env.production", ".credentials.yaml", "key.pem", ".native-bridge.json"}) {
                Path privateFile=workspace.resolve(name);Files.write(privateFile,new byte[]{1});
                try { NativeBridgePolicy.exportFile(privateFile.toString(),roots);throw new AssertionError(name); }catch(IOException expected){checks++;}
            }
            Files.createDirectories(workspace.resolve(".dsh"));Path secret=workspace.resolve(".dsh/ordinary.txt");Files.write(secret,new byte[]{1});
            try {NativeBridgePolicy.exportFile(secret.toString(),roots);throw new AssertionError();}catch(IOException expected){checks++;}
            final AtomicInteger operations = new AtomicInteger();
            final CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            NativeBridgeServer server = new NativeBridgeServer(root.toFile(), new NativeBridgeServer.Host(){
                public Object execute(Map<?, ?> command)throws Exception{
                    operations.incrementAndGet();
                    if("screenshot".equals(command.get("method"))){started.countDown();try{Thread.sleep(30000);}catch(InterruptedException stopped){interrupted.countDown();throw stopped;}}
                    return NativeBridgePolicy.object("value","synthetic");
                }
            });
            Map<?,?> descriptor=(Map<?,?>)JsonValue.parse(new String(Files.readAllBytes(root.resolve(".native-bridge.json")),StandardCharsets.UTF_8));
            String token=(String)descriptor.get("token");int port=server.port();
            check(token.matches("[a-f0-9]{64}")&&((Number)descriptor.get("port")).intValue()==port,"private endpoint identity");
            check(!Files.getPosixFilePermissions(root.resolve(".native-bridge.json")).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),"descriptor private mode");
            String body="{\"method\":\"capabilities\"}";
            check(call(port,request(port,token,body,"")).contains("\"ok\":true"),"authenticated native HTTP");
            int before=operations.get();
            for(String raw:new String[]{request(port,"wrong",body,""),request(port,token,body,"Origin: http://127.0.0.1\r\n"),
                    request(port,token,body,"Sec-Fetch-Site: same-origin\r\n"),request(port,token,body,"").replace("Host: 127.0.0.1:","Host: evil.example:"),
                    request(port,token,body,"").replace("POST /native", "GET /native"),request(port,token,body,"Transfer-Encoding: chunked\r\n")})
                check(call(port,raw).contains("403"),"untrusted request refused");
            check(operations.get()==before,"rejected auth/origin requests have no effects");
            check(call(port,request(port,token,"{\"method\":\"unknown\"}","")).contains("INVALID_REQUEST"),"invalid method visible");
            check(call(port,request(port,token,body,"Content-Length: 20\r\n")).isEmpty(),"duplicate length rejected");
            check(operations.get()==before,"invalid request not dispatched");
            Socket cancelled=new Socket("127.0.0.1",port);
            cancelled.getOutputStream().write(request(port,token,"{\"method\":\"screenshot\"}","").getBytes(StandardCharsets.UTF_8));cancelled.getOutputStream().flush();
            check(started.await(2,TimeUnit.SECONDS),"long native request started");cancelled.close();
            check(interrupted.await(2,TimeUnit.SECONDS),"client disconnect cancels pending native operation");
            check(call(port,request(port,token,body,"")).contains("\"ok\":true"),"cancel does not poison the next worker request");
            server.close();check(!Files.exists(root.resolve(".native-bridge.json")),"shutdown removes credentials");
            try {new Socket("127.0.0.1",port).close();throw new AssertionError();}catch(IOException expected){checks++;}
            System.out.println("Native bridge: "+checks+" protocol, policy, filesystem and socket checks passed (no Android device).");
        } finally {try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException error){throw new RuntimeException(error);}});}}
    }
}
