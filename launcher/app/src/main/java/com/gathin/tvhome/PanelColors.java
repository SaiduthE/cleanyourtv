package com.gathin.tvhome;

/** Colours for the settings panels, all derived from one accent. Pure int maths so it runs in JVM tests. */
final class PanelColors {

    static final int FOCUS_FILL_ALPHA = 0x55;
    static final int SELECTED_FILL_ALPHA = 0x33;
    static final float FOCUS_STROKE_LIGHTEN = 0.45f;
    static final float HEADING_LIGHTEN = 0.30f;
    static final float VALUE_LIGHTEN = 0.60f;

    final int accent;
    final int focusFill;
    final int focusStroke;
    final int selectedFill;
    final int selectedStroke;
    final int heading;
    final int valueText;

    private PanelColors(int accent) {
        this.accent = accent | 0xFF000000;
        focusFill = alpha(this.accent, FOCUS_FILL_ALPHA);
        focusStroke = lighten(this.accent, FOCUS_STROKE_LIGHTEN);
        selectedFill = alpha(this.accent, SELECTED_FILL_ALPHA);
        selectedStroke = this.accent;
        heading = lighten(this.accent, HEADING_LIGHTEN);
        valueText = lighten(this.accent, VALUE_LIGHTEN);
    }

    static PanelColors from(int accent) {
        return new PanelColors(accent);
    }

    /** The same colour with a new alpha (0 to 255). */
    static int alpha(int color, int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
    }

    /** a toward b by t (0 to 1), per channel, alpha included. */
    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return out;
    }

    /** Toward white by t, keeping the alpha. */
    static int lighten(int color, float t) {
        return mix(color, (color & 0xFF000000) | 0x00FFFFFF, t);
    }

    /**
     * The most saturated mid-light colour in a set of ARGB pixels (a decoded wallpaper), as an opaque colour; 0 when
     * nothing in the picture qualifies (all grey, too dark or too bright).
     */
    static int fromPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        // 12 hue buckets of 30 degrees, weighted by saturation so a vivid minority beats a dull majority.
        float[] weight = new float[12];
        float[] sumR = new float[12];
        float[] sumG = new float[12];
        float[] sumB = new float[12];
        for (int p : pixels) {
            if ((p >>> 24) < 0x80) continue;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            if (max == min) continue;
            float light = (max + min) / 510f;
            if (light < 0.25f || light > 0.80f) continue;
            float sat = ((max - min) / 255f) / (1f - Math.abs(2f * light - 1f));
            if (sat < 0.30f) continue;
            float hue;
            if (max == r) hue = 60f * (((g - b) / (float) (max - min)) % 6f);
            else if (max == g) hue = 60f * ((b - r) / (float) (max - min) + 2f);
            else hue = 60f * ((r - g) / (float) (max - min) + 4f);
            if (hue < 0) hue += 360f;
            int bucket = Math.min(11, (int) (hue / 30f));
            // Near the middle of the lightness range makes the best accent.
            float w = sat * (1f - Math.abs(light - 0.55f));
            weight[bucket] += w;
            sumR[bucket] += r * w;
            sumG[bucket] += g * w;
            sumB[bucket] += b * w;
        }
        int best = -1;
        for (int i = 0; i < 12; i++) if (weight[i] > 0 && (best < 0 || weight[i] > weight[best])) best = i;
        if (best < 0) return 0;
        int r = Math.round(sumR[best] / weight[best]);
        int g = Math.round(sumG[best] / weight[best]);
        int b = Math.round(sumB[best] / weight[best]);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** "#RRGGBB" (or "#AARRGGBB", alpha dropped) to an opaque ARGB, or 0 when it is neither. */
    static int parse(String text) {
        if (text == null) return 0;
        String s = text.trim();
        if (!s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return 0;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            return 0xFF000000 | (int) (v & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
