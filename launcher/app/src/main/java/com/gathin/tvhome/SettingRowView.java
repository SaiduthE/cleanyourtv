package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One 44 dp settings row: the label on the left; the value, an optional swatch and a chevron on the right; a thin
 * hold bar along the bottom that fills while OK is held. The slab behind it comes from {@link PanelRows}.
 */
final class SettingRowView extends LinearLayout {

    private final PanelTheme theme;
    private final TextView label;
    private final TextView value;
    private final TextView chevron;
    private final View swatch;
    private ImageView icon;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private float holdProgress;
    private ValueAnimator flash;

    SettingRowView(Context ctx, String labelText, boolean chevronShown, int fadeMs) {
        super(ctx);
        theme = PanelTheme.get(ctx);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(true);
        setClickable(true);
        setPaddingRelative(PanelRows.dp(ctx, 16), 0, PanelRows.dp(ctx, 14), 0);
        setBackground(PanelRows.slabBackground(ctx, PanelRows.ROW_RADIUS_DP, PanelRows.ROW_DP, fadeMs));

        label = text(ctx, 16, R.string.font_regular);
        label.setText(labelText);
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        value = text(ctx, 15, R.string.font_regular);
        value.setVisibility(GONE);
        swatch = new View(ctx);
        swatch.setVisibility(GONE);
        LayoutParams swatchParams = new LayoutParams(PanelRows.dp(ctx, 16), PanelRows.dp(ctx, 11));
        swatchParams.setMarginEnd(PanelRows.dp(ctx, 8));
        addView(swatch, swatchParams);
        LayoutParams valueParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        valueParams.setMarginStart(PanelRows.dp(ctx, 8));
        addView(value, valueParams);

        chevron = text(ctx, 19, R.string.font_regular);
        chevron.setText(ctx.getResources().getBoolean(R.bool.rtl_menu_bar) ? "‹" : "›");
        chevron.setTextColor(PanelRows.TEXT_TERTIARY);
        chevron.setVisibility(chevronShown ? VISIBLE : GONE);
        LayoutParams chevronParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chevronParams.setMarginStart(PanelRows.dp(ctx, 6));
        addView(chevron, chevronParams);

        track.setColor(0x1FFFFFFF);
        applyColors();
    }

    private static TextView text(Context ctx, float sp, int font) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTypeface(UiFont.get(ctx, font));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The value on the right; null or empty hides it (action rows show the chevron only). */
    void setValue(CharSequence text) {
        boolean has = text != null && text.length() > 0;
        value.setText(has ? text : "");
        value.setVisibility(has ? VISIBLE : GONE);
        // The label gives way first: a long value keeps its room.
        value.setMaxWidth(PanelRows.dp(getContext(), 150));
    }

    /** A small picture before the value (the Style row); null removes it. */
    void setSwatch(Drawable drawable) {
        swatch.setBackground(drawable);
        swatch.setVisibility(drawable == null ? GONE : VISIBLE);
    }

    void setIcon(Drawable drawable) {
        if (icon == null) {
            icon = new ImageView(getContext());
            LayoutParams lp = new LayoutParams(PanelRows.dp(getContext(), 28), PanelRows.dp(getContext(), 28));
            lp.setMarginEnd(PanelRows.dp(getContext(), 12));
            addView(icon, 0, lp);
        }
        icon.setImageDrawable(drawable);
    }

    /** A row that is switched off for now: dimmed, but it still takes focus and still explains itself. */
    void setDimmed(boolean dimmed) {
        setAlpha(dimmed ? 0.38f : 1f);
    }

    void setHoldProgress(float progress) {
        holdProgress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /** The value changed: its colour pulses to the accent and back over 200 ms. */
    void flashValue(boolean animate) {
        if (flash != null) flash.cancel();
        if (!animate || value.getVisibility() != VISIBLE) return;
        int rest = isFocused() ? PanelRows.VALUE_FOCUSED : theme.valueText;
        flash = ValueAnimator.ofObject(new ArgbEvaluator(), theme.accent, rest);
        flash.setDuration(200);
        flash.addUpdateListener(a -> value.setTextColor((Integer) a.getAnimatedValue()));
        flash.start();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (label != null) applyColors();
    }

    private void applyColors() {
        boolean focused = isFocused();
        label.setTextColor(focused ? PanelRows.TEXT_FOCUSED : PanelRows.TEXT_ROW);
        value.setTextColor(focused ? PanelRows.VALUE_FOCUSED : theme.valueText);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (holdProgress <= 0f) return;
        float d = getResources().getDisplayMetrics().density;
        float left = 14 * d;
        float right = getWidth() - 14 * d;
        float bottom = getHeight() - 5 * d;
        float radius = d;
        bar.set(left, bottom - 2 * d, right, bottom);
        canvas.drawRoundRect(bar, radius, radius, track);
        fill.setColor(theme.accent);
        bar.right = left + (right - left) * holdProgress;
        canvas.drawRoundRect(bar, radius, radius, fill);
    }
}
