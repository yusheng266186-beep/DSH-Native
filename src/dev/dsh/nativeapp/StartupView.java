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
import android.widget.ScrollView;
import android.widget.TextView;

/** Brand-led loading scene. Only the host's connection and paint evidence allow handoff. */
final class StartupView extends FrameLayout {
    private final TextView title, status;
    private final LinearLayout column;
    private final Whale whale;
    private final Activity activity;
    private final Runnable completed;
    private boolean ready, paused, done, motion;
    private long started, pausedAt, handoffAt = -1L;
    private float restingOffset;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (paused || done || !isAttachedToWindow()) return;
            long now = SystemClock.uptimeMillis();
            if (ready) {
                if (!motion) { finish(); return; }
                float exit = StartupState.handoffProgress(now - handoffAt);
                if (exit >= 1f) { finish(); return; }
                setAlpha(1f - exit);
                column.setTranslationY(restingOffset - DshUi.dp(activity, 6) * exit);
            }
            whale.elapsed = motion ? Math.max(0L, now - started) : 0L;
            whale.invalidate();
            if (motion) postOnAnimation(this);
        }
    };

    StartupView(Activity activity, Runnable completed) {
        super(activity);
        this.activity = activity; this.completed = completed;
        motion = DshUi.animationsEnabled(activity);
        setBackgroundColor(DshUi.BG());
        // Prevent touches reaching a partially initialized page, including during handoff.
        setClickable(true);
        column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(DshUi.dp(activity, 24), DshUi.dp(activity, 16),
                DshUi.dp(activity, 24), DshUi.dp(activity, 16));
        whale = new Whale(activity);
        column.addView(whale, new LinearLayout.LayoutParams(
                DshUi.dp(activity, 208), DshUi.dp(activity, 160)));
        title = DshUi.title(activity, "DeepSeek Harness");
        title.setTextSize(21f); title.setGravity(Gravity.CENTER);
        column.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = DshUi.hint(activity, UiText.t("正在准备 DSH…", "Preparing DSH…"));
        status.setTextSize(14f);
        status.setTextColor(DshUi.TEXT_2());
        status.setGravity(Gravity.CENTER);
        // Reserve the same space for preparation, download and connection messages.
        status.setMinLines(3); status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        text.topMargin = DshUi.dp(activity, 12); column.addView(status, text);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Keep the scene compact in landscape; allow more room for enlarged status text.
        float fontScale = getResources().getConfiguration().fontScale;
        int reserved = DshUi.dp(activity, (int) (100f * Math.max(1f, fontScale)) + 48);
        int hero = Math.max(DshUi.dp(activity, 56),
                Math.min(DshUi.dp(activity, 160), Math.max(0, Math.min(height / 3, height - reserved))));
        whale.setLayoutParams(new LinearLayout.LayoutParams(hero * 208 / 160, hero));
        restingOffset = height > DshUi.dp(activity, 440) ? -DshUi.dp(activity, 20) : 0f;
        column.setTranslationY(restingOffset);
    }

    void applyTheme() {
        setBackgroundColor(DshUi.BG());
        title.setTextColor(DshUi.TEXT()); status.setTextColor(DshUi.TEXT_2());
        whale.applyTheme();
    }

    void status(String text) {
        if (!String.valueOf(text).contentEquals(status.getText())) status.setText(text);
    }

    void begin(String text) {
        removeCallbacks(frame); ready = false; done = false; handoffAt = -1L;
        motion = DshUi.animationsEnabled(activity);
        resetPresentation(); setVisibility(View.VISIBLE); setClickable(true); status(text);
        started = SystemClock.uptimeMillis(); pausedAt = paused ? started : 0L;
        whale.elapsed = 0L; whale.invalidate();
        if (!paused && isAttachedToWindow() && motion) postOnAnimation(frame);
    }

    void ready(boolean value) {
        if (done || ready == value) return;
        ready = value;
        if (!ready) { handoffAt = -1L; resetPresentation(); return; }
        motion = DshUi.animationsEnabled(activity);
        if (paused) return;
        if (!motion) { finish(); return; }
        handoffAt = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        if (isAttachedToWindow()) postOnAnimation(frame);
    }

    void pause() {
        if (!paused) pausedAt = SystemClock.uptimeMillis();
        paused = true; removeCallbacks(frame); handoffAt = -1L; resetPresentation();
    }
    void resume() {
        long now = SystemClock.uptimeMillis();
        if (paused) started += Math.max(0L, now - pausedAt);
        paused = false;
        motion = DshUi.animationsEnabled(activity);
        removeCallbacks(frame);
        if (done || getVisibility() != View.VISIBLE) return;
        // Returning to an already ready page never introduces another entrance animation.
        if (ready) { finish(); return; }
        if (!motion) { whale.elapsed = 0L; whale.invalidate(); }
        else if (isAttachedToWindow()) postOnAnimation(frame);
    }

    void dismiss() {
        done = true; ready = false; handoffAt = -1L; removeCallbacks(frame);
        setClickable(false); setVisibility(View.GONE); resetPresentation();
    }

    private void resetPresentation() { setAlpha(1f); column.setTranslationY(restingOffset); }
    private void finish() { if (done) return; dismiss(); completed.run(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        started = SystemClock.uptimeMillis();
        if (ready && !paused && !done) { handoffAt = started; }
        if (!paused && !done && motion) postOnAnimation(frame);
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(frame); super.onDetachedFromWindow();
    }

    /** Opaque brand silhouette, slow swimming, soft light and two quiet expanding ripples. */
    private static final class Whale extends View {
        private final android.graphics.drawable.Drawable mark;
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint water = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbles = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path wake = new android.graphics.Path();
        long elapsed;
        Whale(Activity activity) {
            super(activity);
            int id = activity.getResources().getIdentifier("dsh_startup_whale", "drawable", activity.getPackageName());
            mark = activity.getDrawable(id).mutate();
            mark.setBounds(48, 0, 160, 112);
            water.setStyle(Paint.Style.STROKE); water.setStrokeWidth(.8f); water.setStrokeCap(Paint.Cap.ROUND);
            bubbles.setStyle(Paint.Style.STROKE); bubbles.setStrokeWidth(.8f);
            applyTheme();
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void applyTheme() {
            int brand = DshUi.BRAND();
            mark.setTint(brand);
            halo.setShader(new android.graphics.RadialGradient(104f, 72f, 68f,
                    new int[]{(brand & 0x00ffffff) | 0x10000000, (brand & 0x00ffffff) | 0x05000000,
                            brand & 0x00ffffff}, new float[]{0f, .55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            shadow.setColor(brand); shadow.setAlpha(10);
            water.setColor(brand); bubbles.setColor(brand);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = Math.min(getWidth() / 208f, getHeight() / 160f);
            float phase = StartupState.swimPhase(elapsed);
            float swim = (float) Math.sin(phase * 2 * Math.PI);
            canvas.save();
            canvas.translate((getWidth() - 208f * unit) / 2f, (getHeight() - 160f * unit) / 2f);
            canvas.scale(unit, unit);
            canvas.drawCircle(104f, 72f, 68f, halo);
            canvas.drawOval(80f + swim, 123f, 128f - swim, 128f, shadow);
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * .5f) % 1f;
                float radius = 19f + 30f * p, height = 3f + 5f * p;
                float envelope = (float) Math.sin(Math.PI * p);
                water.setAlpha((int) (65f * envelope * envelope * (1f - p)));
                canvas.drawOval(104f - radius, 125f - height, 104f + radius, 125f + height, water);
            }
            water.setAlpha(42);
            wake.reset(); wake.moveTo(65f, 119f);
            wake.cubicTo(77f, 117f + swim, 89f, 121f - swim, 100f, 119f);
            wake.moveTo(109f, 119f); wake.cubicTo(118f, 118f - swim, 133f, 120f + swim, 142f, 119f);
            canvas.drawPath(wake, water);
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * .58f) % 1f;
                float envelope = (float) Math.sin(Math.PI * p);
                bubbles.setAlpha((int) (65f * envelope * envelope));
                canvas.drawCircle(166f + i * 8f + 2f * swim, 100f - p * 40f, 1.2f + .8f * p, bubbles);
            }
            canvas.save(); canvas.translate(1.8f * swim, -3.2f * swim);
            canvas.rotate(-1.6f * swim, 104f, 62f);
            mark.draw(canvas); canvas.restore(); canvas.restore();
        }
    }
}
