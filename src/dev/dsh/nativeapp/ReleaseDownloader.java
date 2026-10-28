package dev.dsh.nativeapp;

import java.io.*;
import java.net.*;
import java.util.*;

/** Release transport, resumable files and cancellation. Does not own UI or runtime policy. */
final class ReleaseDownloader {
    interface Host {
        void log(String message); void status(String message);
        default void progress(String asset, long done, long total) { }
    }
    private final String[] sources;
    private final Host host;
    private final Set<Control> active = new HashSet<Control>();
    private boolean closed;
    private volatile int preferredSource;
    ReleaseDownloader(String[] sources, Host host) { this.sources = sources.clone(); this.host = host; }
    private void log(String message) { host.log(message); }
    private void setSplashStatus(String message) { host.status(message); }
    void download(String base, String asset, File out, Control control, boolean resume) throws IOException {
        synchronized (this) {
            if (closed) throw new IOException("Download owner closed");
            active.add(control);
        }
        try { transfer(base, asset, out, control, resume); }
        finally { synchronized (this) { active.remove(control); } }
    }
    void close() {
        List<Control> pending;
        synchronized (this) { closed = true; pending = new ArrayList<Control>(active); active.clear(); }
        for (Control control : pending) control.cancel();
    }
private void transfer(String basePath, String assetName, File out,
                              Control control, boolean resume) throws IOException {
        final boolean quiet = assetName.endsWith(".json");
        final int chunkSize = 2 * 1024 * 1024;
        final int maxRetryPerSource = 4;

        File partial = new File(out.getAbsolutePath() + ".part");
        File identityFile = new File(out.getAbsolutePath() + ".part.id");
        long prepared = TransferState.prepare(partial, identityFile,
                basePath + assetName, resume);
        long total = -1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(partial, "rw");
        try {
            long done = prepared;
            if (raf.length() != done) raf.setLength(done);
            if (done > 0 && !quiet) log("  从 " + (done / 1048576) + " MB 继续下载");
            int chunkIndex = (int) (done / chunkSize);
            int retries = 0;
            long startedAt = System.currentTimeMillis();
            int lastLoggedPct = -1;
            String lastError = null;

            while (total < 0 || done < total) {
                control.check();
                long end = total < 0 ? done + chunkSize - 1
                        : Math.min(done + chunkSize - 1, total - 1);
                RangeResult result = null;

                int firstSource = preferredSource;
                // Try every source once before retrying a failed one. Keep the source
                // that worked across chunks and across the following runtime shards.
                for (int turn = 0; turn < sources.length * maxRetryPerSource && result == null; turn++) {
                    int source = (firstSource + turn) % sources.length;
                    String url = sources[source] + basePath + assetName;
                    try {
                        result = fetchRange(url, done, end, control);
                        preferredSource = source;
                        if (total < 0 && result.total != null) {
                            total = result.total.longValue();
                        }
                        if (source > 0) log("  已切换到镜像源 #" + source);
                    } catch (Exception e) {
                        lastError = shorten(e);
                        retries++;
                        control.check();
                        try {
                            if (turn >= sources.length) Thread.sleep(500L * (turn / sources.length));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("下载被中断");
                        }
                    }
                    if (result == null) {
                        log("  源 #" + source + " 失败（" + lastError + "），尝试下一个");
                    }
                }

                if (result == null) {
                    throw new IOException("下载失败（块 " + chunkIndex + "，已下载 "
                            + (done / 1048576) + "MB）：所有来源均不可用\n最后错误："
                            + lastError + "\n请检查网络后重试，已下载部分会继续保留。");
                }

                control.check();
                long wanted = end - done + 1;
                byte[] data = result.data;
                if (data.length <= 0) throw new IOException("块 " + chunkIndex + " 内容为空");
                if (data.length < wanted && total > 0 && done + data.length < total) {
                    throw new IOException("块 " + chunkIndex + " 长度不足: "
                            + data.length + " / " + wanted);
                }
                if (total > 0 && done + data.length > total) {
                    throw new IOException("块 " + chunkIndex + " 超出文件总长度");
                }
                raf.seek(done);
                raf.write(data);
                done += data.length;
                host.progress(assetName, done, total);
                chunkIndex++;
                if (total < 0 && data.length < wanted) total = done;

                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct / 10 != lastLoggedPct / 10) {
                        lastLoggedPct = pct;
                        long seconds = Math.max(1,
                                (System.currentTimeMillis() - startedAt) / 1000);
                        if (!quiet) {
                            log("  " + pct + "%  (" + (done / 1048576) + "/"
                                    + (total / 1048576) + " MB, "
                                    + (done / 1048576 / seconds) + " MB/s, 重试 "
                                    + retries + " 次)");
                            setSplashStatus("正在下载运行包 " + pct + "%");
                        }
                    }
                }
            }
            control.check();
            raf.getFD().sync();
            log("  下载完成 " + (done / 1048576) + " MB，重试 " + retries + " 次");
        } finally {
            raf.close();
        }
        control.check();
        TransferState.commit(partial, identityFile, out);
    }

    /** disconnect 用于中止 HttpURLConnection 的阻塞读取。 */
    static final class Control {
        private boolean cancelled;
        private HttpURLConnection active;

        synchronized void bind(HttpURLConnection connection) throws IOException {
            if (cancelled) {
                disconnectAsync(connection);
                throw new IOException("下载已取消");
            }
            active = connection;
        }

        synchronized void unbind(HttpURLConnection connection) {
            if (active == connection) active = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (active != null) disconnectAsync(active);
            active = null;
        }

        private static void disconnectAsync(final HttpURLConnection connection) {
            // Some implementations synchronize disconnect with a blocking read; never block the UI owner.
            Thread release = new Thread(new Runnable() {
                @Override public void run() { connection.disconnect(); }
            }, "release-transfer-cancel");
            release.setDaemon(true); release.start();
        }

        synchronized void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) cancel();
            if (cancelled) throw new IOException("下载已取消");
        }
    }

    private static final class RangeResult {
        final byte[] data;
        final Long total;

        RangeResult(byte[] data, Long total) {
            this.data = data;
            this.total = total;
        }
    }



