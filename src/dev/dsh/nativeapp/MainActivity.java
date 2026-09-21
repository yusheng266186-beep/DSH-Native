package dev.dsh.nativeapp;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DSH Native POC.
 *
 * 目标：验证「单个 APK 内自带 Node 运行时，无需 Termux / proot」这一架构成立。
 *
 * 流程：
 *   1. 把 assets 里的 node 二进制、共享库、JS 服务脚本解压到 App 私有目录
 *   2. chmod +x node
 *   3. 以子进程方式启动 node srv.js <root>
 *   4. WebView 加载 http://127.0.0.1:3080 展示结果
 */
public class MainActivity extends Activity {

    private static final String TAG = "DSHNative";
    private static final int PORT = 3080;

    private WebView webView;
    private TextView logView;
    private Process nodeProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        logView = new TextView(this);
        logView.setTextSize(10);
        logView.setPadding(16, 16, 16, 16);
        root.addView(logView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boot();
                } catch (Throwable t) {
                    log("启动失败: " + t);
                    Log.e(TAG, "boot failed", t);
                }
            }
        }).start();
    }

    private void boot() throws Exception {
        final File root = new File(getFilesDir(), "dsh");
        log("私有目录: " + root);

        extractAssets(root);
        log("资源解压完成");

        File node = new File(root, "node");
        File srv = new File(root, "srv.js");
        File libDir = new File(root, "lib");

        // 确保 node 可执行（Android 上 assets 解压后默认无执行位）
        chmod(node, "755");
        log("node 可执行位已设置");

        ProcessBuilder pb = new ProcessBuilder(
                node.getAbsolutePath(),
                srv.getAbsolutePath(),
                root.getAbsolutePath(),
                String.valueOf(PORT));
        pb.redirectErrorStream(true);

        // 关键：让动态链接器找到我们自带的 Bionic 共享库
        pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath());
        pb.environment().put("HOME", root.getAbsolutePath());
        pb.environment().put("TMPDIR", root.getAbsolutePath());
        pb.environment().put("NODE_OPTIONS", "--max-old-space-size=512");

        log("启动 node …");
        nodeProcess = pb.start();
        log("node 已启动, pid=" + pidOf(nodeProcess));

        // 把 node 的 stdout/stderr 转显示到界面，方便排查
        final InputStream is = nodeProcess.getInputStream();
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                StringBuilder carry = new StringBuilder();
                try {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        carry.append(new String(buf, 0, n, "UTF-8"));
                        int idx;
                        while ((idx = carry.indexOf("\n")) >= 0) {
                            final String line = carry.substring(0, idx);
                            carry.delete(0, idx + 1);
                            log("[node] " + line);
                        }
                    }
                } catch (IOException e) {
                    log("[node] 输出流结束: " + e.getMessage());
                }
            }
        }).start();

        Thread.sleep(1200);
        final String url = "http://127.0.0.1:" + PORT + "/";
        log("加载 " + url);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl(url);
            }
        });
    }

    /** 递归把 assets/payload 下的内容解压到 target，保持目录结构。 */
    private void extractAssets(File target) throws IOException {
        List<String> entries = new ArrayList<String>();
        collect("payload", entries);
        for (String path : entries) {
            String rel = path.substring("payload/".length());
            if (rel.length() == 0) continue;
            File out = new File(target, rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            InputStream in = getAssets().open(path);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
        }
    }

    private void collect(String dir, List<String> out) throws IOException {
        String[] children = getAssets().list(dir);
        if (children == null || children.length == 0) {
            out.add(dir);
            return;
        }
        for (String c : children) {
            collect(dir + "/" + c, out);
        }
    }

    /**
     * Android 的 File.setExecutable 在部分 ROM 上对私有目录不可靠，这里直接调用
     * Runtime.exec 执行 chmod。若失败则退回 setExecutable。
     */
    private void chmod(File f, String mode) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", mode, f.getAbsolutePath()});
            p.waitFor();
        } catch (Throwable t) {
            Log.w(TAG, "chmod via exec failed, falling back", t);
            f.setExecutable(true, false);
        }
    }

    /** 反射读取 pid，避免依赖 API 级别较高的 Process.pid()。 */
    private String pidOf(Process p) {
        try {
            java.lang.reflect.Field f = Process.class.getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }

    private void log(final String msg) {
        Log.i(TAG, msg);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logView.append(msg + "\n");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nodeProcess != null) {
            nodeProcess.destroy();
        }
    }
}
