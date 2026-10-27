package dev.dsh.nativeapp;

import java.io.*;

/** Bounded environment probes; no UI or process ownership. */
final class RuntimeChecks {
    interface Log { void line(String message); }
    private RuntimeChecks() { }
    static boolean node(File node, final Log log) {
        if (!node.isFile() || !node.canExecute()) { log.line("内置 Node 缺失或不可执行"); return false; }
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), "--version");
        builder.environment().put("LD_LIBRARY_PATH", new File(node.getParentFile(), "lib").getAbsolutePath());
        builder.environment().put("OPENSSL_CONF", new File(node.getParentFile(), "openssl.cnf").getAbsolutePath());
        try {
            int code = ProcessRunner.run(builder, 15000L, new ProcessRunner.Lines() {
                @Override public void line(String line) { log.line("Node: " + line); }
            });
            if (code != 0) log.line("内置 Node 自检退出码 " + code);
            return code == 0;
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        catch (IOException error) { log.line("内置 Node 无法执行: " + error.getClass().getSimpleName()); return false; }
    }
    static boolean preflight(File node, File root, File toolsDir, File workspace, final Log log) {
        log.line("运行环境自检 …");
        File script = new File(root, "preflight.js");
        if (!script.exists()) { log.line("  [警告] 缺少 preflight.js，跳过"); return false; }

        ProcessBuilder pb = new ProcessBuilder(node.getAbsolutePath(),
                "--expose-internals", script.getAbsolutePath(), root.getAbsolutePath(),
                (workspace != null ? workspace : root).getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.directory(root);
        pb.environment().put("LD_LIBRARY_PATH", new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath());
        pb.environment().put("PATH", ToolEnvironment.path(root));
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
        File caB = new File(root, "ca-certificates.crt");
        if (caB.exists()) {
            pb.environment().put("CURL_CA_BUNDLE", caB.getAbsolutePath());
            pb.environment().put("SSL_CERT_FILE", caB.getAbsolutePath());
            pb.environment().put("GIT_SSL_CAINFO", caB.getAbsolutePath());
        }
        pb.environment().put("NODE_PATH", new File(root, "dsh/node_modules").getAbsolutePath());
        pb.environment().put("TMPDIR", new File(root, "tmp").getAbsolutePath());
        pb.environment().put("TMP", new File(root, "tmp").getAbsolutePath());
        pb.environment().put("TEMP", new File(root, "tmp").getAbsolutePath());
        pb.environment().put("PYTHONHOME", toolsDir.getAbsolutePath());
        pb.environment().put("PYTHONNOUSERSITE", "1");
        pb.environment().put("npm_config_prefix", toolsDir.getAbsolutePath());
        pb.environment().put("npm_config_cache", new File(root, ".npm-cache").getAbsolutePath());
        pb.environment().put("npm_config_update_notifier", "false");
        pb.environment().put("HOME", root.getAbsolutePath());

        try {
            final int[] failed = {-1};
            int code = ProcessRunner.run(pb, 120000L, new ProcessRunner.Lines() {
                @Override public void line(String line) {
                if (line.startsWith("PREFLIGHT|")) {
                    String[] f = line.split("\\|", 4);
                    String tag = f.length > 1 ? f[1] : "";
                    boolean pass = "PASS".equals(tag);
                    boolean warn = "WARN".equals(tag);
                    boolean info = "INFO".equals(tag);
                    String detail = (f.length > 3 && f[3].length() > 0) ? " → " + f[3] : "";
                    // INFO 用于「Android 上本就不需要」的项，避免用户误以为有问题
                    log.line("  " + (pass ? "[通过]" : info ? "[信息]" : warn ? "[警告]" : "[错误]")
                            + " " + (f.length > 2 ? f[2] : "?") + detail);
                } else if (line.startsWith("PREFLIGHT_END|")) {
                    try { failed[0] = Integer.parseInt(line.substring(14).trim()); }
                    catch (NumberFormatException ignored) { }
                }
                }
            });
            if (code != 0) { log.line("  自检进程退出码 " + code); return false; }
            if (failed[0] == 0) { log.line("  自检全部通过"); return true; }
            log.line("  [警告] 有 " + (failed[0] < 0 ? "若干" : String.valueOf(failed[0]))
                    + " 项未通过，仍继续启动以便收集信息");
            return false;
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
        catch (IOException e) {
            log.line("  [警告] 自检执行失败: " + e);
            return false;
        }
    }
}
