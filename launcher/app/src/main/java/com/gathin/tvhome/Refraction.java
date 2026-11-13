package com.gathin.tvhome;

/**
 * The glass_refraction look, with no Android in it so it runs in JVM tests. Real glass bends what is behind it: here the
 * frost slice a surface draws is magnified a little about the surface's centre (a lens), and a thin band just inside its
 * rim samples the frost with a stronger inward pull (the bevel), so the picture visibly bends at the edge. Only the
 * frost shaders' matrices change per draw; nothing is allocated and no bitmap is touched.
 */
final class Refraction {

    /** The lens: the frost slice is magnified by this much about the surface's centre (1.03 to 1.06). */
    static final float LENS = 1.045f;
    /** The bevel's width inside the rim, in dp (3 to 6). */
    static final float BAND_DP = 4.5f;
    /**
     * How far inward, in dp, the rim samples the frost at the very edge: the outer step of the bevel pulls the picture this
     * far, the inner step about 40 percent of it, so the bend ramps up towards the rim.
     */
    static final float BEND_DP = 11f;
    static final float INNER_SHARE = 0.4f;
    /** A magnification is never stronger than this, so a tiny surface does not turn the frost inside out. */
    private static final float MAX_MAG = 1.7f;

    private Refraction() { }

    /**
     * The magnification about the centre that makes the edge of a surface (its half size {@code half}, in px) sample the
     * frost {@code bendPx} further in than it would unmagnified. 1 for a surface with no size.
     */
    static float magFor(float half, float bendPx) {
        if (half <= 0f || bendPx <= 0f) return 1f;
        if (bendPx >= half) return MAX_MAG;
        return Math.min(MAX_MAG, half / (half - bendPx));
    }

    /** The bevel's width in px for a surface with this short side: {@link #BAND_DP}, but never more than a fifth of it. */
    static float bandPx(float density, float shortSide) {
        return Math.max(1f, Math.min(BAND_DP * density, shortSide / 5f));
    }

    /**
     * Where a magnified frost lands on the canvas: writes {scaleX, scaleY, translateX, translateY} into {@code out}, for a
     * matrix that is setScale(scaleX, scaleY) then postTranslate(translateX, translateY).
     *
     * A canvas point at (u, v) in the surface sits at screen (screenX + u, screenY + v) and shows the frost at the screen
     * point pulled towards the surface's centre by {@code magX}, {@code magY} (1 shows it as it is).
     *
     * @param frostScaleX screen px per frost px, horizontally (Frost.scaleX())
     * @param centreX     the surface's centre on the screen, horizontally
     * @param offsetX     screenX - left: how far the canvas origin is from the screen's
     */
    static void mapping(float frostScaleX, float frostScaleY, float magX, float magY, float centreX, float centreY,
                        float offsetX, float offsetY, float[] out) {
        out[0] = frostScaleX * magX;
        out[1] = frostScaleY * magY;
        out[2] = centreX * (1f - magX) - offsetX;
        out[3] = centreY * (1f - magY) - offsetY;
    }
}
