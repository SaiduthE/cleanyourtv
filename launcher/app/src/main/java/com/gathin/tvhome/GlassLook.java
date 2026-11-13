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

    private GlassLook() { }

    static void set(int grain) {
        GlassLook.grain = Math.max(0, Math.min(30, grain));
    }

    /** Whether a glass surface needs anything of its own beyond the plain gradient and stroke. */
    static boolean active() {
        return grain > 0;
    }

    /** Glass takes a light share of the grain, 3 to 5 percent. */
    static int glassGrain() {
        return Grain.glassPercent(grain);
    }

    // ---- the stock surfaces ---------------------------------------------------------------------------------------

    private static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    /** glass_panel.xml: the dark card of Quick settings, the Inputs popup and the library. */
    static GlassSurface.Spec panel(Context ctx) {
        return new GlassSurface.Spec(0xFF2A2A2E, 0xFF161618, 0x33FFFFFF, dp(ctx, 1), dp(ctx, 20));
    }

    /** pill_bg.xml's resting state: white glass fading from 18 to 8 percent. */
    static GlassSurface.Spec pill(Context ctx) {
        return new GlassSurface.Spec(0x2EFFFFFF, 0x14FFFFFF, 0x38FFFFFF, dp(ctx, 1), dp(ctx, 21));
    }

    /** tile_bg.xml: the dark card behind an icon tile; its radius follows the tile (AppAdapter.cornerRadius). */
    static GlassSurface.Spec tile(Context ctx) {
        return new GlassSurface.Spec(0xFF1C1C1E, 0xFF1C1C1E, 0, 0, dp(ctx, 16));
    }

    /**
     * Swaps the stock glass backgrounds under {@code root} for glass drawables that carry the look. A view is
     * recognised by the resource its background was made from (the same cached constant state), so a view with
     * another background is left alone. Call it after inflating a layout that has glass in it.
     */
    static void decorate(View root) {
        if (!active() || root == null) return;
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
