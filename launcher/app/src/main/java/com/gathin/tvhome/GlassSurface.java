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
        /** True for the dock band, which scales itself by dock_opacity; every other surface follows GlassLook.opacity. */
        final boolean ownOpacity;
        /**
         * True for a panel, popup or menu that floats over Home's own views: the frost is what it is made of, so it is drawn
         * fully opaque (it replaces what is behind, or the sharp Home UI would show through the glass), and the glass
         * opacity only scales its tint, sheen and rim. False for a surface in place (the dock band, a pill, a card).
         */
        final boolean floating;

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, 1f, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, true);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, frost, 1f, false, false);
        }

        Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost,
             float frostAlpha) {
            this(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, frost, frostAlpha, true, false);
        }

        private Spec(int fillTop, int fillBottom, int strokeColor, float strokePx, float radiusPx, float light, boolean frost,
                     float frostAlpha, boolean ownOpacity, boolean floating) {
            this.ownOpacity = ownOpacity;
            this.floating = floating;
            this.frostAlpha = Math.max(0f, Math.min(1f, frostAlpha));
            this.frost = frost;
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.strokeColor = strokeColor;
            this.strokePx = strokePx;
            this.radiusPx = radiusPx;
            this.light = light;
        }

        /** The same look as a floating surface: a panel, popup or menu over Home's views (frost fully opaque). */
        Spec floating() {
            return new Spec(fillTop, fillBottom, strokeColor, strokePx, radiusPx, light, frost, frostAlpha, ownOpacity, true);
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
    private BitmapShader bevelShader;
    private final Paint bevelPaint = bevelPaint();
    private final Paint tintPaint = tintPaint();
    private final float[] mapped = new float[4];
    /** How much the host view is scaled on screen (a focus zoom, a popup opening): the frost is un-scaled to match. */
    private float viewScaleX = 1f, viewScaleY = 1f;
    private final RectF ring = new RectF();
    /** The screen's density, for the bevel's width in dp (read once: a surface has no Context). */
    private static float density;

    private static Paint bevelPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setStyle(Paint.Style.STROKE);
        return p;
    }

    private static Paint tintPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        return p;
    }

    /**
     * The scale of the view this surface is drawn into, when it is not 1 (GlassDrawable sets it every draw). The frost slice
     * is positioned on the screen, but the canvas it is drawn on is scaled with the view, so the slice is divided by it.
     */
    void setViewScale(float sx, float sy) {
        viewScaleX = sx > 0.01f ? sx : 1f;
        viewScaleY = sy > 0.01f ? sy : 1f;
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
        if (baked != null) c.drawBitmap(baked, left, top, opacityPaint(frost));
    }

    /** The glass opacity (dock_opacity, launcher-wide) as one alpha over the whole surface: tint, sheen, rim and grain. */
    private float opacity() {
        return spec.ownOpacity ? 1f : GlassLook.surfaceOpacity();
    }

    /**
     * The opacity of the baked fill, sheen, rim and grain. A floating panel with no frost under it (frost off, none built)
     * keeps its fill whole: with the frost gone the fill is all that hides the Home UI behind it.
     */
    private float opacity(boolean frostDrawn) {
        return spec.floating && !frostDrawn ? 1f : opacity();
    }

    private Paint opacityPaint(boolean frostDrawn) {
        float o = opacity(frostDrawn);
        if (o >= 1f) return null;
        scalePaint.setAlpha(Math.round(255 * o));
        return scalePaint;
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
            c.drawBitmap(baked, left, top, opacityPaint(frost));
        } else {
            scaleTo.set(left, top, left + w, top + h);
            Paint p = opacityPaint(frost);
            if (p == null) {
                scalePaint.setAlpha(255);
                p = scalePaint;
            }
            c.drawBitmap(baked, null, scaleTo, p);
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
        bevelShader = null;
        frostFor = null;
        frostPaint.setShader(null);
        bevelPaint.setShader(null);
    }

    /**
     * Everything drawn under the baked fill: the slice of the shared frost behind this surface (lens and bevel), clipped to
     * its rounded rectangle, then the tint over it. Returns whether the frost was drawn.
     */
    private boolean drawFrost(Canvas c, float left, float top, int w, int h, float r, float screenX, float screenY) {
        dropIfStale();
        Bitmap f = Frost.get();
        // The frost was released: do not keep a shader that holds the old bitmap alive.
        if (f == null && frostShader != null) forgetFrost();
        boolean drawn = f != null && frosted() && !Float.isNaN(screenX) && !Float.isNaN(screenY);
        if (drawn) drawSlice(f, c, left, top, w, h, r, screenX, screenY);
        drawTint(c, left, top, w, h, r);
        return drawn;
    }

    /**
     * glass_tint: a black layer over the frost, in the same rounded rectangle, under the baked fill and rim. Drawn with a
     * paint alpha, so it is not part of the bake (and not of its cache key). A dock band scales it by its own opacity.
     */
    private void drawTint(Canvas c, float left, float top, int w, int h, float r) {
        if (!spec.frost || GlassLook.tint <= 0) return;
        float k = spec.frostAlpha * (spec.floating ? 1f : opacity());
        int alpha = Math.round(GlassLook.tintAlpha(GlassLook.tint) * k);
        if (alpha <= 0) return;
        tintPaint.setAlpha(alpha);
        c.drawRoundRect(left, top, left + w, top + h, r, r, tintPaint);
    }

    /** Points a shader on the frost at the screen: frost px to this canvas, magnified by (magX, magY) about the surface's centre. */
    private void place(BitmapShader shader, float magX, float magY, float cx, float cy, float offX, float offY) {
        Refraction.mapping(Frost.scaleX(), Frost.scaleY(), magX, magY, cx, cy, offX, offY, mapped);
        // The screen is un-scaled by the view's own scale, so the slice stays under the glass while the view zooms.
        matrix.setScale(mapped[0] / viewScaleX, mapped[1] / viewScaleY);
        matrix.postTranslate(mapped[2] / viewScaleX, mapped[3] / viewScaleY);
        shader.setLocalMatrix(matrix);
    }

    /**
     * The frost behind the surface: the lens over the middle, and the bevel as {@link Refraction#STEPS} thin rings just inside
     * the rim, each sampling the frost with its own pull so the picture bends smoothly towards the centre and joins the lens
     * with no step. A surface whose frost is translucent gets the lens only inside the rings and the rings side by side, so
     * no pixel is covered twice (a double cover is the lighter strip a translucent bevel used to leave along the edge).
     */
    private void drawSlice(Bitmap f, Canvas c, float left, float top, int w, int h, float r, float screenX, float screenY) {
        if (f != frostFor || frostShader == null) {
            frostShader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostPaint.setShader(frostShader);
            bevelShader = new BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            bevelPaint.setShader(bevelShader);
            frostFor = f;
        }
        boolean bend = GlassLook.refraction;
        // The screen position of the canvas origin and of the surface's centre (the canvas may be scaled with its view).
        float offX = screenX - left * viewScaleX, offY = screenY - top * viewScaleY;
        float cx = screenX + w * viewScaleX / 2f, cy = screenY + h * viewScaleY / 2f;
        float lens = bend ? Refraction.LENS : 1f;
        place(frostShader, lens, lens, cx, cy, offX, offY);
        // The strength sets how much of the frost covers the picture, and the surface's own alpha (dock opacity) scales it
        // more. A floating panel is the frost: it replaces what is behind, so it never lets the Home UI show through.
        int alpha = spec.floating ? 255 : Frost.paintAlpha(spec.frostAlpha * opacity(), GlassLook.frost);
        frostPaint.setAlpha(alpha);
        float d = density();
        float shortSide = Math.min(w, h);
        int ringPx = Math.max(1, Math.round(Refraction.bandPx(d, shortSide) / Refraction.STEPS));
        float band = ringPx * Refraction.STEPS;
        boolean bevel = bend && band <= shortSide / 4f;
        if (bevel && alpha < 255) {
            float inner = Math.max(0f, r - band);
            c.drawRoundRect(left + band, top + band, left + w - band, top + h - band, inner, inner, frostPaint);
        } else {
            c.drawRoundRect(left, top, left + w, top + h, r, r, frostPaint);
        }
        if (bevel) drawBevel(c, left, top, w, h, r, alpha, ringPx, band, cx, cy, offX, offY, d);
    }

    /** The rings of the bevel, outermost first: each {@code ringPx} wide, with the magnification that gives the pull at its depth. */
    private void drawBevel(Canvas c, float left, float top, int w, int h, float r, int alpha, int ringPx, float band,
                           float cx, float cy, float offX, float offY, float d) {
        float bend = Math.min(Refraction.BEND_DP * d, Math.min(w, h) / 2f * 0.4f);
        bevelPaint.setAlpha(alpha);
        bevelPaint.setStrokeWidth(ringPx);
        for (int k = 0; k < Refraction.STEPS; k++) {
            float inset = k * ringPx;
            float depth = inset + ringPx / 2f;
            place(bevelShader, Refraction.ringMag(w / 2f, depth, band, bend), Refraction.ringMag(h / 2f, depth, band, bend),
                    cx, cy, offX, offY);
            ring.set(left + depth, top + depth, left + w - depth, top + h - depth);
            float rr = Math.max(0f, r - depth);
            c.drawRoundRect(ring, rr, rr, bevelPaint);
        }
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
