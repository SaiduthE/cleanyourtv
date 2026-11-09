package com.gathin.tvhome;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;

/**
 * Launcher settings and Remote buttons as a card on the right edge, over a dimmed Home, instead of a full screen.
 * The activities use Theme.TvHome.Panel (translucent, no background); this sizes the window and draws the card.
 */
final class SettingsPanel {

    /** The card's share of the screen width, never narrower than MIN_DP so a long row still reads. */
    private static final float WIDTH_FRACTION = 0.34f;
    private static final int MIN_DP = 480;
    private static final float DIM = 0.55f;

    private SettingsPanel() { }

    static void apply(Activity activity) {
        Window window = activity.getWindow();
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int width = Math.max(Math.round(dm.widthPixels * WIDTH_FRACTION), Math.round(MIN_DP * dm.density));
        window.setLayout(Math.min(width, dm.widthPixels), WindowManager.LayoutParams.MATCH_PARENT);
        window.setGravity(Gravity.END);
        window.setBackgroundDrawable(PanelTheme.get(activity).panelBackground());
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(DIM);
    }
}
