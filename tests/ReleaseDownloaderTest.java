package dev.dsh.nativeapp;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;

public class ReleaseDownloaderTest {
    static int checks;
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); checks++; }
    public static void main(String[] args) throws Exception {
        byte[] bytes = new byte[5 * 1024 * 1024 + 91]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)(i % 251);
        AtomicInteger calls = new AtomicInteger(); List<Long> offsets = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failedSource = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool(r -> {Thread t = new Thread(r); t.setDaemon(true); return t;});server.setExecutor(handlers);
        server.createContext("/file", exchange -> {
            calls.incrementAndGet(); String range = exchange.getRequestHeaders().getFirst("Range");
            String[] pieces = range.substring(6).split("-"); long start = Long.parseLong(pieces[0]); offsets.add(start);
            int end = (int)Math.min(bytes.length - 1, Long.parseLong(pieces[1])); int count = end - (int)start + 1;
            exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
            exchange.sendResponseHeaders(206, count);exchange.getResponseBody().write(bytes, (int)start, count);exchange.close();
        });
        server.createContext("/stall", exchange -> { try { exchange.sendResponseHeaders(206, 1024);exchange.getResponseBody().write(new byte[]{1});exchange.getResponseBody().flush();entered.countDown();release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { exchange.close(); } });
        server.createContext("/bad", exchange -> { failedSource.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start(); String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Path scratch = Files.createTempDirectory("dsh-release-transport-");
        ReleaseDownloader transport = new ReleaseDownloader(new String[]{""}, new ReleaseDownloader.Host() {public void log(String s){} public void status(String s){}});
        try {
            File target = scratch.resolve("runtime").toFile();
            transport.download(base, "file", target, new ReleaseDownloader.Control(), true);
            check(Arrays.equals(bytes, Files.readAllBytes(target.toPath())) && calls.get() == 3, "production transport assembles bounded ranges exactly");
            check(!new File(target + ".part").exists() && !new File(target + ".part.id").exists(), "complete transfer atomically publishes and clears temporary identity");
            Files.delete(target.toPath()); File partial = new File(target + ".part"), identity = new File(target + ".part.id");
            TransferState.prepare(partial, identity, base + "file", true); Files.write(partial.toPath(), Arrays.copyOf(bytes, 2 * 1024 * 1024)); offsets.clear();
            transport.download(base, "file", target, new ReleaseDownloader.Control(), true);
            check(offsets.get(0) == 2 * 1024 * 1024 && Arrays.equals(bytes, Files.readAllBytes(target.toPath())), "retry preserves previously downloaded ranges");
            AtomicLong progress = new AtomicLong();
            ReleaseDownloader failover = new ReleaseDownloader(new String[]{base + "bad?url=", ""}, new ReleaseDownloader.Host() {
                public void log(String s) {} public void status(String s) {}
                public void progress(String name, long done, long total) { progress.set(done); }
            });
            try {
                failover.download(base, "file", scratch.resolve("mirror-one").toFile(), new ReleaseDownloader.Control(), true);
                failover.download(base, "file", scratch.resolve("mirror-two").toFile(), new ReleaseDownloader.Control(), true);
                check(failedSource.get() == 1, "failed source is tried once before failover; working source reused for following chunks and shards");
                check(Arrays.equals(bytes, Files.readAllBytes(scratch.resolve("mirror-two"))) && progress.get() == bytes.length, "preferred source preserves bytes and reports actual aggregate input");
            } finally { failover.close(); }
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread pending = new Thread(() -> {try {transport.download(base, "stall", scratch.resolve("stall").toFile(), new ReleaseDownloader.Control(), false);} catch(IOException expected) {cancelled.set(true);}});
            pending.start();check(entered.await(3, TimeUnit.SECONDS), "blocking read is exercised");long start = System.nanoTime();transport.close();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500, "Activity disposal never waits on a blocking network disconnect");
            release.countDown();pending.join(3000);check(!pending.isAlive() && cancelled.get(), "cancelled transfer cannot publish a final file");
            check(!scratch.resolve("stall").toFile().exists(), "cancellation preserves partial state only");
            try {transport.download(base, "file", target, new ReleaseDownloader.Control(), true);throw new AssertionError("closed owner reused");}catch(IOException expected){checks++;}
            System.out.println("Release transport: " + checks + " actual HTTP range, continuation, atomic publication and disposal checks passed.");
        } finally {release.countDown();transport.close();server.stop(0);handlers.shutdownNow();try(java.util.stream.Stream<Path> paths = Files.walk(scratch)){paths.sorted(Comparator.reverseOrder()).forEach(p -> {try{Files.deleteIfExists(p);}catch(IOException e){throw new UncheckedIOException(e);}});}}
    }
}
