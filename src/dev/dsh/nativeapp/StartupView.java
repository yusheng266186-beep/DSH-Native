package dev.dsh.nativeapp;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A small themed loading motion; never signals readiness on its own. */
final class StartupView extends FrameLayout {
    private final TextView status;
    private final Dots dots;
    private final Activity activity;
    private final Runnable completed;
    private boolean ready, paused, done, motion;
    private long started, previous;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (paused || done || !isAttachedToWindow()) return;
            long now = SystemClock.uptimeMillis();
            if (ready && (!motion || StartupState.cycleEnded(started, previous, now))) {
                finish(); return;
            }
            previous = now;
            dots.phase = motion ? (now - started) % StartupState.CYCLE_MS
                    / (float) StartupState.CYCLE_MS : 0f;
            dots.invalidate();
            if (motion) postOnAnimation(this);
        }
    };

    StartupView(Activity activity, Runnable completed) {
        super(activity);
        this.activity = activity; this.completed = completed;
        motion = DshUi.animationsEnabled(activity);
        setBackgroundColor(DshUi.BG());
        // Prevent touches reaching a partially initialized page below the overlay.
        setClickable(true);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(DshUi.dp(activity, 24), DshUi.dp(activity, 16),
                DshUi.dp(activity, 24), DshUi.dp(activity, 16));
        dots = new Dots(activity);
        column.addView(dots, new LinearLayout.LayoutParams(DshUi.dp(activity, 72), DshUi.dp(activity, 28)));
        status = DshUi.hint(activity, UiText.t("正在准备 DSH…", "Preparing DSH…"));
        status.setTextSize(14f);
        status.setTextColor(DshUi.TEXT_2());
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        text.topMargin = DshUi.dp(activity, 16); column.addView(status, text);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(column, layout);
    }

    void status(String text) { status.setText(text); }

    void begin(String text) {
        removeCallbacks(frame); ready = false; done = false;
        motion = DshUi.animationsEnabled(activity);
        setVisibility(View.VISIBLE); setClickable(true); status(text);
        started = previous = SystemClock.uptimeMillis();
        if (!paused && isAttachedToWindow()) postOnAnimation(frame);
    }

    void ready(boolean value) {
        ready = value;
        if (ready) motion = DshUi.animationsEnabled(activity);
        if (ready && !paused && !done && !motion) finish();
    }

    void pause() { paused = true; removeCallbacks(frame); }
    void resume() {
        paused = false;
        motion = DshUi.animationsEnabled(activity);
        removeCallbacks(frame);
        if (done || getVisibility() != View.VISIBLE) return;
        // Do not make someone returning from the background wait for another loop.
        if (ready) { finish(); return; }
        started = previous = SystemClock.uptimeMillis(); postOnAnimation(frame);
    }

    void dismiss() {
        done = true; ready = false; removeCallbacks(frame);
        setClickable(false); setVisibility(View.GONE);
    }

    private void finish() { if (done) return; dismiss(); completed.run(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        started = previous = SystemClock.uptimeMillis();
        if (!paused && !done) postOnAnimation(frame);
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame); super.onDetachedFromWindow();
    }

    private static final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float phase;
        Dots(Activity activity) {
            super(activity); paint.setColor(DshUi.ACCENT());
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            for (int i = 0; i < 3; i++) {
                double local = (phase - i * .13f + 1f) % 1f;
                float lift = local < .55 ? (float) Math.sin(local / .55 * Math.PI) * 6f : 0f;
                canvas.drawCircle(getWidth() / 2f + (i - 1) * 16f * density,
                        getHeight() / 2f - lift * density, 3f * density, paint);
            }
        }
    }
}
