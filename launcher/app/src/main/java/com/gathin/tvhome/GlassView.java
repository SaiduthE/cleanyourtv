package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: the white glow stays faint, a coloured one needs more alpha to read as warm light. */
    private int glowAlpha = 0x30;
    /** glass_style rim or halo: no sheen and no fill, only the crisp rim (and, for halo, a lighter outer glow). */
    private boolean flat;
    private boolean halo;
    /** A carried tile in reorder mode: the rim is the accent itself, and thicker, instead of glass. */
    private boolean carrying;
    private int plainRim = 0xCCFFFFFF;
    private float baseRimDp = 1.5f;
    /** Film grain over the slab, baked for the size it was drawn at (grain above 0, glass styles only). */
    private Bitmap grainLayer;
    private int grainW, grainH, grainPercent;
    private float grainRadius;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame. That path is
        // software-rendered on the MT5895 and is what makes the highlight crawl.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x30;
            plainRim = 0xCCFFFFFF;
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The rim is the accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            plainRim = 0xE6000000 | (r << 16) | (g << 8) | b;
        }
        applyRim();
    }

    /** Reorder mode: the tile under the glass is picked up, so the rim shows the accent in full and thicker. */
    void setCarrying(boolean on) {
        carrying = on;
        applyRim();
    }

    private void applyRim() {
        rim.setColor(carrying && glowRgb != 0xFFFFFF ? 0xFF000000 | glowRgb : plainRim);
        rim.setStrokeWidth((carrying ? 3f : baseRimDp) * density);
        invalidate();
    }

    /**
     * glass_edge on a glass slab (GlassEdge, shared with every other glass surface): the rim runs from the full rim colour
     * at the top left to about a third of it at the bottom right. A carried tile, and the rim and halo styles, keep their
     * plain rim.
     */
    private boolean edgeLight() {
        return GlassLook.edge && !flat && !carrying;
    }

    /** One of Config's GLASS_* styles. */
    void setStyle(String style) {
        flat = Config.GLASS_RIM.equals(style) || Config.GLASS_HALO.equals(style);
        halo = Config.GLASS_HALO.equals(style);
        baseRimDp = flat ? 2f : 1.5f;
        applyRim();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x3DFFFFFF, 0x12FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    /** The grain for the slab's current size, baked once and kept until the size, the radius or the grain changes. */
    private Bitmap grainFor(int w, int h, float inset) {
        int percent = GlassLook.glassGrain();
        if (percent <= 0 || flat) return null;
        if (grainLayer == null || w != grainW || h != grainH || percent != grainPercent || radius != grainRadius) {
            grainLayer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Grain.bakeGrain(grainLayer, percent, new RectF(inset, inset, w - inset, h - inset), radius);
            grainW = w;
            grainH = h;
            grainPercent = percent;
            grainRadius = radius;
        }
        return grainLayer;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Outer glow: thin rings across the inset, each a little fainter, so it fades out smoothly. Three wide
        // strokes left visible bands once the glow took an app's colour at a stronger alpha. The hardware layer
        // caches the result, so the rings cost one draw, not one per frame of the spring.
        if (!flat || halo) {
            int rings = 12;
            float step = inset / rings;
            int alpha = flat ? glowAlpha / 2 : glowAlpha;
            glow.setStrokeWidth(step + 0.5f);
            for (int i = 0; i < rings; i++) {
                float o = step * (i + 0.5f);
                float t = 1f - (float) i / rings;
                glow.setColor(Math.round(alpha * t) << 24 | glowRgb);
                canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
            }
        }
        if (flat) {
            canvas.drawRoundRect(rect, radius, radius, rim);
            return;
        }

        canvas.drawRoundRect(rect, radius, radius, fill);
        Bitmap grain = grainFor(getWidth(), getHeight(), inset);
        if (grain != null) canvas.drawBitmap(grain, 0, 0, null);

        // Sheen across the top 45%: clipRect stays on the GPU (clipPath did not on the MT5895) and
        // the round rect under it keeps the top corners rounded with a flat bottom edge.
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();

        if (edgeLight()) {
            int a = plainRim >>> 24;
            GlassEdge.draw(canvas, rect, radius, rim.getStrokeWidth(), 1f, plainRim,
                    ((a * 35 / 100) << 24) | (plainRim & 0xFFFFFF));
            return;
        }
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
