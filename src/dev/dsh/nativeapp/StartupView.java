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
            whale.animated = motion;
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
        whale.animated = motion;
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
        whale.animated = motion; whale.elapsed = 0L; whale.invalidate();
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
        if (!motion) { whale.animated = motion; whale.elapsed = 0L; whale.invalidate(); }
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

    /** A single continuous silhouette, with soft light and a wave flowing through the tail. */
    private static final class Whale extends View {
        private final WhaleShape shape = new WhaleShape();
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint water = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bubbles = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path silhouette = new android.graphics.Path();
        private final android.graphics.Path wake = new android.graphics.Path();
        private final WhaleShape.Sink outline = new WhaleShape.Sink() {
            @Override public void move(float x, float y) { silhouette.moveTo(x, y); }
            @Override public void curve(float x1, float y1, float x2, float y2, float x3, float y3) {
                silhouette.cubicTo(x1, y1, x2, y2, x3, y3);
            }
            @Override public void close() { silhouette.close(); }
        };
        long elapsed;
        boolean animated = true;
        Whale(Activity activity) {
            super(activity);
            water.setStyle(Paint.Style.STROKE); water.setStrokeWidth(.65f); water.setStrokeCap(Paint.Cap.ROUND);
            bubbles.setStyle(Paint.Style.STROKE); bubbles.setStrokeWidth(.65f);
            applyTheme();
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        private static int blend(int color, int other, float amount) {
            int red = (int) (((color >> 16) & 255) * (1f - amount) + ((other >> 16) & 255) * amount);
            int green = (int) (((color >> 8) & 255) * (1f - amount) + ((other >> 8) & 255) * amount);
            int blue = (int) ((color & 255) * (1f - amount) + (other & 255) * amount);
            return 0xff000000 | (red << 16) | (green << 8) | blue;
        }
        void applyTheme() {
            int brand = DshUi.BRAND();
            ink.setShader(new android.graphics.LinearGradient(14f, 8f, 31f, 44f,
                    new int[]{blend(brand, 0xffffffff, .16f), brand, blend(brand, 0xff000000, .12f)},
                    new float[]{0f, .52f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            halo.setShader(new android.graphics.RadialGradient(104f, 72f, 68f,
                    new int[]{(brand & 0x00ffffff) | 0x0c000000, (brand & 0x00ffffff) | 0x03000000,
                            brand & 0x00ffffff}, new float[]{0f, .55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            shadow.setColor(brand); shadow.setAlpha(9);
            water.setColor(brand); bubbles.setColor(brand);
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float unit = Math.min(getWidth() / 208f, getHeight() / 160f);
            float phase = StartupState.swimPhase(elapsed);
            float swim = animated ? (float) Math.sin(phase * 2 * Math.PI) : 0f;
            canvas.save();
            canvas.translate((getWidth() - 208f * unit) / 2f, (getHeight() - 160f * unit) / 2f);
            canvas.scale(unit, unit);
            canvas.drawCircle(104f, 72f, 68f, halo);
            canvas.drawOval(81f + swim, 124f, 127f - swim, 128f, shadow);
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * .5f) % 1f;
                float radius = 19f + 28f * p, height = 2.8f + 4f * p;
                float envelope = (float) Math.sin(Math.PI * p);
                water.setAlpha((int) (48f * envelope * envelope * (1f - p)));
                canvas.drawOval(104f - radius, 126f - height, 104f + radius, 126f + height, water);
            }
            water.setAlpha(28);
            wake.reset(); wake.moveTo(67f, 120f);
            wake.cubicTo(78f, 118.8f + swim * .4f, 88f, 121f - swim * .4f, 97f, 120f);
            wake.moveTo(113f, 120f); wake.cubicTo(122f, 119f - swim * .4f, 132f, 121f + swim * .4f, 141f, 120f);
            canvas.drawPath(wake, water);
            for (int i = 0; i < 2; i++) {
                float p = (phase + i * .43f) % 1f;
                float envelope = (float) Math.sin(Math.PI * p);
                bubbles.setAlpha((int) (64f * envelope * envelope));
                canvas.drawCircle(33f + i * 8f + 1.5f * swim, 106f - p * 48f, 1.25f + .65f * p, bubbles);
            }
            canvas.save();
            canvas.translate(2.6f * swim, -2.7f * swim);
            canvas.rotate(-1.8f * swim, 104f, 64f);
            canvas.translate(40f, -2f); canvas.scale(2.56f, 2.56f);
            silhouette.rewind();
            shape.trace(phase, animated ? StartupState.blinkClosure(elapsed) : 0f, animated, outline);
            canvas.drawPath(silhouette, ink);
            canvas.restore(); canvas.restore();
        }
    }
}
