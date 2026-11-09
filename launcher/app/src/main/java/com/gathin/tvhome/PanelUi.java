package com.gathin.tvhome;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The few pieces the settings panels (Remote buttons, Themes) are built from, so they look like the rest of
 * Launcher settings: the same rounded rows, the same type, no stock dialogs.
 */
final class PanelUi {

    /** Height of the fade at the top and bottom of a scrolling panel. */
    static final int FADE_DP = 56;

    private PanelUi() { }

    static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    /** A scrolling column with the panel's padding; add the screen's views to the returned content. */
    static ScrollView scroller(Context ctx, LinearLayout content) {
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        // Feather the top and bottom: rows dissolve into the card's edge instead of being cut off, and focus-scrolling
        // keeps the focused row clear of the fade. The fade only shows on a side there is more to scroll to.
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(ctx, FADE_DP));
        scroll.setVerticalScrollBarEnabled(false);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(ctx, 36), dp(ctx, 36), dp(ctx, 36), dp(ctx, 44));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    static TextView title(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(PanelTheme.get(ctx).textPrimary);
        t.setTextSize(28);
        return t;
    }

    /** Small grey explanatory text. */
    static TextView note(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(PanelTheme.get(ctx).textSecondary);
        t.setTextSize(14);
        t.setPadding(0, dp(ctx, 8), 0, 0);
        return t;
    }
}
