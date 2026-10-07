package dev.dsh.nativeapp;

import java.io.IOException;
import java.util.List;

/** One provider refresh; failed reads never overwrite the current catalog. */
final class ModelCatalogRefresh {
    interface Backend {
        void checkOpen() throws IOException;
        String credential(String provider) throws Exception;
        ProviderCheck.Result fetch(String provider, String key) throws Exception;
        String settings() throws Exception;
        void write(String settings) throws Exception;
    }

    static final class Result {
        final String provider;
        final ProviderCheck.Result upstream;
        final List<LiveModelCatalog.Entry> entries;

        Result(String provider, ProviderCheck.Result upstream, List<LiveModelCatalog.Entry> entries) {
            this.provider = provider;
            this.upstream = upstream;
            this.entries = entries;
        }

        boolean saved() { return entries != null; }
    }

    private ModelCatalogRefresh() { }

    static Result run(String provider, String expectedKey, Backend backend) throws Exception {
        String normalized = ModelConfig.normalizeProvider(provider);
        if (!normalized.equals(provider)) throw new IOException("invalid provider");
        backend.checkOpen();
        String key = backend.credential(provider);
        if (!ModelConfig.DEEPSEEK_ACCOUNT.equals(provider)
                && (key.length() == 0 || !key.equals(expectedKey)))
            throw new IOException("credentials changed");
        ProviderCheck.Result upstream = backend.fetch(provider, key);
        backend.checkOpen();
        if (upstream.state != ProviderCheck.READY) return new Result(provider, upstream, null);
        String settings = backend.settings();
        List<LiveModelCatalog.Entry> entries = LiveModelCatalog.reconcile(upstream,
                ModelConfig.modelsForProvider(settings, provider), provider);
        backend.checkOpen();
        if (!key.equals(backend.credential(provider))) throw new IOException("credentials changed");
        backend.checkOpen();
        backend.write(ModelCatalogSync.writeLiveCatalog(settings, provider, entries));
        return new Result(provider, upstream, entries);
    }
}
