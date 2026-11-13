package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * The launcher's glass look, as the three switches of config.xml set it (grain, glass_edge, glass_frost): one place the
 * surfaces ask. Config.load stores the values here, so a panel that has no Config of its own (Quick settings, a popup)
 * still follows the theme. The defaults match config.xml, for the moment before the first load.
 */
final class GlassLook {

    /** Film grain, 0 to 30 percent. */
    static volatile int grain = 6;
    /** glass_edge: a gradient rim, an inner top highlight and a soft inner bottom shadow on every glass surface. */
    static volatile boolean edge = true;
    /** glass_frost: how much the shared frost behind glass is darkened, 0 to 100; 0 builds and draws none. */
    static volatile int frost = 60;
    /** glass_refraction: the frost behind glass is magnified a little (a lens) and bends at the rim (a bevel). */
    static volatile boolean refraction = true;
    /** glass_tint: a black layer over the frost, 0 to 100 (up to {@link #TINT_MAX} opaque at 100), under the edge light. */
    static volatile int tint = 20;
    /** The tint's alpha at 100 percent. */
    static final float TINT_MAX = 0.85f;

    /**
     * The glass opacity in percent: the Dock band opacity setting (dock_opacity), launcher-wide. The dock band scales itself
     * by it; every other glass surface (pills, chips, cards, menus, panels) is drawn at this opacity, never under
     * {@link #MIN_SURFACE_OPACITY} so a menu or a focused pill never vanishes.
     */
    static volatile int opacity = 100;
    static final float MIN_SURFACE_OPACITY = 0.2f;

    private GlassLook() { }

    /** A surface's alpha for the glass opacity: opacity/100, floored at {@link #MIN_SURFACE_OPACITY}. */
    static float surfaceOpacity(int percent) {
        return Math.max(MIN_SURFACE_OPACITY, Math.max(0, Math.min(100, percent)) / 100f);
    }

    static float surfaceOpacity() {
        return surfaceOpacity(opacity);
    }

    /** Config.load: the dock band opacity in use; a change redraws every glass surface. */
    static void setOpacity(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped == opacity) return;
        opacity = clamped;
        if (live) Frost.lookChanged();
    }

    /** The paint alpha (0..255) of the tint layer for a tint of 0 to 100: 0 at 0, 0.85 opaque (217) at 100. */
    static int tintAlpha(int percent) {
        return Math.round(255 * TINT_MAX * Math.max(0, Math.min(100, percent)) / 100f);
    }

    static void set(int grain, boolean edge, int frost) {
        set(grain, edge, frost, refraction, tint);
    }

    static void set(int grain, boolean edge, int frost, boolean refraction) {
        set(grain, edge, frost, refraction, tint);
    }

    static void set(int grain, boolean edge, int frost, boolean refraction, int tint) {
        int oldFrost = GlassLook.frost;
        int newTint = Math.max(0, Math.min(100, tint));
        boolean changed = GlassLook.grain != grain || GlassLook.edge != edge || GlassLook.refraction != refraction
                || GlassLook.tint != newTint;
        GlassLook.tint = newTint;
        GlassLook.refraction = refraction;
        GlassLook.grain = Math.max(0, Math.min(30, grain));
        GlassLook.edge = edge;
        GlassLook.frost = Math.max(0, Math.min(100, frost));
        changed |= oldFrost != GlassLook.frost;
        if (GlassLook.frost == 0) Frost.release();
        if (!changed || !live) return;
        // A look that changed under a running Home (not the first load): the frost layers are built again at the new
        // strength, and every glass surface redraws (their bakes are keyed by grain and edge, so they bake again).
        if (oldFrost != GlassLook.frost && GlassLook.frost > 0) Frost.rebuildAll();
        Frost.lookChanged();
    }

    /** False until the first Config.load: nothing is on screen to rebuild before that (and the JVM tests have no Looper). */
    private static boolean live;

    /** Config.load calls this after its first set(): from then on a change is applied to what is on screen. */
    static void markLive() {
        live = true;
    }

    /** Glass takes a light share of the grain, 3 to 5 percent. */
    static int glassGrain() {
        return Grain.glassPercent(grain);
    }

    // ---- the stock surfaces ---------------------------------------------------------------------------------------

    private static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /**
     * glass_panel.xml: the dark card of Quick settings, the Inputs popup and the library. It floats over Home's own views,
     * so its frost is drawn fully opaque (see {@link GlassSurface.Spec#floating()}).
     */
    static GlassSurface.Spec panel(Context ctx) {
        return new GlassSurface.Spec(0xFF2A2A2E, 0xFF161618, 0x33FFFFFF, dp(ctx, 1), dp(ctx, 20)).floating();
    }

    /** pill_bg.xml's resting state: white glass fading from 18 to 8 percent. */
    static GlassSurface.Spec pill(Context ctx) {
        return new GlassSurface.Spec(0x2EFFFFFF, 0x14FFFFFF, 0x38FFFFFF, dp(ctx, 1), dp(ctx, 21));
    }

    /** tile_bg.xml: the dark card behind an icon tile; its radius follows the tile (AppAdapter.cornerRadius). */
    static GlassSurface.Spec tile(Context ctx) {
        return new GlassSurface.Spec(0xFF1C1C1E, 0xFF1C1C1E, 0, 0, dp(ctx, 16), 1f, false);
    }

    /**
     * Frosted glass with a coloured outline on top (the VPN pill's state colour, the reorder cue's accent): the glass is
     * the shared library's (frost, refraction, grain, specular), the outline a plain stroke drawn over it, since the
     * glass_edge rim is white and would hide the colour. A layer drawable, so the glass still finds its view.
     */
    static Drawable outlined(GlassSurface.Spec glass, float radiusPx, int strokePx, int strokeColor) {
        android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable();
        outline.setColor(0);
        outline.setCornerRadius(radiusPx);
        outline.setStroke(strokePx, strokeColor);
        return new android.graphics.drawable.LayerDrawable(new Drawable[] {new GlassDrawable(glass, null), outline});
    }

    /**
     * Swaps the stock glass backgrounds under {@code root} for glass drawables that carry the look. A view is
     * recognised by the resource its background was made from (the same cached constant state), so a view with
     * another background is left alone. Call it after inflating a layout that has glass in it.
     */
    static void decorate(View root) {
        if (root == null) return;
        Context ctx = root.getContext();
        Drawable.ConstantState panel = state(ctx, R.drawable.glass_panel);
        Drawable.ConstantState pill = state(ctx, R.drawable.pill_bg);
        decorate(root, ctx, panel, pill);
    }

    private static void decorate(View v, Context ctx, Drawable.ConstantState panel, Drawable.ConstantState pill) {
        Drawable bg = v.getBackground();
        if (bg != null && !(bg instanceof GlassDrawable)) {
            Drawable.ConstantState cs = bg.getConstantState();
            if (cs != null && cs == panel) v.setBackground(new GlassDrawable(panel(ctx), null));
            else if (cs != null && cs == pill) v.setBackground(new GlassDrawable(pill(ctx), bg));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) decorate(g.getChildAt(i), ctx, panel, pill);
        }
    }

    private static Drawable.ConstantState state(Context ctx, int res) {
        Drawable d = ctx.getDrawable(res);
        return d == null ? null : d.getConstantState();
    }
}
