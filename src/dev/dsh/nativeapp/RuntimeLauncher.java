package dev.dsh.nativeapp;

import java.io.File;
import java.util.List;

/** Managed core environment; independent of Android UI and lifecycle. */
final class RuntimeLauncher {
    private RuntimeLauncher() { }
    static ProcessBuilder builder(List<String> command, File root, File toolsDir, File workspace, File cache) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(workspace != null ? workspace : root);

        String libPath = new File(root, "lib").getAbsolutePath()
                + ":" + new File(toolsDir, "lib").getAbsolutePath();
        String binPath = ToolEnvironment.path(root);
        // 注意：这里**故意不设 NODE_PATH**。
        // DSH 从 <dshDir>/lib/bin.js 启动，Node 会沿目录向上自然找到
        // <dshDir>/node_modules，无需 NODE_PATH。
        // 而 NODE_PATH 是 Node 的遗留回退机制，会让同一个包经由不同路径
        // 被加载成**多个模块实例** —— DSH 的错误分类依赖 `instanceof`，
        // 实例不一致会导致「本是图片相关的错误」被兜底包装成
        // "prompt rejected (session/agent-busy)"，真实原因就此丢失。
        // （preflight 脚本位于 <root>/，那是另一个进程，仍需要 NODE_PATH。）

        pb.environment().put("LD_LIBRARY_PATH", libPath);
        pb.environment().put("OPENSSL_CONF", new File(root, "openssl.cnf").getAbsolutePath());
        pb.environment().put("PATH", binPath);
        pb.environment().put("HOME", root.getAbsolutePath());
        pb.environment().put("DSH_HOME", new File(root, ".dsh").getAbsolutePath());
        pb.environment().put("DSH_NATIVE_APP_ROOT", root.getAbsolutePath());
        // V8 validates cache contents against the actual module source and Node version.
        // This is disposable private code cache, never a replacement for payload validation.
        pb.environment().put("NODE_COMPILE_CACHE", new File(cache, "dsh-core-compile").getAbsolutePath());
        pb.environment().put("TMPDIR", new File(root, "tmp").getAbsolutePath());
        pb.environment().put("TMP", new File(root, "tmp").getAbsolutePath());
        pb.environment().put("TEMP", new File(root, "tmp").getAbsolutePath());
        // 用 dumb 而非 xterm-256color：避免 dsh 输出 ANSI 颜色转义。
        // 之前正是这些转义混进了 URL（\u001b[36m...\u001b[0m），
        // 导致 WebView 加载了非法地址 → 下半屏一直空白。
        pb.environment().put("TERM", "dumb");
        // git 把系统配置硬编码为 /data/data/com.termux/files/usr/etc/gitconfig，
        // 我们读不到该路径，禁用系统级配置以免产生警告；同时指定自带的 helper 目录。
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        // git 的辅助程序（git-remote-https 等）在 libexec/git-core，
        // 二进制里硬编码的是 Termux 路径，必须显式指定，否则 clone/push 失败。
        File gitCore = new File(toolsDir, "libexec/git-core");
        if (gitCore.isDirectory()) {
            pb.environment().put("GIT_EXEC_PATH", gitCore.getAbsolutePath());
        }
        File gitTpl = new File(toolsDir, "share/git-core/templates");
        if (gitTpl.isDirectory()) {
            pb.environment().put("GIT_TEMPLATE_DIR", gitTpl.getAbsolutePath());
        }
        // Python 的安装前缀同样被硬编码为 Termux 路径；
        // 指到我们自己的目录，sys.prefix 才正确、pip 才会装到这里。
        // 证书包路径同样被硬编码成 Termux 路径（如 curl 的 cert.pem），
        // App 里读不到会导致 HTTPS 全部失败。改为指向 APK 内置的根证书包。
        // git 也走同一份（此前 curl/git clone 会报 error adding trust anchors）。
        File caBundle = new File(root, "ca-certificates.crt");
        if (caBundle.exists()) {
            String ca = caBundle.getAbsolutePath();
            pb.environment().put("CURL_CA_BUNDLE", ca);
            pb.environment().put("SSL_CERT_FILE", ca);
            pb.environment().put("GIT_SSL_CAINFO", ca);
            pb.environment().put("REQUESTS_CA_BUNDLE", ca);   // 供 python-requests 类工具
        }
        // $SHELL 若不设置会继承到不存在的 Termux 路径（agent 已实测踩到），
        // 显式指向自带 bash，依赖 $SHELL 的工具才不会误判。
        File bash = new File(toolsDir, "bin/bash");
        if (bash.exists()) {
            pb.environment().put("SHELL", bash.getAbsolutePath());
        }
        // npm 的前缀同样被硬编码成 Termux 路径；指到自带目录，
        // 这样 npm install -g 才会装进我们自己的可写目录。
        pb.environment().put("PREFIX", toolsDir.getAbsolutePath());
        pb.environment().put("npm_config_prefix", toolsDir.getAbsolutePath());
        pb.environment().put("npm_config_cache", new File(root, ".npm-cache").getAbsolutePath());
        pb.environment().put("npm_config_update_notifier", "false");
        pb.environment().put("PYTHONHOME", toolsDir.getAbsolutePath());
        pb.environment().put("PYTHONNOUSERSITE", "1");

        return pb;
    }
}
