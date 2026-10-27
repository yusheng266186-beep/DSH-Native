package dev.dsh.nativeapp;

import android.content.Context;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Sole owner of the retained browser. Theme and viewport changes never replace the document. */
final class WebSession {
    private WebView view;
    WebSession(Context context, int textZoom, int background) {
        view = new WebView(context);
        view.setBackgroundColor(background);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(textZoom);
    }
    WebView view() { return view; }
    void navigate(String target) {
        if (view == null) return;
        view.setInitialScale(0);
        view.loadUrl(target);
    }
    void viewport(int screenWidthDp) {
        if (view != null) view.evaluateJavascript(ViewportState.script(screenWidthDp), null);
    }
    void resume() {
        if (view == null) return;
        view.onResume();
        view.evaluateJavascript("window.dispatchEvent(new Event('dsh-native-resume'));", null);
    }
    void pause() { if (view != null) view.onPause(); }
    void close() {
        final WebView old = view; view = null;
        if (old == null) return;
        RuntimeException failure = null;
        for (Runnable step : new Runnable[]{
                new Runnable() { @Override public void run() { old.stopLoading(); } },
                new Runnable() { @Override public void run() { old.setWebChromeClient(null); old.setWebViewClient(null); } },
                new Runnable() { @Override public void run() {
                    if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
                } },
                new Runnable() { @Override public void run() { old.removeAllViews(); } },
                new Runnable() { @Override public void run() { old.destroy(); } }}) {
            try { step.run(); } catch (RuntimeException error) { if (failure == null) failure = error; }
        }
        if (failure != null) throw failure;
    }
}
