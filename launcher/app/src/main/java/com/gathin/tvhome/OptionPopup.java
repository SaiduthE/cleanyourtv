package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The small list that opens over a Launcher settings row: the options, the current one ticked and already focused,
 * placed so the current option sits exactly over the row. The same class draws the wide style picker (a header, a
 * picture and a caption per option) and the confirm card (a title, a sentence and two buttons).
 *
 * It is a view in the panel's own window, not a PopupWindow, so the activity routes the keys (see
 * LauncherSettingsActivity.dispatchKeyEvent): Up and Down move without wrapping, OK commits, Back and Left/Right
 * close without a change.
 */
final class OptionPopup {

    interface OnPick {
        void pick(int position);
    }

    static final int WIDTH_DP = 208;
    static final int WIDE_WIDTH_DP = 268;
    static final int ITEM_DP = 40;
    static final int WIDE_ITEM_DP = 56;
    static final int MAX_VISIBLE = 7;
    private static final int PAD_DP = 6;
    private static final int INSET_DP = 12;
    private static final int POPUP_ELEVATION_DP = 28;

    private final Context ctx;
    private final FrameLayout host;
    private final View card;
    private final View source;
    private final PanelTheme theme;
    private final int widthDp;
    private final int itemDp;
    private final int animMs;
    private final int fadeMs;
    private final LinearLayout box;
    private final LinearLayout items;
    private final List<ItemView> views = new ArrayList<>();
    private ScrollView scroll;
    private ViewGroup list;
    private View dimTarget;
    private Runnable onClose;
    private boolean showing;
    private boolean ticks = true;
    private boolean stayOpen;
    private int current = -1;

    /**
     * @param card   the settings card (the popup's right edge sits 12 dp inside it, and it limits the height)
     * @param source the row the popup belongs to; focus returns to it
     * @param widthDp 208 for a list, 268 for the style picker and the confirm card
     * @param itemDp  40 for a list, 56 for the style picker's picture rows
     * @param animMs length of the open animation (0 for instant), see SettingsModel.duration
     */
    OptionPopup(Context ctx, FrameLayout host, View card, View source, int widthDp, int itemDp,
                int animMs) {
        this.ctx = ctx;
        this.host = host;
        this.card = card;
        this.source = source;
        this.theme = PanelTheme.get(ctx);
        this.widthDp = widthDp;
        this.itemDp = itemDp;
        this.animMs = animMs;
        this.fadeMs = Math.min(animMs, 100);
        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(PanelRows.popupBackground(ctx, PanelRows.POPUP_RADIUS_DP));
        // Views draw in order of elevation, not of position: this must stay above the card's 16 dp, or the card (and
        // its dimmed list) is drawn over the popup and the popup looks faded and underneath.
        box.setElevation(PanelRows.dp(ctx, POPUP_ELEVATION_DP));
        int pad = PanelRows.dp(ctx, PAD_DP);
        box.setPadding(pad, pad, pad, pad);
        items = new LinearLayout(ctx);
        items.setOrientation(LinearLayout.VERTICAL);
    }

