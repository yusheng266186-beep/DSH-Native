#!/usr/bin/env python3
"""Execute production Java rollback and real Node snapshot/unpack in child processes.

Only Service shutdown, preference persistence and logging are replaced. No device,
network, user configuration or paid model is used.
"""
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "src/dev/dsh/nativeapp"
if not SOURCE.is_dir():
    SOURCE = ROOT / "bootstrap/src/dev/dsh/nativeapp"
PAYLOAD = ROOT / "payload"
if not PAYLOAD.is_dir():
    PAYLOAD = ROOT / "bootstrap/payload"
text = (SOURCE / "MainActivity.java").read_text()


def method(signature):
    start = text.index(signature)
    opening = text.index("{", start)
    # These fixed methods have balanced braces in their Java string literals.
    depth = 1
    end = opening + 1
    while depth:
        if text[end] == "{":
            depth += 1
        elif text[end] == "}":
            depth -= 1
        end += 1
    return text[start:end]


methods = "\n".join(method(signature) for signature in [
    "private void createPayloadRollback(",
    "private PayloadRollback.Journal readPayloadRollback(",
    "private PayloadRollback.Journal loadPayloadRollback(",
    "private static boolean restoredTargetLooksUsable(",
    "private void restorePayloadRollback(",
    "private static void deleteRuntimeChild(",
])
assert text.index("PayloadRollback.interruptedMode(root)") < text.index("reusePreparedCore(root,")
assert "interruptedFirstInstall || PayloadUpdate.needsWork(info, applied)" in text
assert "!locallyComplete || PayloadRollback.interruptedMode(root) != null" in text
assert text.index("ConfigBackup.recoverInterruptedRestore(configHome)") < text.index("reusePreparedCore(root,")

HARNESS = r'''
package dev.dsh.nativeapp;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public class RuntimeRecoveryHarness {
  static void log(String ignored) { }
  static String shorten(Throwable error) { return error.getClass().getSimpleName(); }
  static String readText(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), "UTF-8"); }
  static void writeText(File file, String text) throws IOException {
    try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes("UTF-8")); out.getFD().sync(); }
  }
  static String sha256(File file) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
    StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%%02x", b & 255)); return out.toString();
  }
  static void restorePayloadRevisionState(Set<String> revisions) { }
  static void run(File node, File root, String[] args, Object ignored) throws Exception {
    if (Boolean.getBoolean("test.failTools") && args[0].endsWith("unpack.js") && args[2].endsWith("/tools"))
      throw new IOException("synthetic storage failure");
    List<String> command = new ArrayList<>(); command.add(node.getAbsolutePath()); command.addAll(Arrays.asList(args));
    Process process = new ProcessBuilder(command).directory(root).redirectErrorStream(true).start();
    try (InputStream in = process.getInputStream()) { byte[] buffer = new byte[8192]; while (in.read(buffer) >= 0) { } }
    if (process.waitFor() != 0) throw new IOException("archive command failed");
    if (Boolean.getBoolean("test.crash") && args[0].endsWith("unpack.js")) Runtime.getRuntime().halt(70);
  }
  public static void main(String[] args) throws Exception {
    RuntimeRecoveryHarness harness = new RuntimeRecoveryHarness(); File root = new File(args[1]), node = new File(args[2]);
    if ("snapshot".equals(args[0])) harness.createPayloadRollback(node, root,
      new LinkedHashSet<>(Arrays.asList("dsh", "tools")), new HashSet<>(Arrays.asList("dsh.tar.zst=3")));
    else harness.restorePayloadRollback(node, root, true);
  }
  %s
}
class HarnessService { static boolean stopManagedProcessAndWait(long timeout) { return true; } }
''' % methods

checks = 0


def check(condition, label):
    global checks
    if not condition:
        raise AssertionError(label)
    checks += 1


with tempfile.TemporaryDirectory(prefix="dsh-runtime-recovery-") as temp:
    base = pathlib.Path(temp)
    (base / "RuntimeRecoveryHarness.java").write_text(HARNESS)
    sources = [str(SOURCE / (name + ".java")) for name in [
        "PayloadRollback", "PayloadUpdate", "RuntimeDir", "FileOps", "TransferState"]]
    subprocess.run(["javac", "-d", str(base), *sources, str(base / "RuntimeRecoveryHarness.java")], check=True, timeout=60)

    def fixture(name):
        root = base / name
        (root / "dsh/lib").mkdir(parents=True)
        (root / "tools/bin").mkdir(parents=True)
        (root / ".dsh").mkdir()
        (root / ".dsh/keep.txt").write_text("private data retained")
        (root / "dsh/lib/bin.js").write_text("old core")
        (root / "tools/bin/bash").write_text("old tools")
        for script in ["snapshot.js", "unpack.js", "archive-tar.cjs"]:
            shutil.copyfile(PAYLOAD / script, root / script)
        return root

    def run(mode, root, flags=()):
        return subprocess.run(["java", *flags, "-cp", str(base), "dev.dsh.nativeapp.RuntimeRecoveryHarness",
                               mode, str(root), shutil.which("node")], capture_output=True, timeout=30)

    for scenario in ["crash", "storage-failure", "corrupt-snapshot"]:
        root = fixture(scenario)
        result = run("snapshot", root)
        check(result.returncode == 0, "production snapshot succeeds: " + result.stderr.decode())
        (root / "dsh/lib/bin.js").write_text("new core")
        (root / "tools/bin/bash").write_text("new tools")
        marker = root / ".payload-update-in-progress"
        marker.write_text("DSH-PAYLOAD-INTERRUPTED-1\nupdate\n")
        if scenario == "crash":
            result = run("restore", root, ["-Dtest.crash=true"])
            check(result.returncode == 70, "child process halted during actual unpack")
            check(marker.exists(), "recovery interruption remains durable")
        elif scenario == "storage-failure":
            result = run("restore", root, ["-Dtest.failTools=true"])
            check(result.returncode != 0, "later storage failure is reported")
            check((root / "tools/bin/bash").read_text() == "new tools", "failed target put back")
            check(marker.exists(), "failed recovery remains retryable")
        else:
            archive = root / "payload-rollback/dsh.tar.zst"
            original = archive.read_bytes()
            archive.write_bytes(b"corrupt snapshot")
            result = run("restore", root)
            check(result.returncode != 0, "bad snapshot rejected")
            check((root / "dsh/lib/bin.js").read_text() == "new core", "digest failure leaves live runtime alone")
            archive.write_bytes(original)
        result = run("restore", root)
        check(result.returncode == 0, "production recovery retries successfully: " + result.stderr.decode())
        check((root / "dsh/lib/bin.js").read_text() == "old core", "complete original core restored")
        check((root / "tools/bin/bash").read_text() == "old tools", "complete original tools restored")
        check((root / ".dsh/keep.txt").read_text() == "private data retained", "user data preserved")
        check(not marker.exists(), "journal removed only after successful recovery")
        check(not (root / "payload-rollback").exists(), "used snapshot cleaned after completion")

print(f"Runtime recovery: {checks} production Java/Node snapshot, interruption, failure and retry checks passed; no device.")
