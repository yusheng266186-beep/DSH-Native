package dev.dsh.nativeapp;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** JSON/UI boundary for the real core RPC; every operation runs off the UI thread. */
final class NativeCoreApi {
    interface Callback { void complete(Object value, Throwable failure); }
    interface Work { Object run() throws Exception; }
    private final Activity activity;
    private final CoreRpcClient client;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(16));
    private final Set<Job> pending = new HashSet<Job>();
    private volatile boolean closed;
    final OperationGate catalogGate = new OperationGate();

    NativeCoreApi(Activity activity, int port, String token) {
        this.activity = activity;
        this.client = new CoreRpcClient(port, token);
    }

    String origin() { return client.origin(); }

    Object call(String method, JSONObject args) throws Exception {
        CoreRpcClient.Reply reply = client.call(method, args.toString());
        JSONObject envelope = new JSONObject(reply.body);
        if (!"server-response".equals(envelope.optString("type"))
                || !reply.id.equals(envelope.optString("rpcId"))) throw new IOException("invalid core reply");
        JSONObject result = envelope.getJSONObject("result");
        if (!result.optBoolean("ok")) throw new IOException("core operation rejected");
        return result.opt("value");
    }

    void request(final String method, final JSONObject args, Callback callback) {
        execute(new Work() { @Override public Object run() throws Exception { return call(method, args); } }, callback);
    }

    void execute(final Work work, final Callback callback) {
        Job job = new Job(work, callback);
        synchronized (pending) {
            if (closed) { job.finish(null, new IOException("core client closed")); return; }
            pending.add(job);
        }
        try { executor.execute(job); }
        catch (RejectedExecutionException full) { job.finish(null, new IOException("core request queue unavailable")); }
    }

    private final class Job implements Runnable {
        private final Work work;
        private final Callback callback;
        private final AtomicBoolean settled = new AtomicBoolean();
        Job(Work work, Callback callback) { this.work = work; this.callback = callback; }
        @Override public void run() {
            Object value = null; Throwable failure = null;
            try { checkOpen(); value = work.run(); } catch (Exception error) { failure = error; }
            finish(value, failure);
        }
        void finish(final Object value, final Throwable failure) {
            if (!settled.compareAndSet(false, true)) return;
            synchronized (pending) { pending.remove(this); }
            main.post(new Runnable() {
                @Override public void run() {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    callback.complete(closed ? null : value, closed ? new IOException("core client closed") : failure);
                }
            });
        }
    }

    /** Read the current profile rather than relying on the one-time legacy import. */
    String modelSettings(File home) throws Exception {
        File pending = new File(home, "settings.yaml");
        if (pending.isFile()) return ProjectModelSettings.readFile(pending);
        JSONObject description = (JSONObject) call("settings/describe", new JSONObject());
        return ModelSettingsSnapshot.fromDescription(description.toString());
    }

    void checkOpen() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("core client closed");
    }

    void close() {
        closed = true; catalogGate.finish("model catalog"); client.close(); executor.shutdownNow();
        ArrayList<Job> cancelled;
        synchronized (pending) { cancelled = new ArrayList<Job>(pending); }
        for (Job job : cancelled) job.finish(null, new IOException("core client closed"));
    }
}
