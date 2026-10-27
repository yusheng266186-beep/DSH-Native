package dev.dsh.nativeapp;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/** Production installers and lifecycle exclusion with actual files, pipes and interruption. */
public class AppArchitectureTest {
    static int checks;
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); checks++; }
    interface Checked { void run() throws Exception; }
    static void refused(Checked work, String message) throws Exception {
        try { work.run(); throw new AssertionError(message); } catch (IOException expected) { checks++; }
    }
    static String sha(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    static class Assets implements AssetInstaller.Source {
        final Map<String, byte[]> files = new TreeMap<>(); final Map<String, Integer> reads = new HashMap<>();
        String fail;
        void index() throws Exception {
            StringBuilder rows = new StringBuilder();
            for (Map.Entry<String,byte[]> e : files.entrySet()) if (e.getKey().startsWith("payload/"))
                rows.append(e.getKey().substring(8)).append('\t').append(e.getValue().length).append('\t').append(sha(e.getValue())).append('\n');
            files.put(AssetInstaller.INDEX, rows.toString().getBytes("UTF-8"));
        }
        public InputStream open(String name) throws IOException {
            reads.put(name, reads.getOrDefault(name, 0) + 1);
            byte[] data = files.get(name); if (data == null) throw new FileNotFoundException(name);
            if (name.equals(fail)) return new InputStream() { public int read() throws IOException { throw new IOException("injected disk/source failure"); } };
            return new ByteArrayInputStream(data);
        }
    }
    static void assets(Path scratch) throws Exception {
        for (String name : Arrays.asList("lib/libc++_shared.so", "licenses/CodeBuddy-CLI-LICENSE.txt", "node", "android-port-files.tsv"))
            check(AssetInstaller.validName(name), "valid shipped component name: " + name);
        for (String name : Arrays.asList("../outside", "lib/../outside", "lib/./file", "/outside", "lib//file", "lib/", "lib\\file", "lib/file\tname", "lib/file\nname"))
            check(!AssetInstaller.validName(name), "unsafe component name rejected");
        File root = Files.createDirectory(scratch.resolve("installed")).toFile();
        Assets source = new Assets(); source.files.put("payload/node", new byte[2 * 1024 * 1024]);
        source.files.put("payload/config.cjs", "first".getBytes("UTF-8")); source.index();
        AssetInstaller first = new AssetInstaller(source);
        check(!first.ready(root), "fresh installation is not ready");
        check(first.install(root) == 2 && first.ready(root), "first install publishes every verified asset and receipt");
        int nodeReads = source.reads.get("payload/node"); long nodeTime = new File(root, "node").lastModified();
        check(first.install(root) == 0, "unchanged components require no writes");
        check(source.reads.get("payload/node") == nodeReads && new File(root,"node").lastModified() == nodeTime, "warm install does not open the APK binary or rewrite it");
        source.files.put("payload/config.cjs", "next!".getBytes("UTF-8")); source.index(); AssetInstaller next = new AssetInstaller(source);
        check(!next.ready(root), "changed script invalidates contents, independently of app version");
        byte[] receipt = Files.readAllBytes(new File(root,".native-assets").toPath());
        source.fail = "payload/config.cjs";
        refused(() -> next.install(root), "partial source was accepted");
        check(new String(Files.readAllBytes(new File(root,"config.cjs").toPath()),"UTF-8").equals("first"), "failed install preserves previous complete file");
        check(Arrays.equals(receipt, Files.readAllBytes(new File(root,".native-assets").toPath())), "failed install preserves previous receipt");
        source.fail = null;
        check(next.install(root) == 1 && next.ready(root), "retry repairs only changed component");
        check(source.reads.get("payload/node") == nodeReads, "APK upgrade with unchanged binary still avoids large reads");
        Files.write(new File(root,"config.cjs").toPath(), "wrong".getBytes("UTF-8"));
        check(!next.ready(root) && next.install(root) == 1, "same-size script corruption repaired");
        Files.delete(new File(root,".native-assets").toPath());
        check(!next.ready(root) && next.install(root) == 0 && next.ready(root), "lost receipt recovers from actual disk hashes");
        source.files.put("payload/config.cjs", "bad!!".getBytes("UTF-8"));
        Files.write(new File(root,"config.cjs").toPath(), "wrong".getBytes("UTF-8"));
        refused(() -> next.install(root), "wrong APK digest was accepted");
        check(new String(Files.readAllBytes(new File(root,"config.cjs").toPath()),"UTF-8").equals("wrong"), "bad digest cannot overwrite installed file");
        File protectedFile = scratch.resolve("private-canary").toFile(); Files.write(protectedFile.toPath(), "keep".getBytes("UTF-8"));
        Files.delete(new File(root,"config.cjs").toPath()); Files.createSymbolicLink(new File(root,"config.cjs").toPath(), protectedFile.toPath());
        refused(() -> next.install(root), "symlink was followed");
        check(new String(Files.readAllBytes(protectedFile.toPath()),"UTF-8").equals("keep"), "installer preserves outside/private data");
        check(Arrays.stream(root.list()).noneMatch(n -> n.startsWith(".asset-") || n.startsWith(".receipt-")), "no abandoned installation temporary files");
    }
    static void awaitIdle(StartupCoordinator coordinator) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (coordinator.busy() && System.nanoTime() < until) Thread.sleep(5);
        check(!coordinator.busy(), "startup worker relinquishes admission");
    }
    static void lifecycle() throws Exception {
        Lock lock = new ReentrantLock(); List<Runnable> queue = Collections.synchronizedList(new ArrayList<>());
        StartupCoordinator coordinator = new StartupCoordinator(lock, queue::add);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger effects = new AtomicInteger(), failures = new AtomicInteger();
        check(coordinator.start(() -> { entered.countDown(); release.await(); coordinator.post(effects::incrementAndGet); }, e -> failures.incrementAndGet()), "first startup admitted");
        check(entered.await(3, TimeUnit.SECONDS), "worker entered preparation");
        check(!coordinator.start(effects::incrementAndGet, e -> failures.incrementAndGet()) && effects.get() == 0, "duplicate restart rejected before all side effects");
        release.countDown(); awaitIdle(coordinator);
        check(coordinator.start(() -> coordinator.post(() -> effects.addAndGet(10)), e -> failures.incrementAndGet()), "next startup admitted"); awaitIdle(coordinator);
        for (Runnable work : new ArrayList<>(queue)) work.run(); queue.clear();
        check(effects.get() == 10, "stale first-start UI callbacks dropped after restart");
        check(coordinator.start(() -> { throw new IOException("synthetic"); }, e -> failures.incrementAndGet()), "failure path admitted"); awaitIdle(coordinator);
        for (Runnable work : new ArrayList<>(queue)) work.run(); queue.clear(); check(failures.get() == 1, "single actionable failure callback");
        coordinator.post(effects::incrementAndGet); coordinator.close(); for (Runnable work : queue) work.run();
        check(effects.get() == 10 && !coordinator.start(effects::incrementAndGet, e -> {}), "disposed Activity cannot deliver UI or start new workers");
        ReentrantLock held = new ReentrantLock(); held.lock(); StartupCoordinator blocked = new StartupCoordinator(held, Runnable::run);
        try { check(blocked.start(effects::incrementAndGet, e -> failures.incrementAndGet()), "waiting startup admitted once"); blocked.close(); awaitIdle(blocked); }
        finally { held.unlock(); }
        check(effects.get() == 10 && failures.get() == 1, "cancel while waiting for environment lock performs no work or error UI");
    }
    static void processes() throws Exception {
        List<String> lines = new ArrayList<>();
        check(ProcessRunner.run(new ProcessBuilder("sh", "-c", "printf 'ready\\n'; printf 'done'"), 3000, lines::add) == 0
                && lines.equals(Arrays.asList("ready", "done")), "bounded command drains output including final partial line");
        refused(() -> ProcessRunner.run(new ProcessBuilder("sh", "-c", "exec sleep 5"), 50, s -> {}), "hung command never timed out");
        AtomicBoolean cancelled = new AtomicBoolean(); CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> { try { started.countDown(); ProcessRunner.run(new ProcessBuilder("sh", "-c", "exec sleep 5"), 10000, s -> {}); }
            catch (InterruptedException expected) { cancelled.set(true); } catch (IOException error) { throw new AssertionError(error); } });
        worker.start(); started.await(); Thread.sleep(50); worker.interrupt(); worker.join(3000);
        check(!worker.isAlive() && cancelled.get(), "Activity disposal cancels a temporary command promptly");
    }
    static class SettingsHost implements SettingsNavigation.Host {
        long document = 1; boolean ready = true; int fallback, device; String page, acknowledged;
        SettingsNavigation.Result pending;
        public long document(){return document;} public boolean ready(){return ready;}
        public void web(String page,SettingsNavigation.Result result){this.page=page;pending=result;}
        public void acknowledge(String id,SettingsNavigation.Result result){acknowledged=id;pending=result;}
        public void device(AppSettingsCommands command){device++;} public void fallback(){fallback++;}
    }
    static void settings() {
        SettingsHost host = new SettingsHost(); SettingsNavigation navigation = new SettingsNavigation(host);
        check(navigation.accept("request") && !navigation.accept("request"), "settings request executes once per document");
        navigation.open();check(host.page.equals("home"), "normal entry uses the core settings home");
        SettingsNavigation.Result previous = host.pending;host.document++;navigation.documentChanged();previous.complete(false);
        check(host.fallback == 0, "late old document callback cannot open native fallback");
        navigation.open();host.pending.complete(false);check(host.fallback == 1, "unavailable bridge has actionable native fallback");
        AppSettingsCommands command = AppSettingsCommands.parse(AppSettingsCommands.MARKER + "{\"id\":\"app-1-test\",\"action\":\"models\",\"locale\":\"zh\"}");
        navigation.device(command);check(host.acknowledged.equals(command.id) && host.device == 0, "upstream focus layer is acknowledged before opening a native page");
        host.pending.complete(true);check(host.device == 1, "accepted native page opens once");
        navigation.device(command);check(host.device == 1, "duplicate native command does not create duplicate dialogs");
        navigation.open();check(host.page.equals("models"), "return from native page preserves its category");
        previous=host.pending;navigation.close();previous.complete(false);
        check(host.fallback == 1 && !navigation.accept("closed"), "closed settings navigation drops pending events");
    }
    static void logs(Path scratch) throws Exception {
        List<String> system=Collections.synchronizedList(new ArrayList<>());AppLog log=new AppLog(system::add);
        Path file=scratch.resolve("logs/launch.log");log.bind(file.toFile());log.line("Authorization: Bearer synthetic-private-canary");
        check(!system.get(0).contains("synthetic-private-canary"), "credentials redacted before system logging");
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while((!Files.exists(file)||Files.size(file)==0)&&System.nanoTime()<until)Thread.sleep(10);
        check(Files.exists(file)&&!new String(Files.readAllBytes(file),"UTF-8").contains("synthetic-private-canary"), "private disk queue receives redacted messages only");
        Files.write(file,new byte[513*1024]);log.line("rotation-check");until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!Files.exists(Paths.get(file+".1"))&&System.nanoTime()<until)Thread.sleep(10);
        check(Files.exists(Paths.get(file+".1")), "long-running process rotates bounded private diagnostics");
        log.close();
    }
    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("dsh-architecture-");
        try { assets(scratch); lifecycle(); processes(); settings(); logs(scratch);
            ProcessBuilder core = RuntimeLauncher.builder(Arrays.asList("node", "--patch", "native.yml", "--profile", "web"), scratch.toFile(), scratch.resolve("tools").toFile(), scratch.toFile(), scratch.toFile());
            check(core.environment().get("DSH_HOME").endsWith("/.dsh") && core.environment().get("PATH").contains(".native-bin"), "core launcher retains managed config and commands");
            check(core.environment().get("NODE_COMPILE_CACHE").endsWith("dsh-core-compile"), "core cache uses private disposable directory");
            System.out.println("App architecture: " + checks + " atomic installation, content reuse, cancellation, epochs, restart exclusion and bounded process checks passed.");
        } finally { try (java.util.stream.Stream<Path> files = Files.walk(scratch)) { files.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { throw new java.io.UncheckedIOException(e); } }); } }
    }
}