private RangeResult fetchRange(String url, long start, long end,
                                   Control control) throws IOException {
        HttpURLConnection c = null;
        try {
            // 手动跟随重定向：CDN 会 302 到签名 URL，且签名地址可能变化
            String cur = url;
            for (int hop = 0; hop < 8; hop++) {
                control.check();
                c = (HttpURLConnection) new URL(cur).openConnection();
                control.bind(c);
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(url.contains("manifest.json") ? 3000 : 5000);
                c.setReadTimeout(url.contains("manifest.json") ? 5000 : 40000);
                c.setRequestProperty("User-Agent", "DSHNative/0.2.0");
                c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    control.unbind(c);
                    c.disconnect();
                    c = null;
                    if (loc == null) throw new IOException("重定向缺少 Location");
                    cur = loc;
                    continue;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                long maximum = end - start + 1;
                Long total = null;
                if (code == 206) {
                    String cr = c.getHeaderField("Content-Range");   // bytes 0-1/33540352
                    if (cr == null || !cr.startsWith("bytes ")) {
                        throw new IOException("206 响应缺少 Content-Range");
                    }
                    try {
                        int dash = cr.indexOf('-', 6);
                        int slash = cr.lastIndexOf('/');
                        long actualStart = Long.parseLong(cr.substring(6, dash).trim());
                        long actualEnd = Long.parseLong(cr.substring(dash + 1, slash).trim());
                        long parsedTotal = Long.parseLong(cr.substring(slash + 1).trim());
                        if (actualStart != start || actualEnd < actualStart || actualEnd > end
                                || parsedTotal <= actualEnd) {
                            throw new IOException("Content-Range 与请求不一致: " + cr);
                        }
                        total = Long.valueOf(parsedTotal);
                        maximum = actualEnd - actualStart + 1;
                    } catch (NumberFormatException badRange) {
                        throw new IOException("Content-Range 格式错误: " + cr);
                    }
                } else {
                    if (start > 0) throw new IOException("服务器忽略 Range，无法安全续传");
                    String length = c.getHeaderField("Content-Length");
                    if (length != null) {
                        try {
                            long parsed = Long.parseLong(length.trim());
                            if (parsed > maximum) {
                                throw new IOException("服务器忽略 Range，返回整包 "
                                        + parsed + " 字节");
                            }
                            if (parsed >= 0) total = Long.valueOf(parsed);
                        } catch (NumberFormatException ignored) { }
                    }
                }
                InputStream in = c.getInputStream();
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                            (int) Math.min(maximum, 262144L));
                    byte[] b = new byte[131072];
                    long received = 0;
                    int n;
                    while ((n = in.read(b)) > 0) {
                        control.check();
                        received += n;
                        if (received > maximum) {
                            throw new IOException("响应超过请求块大小，已中止以避免内存溢出");
                        }
                        bos.write(b, 0, n);
                    }
                    if (code == 200 && total == null) total = Long.valueOf(received);
                    return new RangeResult(bos.toByteArray(), total);
                } finally {
                    in.close();
                }
            }
            throw new IOException("重定向次数过多");
        } finally {
            if (c != null) {
                control.unbind(c);
                c.disconnect();
            }
        }
    }

private String shorten(Throwable e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.length() > 60) m = m.substring(0, 60) + "…";
        return m;
    }
}
