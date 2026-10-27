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
    private final Whale whale;
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
            whale.phase = motion ? (now - started) % StartupState.CYCLE_MS
                    / (float) StartupState.CYCLE_MS : 0f;
            whale.invalidate();
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
        whale = new Whale(activity);
        column.addView(whale, new LinearLayout.LayoutParams(DshUi.dp(activity, 104), DshUi.dp(activity, 84)));
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

    void applyTheme() {
        setBackgroundColor(DshUi.BG());
        status.setTextColor(DshUi.TEXT_2());
        whale.applyTheme();
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

    /** Brand whale swimming above a quiet wake; never flashes or changes opacity. */
    private static final class Whale extends View {
        private final android.graphics.drawable.Drawable mark;
        private final Paint water = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbles = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path wake = new android.graphics.Path();
        float phase;
        Whale(Activity activity) {
            super(activity);
            int id = activity.getResources().getIdentifier("dsh_startup_whale", "drawable", activity.getPackageName());
            mark = activity.getDrawable(id).mutate();
            mark.setBounds(18, 0, 86, 68);
            water.setStyle(Paint.Style.STROKE); water.setStrokeWidth(1.1f); water.setStrokeCap(Paint.Cap.ROUND);
            applyTheme();
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void applyTheme() {
            mark.setTint(DshUi.BRAND());
            water.setColor(DshUi.BRAND()); water.setAlpha(42);
            bubbles.setColor(DshUi.BRAND()); bubbles.setAlpha(65);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = Math.min(getWidth() / 104f, getHeight() / 84f);
            float swim = (float) Math.sin(phase * 2 * Math.PI);
            canvas.save();
            canvas.translate((getWidth() - 104f * unit) / 2f, (getHeight() - 84f * unit) / 2f);
            canvas.scale(unit, unit);
            wake.reset(); wake.moveTo(34, 70);
            wake.cubicTo(42, 69 + .5f * swim, 49, 71 - .5f * swim, 56, 70);
            wake.moveTo(62, 70); wake.cubicTo(65, 69.5f - .5f * swim, 69, 70.5f + .5f * swim, 72, 70);
            canvas.drawPath(wake, water);
            canvas.drawCircle(89, 20 - swim, .8f, bubbles);

            canvas.save(); canvas.translate(0, -.7f * swim); canvas.rotate(-.8f * swim, 52, 34);
            mark.draw(canvas); canvas.restore(); canvas.restore();
        }
    }
}
