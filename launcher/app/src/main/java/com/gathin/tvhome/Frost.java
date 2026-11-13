package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The shared frost behind the glass_frost switch (config.xml, 0 to 100, 60 by default): ONE small bitmap of the wallpaper
 * (or the sky gradient when there is none), blurred, saturated and darkened, that every glass surface draws its own
 * screen-aligned slice of. It is not a live blur: it is made when the wallpaper loads or rotates and costs a few
 * milliseconds then and nothing per frame. A quarter of the screen's size in RGB_565 it is about 0.25 MB at 1080p,
 * the only allocation of the feature, dropped on a wallpaper change and on onTrimMemory. With glass_frost at 0 nothing
 * is built.
 *
 * Steps: filtered downscale to 1/16 of the screen, a small stack blur, a bilinear scale up to 1/4 of the screen
 * through a saturation 1.4 colour matrix that also blends the picture toward dark by the strength.
 */
final class Frost {

    /** Something that draws the frost and wants a redraw when a new one arrives. */
    interface Listener {
        void onFrostChanged();
    }

    private static final float SATURATION = 1.4f;
    /** The most the picture is darkened at strength 100 (0.27 at the default 60): the wallpaper's colours stay clearly visible. */
    private static final float MAX_DARK = 0.45f;
    /**
     * What is left of a dark surface's own fill when it sits on the frost: the frost is the glass, the fill only a light
     * tint that keeps white text readable (it used to be drawn at 69 percent and more, which hid the frost).
     */
    static final float FILL_KEEP = 0.32f;
    /** A fill lighter than this (white glass) is already a sheen and is drawn as designed. */
    private static final float DARK_LUMA = 0.55f;
    /** The soft white sheen over frosted dark glass, top to bottom (alpha, 0 to 255). */
    static final int SHEEN_TOP = 0x24, SHEEN_BOTTOM = 0x06;
    private static final int DARK_R = 16, DARK_G = 18, DARK_B = 24;
    private static final int BLUR_RADIUS = 4;

