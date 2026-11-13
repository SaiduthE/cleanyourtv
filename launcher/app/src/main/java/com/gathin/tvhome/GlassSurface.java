package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One glass surface: what a panel, a pill, a menu or the dock band is made of, drawn from a bitmap baked once for its size
 * (fill, grain, rim) and shared by every surface of that size. Nothing is drawn per frame but one bitmap, so the look
 * costs no more than the plain gradient it replaces. {@link GlassDrawable} puts it behind a view, DockDecoration behind
 * the dock.
 */
final class GlassSurface {

    /** What a surface looks like before the switches add to it: a vertical gradient fill, a stroke and a corner radius. */
    static final class Spec {
        final int fillTop, fillBottom, strokeColor;
        final float strokePx, radiusPx;
        /** 0 to 1: how much edge light the surface takes (a faint dock band takes less). */
        final float light;
        /** Whether the surface shows the shared frost behind it (glass_frost); false for a card that sits on the tile art. */
        final boolean frost;

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, 1f, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost) {
            this.frost = frost;
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.strokeColor = strokeColor;
            this.strokePx = strokePx;
            this.radiusPx = radiusPx;
            this.light = light;
        }

        /** A surface with a hairline has a rim to light; one without (a borderless tile) keeps none. */
        boolean hasRim() {
            return strokePx > 0;
        }

        /** A fill that hides what is behind it (cards), as opposed to white glass. */
        boolean opaque() {
            return (fillTop >>> 24) >= 0xE0 && (fillBottom >>> 24) >= 0xE0;
        }
    }

    /** Baked bitmaps, shared by size and look, least recently used first; capped so many sizes cannot pile up. */
    private static final long BUDGET_BYTES = 6L * 1024 * 1024;
    private static final LinkedHashMap<String, Bitmap> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static long cached;

    /** A frost-backed opaque card keeps this much of its fill over the frost, so text stays readable on a bright picture. */
    private static final float FILL_OVER_FROST = 0.69f;

    private final Spec spec;
    private Bitmap baked;
    private int bakedW, bakedH, bakedGrain;
    private boolean bakedEdge, bakedFrost;
    private float bakedRadius;
    /** This surface's own shader on the shared frost (its matrix is its screen position), made once per frost bitmap. */
    private BitmapShader frostShader;
    private Bitmap frostFor;
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();

    GlassSurface(Spec spec) {
        this.spec = spec;
    }

    Spec spec() {
        return spec;
    }

    /** Whether this surface would show the frost now: the switch is on, the spec allows it and a frost is built. */
    boolean frosted() {
        return spec.frost && GlassLook.frost > 0 && Frost.get() != null;
    }

    /**
     * Draws the surface into (left, top, left + w, top + h) of the canvas, with a corner radius no bigger than half the
     * short side. {@code screenX} and {@code screenY} are where (left, top) is on the screen, which picks the slice of the
     * shared frost behind it; NaN draws no frost.
     */
    void draw(Canvas c, float left, float top, int w, int h, float radius, float screenX, float screenY) {
        if (w <= 0 || h <= 0) return;
        float r = Math.min(radius, Math.min(w, h) / 2f);
        boolean frost = drawFrost(c, left, top, w, h, r, screenX, screenY);
        int grain = GlassLook.glassGrain();
        boolean edge = GlassLook.edge && spec.hasRim();
        if (baked == null || baked.isRecycled() || w != bakedW || h != bakedH || grain != bakedGrain || r != bakedRadius
                || edge != bakedEdge || frost != bakedFrost) {
            baked = bitmap(w, h, r, grain, edge, frost);
            bakedEdge = edge;
            bakedFrost = frost;
            bakedW = w;
            bakedH = h;
            bakedGrain = grain;
            bakedRadius = r;
        }
        if (baked != null) c.drawBitmap(baked, left, top, null);
    }

    /** The slice of the shared frost behind this surface, clipped to its rounded rectangle; false when there is none to draw. */
    private boolean drawFrost(Canvas c, float left, float top, int w, int h, float r, float screenX, float screenY) {
        if (!frosted() || Float.isNaN(screenX) || Float.isNaN(screenY)) return false;
        Bitmap f = Frost.get();
        if (f == null) return false;
        if (f != frostFor || frostShader == null) {
            frostShader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostPaint.setShader(frostShader);
            frostFor = f;
        }
        // Frost pixel -> this canvas: scale it up to the screen, then move the screen's origin to where the canvas starts.
        matrix.setScale(Frost.scaleX(), Frost.scaleY());
        matrix.postTranslate(-(screenX - left), -(screenY - top));
        frostShader.setLocalMatrix(matrix);
        c.drawRoundRect(left, top, left + w, top + h, r, r, frostPaint);
        return true;
    }

    private Bitmap bitmap(int w, int h, float r, int grain, boolean edge, boolean frost) {
        String key = spec.fillTop + ":" + spec.fillBottom + ":" + spec.strokeColor + ":" + spec.strokePx + ":" + spec.light + ":" + w + "x" + h
                + ":" + r + ":" + grain + ":" + edge + ":" + frost;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
        }
        Bitmap made = bake(w, h, r, grain, edge, frost);
        if (made == null) return null;
        synchronized (CACHE) {
            Bitmap old = CACHE.put(key, made);
            if (old != null) cached -= bytes(old);
            cached += bytes(made);
            for (Iterator<Map.Entry<String, Bitmap>> it = CACHE.entrySet().iterator(); cached > BUDGET_BYTES && it.hasNext(); ) {
                Bitmap eldest = it.next().getValue();
                if (eldest == made) continue;   // never drop the one just made, even when it alone is over the budget
                cached -= bytes(eldest);
                it.remove();   // not recycled: a view may still be drawing it
            }
        }
        return made;
    }

    private static long bytes(Bitmap b) {
        return b.getAllocationByteCount();
    }

    /** Drops every baked bitmap (onTrimMemory); the next draw bakes again. */
    static void release() {
        synchronized (CACHE) {
            CACHE.clear();
            cached = 0;
        }
    }

    private Bitmap bake(int w, int h, float r, int grain, boolean edge, boolean frost) {
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas c = new Canvas(b);   // bitmap-backed, so software: OVERLAY and every other blend mode work
        RectF shape = new RectF(0, 0, w, h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Over frost a card lets some of it through; white glass (already translucent) is drawn as designed.
        float keep = frost && spec.opaque() ? FILL_OVER_FROST : 1f;
        p.setShader(new LinearGradient(0, 0, 0, h, GlassEdge.scaleAlpha(spec.fillTop, keep),
                GlassEdge.scaleAlpha(spec.fillBottom, keep), Shader.TileMode.CLAMP));
        c.drawRoundRect(shape, r, r, p);
        Grain.bakeGrain(b, grain, shape, r);
        if (edge && spec.hasRim()) {
            GlassEdge.draw(c, shape, r, spec.strokePx, spec.light, GlassEdge.RIM_START, GlassEdge.RIM_END);
        } else if (spec.strokePx > 0) {
            // As a GradientDrawable strokes: centred on the edge, so inset by half its width.
            float half = spec.strokePx / 2f;
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(spec.strokePx);
            p.setColor(spec.strokeColor);
            c.drawRoundRect(new RectF(half, half, w - half, h - half), Math.max(0, r - half), Math.max(0, r - half), p);
        }
        return b;
    }
}
