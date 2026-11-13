package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
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

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx) {
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.strokeColor = strokeColor;
            this.strokePx = strokePx;
            this.radiusPx = radiusPx;
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

    private final Spec spec;
    private Bitmap baked;
    private int bakedW, bakedH, bakedGrain;
    private float bakedRadius;

    GlassSurface(Spec spec) {
        this.spec = spec;
    }

    Spec spec() {
        return spec;
    }

    /** Draws the surface into (left, top, left + w, top + h) of the canvas, with a corner radius no bigger than half the short side. */
    void draw(Canvas c, float left, float top, int w, int h, float radius) {
        if (w <= 0 || h <= 0) return;
        float r = Math.min(radius, Math.min(w, h) / 2f);
        int grain = GlassLook.glassGrain();
        if (baked == null || baked.isRecycled() || w != bakedW || h != bakedH || grain != bakedGrain || r != bakedRadius) {
            baked = bitmap(w, h, r, grain);
            bakedW = w;
            bakedH = h;
            bakedGrain = grain;
            bakedRadius = r;
        }
        if (baked != null) c.drawBitmap(baked, left, top, null);
    }

    private Bitmap bitmap(int w, int h, float r, int grain) {
        String key = spec.fillTop + ":" + spec.fillBottom + ":" + spec.strokeColor + ":" + spec.strokePx + ":" + w + "x" + h
                + ":" + r + ":" + grain;
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
        }
        Bitmap made = bake(w, h, r, grain);
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

    private Bitmap bake(int w, int h, float r, int grain) {
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return null;
        }
        Canvas c = new Canvas(b);   // bitmap-backed, so software: OVERLAY and every other blend mode work
        RectF shape = new RectF(0, 0, w, h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, spec.fillTop, spec.fillBottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(shape, r, r, p);
        Grain.bakeGrain(b, grain, shape, r);
        if (spec.strokePx > 0) {
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
