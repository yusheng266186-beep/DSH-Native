package dev.dsh.nativeapp;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Concurrency, integrity and cancellation at the production staging boundary. */
public class PayloadDownloadsTest {
    static int checks;
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); checks++; }
    static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    static void await(CountDownLatch latch) throws IOException {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new IOException("barrier timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("cancelled", error); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("dsh-staging-");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Path user = Files.createDirectories(root.resolve(".dsh"));
            Files.write(user.resolve("history"), new byte[]{42});
            List<PayloadDownloads.Part> plan = new ArrayList<>();
            Map<String, byte[]> contents = new HashMap<>();
            long total = 0;
            for (int i = 0; i < 5; i++) {
                byte[] bytes = new byte[16384 + i]; Arrays.fill(bytes, (byte)i);
                String name = "part" + i + ".tar.zst"; contents.put(name, bytes);
                plan.add(new PayloadDownloads.Part(name, bytes.length, hash(bytes))); total += bytes.length;
            }
            AtomicLong previous = new AtomicLong(); AtomicInteger progressCalls = new AtomicInteger();
            PayloadDownloads stage = new PayloadDownloads(plan, (done, all) -> {
                if (done < previous.getAndSet(done) || done > all) throw new AssertionError("progress regressed");
                progressCalls.incrementAndGet();
            });
            AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(3), release = new CountDownLatch(1);
            Future<LinkedHashMap<String, File>> result = caller.submit(() -> stage.stage(root.toFile(), (name, out, control) -> {
                int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max); entered.countDown();
                try {
                    await(release); control.check(); byte[] bytes = contents.get(name);
                    stage.progress(name, bytes.length / 2); Files.write(out.toPath(), bytes);
                } finally { active.decrementAndGet(); }
            }));
            await(entered);
            check(!result.isDone() && active.get() == 3, "three shards overlap while staging has not committed");
            release.countDown();
            LinkedHashMap<String, File> archives = result.get(5, TimeUnit.SECONDS);
            check(maximum.get() == 3 && active.get() == 0, "parallelism is bounded and workers finish");
            check(new ArrayList<>(archives.keySet()).equals(Arrays.asList("part0.tar.zst", "part1.tar.zst", "part2.tar.zst", "part3.tar.zst", "part4.tar.zst")), "extraction order remains manifest order");
            check(previous.get() == total && progressCalls.get() >= 5, "aggregate progress reaches the verified total monotonically");
            AtomicInteger fetches = new AtomicInteger();
            stage.stage(root.toFile(), (name, out, control) -> { fetches.incrementAndGet(); throw new IOException("unexpected download"); });
            check(fetches.get() == 0, "verified cached archives need no network");
            Files.write(root.resolve("part2.tar.zst"), new byte[]{0});
            stage.stage(root.toFile(), (name, out, control) -> { fetches.incrementAndGet(); Files.write(out.toPath(), contents.get(name)); });
            check(fetches.get() == 1, "only the corrupt cached shard is downloaded again");
            check(Files.readAllBytes(user.resolve("history"))[0] == 42, "staging preserves private history");

            Path failureRoot = Files.createDirectory(root.resolve("failure"));
            List<PayloadDownloads.Part> failurePlan = plan.subList(0, 2);
            CountDownLatch siblingEntered = new CountDownLatch(1); AtomicBoolean siblingStopped = new AtomicBoolean();
            PayloadDownloads failure = new PayloadDownloads(failurePlan, (done, all) -> {});
            boolean rejected = false;
            try {
                failure.stage(failureRoot.toFile(), (name, out, control) -> {
                    if (name.equals("part0.tar.zst")) { await(siblingEntered); byte[] corrupt = contents.get(name).clone(); corrupt[0] = 9; Files.write(out.toPath(), corrupt); }
                    else {
                        siblingEntered.countDown();
                        try { while (true) { control.check(); Thread.sleep(10); } }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("cancelled", error); }
                        finally { siblingStopped.set(true); }
                    }
                });
            } catch (IOException expected) { rejected = true; }
            check(rejected && siblingStopped.get(), "one integrity failure cancels the in-flight sibling");
            check(!Files.exists(failureRoot.resolve("part0.tar.zst")), "corrupt downloaded archive is discarded");
            check(Files.readAllBytes(user.resolve("history"))[0] == 42, "failed stage cannot alter the installed environment or history");
            try {
                new PayloadDownloads(Arrays.asList(new PayloadDownloads.Part("../bad.tar.zst", 1, hash(new byte[]{0}))), (d, t) -> {});
                throw new AssertionError("unsafe name accepted");
            } catch (IllegalArgumentException expected) { checks++; }
            System.out.println("Payload staging: " + checks + " bounded parallel, integrity, cache, progress, cancellation and preservation checks passed.");
        } finally {
            caller.shutdownNow();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException error) { throw new UncheckedIOException(error); } });
            }
        }
    }
}