    /** A non-focusable line above the options ("Styles of Clean Look"). */
    OptionPopup header(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0x80FFFFFF);
        t.setTypeface(UiFont.get(ctx, R.string.font_regular));
        t.setPadding(PanelRows.dp(ctx, 12), PanelRows.dp(ctx, 8), PanelRows.dp(ctx, 12), 0);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PanelRows.dp(ctx, 30)));
        return this;
    }

    /** A title and a sentence above the options (the confirm card). */
    OptionPopup message(String title, String body) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(PanelRows.dp(ctx, 10), PanelRows.dp(ctx, 8), PanelRows.dp(ctx, 10), PanelRows.dp(ctx, 8));
        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(0xFFFFFFFF);
        t.setTypeface(UiFont.get(ctx, R.string.font_medium));
        block.addView(t);
        TextView b = new TextView(ctx);
        b.setText(body);
        b.setTextSize(14);
        b.setTextColor(0xCCFFFFFF);
        b.setTypeface(UiFont.get(ctx, R.string.font_regular));
        b.setLineSpacing(PanelRows.dp(ctx, 3), 1f);
        b.setPadding(0, PanelRows.dp(ctx, 4), 0, 0);
        block.addView(b);
        box.addView(block, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /**
     * A list of switches (Photo themes): OK on an option calls {@code pick} but leaves the popup open, so several
     * can be changed one after the other; {@link #setTicked} redraws a tick. Back or Left/Right close it.
     */
    OptionPopup stayOpen() {
        stayOpen = true;
        return this;
    }

    /** Ticks or unticks the option at {@code position} (the switches of a stay-open list). */
    void setTicked(int position, boolean ticked) {
        if (position >= 0 && position < views.size()) views.get(position).setTicked(ticked);
    }

    /** Buttons have no tick column. */
    OptionPopup noTicks() {
        ticks = false;
        return this;
    }

    /** Adds an option; the current one gets the tick. Returns its position. */
    int add(String label, String caption, Drawable preview, boolean isCurrent) {
        ItemView v = new ItemView(ctx, label, caption, preview, isCurrent && ticks);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(ctx, itemDp));
        items.addView(v, lp);
        views.add(v);
        if (isCurrent) current = views.size() - 1;
        return views.size() - 1;
    }

    boolean isShowing() {
        return showing;
    }

    /**
     * Opens the popup. {@code list} is the settings list behind it: it dims and takes no focus while the popup is
     * open. Focus starts on {@code focus} (normally the current option); OK on an option calls {@code pick} after
     * the popup has closed.
     */
    void show(ViewGroup list, int focus, OnPick pick, Runnable onClose) {
        this.list = list;
        this.onClose = onClose;
        for (int i = 0; i < views.size(); i++) {
            final int position = i;
            views.get(i).setOnClickListener(v -> {
                if (!showing) return;
                if (!stayOpen) close(true);
                pick.pick(position);
            });
        }
        int n = views.size();
        int itemPx = PanelRows.dp(ctx, itemDp);
        int scrollY = 0;
        if (n > MAX_VISIBLE) {
            scroll = new ScrollView(ctx);
            scroll.setVerticalScrollBarEnabled(false);
            // Long lists feather out at the top and bottom edge instead of being cut off.
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(PanelRows.dp(ctx, 32));
            scroll.addView(items, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    MAX_VISIBLE * itemPx));
            scrollY = Math.max(0, Math.min((n - MAX_VISIBLE) * itemPx, focus * itemPx - (MAX_VISIBLE / 2) * itemPx));
        } else {
            box.addView(items, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int width = PanelRows.dp(ctx, widthDp);
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = box.getMeasuredHeight();

        // Where the focused option sits inside the popup, so it can be put right over the row.
        int itemsTop = box.getPaddingTop();
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (child == items || child == scroll) break;
            itemsTop += child.getMeasuredHeight();
        }
        int optionCenter = itemsTop + focus * itemPx + itemPx / 2 - scrollY;

        int[] a = new int[2];
        int[] b = new int[2];
        source.getLocationInWindow(a);
        host.getLocationInWindow(b);
        int rowCenter = a[1] - b[1] + source.getHeight() / 2;
        int inset = PanelRows.dp(ctx, INSET_DP);
        int top = rowCenter - optionCenter;
        int minTop = card.getTop() + inset;
        int maxTop = card.getBottom() - inset - height;
        top = Math.max(minTop, Math.min(top, Math.max(minTop, maxTop)));
        int left = card.getRight() - inset - width;

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = left;
        lp.topMargin = top;
        host.addView(box, lp);

        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (dimTarget == null) dimTarget = list;
        dimTarget.animate().alpha(0.45f).setDuration(fadeMs).start();

        showing = true;
        box.setAlpha(0f);
        box.setScaleY(0.96f);
        box.setPivotX(width / 2f);
        box.setPivotY(optionCenter);
        final int startScroll = scrollY;
        final int focusPosition = focus;
        box.post(() -> {
            if (!showing) return;
            if (scroll != null) scroll.scrollTo(0, startScroll);
            if (focusPosition >= 0 && focusPosition < views.size()) views.get(focusPosition).requestFocus();
        });
        box.animate().alpha(1f).scaleY(1f).setDuration(animMs).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Up (-1) or Down (+1) by one option, never wrapping. */
    void move(int delta) {
        if (!showing || views.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < views.size(); i++) if (views.get(i).isFocused()) at = i;
        int to = at < 0 ? Math.max(0, current) : Math.max(0, Math.min(views.size() - 1, at + delta));
        views.get(to).requestFocus();
    }

    /** Closes without a change. */
    void dismiss() {
        if (showing) close(true);
    }

    private void close(boolean restoreFocus) {
        showing = false;
        if (list != null) list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (dimTarget != null) dimTarget.animate().alpha(1f).setDuration(fadeMs).start();
        if (restoreFocus) source.requestFocus();
        final View gone = box;
        gone.animate().alpha(0f).setDuration(fadeMs).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> host.removeView(gone)).start();
        if (onClose != null) onClose.run();
    }

    /** One option: the label (and caption and picture) with the tick column on the left. */
    private final class ItemView extends LinearLayout {
        private boolean ticked;
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ItemView(Context ctx, String label, String caption, Drawable preview, boolean ticked) {
            super(ctx);
            this.ticked = ticked;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);
            setClickable(true);
            int start = OptionPopup.this.ticks ? PanelRows.dp(ctx, 40) : PanelRows.dp(ctx, 14);
            setPaddingRelative(start, 0, PanelRows.dp(ctx, caption != null ? 12 : 14), 0);
            setBackground(PanelRows.slabBackground(ctx, 12, itemDp, fadeMs));
            if (preview != null) {
                View p = new View(ctx);
                p.setBackground(preview);
                LayoutParams pl = new LayoutParams(PanelRows.dp(ctx, StylePreviewDrawable.WIDTH_DP),
                        PanelRows.dp(ctx, StylePreviewDrawable.HEIGHT_DP));
                pl.setMarginEnd(PanelRows.dp(ctx, 12));
                addView(p, pl);
            }
            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(VERTICAL);
            TextView name = new TextView(ctx);
            name.setText(label);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(0xDBFFFFFF);
            name.setTypeface(UiFont.get(ctx, R.string.font_regular));
            name.setIncludeFontPadding(false);
            text.addView(name);
            if (caption != null) {
                TextView c = new TextView(ctx);
                c.setText(caption);
                c.setTextSize(12);
                c.setSingleLine(true);
                c.setEllipsize(android.text.TextUtils.TruncateAt.END);
                c.setTextColor(0x80FFFFFF);
                c.setTypeface(UiFont.get(ctx, R.string.font_regular));
                c.setIncludeFontPadding(false);
                c.setPadding(0, PanelRows.dp(ctx, 2), 0, 0);
                text.addView(c);
            }
            addView(text, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(PanelRows.dp(ctx, 2.5f));
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            setWillNotDraw(false);
        }

        void setTicked(boolean value) {
            ticked = value && OptionPopup.this.ticks;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!ticked) return;
            float d = getResources().getDisplayMetrics().density;
            tick.setColor(isFocused() ? 0xFFFFFFFF : theme.accent);
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float x = rtl ? getWidth() - 15 * d - 11 * d : 15 * d;
            float cy = getHeight() / 2f;
            path.reset();
            path.moveTo(x, cy);
            path.lineTo(x + 3.6f * d, cy + 3.4f * d);
            path.lineTo(x + 11 * d, cy - 3.6f * d);
            canvas.drawPath(path, tick);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
    }
}
