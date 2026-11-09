package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

/**
 * The weather picture: sun, moon, clouds, rain, snow, bolt and fog drawn with Canvas in a 64 x 64 unit box, scaled
 * to the view, so the same code draws the chip's 38 dp art and the screensaver's 92 dp art. Geometry and timing
 * follow design-spec.md 3.4 and 3.5 (mockups.html section 1).
 *
 * Every Paint and Path is made once (constructor); onDraw allocates nothing. The animation is time-based and
 * redrawn at most {@link #setFrameIntervalMs} apart (30 fps by default). It runs only while the view is shown, the
 * window has focus (unless {@link #setRequireWindowFocus} is off), and {@link #setAnimationAllowed} is true; any other
 * time it draws one still pose.
 */
final class WeatherArtView extends View {

    static final int PALETTE_DARK = 0, PALETTE_LIGHT = 1, PALETTE_SCREENSAVER = 2;

    // ---- palette, indexes into the arrays below ------------------------------------------------------------------
    private static final int SUN = 0, RAY = 1, CLOUD = 2, CLOUD2 = 3, STORM = 4, RAIN = 5, SNOW = 6, BOLT = 7,
            MOON = 8, STAR = 9, FOG = 10, COLORS = 11;
    private static final int[] DARK = {0xFFFFC94D, 0xFFFFB23E, 0xFFE8EDF3, 0xFF8E9AAB, 0xFF6F7889, 0xFF6EC1FF,
            0xFFFFFFFF, 0xFFFFD84D, 0xFFECE6D2, 0xFFFFFFFF, 0xFFB8C2CE};
    private static final int[] LIGHT = {0xFFFFC94D, 0xFFFFB23E, 0xFFC9D3DE, 0xFF97A3B3, 0xFF7D8798, 0xFF2F8FE0,
            0xFF8FB6DA, 0xFFFFD84D, 0xFFC9BE98, 0xFF7B8494, 0xFF8A95A3};
    private static final float SCREENSAVER_SATURATION = 0.7f;

    // ---- geometry (unit box) -------------------------------------------------------------------------------------
    private static final float[] SUN_BIG = {32f, 32f, 11f};
    private static final float[] SUN_SMALL = {24f, 24f, 8f};

    private final int[] palette = new int[COLORS];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cloud = new Path();
    private final Path moonBig = new Path();
    private final Path moonSmall = new Path();
    private final Path bolt = new Path();
    private final Path[] stars = new Path[3];
    private final float[] raysBig = new float[32];
    private final float[] raysSmall = new float[32];

    private WeatherData.Condition condition = WeatherData.Condition.CLEAR;
    private boolean day = true;
    private int paletteKind = PALETTE_DARK;
    private float artAlpha = 1f;
    private boolean animationAllowed = true;
    private boolean requireWindowFocus = true;
    private int frameIntervalMs = 33;
    private boolean running;
    private float scale = 1f;
    private float offsetX, offsetY;
    private final Runnable frame = this::invalidate;

