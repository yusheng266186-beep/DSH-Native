package dev.dsh.nativeapp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** InteractionFeedback 与面板动画资源的离线回归测试。 */
public class InteractionFeedbackTest {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else {
            fail++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }

    static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        check("dialog enter duration", InteractionFeedback.DIALOG_ENTER_MS == 240, "value");
        check("dialog exit duration", InteractionFeedback.DIALOG_EXIT_MS == 160, "value");
        check("press duration", InteractionFeedback.BUTTON_PRESS_MS == 85, "value");
        check("release duration", InteractionFeedback.BUTTON_RELEASE_MS == 145, "value");
        check("content reveal duration", InteractionFeedback.CONTENT_REVEAL_MS == 240,
                "value");
        check("choice change duration", InteractionFeedback.CHOICE_CHANGE_MS == 180,
                "value");
        check("dialog swap duration", InteractionFeedback.DIALOG_SWAP_MS == 110, "value");
        check("result hold", InteractionFeedback.RESULT_HOLD_MS >= 600
                && InteractionFeedback.RESULT_HOLD_MS <= 900, "value");
        check("submit timeout", InteractionFeedback.SUBMIT_TIMEOUT_MS == 5000, "value");
        check("pressed scale visible", InteractionFeedback.PRESSED_SCALE >= 0.95f
                && InteractionFeedback.PRESSED_SCALE <= 0.97f, "value");
        check("reveal first item", InteractionFeedback.revealDelay(0) == 0, "value");
        check("reveal stagger", InteractionFeedback.revealDelay(3) == 72, "value");
        check("reveal negative clamps", InteractionFeedback.revealDelay(-2) == 0, "value");
        check("reveal delay capped", InteractionFeedback.revealDelay(100) == 144, "value");

        check("progress normal", InteractionFeedback.progress(2, 5) == 2, "value");
        check("progress clamps low", InteractionFeedback.progress(-1, 5) == 0, "value");
        check("progress clamps high", InteractionFeedback.progress(8, 5) == 5, "value");
        check("progress invalid total", InteractionFeedback.progress(2, 0) == 0, "value");
        check("percent normal", InteractionFeedback.percent(2, 5) == 40, "value");
        check("percent floor", InteractionFeedback.percent(1, 3) == 33, "value");
        check("percent clamps high", InteractionFeedback.percent(8, 5) == 100, "value");
        check("percent overflow safe", InteractionFeedback.percent(
                Integer.MAX_VALUE, Integer.MAX_VALUE) == 100, "value");

        check("generation increments", InteractionFeedback.nextGeneration(8) == 9, "value");
        check("generation wraps", InteractionFeedback.nextGeneration(Integer.MAX_VALUE) == 1,
                "value");
        check("generation matches", InteractionFeedback.isCurrent(9, 9), "false");
        check("zero generation rejected", !InteractionFeedback.isCurrent(0, 0), "true");
        check("stale generation rejected", !InteractionFeedback.isCurrent(8, 9), "true");

        check("motion disabled", InteractionFeedback.dialogAnimationStyle(
                false, 7, 9) == 0, "style");
        check("custom motion preferred", InteractionFeedback.dialogAnimationStyle(
                true, 7, 9) == 7, "style");
        check("system motion fallback", InteractionFeedback.dialogAnimationStyle(
                true, 0, 9) == 9, "style");
        check("invalid fallback disabled", InteractionFeedback.dialogAnimationStyle(
                true, 0, -1) == 0, "style");

        String enter = read("icon/res/anim/dsh_dialog_enter.xml");
        String exit = read("icon/res/anim/dsh_dialog_exit.xml");
        String styles = read("icon/res/values/styles.xml");
        check("enter resource duration", enter.contains("android:duration=\"240\""), "xml");
        check("enter resource alpha", enter.contains("android:fromAlpha=\"0.0\"")
                && enter.contains("android:fromYDelta=\"18dp\"")
                && enter.contains("android:toYDelta=\"0dp\""), "xml");
        check("exit resource duration", exit.contains("android:duration=\"160\""), "xml");
        check("exit resource alpha", exit.contains("android:toAlpha=\"0.0\"")
                && exit.contains("android:toYDelta=\"10dp\""), "xml");
        check("dialog motion avoids scale", !enter.contains("<scale")
                && !exit.contains("<scale"), "xml");
        check("dialog style wired", styles.contains("name=\"DshDialogAnimation\"")
                && styles.contains("@anim/dsh_dialog_enter")
                && styles.contains("@anim/dsh_dialog_exit"), "xml");

        String sourceRoot = new File("src/dev/dsh/nativeapp").isDirectory()
                ? "src/dev/dsh/nativeapp/" : "bootstrap/src/dev/dsh/nativeapp/";
        String ui = read(sourceRoot + "DshUi.java");
        String activity = read(sourceRoot + "MainActivity.java");
        check("layered content reveal wired", ui.contains("class MotionCard")
                && ui.contains("InteractionFeedback.revealDelay(i)"), "source");
        check("bounded ripple wired", ui.contains("new android.graphics.drawable.RippleDrawable")
                && ui.contains("!animationsEnabled(c)"), "source");
        check("toggle accessibility wired", ui.contains("markToggleState")
                && ui.contains("setContentDescription"), "source");
        check("settings page swap wired", ui.contains("void swapDialog")
                && activity.contains("DshUi.swapDialog(dialog, false")
                && activity.contains("DshUi.swapDialog(dialog, true"), "source");
        check("choice feedback wired", activity.contains("DshUi.choiceActivated(v)")
                && activity.contains("DshUi.animateChoiceChange(row)"), "source");
        check("busy result feedback wired", ui.contains("setButtonFeedbackText(b, outcome)")
                && ui.contains("setButtonFeedbackText(b, idle)"), "source");

        System.out.println("TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }
}
