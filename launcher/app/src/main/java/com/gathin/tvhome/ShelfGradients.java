package com.gathin.tvhome;

/**
 * The stop tables of the Top Shelf's feathered shades, with no Android in it so they run in JVM tests. Every shade is
 * a fade from a peak alpha to fully transparent over {@link #STOPS} stops placed on a smoothstep curve (zero slope at
 * both ends), so it never ends in an edge and shows no Mach bands; the tables are built once, never per frame.
 */
final class ShelfGradients {

    /** Stops per fade: 6, so the curve is smooth without a 2-stop linear ramp's visible start and end. */
    static final int STOPS = 6;
    /** The dock band's own tint (DockDecoration's translucent band), so the veil and the band share one colour. */
    static final int TINT = 0x121014;

    /** Peak darkness (0..1) of the left text shade: lighter at rest, a little stronger behind a shelf's own type. */
    static final float LEFT_REST_PEAK = 0.34f, LEFT_SHELF_PEAK = 0.45f;
    /** Peak darkness behind the clock and the top bar. */
    static final float TOP_PEAK = 0.40f;
    /** The bottom veil's peak at the screen's bottom edge: from a faint one (dock band off or 0%) to the band's own. */
    static final float BOTTOM_MIN_PEAK = 0.20f, BOTTOM_MAX_PEAK = 0.50f;
    /** How far each shade reaches, as a fraction of the width (left) or height (bottom) of the full-bleed layer. */
    static final float LEFT_SPAN = 0.65f, BOTTOM_SPAN = 0.42f;

    private ShelfGradients() { }

    /** Smoothstep: 0 at 0, 1 at 1, flat at both ends. */
    static float ease(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return c * c * (3f - 2f * c);
    }

    /** Stop positions 0..1, evenly spaced, strictly increasing. */
    static float[] positions() {
        float[] p = new float[STOPS];
        for (int i = 0; i < STOPS; i++) p[i] = i / (float) (STOPS - 1);
        return p;
    }

    /** Alphas (0..1) of a fade from peak down to 0, eased: never increasing, ending at exactly 0. */
    static float[] fadeAlphas(float peak) {
        float[] a = new float[STOPS];
        for (int i = 0; i < STOPS; i++) a[i] = i == STOPS - 1 ? 0f : peak * (1f - ease(i / (float) (STOPS - 1)));
        return a;
    }

    /** ARGB colours of the tint (rgb) at each alpha. */
    static int[] colors(int rgb, float[] alphas) {
        int[] c = new int[alphas.length];
        for (int i = 0; i < c.length; i++) c[i] = (Math.round(255f * alphas[i]) << 24) | (rgb & 0xFFFFFF);
        return c;
    }

    /** The bottom veil's peak for the dock band's opacity setting (0-100): follows the band, so there is no seam. */
    static float bottomPeak(int dockOpacityPercent, boolean bandVisible) {
        if (!bandVisible) return BOTTOM_MIN_PEAK;
        float f = Math.max(0, Math.min(100, dockOpacityPercent)) / 100f;
        return BOTTOM_MIN_PEAK + (BOTTOM_MAX_PEAK - BOTTOM_MIN_PEAK) * f;
    }
}