    WeatherArtView(Context context) {
        super(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_NONE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        // The cloud: round rect (15, 33, 52, 46, r 6.5) with circles (25, 34, 8), (35, 29, 11), (45, 36, 7), one shape.
        Path part = new Path();
        cloud.addRoundRect(15f, 33f, 52f, 46f, 6.5f, 6.5f, Path.Direction.CW);
        part.addCircle(25f, 34f, 8f, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.rewind();
        part.addCircle(35f, 29f, 11f, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.rewind();
        part.addCircle(45f, 36f, 7f, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        buildMoon(moonBig, 30f, 33f, 13f);
        buildMoon(moonSmall, 24f, 24f, 8f);
        // The bolt polygon.
        bolt.moveTo(35f, 38f);
        bolt.lineTo(27f, 50f);
        bolt.lineTo(33f, 50f);
        bolt.lineTo(29f, 61f);
        bolt.lineTo(41f, 46f);
        bolt.lineTo(35f, 46f);
        bolt.lineTo(38f, 38f);
        bolt.close();
        stars[0] = star(47f, 17f, 3.2f);
        stars[1] = star(52f, 34f, 2.2f);
        stars[2] = star(13f, 15f, 2.4f);
        rays(raysBig, SUN_BIG);
        rays(raysSmall, SUN_SMALL);
        applyPalette();
    }

    /** Crescent: disc minus a second disc offset up and to the right (Path.op DIFFERENCE, built once). */
    private static void buildMoon(Path out, float cx, float cy, float r) {
        out.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.85f, Path.Direction.CW);
        out.op(bite, Path.Op.DIFFERENCE);
    }

    private static Path star(float x, float y, float s) {
        Path p = new Path();
        p.moveTo(x, y - s);
        p.lineTo(x + s * .28f, y - s * .28f);
        p.lineTo(x + s, y);
        p.lineTo(x + s * .28f, y + s * .28f);
        p.lineTo(x, y + s);
        p.lineTo(x - s * .28f, y + s * .28f);
        p.lineTo(x - s, y);
        p.lineTo(x - s * .28f, y - s * .28f);
        p.close();
        return p;
    }

    /** 8 rays from r + 4 to r + 8.5 around the centre, as 8 line segments for drawLines. */
    private static void rays(float[] out, float[] sun) {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            out[i * 4] = sun[0] + c * (sun[2] + 4f);
            out[i * 4 + 1] = sun[1] + s * (sun[2] + 4f);
            out[i * 4 + 2] = sun[0] + c * (sun[2] + 8.5f);
            out[i * 4 + 3] = sun[1] + s * (sun[2] + 8.5f);
        }
    }

    // ---- configuration -------------------------------------------------------------------------------------------

    void setWeather(WeatherData.Condition condition, boolean day) {
        if (this.condition == condition && this.day == day) return;
        this.condition = condition;
        this.day = day;
        invalidate();
    }

    void setPalette(int kind) {
        if (paletteKind == kind) return;
        paletteKind = kind;
        applyPalette();
        invalidate();
    }

    /** Overall opacity of the art (old data, screensaver). */
    void setArtAlpha(float alpha) {
        artAlpha = Math.max(0f, Math.min(1f, alpha));
        invalidate();
    }

    /** False for a still pose (animations off, reduced motion, weather_animated false, old data). */
    void setAnimationAllowed(boolean allowed) {
        if (animationAllowed == allowed) return;
        animationAllowed = allowed;
        updateRunning();
    }

    /** The screensaver's window may never report focus the way an activity's does. */
    void setRequireWindowFocus(boolean require) {
        requireWindowFocus = require;
        updateRunning();
    }

    /** Longest redraw rate: 33 ms is 30 fps (Home), 66 ms is 15 fps (screensaver). */
    void setFrameIntervalMs(int ms) {
        frameIntervalMs = Math.max(16, ms);
    }

    /** The screensaver palette is the dark one with its saturation scaled down (computed once, not per frame). */
    private void applyPalette() {
        int[] source = paletteKind == PALETTE_LIGHT ? LIGHT : DARK;
        float[] hsv = new float[3];
        for (int i = 0; i < COLORS; i++) {
            int color = source[i];
            if (paletteKind == PALETTE_SCREENSAVER) {
                Color.colorToHSV(color, hsv);
                hsv[1] *= SCREENSAVER_SATURATION;
                color = Color.HSVToColor(hsv);
            }
            palette[i] = color | 0xFF000000;
        }
    }

    // ---- running -------------------------------------------------------------------------------------------------

    private void updateRunning() {
        boolean want = animationAllowed && isAttachedToWindow() && isShown()
                && (!requireWindowFocus || hasWindowFocus());
        if (want == running) return;
        running = want;
        if (!want) removeCallbacks(frame);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateRunning();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scale = Math.min(w, h) / 64f;
        offsetX = (w - 64f * scale) / 2f;
        offsetY = (h - 64f * scale) / 2f;
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        long start = SystemClock.uptimeMillis();
        // A still pose is drawn at a fixed time: -1 marks it, and each element picks its still value.
        long t = running ? start : -1L;
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawScene(canvas, t);
        canvas.restore();
        if (running) {
            long used = SystemClock.uptimeMillis() - start;
            postOnAnimationDelayed(frame, Math.max(0L, frameIntervalMs - used - 8L));
        }
    }

    private void drawScene(Canvas canvas, long t) {
        switch (condition) {
            case CLEAR:
                if (day) sun(canvas, t, SUN_BIG, raysBig);
                else night(canvas, t, moonBig, 30f, 33f, 13f, true);
                break;
            case PARTLY:
                if (day) sun(canvas, t, SUN_SMALL, raysSmall);
                else night(canvas, t, moonSmall, 24f, 24f, 8f, false);
                drawCloud(canvas, CLOUD, 4f, 4f, driftA(t));
                break;
            case CLOUDY:
                drawCloud(canvas, CLOUD2, -7f, -8f, driftB(t));
                drawCloud(canvas, CLOUD, 4f, 4f, driftA(t));
                break;
            case DRIZZLE:
                drawCloud(canvas, CLOUD2, 0f, -8f, driftA(t));
                streak(canvas, t, 22f, 42f, 0);
                streak(canvas, t, 38f, 42f, 2);
                break;
            case RAIN:
                drawCloud(canvas, CLOUD2, 0f, -8f, driftA(t));
                streak(canvas, t, 22f, 42f, 0);
                streak(canvas, t, 30f, 44f, 1);
                streak(canvas, t, 38f, 42f, 2);
                streak(canvas, t, 46f, 44f, 3);
                break;
            case SLEET:
                drawCloud(canvas, CLOUD2, 0f, -8f, driftA(t));
                streak(canvas, t, 22f, 42f, 0);
                streak(canvas, t, 38f, 42f, 2);
                flake(canvas, t, 31f, 48f, 1);
                flake(canvas, t, 47f, 48f, 3);
                break;
            case SNOW:
                drawCloud(canvas, CLOUD, 0f, -8f, driftA(t));
                flake(canvas, t, 23f, 44f, 0);
                flake(canvas, t, 31f, 48f, 1);
                flake(canvas, t, 39f, 44f, 2);
                flake(canvas, t, 47f, 48f, 3);
                flake(canvas, t, 35f, 53f, 4);
                break;
            case THUNDER:
                drawCloud(canvas, STORM, 0f, -8f, driftA(t));
                bolt(canvas, t);
                streakAt(canvas, t, 22f, 44f, -300L);
                streakAt(canvas, t, 46f, 44f, -700L);
                break;
            case FOG:
                drawCloud(canvas, CLOUD2, 0f, -10f, driftB(t));
                fogLine(canvas, t, 14f, 50f, 44f, 0L);
                fogLine(canvas, t, 20f, 56f, 51f, 2000L);
                fogLine(canvas, t, 12f, 40f, 58f, 4000L);
                break;
            default:
                break;
        }
    }

    // ---- elements ------------------------------------------------------------------------------------------------

    /** 0 to 1 and back over one period, eased: the "ease-in-out infinite" of the mockup's alternating keyframes. */
    private static float wave(long t, long periodMs, long delayMs) {
        float p = ((t + delayMs) % periodMs) / (float) periodMs;
        return 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * p);
    }

    private static float cycle(long t, long periodMs, long delayMs) {
        return ((t + delayMs) % periodMs) / (float) periodMs;
    }

    private float driftA(long t) {
        return t < 0 ? 0f : -1.5f + 4f * wave(t, 7000, 0);
    }

    private float driftB(long t) {
        return t < 0 ? 0f : 2f - 4.5f * wave(t, 9000, 0);
    }

    private void paint(Paint p, int index, float alpha) {
        p.setColor(palette[index]);
        p.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, alpha)) * artAlpha));
    }

    private void drawCloud(Canvas canvas, int color, float dx, float dy, float drift) {
        paint(fill, color, 1f);
        canvas.save();
        canvas.translate(dx + drift, dy);
        canvas.drawPath(cloud, fill);
        canvas.restore();
    }

    /** Halo breathing between 10% and 22% over 3.2 s (16% still), rays turning once in 24 s. */
    private void sun(Canvas canvas, long t, float[] sun, float[] rays) {
        float halo = t < 0 ? 0.16f : 0.10f + 0.12f * wave(t, 3200, 0);
        paint(fill, SUN, halo);
        canvas.drawCircle(sun[0], sun[1], sun[2] * 1.9f, fill);
        paint(stroke, RAY, 1f);
        stroke.setStrokeWidth(3f);
        canvas.save();
        if (t >= 0) canvas.rotate(360f * cycle(t, 24000, 0), sun[0], sun[1]);
        canvas.drawLines(rays, stroke);
        canvas.restore();
        paint(fill, SUN, 1f);
        canvas.drawCircle(sun[0], sun[1], sun[2], fill);
    }

    /** Crescent with a soft glow; the full night scene also has its three twinkling stars. */
    private void night(Canvas canvas, long t, Path moon, float cx, float cy, float r, boolean withStars) {
        float glow = t < 0 ? 0.16f : 0.10f + 0.12f * wave(t, 5000, 0);
        paint(fill, MOON, glow);
        canvas.drawCircle(cx, cy, r * 1.6f, fill);
        paint(fill, MOON, 1f);
        canvas.drawPath(moon, fill);
        if (!withStars) return;
        for (int i = 0; i < stars.length; i++) {
            // Alpha 25% to 100% over 2.4 s; the stars are offset by 0, 0.8 and 1.6 s. Still pose: 60%.
            float a = t < 0 ? 0.6f : 0.25f + 0.75f * wave(t, 2400, 800L * i);
            paint(fill, STAR, a);
            canvas.drawPath(stars[i], fill);
        }
    }

    // Rain streak delays in the mockup: 0, -0.45, -0.2, -0.65 s over a 0.9 s loop.
    private static final long[] STREAK_DELAY_MS = {0L, 450L, 200L, 650L};

    private void streak(Canvas canvas, long t, float x, float y, int index) {
        streakAt(canvas, t, x, y, STREAK_DELAY_MS[index]);
    }

    /** 6 units long, slanting -2 in x; travels from (+1, -5) to (-2, +9) in 0.9 s, fading in over 25% and out after. */
    private void streakAt(Canvas canvas, long t, float x, float y, long delayMs) {
        float p = t < 0 ? 0.4f : cycle(t, 900, Math.abs(delayMs));
        float a = p < 0.25f ? p / 0.25f : 1f - (p - 0.25f) / 0.75f;
        float dx = 1f - 3f * p, dy = -5f + 14f * p;
        paint(stroke, RAIN, a);
        stroke.setStrokeWidth(2.4f);
        canvas.drawLine(x + dx, y + dy, x + dx - 2f, y + dy + 6f, stroke);
    }

    // Snow flake delays: 0, -1.1, -2.2, -0.6, -1.7 s over 3.2 s.
    private static final long[] FLAKE_DELAY_MS = {0L, 1100L, 2200L, 600L, 1700L};

    /** Falls from y -5 to +11 in 3.2 s, swaying 0, -2, +1.5; in over the first 20%, then fading out to the end. */
    private void flake(Canvas canvas, long t, float x, float y, int index) {
        float p = t < 0 ? 0.5f : cycle(t, 3200, FLAKE_DELAY_MS[index]);
        float dx, dy;
        if (p < 0.5f) {
            float q = p / 0.5f;
            dx = -2f * q;
            dy = -5f + 8f * q;
        } else {
            float q = (p - 0.5f) / 0.5f;
            dx = -2f + 3.5f * q;
            dy = 3f + 8f * q;
        }
        float a = p < 0.2f ? p / 0.2f : 1f - (p - 0.2f) / 0.8f;
        paint(fill, SNOW, a);
        canvas.drawCircle(x + dx, y + dy, 1.9f, fill);
    }

    // The bolt's opacity over its 4.5 s loop: 85%, up to 100% at 57%, a dip to 20% at 59%, 100% at 61%, back to 85%.
    private static final float[] BOLT_AT = {0f, 0.55f, 0.57f, 0.59f, 0.61f, 0.70f, 1f};
    private static final float[] BOLT_A = {0.85f, 0.85f, 1f, 0.2f, 1f, 0.85f, 0.85f};

    private void bolt(Canvas canvas, long t) {
        float a = 0.85f;
        if (t >= 0) {
            float p = cycle(t, 4500, 0);
            for (int i = 1; i < BOLT_AT.length; i++) {
                if (p <= BOLT_AT[i]) {
                    float q = (p - BOLT_AT[i - 1]) / (BOLT_AT[i] - BOLT_AT[i - 1]);
                    a = BOLT_A[i - 1] + (BOLT_A[i] - BOLT_A[i - 1]) * q;
                    break;
                }
            }
        }
        paint(fill, BOLT, a);
        canvas.drawPath(bolt, fill);
    }

    /** A fog bar drifting -3 to +3 units over 6 s. */
    private void fogLine(Canvas canvas, long t, float x1, float x2, float y, long delayMs) {
        float dx = t < 0 ? 0f : -3f + 6f * wave(t, 6000, delayMs);
        paint(stroke, FOG, 1f);
        stroke.setStrokeWidth(3f);
        canvas.drawLine(x1 + dx, y, x2 + dx, y, stroke);
    }
}
