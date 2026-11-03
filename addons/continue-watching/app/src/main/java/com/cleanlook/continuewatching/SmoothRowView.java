package com.cleanlook.continuewatching;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;

/**
 * The sideways twin of {@link SmoothScrollView}, for one row of cards: a single decelerating glide that puts the focused
 * card in the middle (held inside the row at either end). The platform's own jump and arrow scrolling are off, so the
 * focus frame and the row move together and nothing animates twice.
 */
public final class SmoothRowView extends HorizontalScrollView {
    private ValueAnimator glide;
    private long lastGlideAt;
    private boolean jumpNext;
    private int pendingX = -1;

    public SmoothRowView(Context c) {
        super(c);
    }

    public SmoothRowView(Context c, AttributeSet a) {
        super(c, a);
    }

    public SmoothRowView(Context c, AttributeSet a, int style) {
        super(c, a, style);
    }

    /** The next focus change moves at once (a freshly built screen) instead of gliding. */
    void jumpOnNextFocus() {
        jumpNext = true;
    }

    /** Scrolls to {@code x} as soon as the row has been laid out (a rebuilt page keeps each row where it was). */
    void restoreScroll(int x) {
        pendingX = x;
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingX >= 0) {
            scrollTo(pendingX, 0);
            pendingX = -1;
        }
    }

    @Override protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override public boolean arrowScroll(int direction) {
        return false;
    }

    @Override public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (focused != null) reveal(focused);
    }

    private void reveal(final View v) {
        if (getWidth() == 0 || getChildCount() == 0 || !v.isLaidOut()) {
            post(new Runnable() {
                @Override public void run() {
                    if (v.isAttachedToWindow() && getWidth() > 0) reveal(v);
                }
            });
            return;
        }
        pendingX = -1;
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        offsetDescendantRectToMyCoords(v, r);
        View content = getChildAt(0);
        int maxScroll = content.getWidth() + getPaddingLeft() + getPaddingRight() - getWidth();
        int target = SmoothScrollView.centreTarget(r.centerX(), getWidth(), maxScroll);
        glideTo(target, !jumpNext);
        jumpNext = false;
    }

    private void glideTo(int target, boolean animate) {
        if (glide != null) glide.cancel();
        glide = null;
        int from = getScrollX();
        if (!animate || from == target) {
            scrollTo(target, 0);
            return;
        }
        long now = System.currentTimeMillis();
        boolean repeating = now - lastGlideAt < 140;
        lastGlideAt = now;
        glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(repeating ? SmoothScrollView.GLIDE_REPEAT_MS : SmoothScrollView.GLIDE_MS);
        glide.setInterpolator(new DecelerateInterpolator(1.6f));
        glide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                scrollTo((Integer) a.getAnimatedValue(), 0);
            }
        });
        glide.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (glide != null) glide.cancel();
        super.onDetachedFromWindow();
    }
}
