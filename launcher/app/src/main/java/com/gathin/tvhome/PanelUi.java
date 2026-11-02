package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The few pieces the settings panels (Remote buttons, Themes) are built from, so they look like the rest of
 * Launcher settings: the same rounded rows, the same type, no stock dialogs.
 */
final class PanelUi {

    /** Height of the fade at the top and bottom of a scrolling panel; matches android:fadingEdgeLength in the settings layout. */
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
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(28);
        return t;
    }

    /** Small grey explanatory text. */
    static TextView note(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFACB8C1);
        t.setTextSize(14);
        t.setPadding(0, dp(ctx, 8), 0, 0);
        return t;
    }

    /** A group heading such as "Apps" or "Actions". */
    static TextView section(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFF9AD0AE);
        t.setTextSize(14);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(ctx, 4), dp(ctx, 26), 0, 0);
        return t;
    }

    /** A focusable settings row, one line, with an optional icon on the left. */
    static TextView row(Context ctx, String label, Drawable icon) {
        TextView t = new TextView(ctx, null, 0, R.style.SettingsRow);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (icon != null) {
            int size = dp(ctx, 30);
            icon.setBounds(0, 0, size, size);
            t.setCompoundDrawablesRelative(icon, null, null, null);
            t.setCompoundDrawablePadding(dp(ctx, 14));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(ctx, 52));
        lp.topMargin = dp(ctx, 10);
        t.setLayoutParams(lp);
        return t;
    }

    static TextView row(Context ctx, String label) {
        return row(ctx, label, null);
    }
}
