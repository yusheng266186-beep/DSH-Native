package dev.dsh.nativeapp;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import java.util.Map;

/** Explicit setup and session controls, presented through the existing settings host. */
final class PhoneControlPanel {
    private PhoneControlPanel() { }
    static void show(Activity owner, Runnable previous) {
        LinearLayout body = DshUi.paddedBody(owner);
        body.addView(DshUi.title(owner, UiText.t("手机操控", "Phone control")));
        body.addView(DshUi.hint(owner, UiText.t(
                "使用系统无障碍读取当前屏幕、点击、输入和滑动。先开启系统服务，再手动开始本次操控。",
                "Read the visible screen, tap, type and swipe with Android Accessibility. Enable the system service, then start a control session.")), DshUi.fullWidth(owner, 8));
        TextView status = DshUi.status(owner, ""); body.addView(status, DshUi.fullWidth(owner, 12));
        Button settings = DshUi.button(owner, UiText.t("打开系统无障碍设置", "Open Accessibility settings"), false);
        settings.setOnClickListener(v -> launch(owner, new Intent("android.settings.ACCESSIBILITY_SETTINGS")));
        body.addView(settings, DshUi.fullWidth(owner, 8));
        body.addView(DshUi.hint(owner, UiText.t(
                "找到“DSH 手机操控”并开启。侧载应用的开关若为灰色，请在系统应用信息中查看“允许受限制的设置”。",
                "Enable “DSH Phone control”. If Android blocks a sideloaded service, check “Allow restricted settings” in system App info.")), DshUi.fullWidth(owner, 6));
        Button info = DshUi.button(owner, UiText.t("打开系统应用信息", "Open system App info"), false);
        info.setOnClickListener(v -> launch(owner, new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + owner.getPackageName()))));
        body.addView(info, DshUi.fullWidth(owner, 8));
        Button start = DshUi.button(owner, UiText.t("开始本次操控", "Start this session"), true);
        start.setOnClickListener(v -> DshUi.confirm(owner, UiText.t("开启本次手机操控？", "Start Phone control?"),
                UiText.t("屏幕文字和你请求的截图会提供给所选模型，模型可操作其他应用。浮动按钮可暂停或停止；锁屏、服务断开或闲置 3 分钟会停止。密码、验证码和系统授权需要手动处理。",
                        "Screen text and requested screenshots are shared with your selected model, which can operate other Apps. Pause or stop with the floating controls. Locking, disconnection or three minutes of inactivity stops control. Handle passwords, verification codes and system permissions manually."),
                UiText.t("同意并开始", "Agree and start"), () -> {
                    try { PhoneAccessibilityService.startByUser(); }
                    catch (NativeBridgeServer.Failure error) { status.setText(UiText.t("请先开启系统无障碍服务，并保持亮屏解锁。", "Enable Accessibility and keep the phone unlocked with its screen on.") + " (" + error.code + ")"); }
                }));
        body.addView(start, DshUi.fullWidth(owner, 16));
        Button stop = DshUi.button(owner, UiText.t("停止操控", "Stop Phone control"), false);
        stop.setOnClickListener(v -> PhoneAccessibilityService.stopByUser()); body.addView(stop, DshUi.fullWidth(owner, 8));
        body.addView(DshUi.hint(owner, UiText.t(
                "开始后回到聊天，说出需要完成的手机操作。每次重启应用或重新连接服务，都需要重新开始。截图需要 Android 11 或更新系统。",
                "After starting, return to chat and describe the phone task. Restarting the App or reconnecting the service requires a new session. Accessibility screenshots require Android 11+.")), DshUi.fullWidth(owner, 12));
        Button back = DshUi.button(owner, UiText.t("返回", "Back"), false);
        Dialog dialog = DshUi.dialog(owner, DshUi.scroll(owner, body), DshUi.footer(owner, back), 700);
        Handler timer = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || owner.isDestroyed()) return;
                Map<String, Object> value = PhoneAccessibilityService.status();
                boolean ready = Boolean.TRUE.equals(value.get("connected")), active = Boolean.TRUE.equals(value.get("enabled"));
                status.setText(!ready ? UiText.t("系统无障碍服务未连接", "Accessibility is not connected")
                        : !active ? UiText.t("服务已连接，当前未操控手机", "Service connected; Phone control is off")
                        : Boolean.TRUE.equals(value.get("paused")) ? UiText.t("手机操控已暂停，可在浮动条继续", "Phone control paused; resume with the floating control")
                        : UiText.t("手机操控已开启，浮动条可随时停止", "Phone control active; use the floating Stop control"));
                start.setEnabled(ready && !active); stop.setEnabled(active); timer.postDelayed(this, 1000);
            }
        };
        dialog.setOnDismissListener(ignored -> timer.removeCallbacks(tick));
        back.setOnClickListener(v -> DshUi.swapDialog(dialog, true, previous));
        DshUi.onBack(dialog, previous); dialog.show(); tick.run();
    }
    private static void launch(Activity owner, Intent intent) {
        try { owner.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException error) { DshUi.confirm(owner, UiText.t("请手动打开系统设置", "Open system settings manually"), UiText.t("此系统没有提供对应设置入口。", "This Android build does not expose that settings screen."), UiText.t("知道了", "OK"), () -> { }); }
    }
}
