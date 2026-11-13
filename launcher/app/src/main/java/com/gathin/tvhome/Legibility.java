package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/**
 * Text that stays readable over pictures that change (the wallpaper rotates every minute, the Top Shelf shows photos and
 * show artwork), the way tvOS and Google TV do it: a soft shadow on the text itself, and behind a block of text a soft
 * scrim whose strength follows how bright the picture really is there. One place for both, so the header (clock,
 * greeting, date, weather chip), the scene clock's date line and the Top Shelf's text agree.
 *
 * The pure maths (shadow size from text size, picture brightness to scrim strength) has no Android in it and is
 * JVM-tested; {@link Scrim} is the one view that applies it. The brightness comes from the frost the glass already
 * publishes ({@link Frost#get}, a small blurred copy of the picture on screen), so nothing is decoded again.
 */
final class Legibility {

    private Legibility() { }

    // ---- the text shadow (pure) -----------------------------------------------------------------------------------

    /** The blur radius in dp: 8 for text up to 12 sp, growing to 12 for text of 40 sp and more (the clock). */
    static float shadowRadiusDp(float textSp) {
        float t = clamp01((textSp - 12f) / 28f);
        return 8f + 4f * t;
    }

    /** The shadow's drop in dp: 1 for small text, 2 from 20 sp up. */
    static float shadowDyDp(float textSp) {
        return textSp > 20f ? 2f : 1f;
    }

    /** The shadow's black, as an alpha 0..1: 0.55 for small text (it needs the most help), 0.42 for the biggest. */
    static float shadowAlpha(float textSp) {
        float t = clamp01((textSp - 16f) / 24f);
        return 0.55f - 0.13f * t;
    }

    /** The shadow's ARGB for a text size. */
    static int shadowColor(float textSp) {
        return Math.round(255f * shadowAlpha(textSp)) << 24;
    }

    // ---- brightness to scrim (pure) -------------------------------------------------------------------------------

    /** The weakest and strongest scrim: never invisible on a bright photo, never heavy on a dark one. */
    static final float SCRIM_MIN = 0.18f, SCRIM_MAX = 0.55f;
    /** The picture brightness (0..1) at which the scrim is at its weakest, and at its strongest. */
    static final float LUMA_DARK = 0.25f, LUMA_BRIGHT = 0.80f;
    /** What the scrim is when there is no frost to measure (glass_frost 0, or none built yet). */
    static final float SCRIM_UNKNOWN = 0.34f;
    /** How long a change of strength takes. */
    static final int FADE_MS = 300;
    /** The frost is blended toward this dark (Frost's DARK_R,G,B) by {@link Frost#darken}; undone in {@link #sourceLuma}. */
    private static final float FROST_DARK_LUMA = (0.299f * 16 + 0.587f * 18 + 0.114f * 24) / 255f;

    /** Perceived brightness 0..1 of an RGB colour (Rec. 601 weights, alpha ignored). */
    static float luma(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
    }

    /**
     * The brightness of a region from its samples: mostly the mean, a little of the brightest sample, since a bright
     * patch (the sun, a window) under a word is what loses it.
     */
    static float regionLuma(float mean, float max) {
        return clamp01(0.65f * mean + 0.35f * max);
    }

    /** The brightness the picture itself had, from the frost's (which is blended toward dark by {@code darken}, 0..0.45). */
    static float sourceLuma(float frostLuma, float darken) {
        float keep = 1f - clamp01(darken);
        if (keep < 0.05f) return clamp01(frostLuma);
        return clamp01((frostLuma - FROST_DARK_LUMA * (1f - keep)) / keep);
    }

    /** The scrim's strength for a picture brightness: 0.18 up to a dark picture, 0.55 from a bright one, eased between. */
    static float scrimAlpha(float sourceLuma) {
        float e = ShelfGradients.ease((sourceLuma - LUMA_DARK) / (LUMA_BRIGHT - LUMA_DARK));
        return SCRIM_MIN + (SCRIM_MAX - SCRIM_MIN) * e;
    }

