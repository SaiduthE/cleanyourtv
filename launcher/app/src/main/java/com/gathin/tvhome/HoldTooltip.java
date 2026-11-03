package com.gathin.tvhome;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What holding OK on a row shows: a card to the left of the settings panel with the row's name and what it does,
 * and a pointer toward the row. A view in the panel's window, so it sits over the dimmed Home.
 */
final class HoldTooltip {

    static final int WIDTH_DP = 300;
    private static final int GAP_DP = 14;
    private static final int POINTER_DP = 12;

    private final Context ctx;
    private final FrameLayout host;
    private final View card;
    private View view;

    HoldTooltip(Context ctx, FrameLayout host, View card) {
        this.ctx = ctx;
        this.host = host;
        this.card = card;
    }

    boolean isShowing() {
        return view != null;
    }

    void show(View row, String title, String body, int animMs) {
        dismiss(0);
        PanelTheme theme = PanelTheme.get(ctx);
        FrameLayout tip = new FrameLayout(ctx);
        tip.setClipChildren(false);
        tip.setClipToPadding(false);

        // The pointer is drawn first so the card covers its inner half; only the outer corner shows.
        View pointer = new View(ctx);
        pointer.setBackground(PanelRows.popupBackground(ctx, 2));
        pointer.setRotation(45f);
        int ps = PanelRows.dp(ctx, POINTER_DP);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(ps, ps, Gravity.END | Gravity.CENTER_VERTICAL);
        pl.setMarginEnd(-ps / 2);
        tip.addView(pointer, pl);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(PanelRows.popupBackground(ctx, PanelRows.TIP_RADIUS_DP));
        content.setElevation(PanelRows.dp(ctx, 12));
        content.setPadding(PanelRows.dp(ctx, 16), PanelRows.dp(ctx, 14), PanelRows.dp(ctx, 16), PanelRows.dp(ctx, 14));
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(theme.textPrimary);
        t.setTypeface(UiFont.get(ctx, R.string.font_medium));
        t.setSingleLine(true);
        t.setPadding(0, 0, 0, PanelRows.dp(ctx, 4));
        content.addView(t);
        TextView b = new TextView(ctx);
        b.setText(body);
        b.setTextSize(15);
        b.setTextColor(0xCCFFFFFF);
        b.setTypeface(UiFont.get(ctx, R.string.font_regular));
        b.setLineSpacing(0f, 1.15f);
        b.setMaxLines(4);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(b);
        tip.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = PanelRows.dp(ctx, WIDTH_DP);
        tip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = tip.getMeasuredHeight();
        int[] a = new int[2];
        int[] h = new int[2];
        row.getLocationInWindow(a);
        host.getLocationInWindow(h);
        int rowCenter = a[1] - h[1] + row.getHeight() / 2;
        int margin = PanelRows.dp(ctx, 16);
        int top = Math.max(margin, Math.min(rowCenter - height / 2, host.getHeight() - margin - height));
        int left = Math.max(margin, card.getLeft() - PanelRows.dp(ctx, GAP_DP) - width);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = left;
        lp.topMargin = top;
        host.addView(tip, lp);
        view = tip;
        tip.setAlpha(0f);
        tip.setTranslationX(PanelRows.dp(ctx, 8));
        tip.animate().alpha(1f).translationX(0f).setDuration(Math.min(animMs, 120)).start();
    }

    void dismiss(int animMs) {
        final View gone = view;
        view = null;
        if (gone == null) return;
        if (animMs <= 0) {
            host.removeView(gone);
            return;
        }
        gone.animate().alpha(0f).setDuration(animMs).withEndAction(() -> host.removeView(gone)).start();
    }
}
