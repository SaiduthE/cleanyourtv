package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The shared pieces of the settings panels in the Clean Look design: the focus slab, the section header, the
 * compact row factory and the full-screen window. Colours come from {@link PanelTheme}, so every panel follows
 * the active style.
 */
final class PanelRows {

    static final int ROW_DP = 44;
    static final int ROW_GAP_DP = 2;
    static final int ROW_RADIUS_DP = 14;
    static final int POPUP_RADIUS_DP = 18;
    static final int TIP_RADIUS_DP = 16;
    static final int CARD_RADIUS_DP = 24;
    static final int TEXT_ROW = 0xEBFFFFFF;
    static final int TEXT_FOCUSED = 0xFFFFFFFF;
    static final int VALUE_FOCUSED = 0xDBFFFFFF;
    static final int TEXT_TERTIARY = 0x61FFFFFF;
    static final int RIM_POPUP = 0x29FFFFFF;
    static final int RIM_PANEL = 0x1FFFFFFF;
    static final int SCRIM = 0x9E000000;
    private static final int SHEEN = 0x1AFFFFFF;

    private PanelRows() { }

    /**
     * The window of the Launcher settings panel: the whole screen, transparent, no system dim. The scrim is a view
     * of the panel, so the dim is the same on every TV. (SettingsPanel.apply is the older narrow window that
     * Remote buttons and Styles still use.)
     */
    static void fullScreen(Activity activity) {
        Window window = activity.getWindow();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    static int dp(Context ctx, float value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static GradientDrawable shape(int fill, float radiusPx, int strokePx, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) d.setStroke(strokePx, stroke);
        return d;
    }

    /** Focus fill, a sheen on the top half, and the rim. */
    private static Drawable slab(Context ctx, int fill, int rim, int rimDp, int radiusDp, int heightDp) {
        float radius = dp(ctx, radiusDp);
        GradientDrawable base = shape(fill, radius, dp(ctx, rimDp), rim);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {SHEEN, 0x00FFFFFF});
        sheen.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        LayerDrawable layers = new LayerDrawable(new Drawable[] {base, sheen});
        layers.setLayerGravity(1, Gravity.TOP);
        layers.setLayerHeight(1, dp(ctx, heightDp / 2f));
        return layers;
    }

    /**
     * The background of a row or a popup option: the slab when focused, a stronger fill when pressed, a quiet one
     * when selected, and nothing at rest. The slab cross-fades over fadeMs.
     */
    static Drawable slabBackground(Context ctx, int radiusDp, int heightDp, int fadeMs) {
        PanelTheme theme = PanelTheme.get(ctx);
        StateListDrawable list = new StateListDrawable();
        list.addState(new int[] {android.R.attr.state_pressed},
                slab(ctx, PanelColors.alpha(theme.accent, 0x59), theme.focusStroke, 2, radiusDp, heightDp));
        list.addState(new int[] {android.R.attr.state_focused},
                slab(ctx, theme.focusFill, theme.focusStroke, 2, radiusDp, heightDp));
        list.addState(new int[] {android.R.attr.state_selected},
                shape(theme.selectedFill, dp(ctx, radiusDp), dp(ctx, 1), theme.selectedStroke));
        list.addState(new int[0], shape(0, dp(ctx, radiusDp), 0, 0));
        list.setEnterFadeDuration(fadeMs);
        list.setExitFadeDuration(fadeMs);
        return list;
    }

    /** The card behind a popup or a tooltip. */
    static Drawable popupBackground(Context ctx, int radiusDp) {
        return shape(PanelTheme.get(ctx).popupFill | 0xFF000000, dp(ctx, radiusDp), dp(ctx, 1), RIM_POPUP);
    }

    /** The settings card: a vertical gradient from the panel colour, rounded, with a hairline rim. */
    static Drawable cardBackground(Context ctx) {
        int base = PanelTheme.get(ctx).panelFill;
        int top = PanelColors.lighten(base, 0.05f);
        int bottom = PanelColors.mix(base, base & 0xFF000000, 0.18f);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        d.setCornerRadius(dp(ctx, CARD_RADIUS_DP));
        d.setStroke(dp(ctx, 1), RIM_PANEL);
        return d;
    }

    /** The style-coloured light in the card's top corner. */
    static Drawable cardWash(Context ctx, int widthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientCenter(0f, 0f);
        d.setGradientRadius(dp(ctx, widthDp * 0.9f));
        d.setColors(new int[] {PanelColors.alpha(PanelTheme.get(ctx).accent, 0x29), 0x00000000});
        d.setCornerRadius(dp(ctx, CARD_RADIUS_DP));
        return d;
    }

    /** A group heading: 13sp, in the style's heading colour, not focusable. */
    static TextView header(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(PanelTheme.get(ctx).heading);
        t.setTextSize(13);
        t.setTypeface(UiFont.get(ctx, R.string.font_medium));
        t.setLetterSpacing(0.02f);
        t.setSingleLine(true);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), 0);
        t.setFocusable(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 30)));
        return t;
    }

    /** A compact row for the sub-pages (Remote buttons, Styles): label, optional icon, chevron. */
    static SettingRowView row(Context ctx, String label, Drawable icon) {
        SettingRowView row = new SettingRowView(ctx, label, true, 120);
        if (icon != null) row.setIcon(icon);
        row.setLayoutParams(rowParams(ctx));
        return row;
    }

    static SettingRowView row(Context ctx, String label) {
        return row(ctx, label, null);
    }

    static LinearLayout.LayoutParams rowParams(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, ROW_DP));
        lp.bottomMargin = dp(ctx, ROW_GAP_DP);
        return lp;
    }
}
