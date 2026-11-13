package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The glass_edge look, in one place for every glass surface (GlassSurface's bake and the focus glass): a rim that runs
 * from about 40 percent white at the top left to about 8 percent at the bottom right, a 1 px line of light just inside
 * the top edge, and a very soft shadow inside the bottom edge. Plain Canvas drawing; callers draw it once.
 */
final class GlassEdge {

    static final int RIM_START = 0x66FFFFFF;      // about 40 percent white
    static final int RIM_END = 0x14FFFFFF;        // about 8 percent
    private static final int HIGHLIGHT = 0x59FFFFFF;
    private static final int SHADOW = 0x1F000000;

    private GlassEdge() { }

    /** A colour with its alpha scaled by {@code f} (0 to 1). */
    static int scaleAlpha(int argb, float f) {
        return (Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, f))) << 24) | (argb & 0xFFFFFF);
    }

    /**
     * Draws the edge light of a rounded rectangle.
     *
     * @param shape  the rectangle the surface fills
     * @param r      its corner radius
     * @param sw     the rim's width in px (at least 1)
     * @param light  0 to 1, how much of the light to draw (a faint dock band takes less)
     * @param start  the rim's colour at the top left
     * @param end    and at the bottom right
     */
    static void draw(Canvas c, RectF shape, float r, float sw, float light, int start, int end) {
        sw = Math.max(1f, sw);
        float half = sw / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // The soft shadow first, so the rim and the line of light stay on top of it.
        float h = shape.height();
        float shadowH = Math.min(h * 0.22f, 24f);
        RectF inner = new RectF(shape.left + sw, shape.top + sw, shape.right - sw, shape.bottom - sw);
        float innerR = Math.max(0f, r - sw);
        p.setShader(new LinearGradient(0, inner.bottom - shadowH, 0, inner.bottom, 0x00000000,
                scaleAlpha(SHADOW, light), Shader.TileMode.CLAMP));
        c.drawRoundRect(inner, innerR, innerR, p);

        // The rim, brightest where the light comes from.
        p.setShader(new LinearGradient(shape.left, shape.top, shape.right, shape.bottom,
                scaleAlpha(start, light), scaleAlpha(end, light), Shader.TileMode.CLAMP));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        float rimR = Math.max(0f, r - half);
        c.drawRoundRect(new RectF(shape.left + half, shape.top + half, shape.right - half, shape.bottom - half), rimR, rimR, p);

        // A 1 px line of light just inside the top edge, fading out towards the corners.
        float x0 = shape.left + r + sw, x1 = shape.right - r - sw;
        if (x1 - x0 > 4f) {
            int hi = scaleAlpha(HIGHLIGHT, light);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(x0, 0, x1, 0, new int[] {hi & 0xFFFFFF, hi, hi, hi & 0xFFFFFF},
                    new float[] {0f, 0.2f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(x0, shape.top + sw, x1, shape.top + sw + 1f, p);
        }
    }
}
