package com.gathin.tvhome;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The tip card that holding OK on a Launcher settings row shows: a small rounded card in the panel's colours, at most
 * two lines, placed under the focused row (or above it near the bottom) and always inside the settings card. It
 * never takes focus, fades in, and goes away on release, on any other key or after {@link #AUTO_HIDE_MS}.
 */
final class HoldTip {

    static final long AUTO_HIDE_MS = 5000;
    private static final int INSET_DP = 12;
    private static final int GAP_DP = 2;
    private static final int ELEVATION_DP = 30;

    private final Context ctx;
    private final FrameLayout host;
    private final View card;
    private TextView tip;
    private final Runnable autoHide = () -> hide(true);

    HoldTip(Context ctx, FrameLayout host, View card) {
        this.ctx = ctx;
        this.host = host;
        this.card = card;
    }

    boolean isShowing() {
        return tip != null;
    }

    /** Shows {@code text} next to {@code row}; any tip already up is replaced. fadeMs 0 appears at once. */
    void show(View row, CharSequence text, int fadeMs) {
        hide(false);
        if (text == null || text.length() == 0) return;
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xE6FFFFFF);
        t.setTypeface(UiFont.get(ctx, R.string.font_regular));
        t.setIncludeFontPadding(false);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setLineSpacing(PanelRows.dp(ctx, 2), 1f);
        t.setPadding(PanelRows.dp(ctx, 14), PanelRows.dp(ctx, 10), PanelRows.dp(ctx, 14), PanelRows.dp(ctx, 10));
        t.setBackground(PanelRows.popupBackground(ctx, PanelRows.TIP_RADIUS_DP));
        t.setElevation(PanelRows.dp(ctx, ELEVATION_DP));
        t.setFocusable(false);

        int inset = PanelRows.dp(ctx, INSET_DP);
        int width = Math.max(1, card.getWidth() - 2 * inset);
        t.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = t.getMeasuredHeight();

        int[] r = new int[2];
        int[] h = new int[2];
        row.getLocationInWindow(r);
        host.getLocationInWindow(h);
        int rowTop = r[1] - h[1];
        int top = top(rowTop, rowTop + row.getHeight(), height, card.getTop(), card.getBottom(), inset,
                PanelRows.dp(ctx, GAP_DP));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = card.getLeft() + inset;
        lp.topMargin = top;
        host.addView(t, lp);
        tip = t;
        if (fadeMs > 0) {
            t.setAlpha(0f);
            t.animate().alpha(1f).setDuration(fadeMs).start();
        }
        t.postDelayed(autoHide, AUTO_HIDE_MS);
    }

    void hide(boolean animate) {
        TextView t = tip;
        if (t == null) return;
        tip = null;
        t.removeCallbacks(autoHide);
        t.animate().cancel();
        if (animate) {
            t.animate().alpha(0f).setDuration(100).withEndAction(() -> host.removeView(t)).start();
        } else {
            host.removeView(t);
        }
    }

    /**
     * Top edge of a tip of {@code height} for a row spanning rowTop..rowBottom inside a card spanning
     * cardTop..cardBottom (all in the same coordinates): under the row when it fits within {@code inset} of the
     * card's bottom, otherwise over the row, and never outside the card.
     */
    static int top(int rowTop, int rowBottom, int height, int cardTop, int cardBottom, int inset, int gap) {
        int minTop = cardTop + inset;
        int maxTop = cardBottom - inset - height;
        int below = rowBottom + gap;
        int top = below <= maxTop ? below : rowTop - gap - height;
        return Math.max(minTop, Math.min(top, Math.max(minTop, maxTop)));
    }
}
