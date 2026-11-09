package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * A small panel for a numeric setting, the sibling of {@link OptionPopup}: same card, same layer order (it is a view
 * in the panel's own window above the settings card), opaque. The value shows big, a track and thumb in the style's
 * accent colour sit under it. Left/Right change the value by {@code step} (a held key repeats, and after
 * {@link SliderModel#FAST_AFTER_MS} moves twice as far), OK keeps it, Back puts the old value back.
 *
 * Reusable: give it a title, a range, a step, a start value and how to write the value ("65%"); an optional preview
 * view sits between the number and the track and is told each change through {@code onChange}. The activity routes
 * keys through {@link #handleKey} while {@link #isShowing}.
 */
final class SliderPopup {

    static final int WIDTH_DP = 288;
    private static final int PAD_DP = 18;
    private static final int INSET_DP = 12;
    private static final int POPUP_ELEVATION_DP = 28;   // above the card's 16 dp, as OptionPopup's

    interface Formatter {
        String format(int value);
    }

    private final Context ctx;
    private final FrameLayout host;
    private final View card;
    private final View source;
    private final PanelTheme theme;
    private final int min;
    private final int max;
    private final int step;
    private final int original;
    private final boolean rtl;
    private final int animMs;
    private final int fadeMs;
    private final Formatter formatter;
    private final LinearLayout box;
    private final TextView valueText;
    private final Track track;
    private IntConsumer onChange;
    private IntConsumer onConfirm;
    private Runnable onClose;
    private ViewGroup list;
    private int value;
    private long lastMoveMs;
    private boolean showing;

    /**
     * @param value the value to start on, as saved (it is snapped to the step's grid for the track; Back restores it
     *              exactly)
     * @param rtl   the track runs right to left, so Right lowers the value
     */
    SliderPopup(Context ctx, FrameLayout host, View card, View source, String title, int min, int max, int step,
                int value, boolean rtl, int animMs, Formatter formatter) {
        this.ctx = ctx;
        this.host = host;
        this.card = card;
        this.source = source;
        this.theme = PanelTheme.get(ctx);
        this.min = min;
        this.max = max;
        this.step = step;
        this.original = value;
        this.value = SliderModel.snap(value, step, min, max);
        this.rtl = rtl;
        this.animMs = animMs;
        this.fadeMs = Math.min(animMs, 100);
        this.formatter = formatter;

        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(PanelRows.popupBackground(ctx, PanelRows.POPUP_RADIUS_DP));
        box.setElevation(PanelRows.dp(ctx, POPUP_ELEVATION_DP));
        int pad = PanelRows.dp(ctx, PAD_DP);
        box.setPadding(pad, PanelRows.dp(ctx, 14), pad, PanelRows.dp(ctx, 16));

        TextView heading = new TextView(ctx);
        heading.setText(title);
        heading.setTextSize(13);
        heading.setTextColor(0x80FFFFFF);
        heading.setTypeface(UiFont.get(ctx, R.string.font_regular));
        heading.setSingleLine(true);
        box.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        valueText = new TextView(ctx);
        valueText.setTextSize(44);
        valueText.setTextColor(0xFFFFFFFF);
        valueText.setTypeface(UiFont.get(ctx, R.string.font_medium));
        valueText.setIncludeFontPadding(false);
        valueText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = PanelRows.dp(ctx, 8);
        box.addView(valueText, vp);

        track = new Track(ctx);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(ctx, 32));
        tp.topMargin = PanelRows.dp(ctx, 14);
        box.addView(track, tp);
        update();
    }

    /** A view between the number and the track that shows the effect of the value (the dock band swatch). */
    SliderPopup preview(View view, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                PanelRows.dp(ctx, heightDp));
        lp.topMargin = PanelRows.dp(ctx, 12);
        box.addView(view, 2, lp);
        return this;
    }

    /** A line under the track (the keys). */
    SliderPopup hint(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(0x80FFFFFF);
        t.setTypeface(UiFont.get(ctx, R.string.font_regular));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = PanelRows.dp(ctx, 12);
        box.addView(t, lp);
        return this;
    }

    /** Called with every value shown, so a live preview can follow, and with the original one on cancel. */
    SliderPopup onChange(IntConsumer onChange) {
        this.onChange = onChange;
        return this;
    }

    boolean isShowing() {
        return showing;
    }

    int value() {
        return value;
    }

    /**
     * Opens the popup over the settings card, centred on {@code source}'s row. {@code list} is the settings list
     * behind it: it dims and takes no focus meanwhile. {@code confirm} gets the value on OK; a cancel puts the
     * original back through {@code onChange} and calls neither.
     */
    void show(ViewGroup list, IntConsumer confirm, Runnable close) {
        this.list = list;
        this.onConfirm = confirm;
        this.onClose = close;
        int width = PanelRows.dp(ctx, WIDTH_DP);
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = box.getMeasuredHeight();
        int[] a = new int[2];
        int[] b = new int[2];
        source.getLocationInWindow(a);
        host.getLocationInWindow(b);
        int rowCenter = a[1] - b[1] + source.getHeight() / 2;
        int inset = PanelRows.dp(ctx, INSET_DP);
        int minTop = card.getTop() + inset;
        int maxTop = card.getBottom() - inset - height;
        int top = Math.max(minTop, Math.min(rowCenter - height / 2, Math.max(minTop, maxTop)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = card.getRight() - inset - width;
        lp.topMargin = top;
        host.addView(box, lp);

        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        list.animate().alpha(0.45f).setDuration(fadeMs).start();
        showing = true;
        box.setAlpha(0f);
        box.setScaleY(0.96f);
        box.setPivotX(width / 2f);
        box.setPivotY(height / 2f);
        box.animate().alpha(1f).scaleY(1f).setDuration(animMs).setInterpolator(new DecelerateInterpolator()).start();
        if (onChange != null) onChange.accept(value);
    }

    /**
     * Left/Right move the value (a direction is flipped when {@code rtl}), OK keeps it, Back puts the old one
     * back; Up/Down do nothing. Returns true for every key: the popup owns the keyboard while it shows.
     */
    boolean handleKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.getRepeatCount() == 0) cancel();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && event.getRepeatCount() == 0) confirm();
            return true;
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && down) {
            long now = event.getEventTime();
            if (SliderModel.accept(event.getRepeatCount(), now, lastMoveMs)) {
                lastMoveMs = now;
                long held = now - event.getDownTime();
                int amount = SliderModel.stepFor(held, step);
                boolean up = (code == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl;
                nudge(up ? 1 : -1, amount / step);
            }
        }
        return true;
    }

    /** Moves {@code steps} grid points up (+1) or down (-1), stopping at the ends. */
    private void nudge(int direction, int steps) {
        int next = value;
        for (int k = 0; k < steps; k++) next = SliderModel.move(next, direction, step, min, max);
        if (next == value) return;
        value = next;
        update();
        if (onChange != null) onChange.accept(value);
    }

    private void update() {
        valueText.setText(formatter.format(value));
        track.setFraction(SliderModel.fraction(value, min, max));
    }

    private void confirm() {
        if (!showing) return;
        close();
        if (onConfirm != null) onConfirm.accept(value);
    }

    /** Back: the original value returns (the preview follows), nothing is saved. */
    void cancel() {
        if (!showing) return;
        if (onChange != null) onChange.accept(original);
        close();
    }

    private void close() {
        showing = false;
        if (list != null) {
            list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            list.animate().alpha(1f).setDuration(fadeMs).start();
        }
        source.requestFocus();
        final View gone = box;
        gone.animate().alpha(0f).setDuration(fadeMs).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> host.removeView(gone)).start();
        if (onClose != null) onClose.run();
    }

    /** The groove, the filled part and the thumb, in the style's accent. */
    private final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;

        Track(Context ctx) {
            super(ctx);
        }

        void setFraction(float f) {
            fraction = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float thumb = 11 * d;
            float groove = 8 * d;
            float left = thumb;
            float right = getWidth() - thumb;
            float cy = getHeight() / 2f;
            float pos = rtl ? right - (right - left) * fraction : left + (right - left) * fraction;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33FFFFFF);
            rect.set(left - groove / 2, cy - groove / 2, right + groove / 2, cy + groove / 2);
            canvas.drawRoundRect(rect, groove / 2, groove / 2, paint);
            paint.setColor(theme.accent);
            if (rtl) rect.set(pos, cy - groove / 2, right + groove / 2, cy + groove / 2);
            else rect.set(left - groove / 2, cy - groove / 2, pos, cy + groove / 2);
            canvas.drawRoundRect(rect, groove / 2, groove / 2, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(pos, cy, thumb, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * d);
            paint.setColor(theme.accent);
            canvas.drawCircle(pos, cy, thumb - 1.5f * d, paint);
        }
    }
}