    /** The scrim's strength for a region's frost brightness, or {@link #SCRIM_UNKNOWN} when it is NaN (no frost). */
    static float scrimFor(float frostLuma, float darken) {
        if (Float.isNaN(frostLuma)) return SCRIM_UNKNOWN;
        return scrimAlpha(sourceLuma(frostLuma, darken));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ---- applying the shadow --------------------------------------------------------------------------------------

    /** The soft shadow for a TextView, sized from its current text size. Call again after the size changes. */
    static void shadow(TextView t) {
        shadow(t, t.getTextSize() / t.getResources().getDisplayMetrics().scaledDensity);
    }

    /** The soft shadow for a TextView of {@code textSp} sp. */
    static void shadow(TextView t, float textSp) {
        float density = t.getResources().getDisplayMetrics().density;
        t.setShadowLayer(shadowRadiusDp(textSp) * density, 0f, shadowDyDp(textSp) * density, shadowColor(textSp));
    }

    /** No shadow (dark text on a light pill). */
    static void clearShadow(TextView t) {
        t.setShadowLayer(0f, 0f, 0f, 0);
    }

    /** The soft shadow for text a view draws itself; {@code pxPerDp} is what a dp measures on that canvas. */
    static void shadow(Paint paint, float textSp, float pxPerDp) {
        paint.setShadowLayer(shadowRadiusDp(textSp) * pxPerDp, 0f, shadowDyDp(textSp) * pxPerDp, shadowColor(textSp));
    }

    // ---- the scrim ------------------------------------------------------------------------------------------------

    /**
     * Mean and brightest brightness of a screen region of the frost bitmap, from a small grid of samples; NaN when
     * there is no frost. {@code left..bottom} are screen pixels, {@code scaleX/Y} screen pixels per frost pixel.
     */
    static float regionLuma(Bitmap frost, float left, float top, float right, float bottom, float scaleX, float scaleY) {
        if (frost == null || frost.isRecycled() || scaleX <= 0f || scaleY <= 0f) return Float.NaN;
        int w = frost.getWidth(), h = frost.getHeight();
        int x0 = clampInt((int) Math.floor(left / scaleX), 0, w - 1), x1 = clampInt((int) Math.ceil(right / scaleX) - 1, 0, w - 1);
        int y0 = clampInt((int) Math.floor(top / scaleY), 0, h - 1), y1 = clampInt((int) Math.ceil(bottom / scaleY) - 1, 0, h - 1);
        if (x1 < x0) x1 = x0;
        if (y1 < y0) y1 = y0;
        final int cols = 6, rows = 4;
        float sum = 0f, max = 0f;
        int n = 0;
        for (int j = 0; j < rows; j++) {
            int y = y0 + Math.round((y1 - y0) * (j + 0.5f) / rows);
            for (int i = 0; i < cols; i++) {
                int x = x0 + Math.round((x1 - x0) * (i + 0.5f) / cols);
                float l = luma(frost.getPixel(x, y));
                sum += l;
                if (l > max) max = l;
                n++;
            }
        }
        return regionLuma(sum / n, max);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * A soft elliptical shade, darkest at its centre and gone at its edge on the Top Shelf's eased stops (no visible
     * edge), whose strength follows the brightness of the picture under it: it listens to {@link Frost} and eases to
     * the new strength in {@link #FADE_MS}. The view is as large as the screen and sits at its origin (the frost is
     * screen-aligned); only the ellipse is drawn. The view's own alpha stays free for fading it in and out.
     */
    static final class Scrim extends View implements Frost.Listener {
        private final Paint paint = new Paint(Paint.DITHER_FLAG);
        private final Matrix matrix = new Matrix();
        private final int fadeMs;
        private final float[] positions = ShelfGradients.positions();
        private final int[] colors = ShelfGradients.colors(ShelfGradients.TINT, ShelfGradients.fadeAlphas(1f));
        private final RadialGradient unit;
        private float cx, cy, rx, ry;
        private float level = SCRIM_UNKNOWN, target = SCRIM_UNKNOWN;
        private ValueAnimator animator;

        /** @param fadeMs how long a change of strength takes (0 for none: reduced motion or animations off) */
        Scrim(Context context, int fadeMs) {
            super(context);
            this.fadeMs = fadeMs;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setFocusable(false);
            forceHasOverlappingRendering(false);
            unit = new RadialGradient(0f, 0f, 1f, colors, positions, Shader.TileMode.CLAMP);
            paint.setShader(unit);
            Frost.listen(this);
        }

        /** The ellipse in screen pixels: its centre and its two radii. Measures the picture there again. */
        void setEllipse(float cx, float cy, float rx, float ry) {
            if (cx == this.cx && cy == this.cy && rx == this.rx && ry == this.ry) return;
            this.cx = cx;
            this.cy = cy;
            this.rx = Math.max(1f, rx);
            this.ry = Math.max(1f, ry);
            matrix.setScale(this.rx, this.ry);
            matrix.postTranslate(cx, cy);
            unit.setLocalMatrix(matrix);
            retarget(false);
            invalidate();
        }

        /** The picture behind changed (a new photo, a show's artwork, the wallpaper): ease to the strength it needs. */
        @Override
        public void onFrostChanged() {
            retarget(true);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            retarget(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (animator != null) animator.cancel();
            super.onDetachedFromWindow();
        }

        /** The strength the picture under the ellipse calls for, now (what the scrim is easing to). */
        float target() {
            return target;
        }

        private void retarget(boolean animate) {
            if (rx <= 1f && ry <= 1f) return;
            // The middle 60 percent of the ellipse is where the shade is strong, so that is the picture that matters.
            float lum = regionLuma(Frost.get(), cx - 0.6f * rx, cy - 0.6f * ry, cx + 0.6f * rx, cy + 0.6f * ry,
                    Frost.scaleX(), Frost.scaleY());
            float next = scrimFor(lum, Frost.darken(GlassLook.frost));
            if (next == target) return;
            target = next;
            if (animator != null) animator.cancel();
            if (!animate || fadeMs <= 0 || !isAttachedToWindow() || getWidth() == 0) {
                level = next;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(level, next);
            animator.setDuration(fadeMs);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                level = (Float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (level <= 0f) return;
            paint.setAlpha(Math.round(255f * level));
            canvas.drawRect(Math.max(0f, cx - rx), Math.max(0f, cy - ry), Math.min(getWidth(), cx + rx),
                    Math.min(getHeight(), cy + ry), paint);
        }
    }
}