    /**
     * What the frost is made from, bottom to top: the theme's wallpaper (or sky), the Top Shelf's photo at rest, and a
     * show's own Resume artwork. The glass always frosts the top one that is on screen, so it shows what is really
     * behind it, not a wallpaper the shelf covers.
     */
    static final int LAYER_WALL = 0, LAYER_SHELF = 1, LAYER_ART = 2;
    private static final Bitmap[] LAYERS = new Bitmap[3];
    private static volatile Bitmap bitmap;
    private static volatile float scaleX = 4f, scaleY = 4f;
    /** Made on first use, so the JVM tests (no Looper) can load this class. */
    private static Handler main;

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    private static final Set<Listener> LISTENERS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Listener, Boolean>()));

    private Frost() { }

    /** The frost, or null while none is built (surfaces then draw their plain fill). */
    static Bitmap get() {
        return bitmap;
    }

    /** Screen pixels per frost pixel, so a slice can be mapped to the screen. */
    static float scaleX() {
        return scaleX;
    }

    static float scaleY() {
        return scaleY;
    }

    static void listen(Listener l) {
        LISTENERS.add(l);
    }

    /** The wallpaper's (or sky's) frost, made for a screen of this size; null clears that layer. */
    static void set(Bitmap frost, int screenW, int screenH) {
        setLayer(LAYER_WALL, frost, screenW, screenH);
    }

    /** One layer's frost (the old one is dropped, not recycled: views may be drawing it); null clears the layer. */
    static void setLayer(int layer, Bitmap frost, int screenW, int screenH) {
        synchronized (LAYERS) {
            if (frost != null) {
                scaleX = screenW / (float) frost.getWidth();
                scaleY = screenH / (float) frost.getHeight();
            }
            LAYERS[layer] = frost;
        }
        publish();
    }

    static void clearLayer(int layer) {
        boolean changed;
        synchronized (LAYERS) {
            changed = LAYERS[layer] != null;
            LAYERS[layer] = null;
        }
        if (changed) publish();
    }

    /** The top layer becomes the frost on show, and every glass surface redraws with it. */
    private static void publish() {
        Bitmap top = null;
        synchronized (LAYERS) {
            for (int i = LAYERS.length - 1; i >= 0 && top == null; i--) top = LAYERS[i];
        }
        if (top == bitmap) return;
        bitmap = top;
        main().post(() -> {
            Listener[] all;
            synchronized (LISTENERS) {
                all = LISTENERS.toArray(new Listener[0]);
            }
            for (Listener l : all) l.onFrostChanged();
        });
    }

    /**
     * Builds a layer's frost from a picture on {@code worker}, then publishes it on the main thread if {@code wanted}
     * still says so (the picture may have changed meanwhile). Nothing happens with glass_frost at 0.
     */
    static void buildLayer(int layer, Bitmap src, android.content.Context ctx, java.util.concurrent.Executor worker,
                           java.util.function.BooleanSupplier wanted) {
        int strength = GlassLook.frost;
        if (strength <= 0 || src == null) return;
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        try {
            worker.execute(() -> {
                Bitmap f = build(src, w, h, strength, 0, 1f, true);   // a picture recycled meanwhile just gives null
                if (f == null) return;
                main().post(() -> {
                    if (wanted.getAsBoolean()) setLayer(layer, f, w, h);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // the owner is closing
        }
    }

    /** Frees every layer (onTrimMemory, glass_frost off); each owner builds its own again when it shows a picture. */
    static void release() {
        synchronized (LAYERS) {
            java.util.Arrays.fill(LAYERS, null);
        }
        bitmap = null;
    }

    /** Frees a frost only if it is still published (a closing wallpaper must not drop a newer owner's frost). */
    static void releaseIf(Bitmap published) {
        if (published == null) return;
        boolean changed = false;
        synchronized (LAYERS) {
            for (int i = 0; i < LAYERS.length; i++) {
                if (LAYERS[i] == published) {
                    LAYERS[i] = null;
                    changed = true;
                }
            }
        }
        if (changed) publish();
    }

    // ---- pure helpers (JVM-tested) --------------------------------------------------------------------------------

    /** How far the picture is blended toward dark, 0 to 0.45, for a strength of 0 to 100 (60 gives 0.27). */
    static float darken(int strength) {
        return MAX_DARK * Math.max(0, Math.min(100, strength)) / 100f;
    }

    /** Whether a fill is dark enough that, over the frost, it is cut back to a light tint (white glass is left as it is). */
    static boolean isDarkFill(int argb) {
        float luma = (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF) + 0.114f * (argb & 0xFF)) / 255f;
        return luma < DARK_LUMA;
    }

    /**
     * The fill to draw over the frost for a surface designed with {@code argb}: a dark fill keeps {@link #FILL_KEEP} of its
     * alpha (so the dock band still follows dock_opacity: its alpha is already scaled by it), a light one is unchanged.
     */
    static int fillOnFrost(int argb) {
        if (!isDarkFill(argb)) return argb;
        return (Math.round((argb >>> 24) * FILL_KEEP) << 24) | (argb & 0xFFFFFF);
    }

    /** The size of the blurred source: 1/16 of the screen, never under 8 px. */
    static int[] smallSize(int screenW, int screenH) {
        return new int[] {Math.max(8, screenW / 16), Math.max(8, screenH / 16)};
    }

    /** The size of the shared frost: 1/4 of the screen, never under 8 px. */
    static int[] frostSize(int screenW, int screenH) {
        return new int[] {Math.max(8, screenW / 4), Math.max(8, screenH / 4)};
    }

    /**
     * The part of a picture a screen shows when the picture is scaled to cover it and centred (ImageView CENTER_CROP):
     * {x, y, width, height} in picture pixels. The whole picture when the aspect ratios match.
     */
    static int[] centerCrop(int srcW, int srcH, int screenW, int screenH) {
        if (srcW <= 0 || srcH <= 0 || screenW <= 0 || screenH <= 0) return new int[] {0, 0, Math.max(0, srcW), Math.max(0, srcH)};
        if ((long) srcW * screenH > (long) srcH * screenW) {
            // Wider than the screen: the sides are cut off.
            int w = Math.max(1, Math.min(srcW, Math.round(srcH * (float) screenW / screenH)));
            return new int[] {(srcW - w) / 2, 0, w, srcH};
        }
        int h = Math.max(1, Math.min(srcH, Math.round(srcW * (float) screenH / screenW)));
        return new int[] {0, (srcH - h) / 2, srcW, h};
    }

    /** Stack blur (Mario Klingemann's) of an ARGB image in place, alpha forced opaque. */
    static void stackBlur(int[] pix, int w, int h, int radius) {
        if (radius < 1 || w < 2 || h < 2) return;
        radius = Math.min(radius, Math.min(w, h) - 1);
        int wm = w - 1, hm = h - 1, wh = w * h, div = radius + radius + 1;
        int[] r = new int[wh], g = new int[wh], b = new int[wh];
        int[] vmin = new int[Math.max(w, h)];
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divsum;
        int[][] stack = new int[div][3];
        int r1 = radius + 1;
        int yw = 0, yi = 0;
        for (int y = 0; y < h; y++) {
            int rinsum = 0, ginsum = 0, binsum = 0, routsum = 0, goutsum = 0, boutsum = 0, rsum = 0, gsum = 0, bsum = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[yi + Math.min(wm, Math.max(i, 0))];
                int[] sir = stack[i + radius];
                sir[0] = (p & 0xFF0000) >> 16;
                sir[1] = (p & 0x00FF00) >> 8;
                sir[2] = p & 0x0000FF;
                int rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            int sp = radius;
            for (int x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];
                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                int[] sir = stack[(sp - radius + div) % div];
                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                int p = pix[yw + vmin[x]];
                sir[0] = (p & 0xFF0000) >> 16;
                sir[1] = (p & 0x00FF00) >> 8;
                sir[2] = p & 0x0000FF;
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                sp = (sp + 1) % div;
                sir = stack[sp];
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                yi++;
            }
            yw += w;
        }
        for (int x = 0; x < w; x++) {
            int rinsum = 0, ginsum = 0, binsum = 0, routsum = 0, goutsum = 0, boutsum = 0, rsum = 0, gsum = 0, bsum = 0;
            int yp = -radius * w;
            for (int i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                int[] sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                int rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            int sp = radius;
            for (int y = 0; y < h; y++) {
                pix[yi] = 0xFF000000 | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;
                int[] sir = stack[(sp - radius + div) % div];
                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];
                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                int p = x + vmin[y];
                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;
                sp = (sp + 1) % div;
                sir = stack[sp];
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];
                yi += w;
            }
        }
    }

    // ---- building -------------------------------------------------------------------------------------------------

    /**
     * Builds the frost of a picture (never a HARDWARE bitmap: it must be readable). Safe off the main thread. Returns
     * null when it cannot (out of memory, no picture), and the surfaces keep their plain fill.
     *
     * @param src      the wallpaper as shown (dim already painted in), or the sky texture
     * @param under    a colour the picture is laid over when {@code alpha} is under 1 (the sky is partly transparent)
     * @param alpha    how opaque the picture is on screen, 0 to 1
     * @param crop     true for a picture shown centre-cropped (a wallpaper ImageView): only the part of it the screen
     *                 shows is frosted, so the frost lines up with what is on screen. False for a texture stretched
     *                 over the whole screen (the sky).
     */
    static Bitmap build(Bitmap src, int screenW, int screenH, int strength, int under, float alpha, boolean crop) {
        if (src == null || src.isRecycled() || strength <= 0 || screenW <= 0 || screenH <= 0) return null;
        int[] small = smallSize(screenW, screenH);
        int[] size = frostSize(screenW, screenH);
        Bitmap cur = src;
        Bitmap blurred = null;
        boolean own = false;
        try {
            if (crop) {
                int[] area = centerCrop(src.getWidth(), src.getHeight(), screenW, screenH);
                if (area[2] != src.getWidth() || area[3] != src.getHeight()) {
                    cur = Bitmap.createBitmap(src, area[0], area[1], area[2], area[3]);
                    own = cur != src;
                }
            }
            // Halve with filtering until about the size wanted, then one last filtered step to exactly 1/16.
            while (cur.getWidth() / 2 >= small[0] && cur.getHeight() / 2 >= small[1]) {
                Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                if (own) cur.recycle();
                cur = next;
                own = true;
            }
            Bitmap tiny = Bitmap.createScaledBitmap(cur, small[0], small[1], true);
            if (own && tiny != cur) cur.recycle();
            cur = tiny;
            own = tiny != src;

            int[] px = new int[small[0] * small[1]];
            cur.getPixels(px, 0, small[0], 0, 0, small[0], small[1]);
            if (own) cur.recycle();
            stackBlur(px, small[0], small[1], BLUR_RADIUS);
            blurred = Bitmap.createBitmap(px, small[0], small[1], Bitmap.Config.ARGB_8888);

            Bitmap out = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            c.drawColor(under | 0xFF000000);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(new ColorMatrixColorFilter(matrix(strength)));
            p.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
            c.drawBitmap(blurred, null, new android.graphics.Rect(0, 0, size[0], size[1]), p);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (blurred != null) blurred.recycle();
            if (own && cur != src) cur.recycle();   // the crop or a halving left over by a failure (recycle is idempotent)
        }
    }

    /** Saturation 1.4, then a blend toward dark by the strength: rgb * (1 - t) + dark * t. */
    private static ColorMatrix matrix(int strength) {
        float t = darken(strength);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION);
        float s = 1f - t;
        cm.postConcat(new ColorMatrix(new float[] {
                s, 0, 0, 0, DARK_R * t,
                0, s, 0, 0, DARK_G * t,
                0, 0, s, 0, DARK_B * t,
                0, 0, 0, 1, 0}));
        return cm;
    }
}
