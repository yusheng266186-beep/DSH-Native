package dev.dsh.nativeapp;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.TimeZone;

/** Native account settings; authorization and grant storage belong to the core. */
final class DeepSeekAccountPanel {
    private final Activity activity;
    private final NativeCoreApi api;
    private final Runnable back;
    private final Handler handler = new Handler();
    private Dialog dialog;
    private TextView status;
    private Button login, browser, cancel, signOut, refresh;
    private JSONObject view;
    private boolean foreground = true;
    private boolean busy;
    private boolean openAfterStart;
    private String openedAttempt = "";
    private final Runnable poll = new Runnable() { @Override public void run() { refresh(); } };

    DeepSeekAccountPanel(Activity activity, NativeCoreApi api, Runnable back) {
        this.activity = activity; this.api = api; this.back = back;
    }

    void show() {
        LinearLayout body = DshUi.paddedBody(activity);
        body.addView(DshUi.title(activity, UiText.t("DeepSeek 账号", "DeepSeek account")));
        body.addView(DshUi.hint(activity, UiText.t(
                "在系统浏览器中登录 DeepSeek 并授权本机。完成后返回 App，在模型中心选择“DeepSeek 账号”。",
                "Sign in to DeepSeek and authorize this device in your browser. Return to the app, then choose DeepSeek account in Model center.")), DshUi.fullWidth(activity, 8));
        status = DshUi.status(activity, UiText.t("正在读取账号状态…", "Reading account status…"));
        body.addView(status, DshUi.fullWidth(activity, 14));
        login = action(body, UiText.t("登录 DeepSeek 账号", "Sign in to DeepSeek"));
        browser = action(body, UiText.t("打开授权页面", "Open authorization page"));
        cancel = action(body, UiText.t("取消本次授权", "Cancel authorization"));
        signOut = action(body, UiText.t("退出 DeepSeek 账号", "Sign out of DeepSeek"));
        refresh = action(body, UiText.t("刷新账号状态", "Refresh account status"));
        body.addView(DshUi.hint(activity, UiText.t(
                "API Key 与账号授权分别保存。退出账号会停止使用该账号的运行任务，API Key、会话和项目仍保留。",
                "API keys and account authorization are stored separately. Signing out stops tasks using the account and keeps API keys, sessions, and projects.")), DshUi.fullWidth(activity, 12));
        Button previous = DshUi.button(activity, UiText.t("返回模型中心", "Back to model center"), true);
        dialog = DshUi.dialog(activity, DshUi.scroll(activity, body), DshUi.footer(activity, previous), 560);
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { DshUi.swapDialog(dialog, true, back); }
        });
        DshUi.onBack(dialog, back);
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) { handler.removeCallbacks(poll); }
        });
        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busy) return;
                try {
                    openAfterStart = true;
                    request("account/startSignIn", new JSONObject().put("client", metadata())
                            .put("callbackOrigin", api.origin()).put("loginSource", "web"));
                } catch (Exception invalid) { showFailure(); }
            }
        });
        browser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBrowser(); }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busy || view == null) return;
                try {
                    JSONObject attempt = view.optJSONObject("attempt");
                    if (attempt != null && DeepSeekAccount.canCancel(attempt.optString("phase")))
                        request("account/cancelSignIn", new JSONObject().put("attemptId", attempt.getString("id")));
                } catch (Exception invalid) { showFailure(); }
            }
        });
        signOut.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (busy) return;
                busy = true; render(); handler.removeCallbacks(poll);
                api.request("account/hasRunningAccountTasks", new JSONObject(), new NativeCoreApi.Callback() {
                    @Override public void complete(Object value, Throwable failure) {
                        busy = false;
                        if (!alive()) return;
                        if (failure != null) { render(); showFailure(); return; }
                        String detail = Boolean.TRUE.equals(value)
                                ? UiText.t("使用 DeepSeek 账号的任务仍在运行。退出将停止这些任务。", "Tasks using your DeepSeek account are running. Signing out will stop them.")
                                : UiText.t("移除本机的 DeepSeek 账号授权？", "Remove DeepSeek account authorization from this device?");
                        DshUi.confirm(activity, UiText.t("退出 DeepSeek 账号", "Sign out of DeepSeek"), detail,
                                UiText.t("退出账号", "Sign out"), new Runnable() {
                            @Override public void run() {
                                if (!alive()) return;
                                try { request("account/signOut", new JSONObject().put("client", metadata())); }
                                catch (Exception invalid) { showFailure(); }
                            }
                        });
                        render();
                    }
                });
            }
        });
        refresh.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { refresh(); } });
        dialog.show(); render(); refresh();
    }

    private Button action(LinearLayout body, String label) {
        Button button = DshUi.button(activity, label, false);
        body.addView(button, DshUi.fullWidth(activity, 8)); return button;
    }

    private boolean alive() { return dialog != null && dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }

    void foreground(boolean foreground) {
        this.foreground = foreground; handler.removeCallbacks(poll);
        if (foreground && alive()) refresh();
    }

    private void refresh() { if (alive() && foreground && !busy) request("account/getState", new JSONObject()); }

    private void request(String method, JSONObject args) {
        busy = true; render(); handler.removeCallbacks(poll);
        api.request(method, args, new NativeCoreApi.Callback() {
            @Override public void complete(Object result, Throwable failure) {
                busy = false;
                if (!alive()) return;
                if (failure != null || !(result instanceof JSONObject)) { openAfterStart = false; render(); showFailure(); return; }
                view = (JSONObject) result; render();
                JSONObject attempt = view.optJSONObject("attempt");
                String phase = attempt == null ? "" : attempt.optString("phase");
                if (foreground && openAfterStart && "waiting-browser".equals(phase)) {
                    openAfterStart = false;
                    if (!openedAttempt.equals(attempt.optString("id"))) openBrowser();
                } else if (!DeepSeekAccount.active(phase)) openAfterStart = false;
                if (foreground && DeepSeekAccount.active(phase)) handler.postDelayed(poll, 2000);
            }
        });
    }

    private void render() {
        JSONObject attempt = view == null ? null : view.optJSONObject("attempt");
        String phase = attempt == null ? "" : attempt.optString("phase");
        String stored = view == null ? "" : view.optString("status");
        if (view != null) status.setText(DeepSeekAccount.status(stored, phase,
                attempt == null ? "" : attempt.optString("errorCode"), UiText.isEnglish()));
        boolean active = DeepSeekAccount.active(phase);
        login.setVisibility("credential-stored".equals(stored) ? View.GONE : View.VISIBLE);
        login.setEnabled(!busy && !active && view != null);
        browser.setVisibility("waiting-browser".equals(phase) ? View.VISIBLE : View.GONE);
        browser.setEnabled(!busy);
        cancel.setVisibility(DeepSeekAccount.canCancel(phase) ? View.VISIBLE : View.GONE);
        cancel.setEnabled(!busy);
        signOut.setVisibility("credential-stored".equals(stored) ? View.VISIBLE : View.GONE);
        signOut.setEnabled(!busy && !active);
        refresh.setEnabled(!busy);
    }

    private void openBrowser() {
        if (!alive() || view == null) return;
        JSONObject attempt = view.optJSONObject("attempt");
        if (attempt == null || !"waiting-browser".equals(attempt.optString("phase"))) return;
        String url = attempt.optString("authorizeUrl");
        if (!DeepSeekAccount.browserUrl(url, "/dsh/authorize")) { showFailure(); return; }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            openedAttempt = attempt.optString("id");
        } catch (Exception unavailable) {
            DshUi.toast(activity, UiText.t("无法打开浏览器，请安装或启用浏览器后重试", "Could not open a browser. Enable or install one, then retry."));
        }
    }

    private void showFailure() {
        status.setText(UiText.t("账号操作失败，请刷新或重试；详情见运行日志", "Account operation failed. Refresh or retry; check the runtime log for details."));
        DshUi.toast(activity, UiText.t("账号操作失败，请重试", "Account operation failed. Please retry."));
    }

    private static JSONObject metadata() throws Exception {
        return new JSONObject().put("version", "0.2.0-rc.2")
                .put("locale", UiText.isEnglish() ? "en-US" : "zh-CN")
                .put("timezoneOffsetSeconds", TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000);
    }

    void close() { handler.removeCallbacks(poll); if (dialog != null) dialog.dismiss(); }
}
