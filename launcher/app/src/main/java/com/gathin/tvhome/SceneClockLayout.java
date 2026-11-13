package com.gathin.tvhome;

import java.util.Locale;

/**
 * Where the scene clock sits on one wallpaper and how it looks there (scene_clock_layouts in a theme's config.json,
 * keyed by the wallpaper's name without its extension). Positions are shares of the screen so one theme fits any
 * resolution. Pure Java: every value is clamped here, so a bad entry never reaches the drawing code.
 */
final class SceneClockLayout {
    static final int LEFT = 0, CENTRE = 1, RIGHT = 2;

    /** Horizontal place of the clock and the date line above it: LEFT, CENTRE or RIGHT. */
    final int align;
    /** Top of the clock's line box, as a share of the screen height. */
    final float y;
    /** Font size, as a share of the screen height. */
    final float size;
    /** Distance of a left or right aligned clock from the edge, as a share of the screen width. */
    final float inset;
    /** Top of the date line, as a share of the screen height. */
    final float dateY;
    /** A dark smoky clock with no sheen and dark date text, for a pale scene. */
    final boolean dark;
    /** RGB of the glass rim, or 0 for white. */
    final int rimColor;
    /** ARGB of the date line, or 0 for the tint's default. */
    final int dateColor;

    static final SceneClockLayout DEFAULT = parse(null, Double.NaN, Double.NaN, Double.NaN, null, Double.NaN, null, null);

    private SceneClockLayout(int align, float y, float size, float inset, float dateY, boolean dark, int rimColor, int dateColor) {
        this.align = align;
        this.y = y;
        this.size = size;
        this.inset = inset;
        this.dateY = dateY;
        this.dark = dark;
        this.rimColor = rimColor;
        this.dateColor = dateColor;
    }

    /** Any argument that is null, NaN or out of range takes its default (centred, y 0.15, size 0.37, light, date 0.05 above). */
    static SceneClockLayout parse(String x, double y, double size, double inset, String tint, double dateY,
                                  String rim, String dateColor) {
        int align = CENTRE;
        if (x != null) {
            String v = x.trim().toLowerCase(Locale.ROOT);
            if ("left".equals(v)) align = LEFT;
            else if ("right".equals(v)) align = RIGHT;
        }
        float top = (float) within(y, 0.0, 0.8, 0.15);
        float fontSize = (float) within(size, 0.10, 0.70, 0.37);
        float edge = (float) within(inset, 0.0, 0.40, 0.05);
        float date = (float) within(dateY, 0.0, 0.9, Math.max(0.02, top - 0.05));
        boolean dark = tint != null && "dark".equals(tint.trim().toLowerCase(Locale.ROOT));
        return new SceneClockLayout(align, top, fontSize, edge, date, dark, hex(rim) & 0x00FFFFFF, hex(dateColor));
    }

    private static double within(double value, double min, double max, double fallback) {
        return Double.isNaN(value) || value < min || value > max ? fallback : value;
    }

    /** "#RRGGBB" as opaque ARGB, "#AARRGGBB" as is; 0 for anything else (0 means "no colour set"). */
    static int hex(String text) {
        if (text == null) return 0;
        String t = text.trim();
        if (!t.startsWith("#") || (t.length() != 7 && t.length() != 9)) return 0;
        try {
            long v = Long.parseLong(t.substring(1), 16);
            return t.length() == 7 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
