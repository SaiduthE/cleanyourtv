package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/**
 * The settings list. The stock ScrollView moves for a focus change twice (a jump in requestChildFocus, a second
 * animation from arrowScroll), so the highlight and the list never agree. Here one place decides: when a row takes
 * focus the list glides, in one animation, to the offset that centres that row (with its group heading when it is
 * the first of a group). Up and Down only move focus. A key repeat redirects the running animation instead of
 * stacking another; nothing is allocated per key.
 */
final class GlideScrollView extends ScrollView {

    private final ValueAnimator glide = ValueAnimator.ofInt(0, 0);
    private int goal = Integer.MIN_VALUE;
    private int durationMs;
    private View pendingJump;

    GlideScrollView(Context context) {
        super(context);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> scrollTo(0, (Integer) a.getAnimatedValue()));
    }

    /** Length of a glide; 0 moves at once. */
    void setDurationMs(int ms) {
        durationMs = ms;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (focused != null) focused.setRevealOnFocusHint(false);   // the stock jump is off; glideTo does it
        super.requestChildFocus(child, focused);
        if (focused == null || getChildCount() == 0) return;
        if (!isLaidOut() || getHeight() == 0) {
            pendingJump = focused;   // the first focus, before the first layout: placed in onLayout
            return;
        }
        glideTo(targetFor(focused));
    }

    @Override
    public boolean arrowScroll(int direction) {
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return super.arrowScroll(direction);
        View current = findFocus();
        View next = FocusFinder.getInstance().findNextFocus(this, current == this ? null : current, direction);
        if (next != null && next != current) next.requestFocus(direction);   // requestChildFocus scrolls
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingJump != null && getChildCount() > 0) {
            View view = pendingJump;
            pendingJump = null;
            glide.cancel();
            scrollTo(0, targetFor(view));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        glide.cancel();
        super.onDetachedFromWindow();
    }

    private void glideTo(int target) {
        if (target == goal && glide.isRunning()) return;
        if (target == getScrollY()) {
            glide.cancel();
            goal = target;
            return;
        }
        glide.cancel();
        goal = target;
        if (durationMs <= 0) {
            scrollTo(0, target);
            return;
        }
        glide.setIntValues(getScrollY(), target);
        glide.setDuration(durationMs);
        glide.start();
    }

    /** The scroll offset that centres the row of {@code focused} (and its heading), kept inside the content. */
    private int targetFor(View focused) {
        ViewGroup content = (ViewGroup) getChildAt(0);
        View row = focused;
        while (row.getParent() != content && row.getParent() instanceof View) row = (View) row.getParent();
        int top = row.getTop();
        int bottom = row.getBottom();
        int index = content.indexOfChild(row);
        if (index > 0) {
            View before = content.getChildAt(index - 1);
            if (!before.isFocusable()) top = before.getTop();
        }
        int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
        int max = Math.max(0, content.getHeight() - viewport);
        return Math.max(0, Math.min(max, (top + bottom - viewport) / 2));
    }
}
