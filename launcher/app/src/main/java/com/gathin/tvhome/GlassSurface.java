package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
        /**
         * 0 to 1: how much of the frost this surface draws (the dock band's opacity, so a band at 0 percent shows nothing,
         * not an opaque slab of frost under a transparent fill). Applied when drawing, so it is not part of the bake.
         */
        final float frostAlpha;

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, 1f, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, frost, 1f);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost,
             float frostAlpha) {
            this.frostAlpha = Math.max(0f, Math.min(1f, frostAlpha));
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
    /** Bumped by release(): a surface whose own generation is older has stale bakes and frost shaders and drops them. */
    private static volatile int generation;
    /** Every live surface (weakly), so release() can let go of their baked bitmaps and frost references at once. */
    private static final Set<GlassSurface> LIVE = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<GlassSurface, Boolean>()));

    private final Spec spec;
    private Bitmap baked;
    private int bakedW, bakedH, bakedGrain;
    private boolean bakedEdge, bakedFrost;
    private float bakedRadius;
    /** This surface's own shader on the shared frost (its matrix is its screen position), made once per frost bitmap. */
    private BitmapShader frostShader;
    private Bitmap frostFor;
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /**
     * glass_refraction: the bevel, two thin rings just inside the rim that sample the frost with a stronger pull towards
     * the centre (the outer ring the strongest), so the picture bends at the edge. Their shaders are made once per frost
     * bitmap, like the lens's, and only their matrices change per draw.
     */
    private BitmapShader bevelOuter, bevelInner;
    private final Paint bevelOuterPaint = bevelPaint(), bevelInnerPaint = bevelPaint();
    private final float[] mapped = new float[4];
    private final RectF ring = new RectF();
    /** The screen's density, for the bevel's width in dp (read once: a surface has no Context). */
    private static float density;

    private static Paint bevelPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    private static float density() {
        if (density == 0f) density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        return density;
    }
    private final Matrix matrix = new Matrix();
    /** Stretches a stable-size bake onto the live rect (drawSnapped); made once, so a frame allocates nothing. */
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF scaleTo = new RectF();
    private int seenGeneration = generation;

    GlassSurface(Spec spec) {
        this.spec = spec;
        LIVE.add(this);
    }

    Spec spec() {
        return spec;
    }

    /** Whether this surface would show the frost now: the switch is on, the spec allows it and a frost is built. */
    boolean frosted() {
        return spec.frost && spec.frostAlpha > 0f && GlassLook.frost > 0 && Frost.get() != null;
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
        ensureBaked(w, h, r, frost);
        if (baked != null) c.drawBitmap(baked, left, top, null);
    }

    /**
     * Like {@link #draw}, for a surface whose rect changes size a little every frame (the dock band while the grid scrolls
     * or its tiles animate): the bake is made at the size rounded up to a multiple of {@code stepW} x {@code stepH} (and
     * the radius to a whole pixel), so it is made again only when the rect crosses a step, and is drawn squeezed onto the
     * live rect by under a step. Nothing is allocated per frame.
     */
    void drawSnapped(Canvas c, float left, float top, int w, int h, float radius, float screenX, float screenY,
                     int stepW, int stepH) {
        if (w <= 0 || h <= 0) return;
        float r = Math.round(Math.min(radius, Math.min(w, h) / 2f));
        boolean frost = drawFrost(c, left, top, w, h, r, screenX, screenY);
        ensureBaked(snap(w, stepW), snap(h, stepH), r, frost);
        if (baked == null) return;
        if (bakedW == w && bakedH == h) {
            c.drawBitmap(baked, left, top, null);
        } else {
            scaleTo.set(left, top, left + w, top + h);
            c.drawBitmap(baked, null, scaleTo, scalePaint);
        }
    }

    /** {@code size} rounded up to a multiple of {@code step} (the size itself for a step under 2). */
    static int snap(int size, int step) {
        if (step < 2 || size <= 0) return size;
        return (size + step - 1) / step * step;
    }

    private void ensureBaked(int w, int h, float r, boolean frost) {
        dropIfStale();
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
    }

    /** Lets go of the baked bitmap and the frost shader (release() ran since this surface last drew). */
    private void dropIfStale() {
        if (seenGeneration == generation) return;
        seenGeneration = generation;
        drop();
    }

    private void drop() {
        baked = null;
        forgetFrost();
    }

    private void forgetFrost() {
        frostShader = null;
        bevelOuter = bevelInner = null;
        frostFor = null;
        frostPaint.setShader(null);
        bevelOuterPaint.setShader(null);
        bevelInnerPaint.setShader(null);
    }

    /** The slice of the shared frost behind this surface, clipped to its rounded rectangle; false when there is none to draw. */
    private boolean drawFrost(Canvas c, float left, float top, int w, int h, float r, float screenX, float screenY) {
        dropIfStale();
        Bitmap f = Frost.get();
        if (f == null) {
            // The frost was released: do not keep a shader that holds the old bitmap alive.
            if (frostShader != null) forgetFrost();
            return false;
        }
        if (!frosted() || Float.isNaN(screenX) || Float.isNaN(screenY)) return false;
        if (f != frostFor || frostShader == null) {
            frostShader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostPaint.setShader(frostShader);
            bevelOuter = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bevelOuterPaint.setShader(bevelOuter);
            bevelInner = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bevelInnerPaint.setShader(bevelInner);
            frostFor = f;
        }
        boolean bend = GlassLook.refraction;
        float offX = screenX - left, offY = screenY - top;
        float cx = screenX + w / 2f, cy = screenY + h / 2f;
        // Frost pixel -> this canvas: scale it up to the screen (magnified a little about the centre when the glass is a
        // lens), then move the screen's origin to where the canvas starts.
        float lens = bend ? Refraction.LENS : 1f;
        Refraction.mapping(Frost.scaleX(), Frost.scaleY(), lens, lens, cx, cy, offX, offY, mapped);
        matrix.setScale(mapped[0], mapped[1]);
        matrix.postTranslate(mapped[2], mapped[3]);
        frostShader.setLocalMatrix(matrix);
        // The strength sets how much of the frost covers the picture; the surface's own alpha (dock opacity) scales it more.
        int alpha = Frost.paintAlpha(spec.frostAlpha, GlassLook.frost);
        frostPaint.setAlpha(alpha);
        c.drawRoundRect(left, top, left + w, top + h, r, r, frostPaint);
        if (bend) drawBevel(c, left, top, w, h, r, alpha, cx, cy, offX, offY);
        return true;
    }

    /**
     * The liquid-glass bevel: two rings inside the rim (clipped to the rounded rectangle by being strokes of it), drawn from
     * the frost at a stronger magnification than the lens, so what is behind the glass visibly bends at the edge.
     */
    private void drawBevel(Canvas c, float left, float top, int w, int h, float r, int alpha, float cx, float cy,
                           float offX, float offY) {
        float d = density();
        float band = Refraction.bandPx(d, Math.min(w, h));
        float bend = Math.min(Refraction.BEND_DP * d, Math.min(w, h) / 2f * 0.4f);
        drawRing(c, bevelOuter, bevelOuterPaint, left, top, w, h, r, band, bend, alpha, cx, cy, offX, offY);
        drawRing(c, bevelInner, bevelInnerPaint, left, top, w, h, r, band / 2f, bend * Refraction.INNER_SHARE, alpha, cx, cy,
                offX, offY);
    }

    private void drawRing(Canvas c, BitmapShader shader, Paint paint, float left, float top, int w, int h, float r,
                          float width, float bendPx, int alpha, float cx, float cy, float offX, float offY) {
        float magX = Refraction.magFor(w / 2f, bendPx) * Refraction.LENS;
        float magY = Refraction.magFor(h / 2f, bendPx) * Refraction.LENS;
        Refraction.mapping(Frost.scaleX(), Frost.scaleY(), magX, magY, cx, cy, offX, offY, mapped);
        matrix.setScale(mapped[0], mapped[1]);
        matrix.postTranslate(mapped[2], mapped[3]);
        shader.setLocalMatrix(matrix);
        paint.setAlpha(Math.round(alpha * 0.85f));
        paint.setStrokeWidth(width);
        float half = width / 2f;
        ring.set(left + half, top + half, left + w - half, top + h - half);
        float rr = Math.max(0f, r - half);
        c.drawRoundRect(ring, rr, rr, paint);
    }

    private Bitmap bitmap(int w, int h, float r, int grain, boolean edge, boolean frost) {
        String key = cacheKey(spec, w, h, r, grain, edge, frost);
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

    /**
     * The cache key of one bake: every input of {@link #bake}. A look that changes any of them (grain, edge, frost, the
     * fill, the opacity-scaled alpha of a dock band, the size) must bake again, not hit an older bitmap.
     */
    static String cacheKey(Spec spec, int w, int h, float r, int grain, boolean edge, boolean frost) {
        return spec.fillTop + ":" + spec.fillBottom + ":" + spec.strokeColor + ":" + spec.strokePx + ":" + spec.light + ":" + w + "x" + h
                + ":" + r + ":" + grain + ":" + edge + ":" + frost;
    }

    private static long bytes(Bitmap b) {
        return b.getAllocationByteCount();
    }

    /**
     * Drops every baked bitmap (onTrimMemory), those held by live surfaces included; the next draw bakes again. Runs on
     * the main thread, like every draw.
     */
    static void release() {
        generation++;
        synchronized (CACHE) {
            CACHE.clear();
            cached = 0;
        }
        GlassSurface[] all;
        synchronized (LIVE) {
            all = LIVE.toArray(new GlassSurface[0]);
        }
        for (GlassSurface surface : all) {
            surface.seenGeneration = generation;
            surface.drop();
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
        // Over the frost the surface is only a light tint (Frost.fillOnFrost): the blurred wallpaper is the glass. White
        // glass (already a sheen) is drawn as designed.
        int top = frost ? Frost.fillOnFrost(spec.fillTop) : spec.fillTop;
        int bottom = frost ? Frost.fillOnFrost(spec.fillBottom) : spec.fillBottom;
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRoundRect(shape, r, r, p);
        if (frost && (Frost.isDarkFill(spec.fillTop) || Frost.isDarkFill(spec.fillBottom))) {
            // A soft white sheen, brightest at the top, so dark glass reads as glass and not as a grey plate. It scales with
            // spec.light, which the dock band sets to its opacity: a band at 0 percent stays invisible.
            p.setShader(new LinearGradient(0, 0, 0, h, (Math.round(Frost.SHEEN_TOP * spec.light) << 24) | 0xFFFFFF,
                    (Math.round(Frost.SHEEN_BOTTOM * spec.light) << 24) | 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(shape, r, r, p);
        }
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
