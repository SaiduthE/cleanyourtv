package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The colours and backgrounds of the settings panels, taken from the active style: an accent from the overlay key
 * panel_accent ("#RRGGBB", or "auto" to take one from the theme wallpaper), else the launcher's green. Everything
 * is derived from that one accent by {@link PanelColors}. The values below are the design tokens; change them here.
 */
final class PanelTheme {

    // ---- design tokens -----------------------------------------------------------------------------------------
    static final int DEFAULT_ACCENT = 0xFF4DAE6D;
    static final int TEXT_PRIMARY = 0xFFFFFFFF;
    static final int TEXT_SECONDARY = 0xFFACB8C1;
    static final int PANEL_BASE = 0xFF111820;
    static final int PANEL_ALPHA = 0xF2;
    static final float PANEL_TINT = 0.25f;            // share of the theme's background_color mixed into PANEL_BASE
    static final int POPUP_ALPHA = 0xFA;
    static final float POPUP_LIGHTEN = 0.06f;
    static final int PANEL_RADIUS_DP = 28;
    static final int PANEL_INSET_DP = 28;
    static final int PANEL_STROKE = 0x33FFFFFF;
    static final int PANEL_STROKE_DP = 1;
    /** Wallpaper sampling decodes at least this far down (1/16 side), so it costs a few KB. */
    static final int SAMPLE_SIZE = 16;
    static final String ACCENT_CACHE = "panel_accent";

    final int accent;
    final int heading;
    final int focusFill;
    final int focusStroke;
    final int selectedFill;
    final int selectedStroke;
    final int panelFill;
    final int popupFill;
    final int textPrimary = TEXT_PRIMARY;
    final int textSecondary = TEXT_SECONDARY;
    final int valueText;
    private final float density;

    private static PanelTheme cached;
    private static String cachedStamp;

    private PanelTheme(PanelColors colors, int panelFill, float density) {
        accent = colors.accent;
        heading = colors.heading;
        focusFill = colors.focusFill;
        focusStroke = colors.focusStroke;
        selectedFill = colors.selectedFill;
        selectedStroke = colors.selectedStroke;
        valueText = colors.valueText;
        this.panelFill = panelFill;
        this.popupFill = PanelColors.alpha(PanelColors.lighten(panelFill, POPUP_LIGHTEN), POPUP_ALPHA);
        this.density = density;
    }

    /** The theme for the current style; cached until the theme or the launcher settings change. */
    static synchronized PanelTheme get(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        String stamp = stamp(app);
        if (cached != null && stamp.equals(cachedStamp)) return cached;
        cached = build(app);
        cachedStamp = stamp;
        return cached;
    }

    private static String stamp(Context ctx) {
        File dir = Config.dir(ctx);
        File revision = dir == null ? null : new File(dir, "theme_revision");
        String theme = revision != null && revision.isFile() ? revision.length() + "/" + revision.lastModified() : "-";
        long settings = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getLong(Config.SETTINGS_REVISION_KEY, 0);
        File wallpaper = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        String picture = wallpaper != null && wallpaper.isFile() ? wallpaper.length() + "/" + wallpaper.lastModified() : "-";
        return theme + ":" + settings + ":" + picture;
    }

    private static PanelTheme build(Context ctx) {
        JSONObject overlay = ThemeOverlay.read(ctx);
        int accent = 0;
        String wanted = overlay == null ? "" : overlay.optString("panel_accent", "").trim();
        if ("auto".equalsIgnoreCase(wanted)) accent = sampledAccent(ctx);
        else if (!wanted.isEmpty()) accent = PanelColors.parse(wanted);
        if (accent == 0) accent = DEFAULT_ACCENT;
        int fill = PANEL_BASE;
        int background = overlay == null ? 0 : PanelColors.parse(overlay.optString("background_color", ""));
        if (background != 0) fill = PanelColors.mix(PANEL_BASE, background, PANEL_TINT);
        fill = PanelColors.alpha(fill, PANEL_ALPHA);
        return new PanelTheme(PanelColors.from(accent), fill, ctx.getResources().getDisplayMetrics().density);
    }

    // ---- drawables ---------------------------------------------------------------------------------------------

    /** The settings card on the right edge: inset from the top, right and bottom, rounded, with a hairline edge. */
    Drawable panelBackground() {
        int inset = px(PANEL_INSET_DP);
        // Frosted glass like the other panels (frost, refraction, edge light, grain), inset from the window's edges.
        return new InsetDrawable(new GlassDrawable(new GlassSurface.Spec(panelFill, panelFill, PANEL_STROKE,
                px(PANEL_STROKE_DP), px(PANEL_RADIUS_DP)).floating(), null), 0, inset, inset, inset);
    }

    /** A menu or dialog card: the panel colour a little lighter, same corners and edge, no inset. */
    Drawable popupBackground() {
        return shape(popupFill, px(PANEL_RADIUS_DP), px(PANEL_STROKE_DP), PANEL_STROKE);
    }

    private static GradientDrawable shape(int fill, float radius, int strokeWidth, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    private int px(int dp) {
        return Math.max(1, Math.round(dp * density));
    }

    // ---- wallpaper accent --------------------------------------------------------------------------------------

    /** The colour sampled from the theme wallpaper, or 0. The result is kept, keyed by the file's size and date. */
    private static int sampledAccent(Context ctx) {
        try {
            File dir = Config.dir(ctx);
            File wallpaper = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
            if (wallpaper == null || !wallpaper.isFile() || wallpaper.length() == 0) return 0;
            String key = wallpaper.length() + ":" + wallpaper.lastModified();
            File cache = new File(ctx.getFilesDir(), ACCENT_CACHE);
            String saved = readText(cache);
            if (saved != null) {
                String[] parts = saved.trim().split("\n");
                if (parts.length == 2 && parts[0].equals(key)) return PanelColors.parse(parts[1]);
            }
            int accent = sample(wallpaper);
            String text = key + "\n" + (accent == 0 ? "none" : String.format("#%06X", accent & 0xFFFFFF));
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return accent;
        } catch (Exception e) {
            return 0;
        }
    }

    private static int sample(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return 0;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int size = SAMPLE_SIZE;
        while (bounds.outWidth / size > 240) size *= 2;
        opts.inSampleSize = size;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
        if (bitmap == null) return 0;
        try {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            return PanelColors.fromPixels(pixels);
        } finally {
            bitmap.recycle();
        }
    }

    private static String readText(File file) {
        if (!file.isFile() || file.length() > 256) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
