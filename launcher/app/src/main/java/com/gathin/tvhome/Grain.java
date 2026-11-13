package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Film grain for the {@code grain} switch (config.xml, 0 to 30 percent, 6 by default): a 128x128 noise tile made in
 * code (no asset) and drawn once into a gradient's own bitmap with OVERLAY on a software canvas, so it breaks up
 * banding and costs nothing per frame. With grain at 0 none of this runs.
 */
final class Grain {

    static final int TILE = 128;
    private static final long SEED = 0xC1EA9L;
    /** Standard deviation of the noise around mid-grey, in 0..255 levels: fine grain. */
    private static final double SIGMA = 28.0;

    private static Bitmap tile;

    private Grain() { }

    /** Opaque grey values centred on 128, gaussian, {@code size * size} of them. The same seed gives the same pixels. */
    static int[] noisePixels(int size, long seed) {
        Random r = new Random(seed);
        int[] px = new int[size * size];
        for (int i = 0; i < px.length; i++) {
            int g = 128 + (int) Math.round(r.nextGaussian() * SIGMA);
            g = Math.max(0, Math.min(255, g));
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        return px;
    }

    static Bitmap noiseTile(int size, long seed) {
        return Bitmap.createBitmap(noisePixels(size, seed), size, size, Bitmap.Config.ARGB_8888);
    }

    /** The one tile every bake shares (64 KB), made on first use. */
    private static synchronized Bitmap sharedTile() {
        if (tile == null || tile.isRecycled()) tile = noiseTile(TILE, SEED);
        return tile;
    }

    /** Drops the shared tile (onTrimMemory); the next bake makes it again. */
    static synchronized void release() {
        tile = null;
    }

    /** Alpha of the noise layer, 0..255, for a percent. */
    static int alphaFor(int percent) {
        return Math.round(255 * Math.max(0, Math.min(100, percent)) / 100f);
    }

    /** The most grain a glass surface takes, at the slider's top (30): 15 percent, so panels read as frosted, not noisy. */
    static final int GLASS_MAX = 15;

    /**
     * What glass surfaces take of the grain setting: half of it, so every step of the slider is a visible step (it used to be
     * pinned between 3 and 5 percent, which made 6 and 30 look the same). 0 stays 0, and any grain shows at least 1 percent.
     */
    static int glassPercent(int grain) {
        if (grain <= 0) return 0;
        return Math.max(1, Math.min(GLASS_MAX, Math.round(grain / 2f)));
    }

    /** The alpha (0..255) of the noise layer a glass surface bakes for a grain setting: the setting to the layer, in one place. */
    static int glassAlpha(int grain) {
        return alphaFor(glassPercent(grain));
    }

    /** Bakes the grain over a whole bitmap; the target must be mutable ARGB_8888. 0 percent does nothing. */
    static void bakeGrain(Bitmap target, int percent) {
        bakeGrain(target, percent, null, 0f);
    }

    /** As {@link #bakeGrain(Bitmap, int)}, inside a rounded rectangle of the bitmap (area null: all of it). */
    static void bakeGrain(Bitmap target, int percent, RectF area, float radius) {
        if (percent <= 0) return;   // off: nothing runs
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new BitmapShader(sharedTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        p.setAlpha(alphaFor(percent));
        Canvas c = new Canvas(target);   // bitmap-backed, so software: every blend mode works
        if (area == null) c.drawPaint(p);
        else c.drawRoundRect(area, radius, radius, p);
    }
}
