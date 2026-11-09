package com.gathin.tvhome;

/**
 * The dock band's opacity setting, with no Android in it so it runs in JVM tests. The setting is a percent, 0-100,
 * saved as an int under Config.DOCK_OPACITY_KEY. It scales whatever the active style draws as its band (the plain
 * near-black band, the cinematic shelf's dark glass, the light frosted glass), so 100 is the style as designed and
 * lower shows more of what is behind it.
 */
final class DockOpacity {

    static final int MIN = 0;
    static final int MAX = 100;
    static final int STEP = 5;
    /** What nothing saved means. */
    static final int NONE = -1;
    /** The presets the old popup offered; they were already saved as these percents. */
    static final int[] OLD_PRESETS = {100, 70, 50, 30};

    private DockOpacity() { }

    /**
     * The percent for a saved value: the old presets keep their percent, any other value in range is kept, anything
     * else (nothing saved, or garbage) is NONE so the theme's value applies.
     */
    static int fromSaved(int saved) {
        return saved >= MIN && saved <= MAX ? saved : NONE;
    }

    /** The percent the band is drawn with: whatever arrives, kept inside 0-100. */
    static int forDraw(int percent) {
        return SliderModel.clamp(percent, MIN, MAX);
    }

    /** The percent to start a slider on: the saved one snapped to the 5% grid. */
    static int forSlider(int percent) {
        return SliderModel.snap(percent, STEP, MIN, MAX);
    }

    /** A style's own alpha (0-255) for its band, scaled by the percent. */
    static int scaleAlpha(int nativeAlpha, int percent) {
        return Math.round(nativeAlpha * SliderModel.clamp(percent, MIN, MAX) / 100f);
    }

    /** {@code argb} with its alpha scaled by the percent. */
    static int scaleColor(int argb, int percent) {
        return (scaleAlpha(argb >>> 24, percent) << 24) | (argb & 0xFFFFFF);
    }

    static int percentToAlpha(int percent) {
        return Math.round(SliderModel.clamp(percent, MIN, MAX) * 2.55f);
    }

    static int alphaToPercent(int alpha) {
        return SliderModel.clamp(Math.round(alpha / 2.55f), MIN, MAX);
    }
}
