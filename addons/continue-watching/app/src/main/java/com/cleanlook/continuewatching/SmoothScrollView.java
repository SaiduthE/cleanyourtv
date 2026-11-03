package com.cleanlook.continuewatching;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/**
 * A ScrollView that moves once per focus change: one 150-200 ms decelerating glide that puts the focused item in the
 * middle. The platform's own jump to the focused child and its arrow-key scrolling are switched off, so there is never a
 * second animation fighting the first; a new focus (a key held down) redirects the running glide from where it is.
 */
public final class SmoothScrollView extends ScrollView {
    static final int GLIDE_MS = 180;
    static final int GLIDE_REPEAT_MS = 150;

    private ValueAnimator glide;
    private long lastGlideAt;
    private boolean jumpNext;

    public SmoothScrollView(Context c) {
        super(c);
    }

    public SmoothScrollView(Context c, AttributeSet a) {
        super(c, a);
    }

    public SmoothScrollView(Context c, AttributeSet a, int style) {
        super(c, a, style);
    }

    /** The scroll position that centres an item whose middle is {@code centre} (content pixels), kept inside the content. */
    static int centreTarget(int centre, int viewportH, int maxScroll) {
        return Math.max(0, Math.min(centre - viewportH / 2, Math.max(0, maxScroll)));
    }

    /** The next focus change moves at once (a freshly built screen) instead of gliding. */
    void jumpOnNextFocus() {
        jumpNext = true;
    }

    // The platform scrolls the focused child into view by itself; this view does it once, below.
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
        if (getHeight() == 0 || getChildCount() == 0 || !v.isLaidOut()) {
            post(new Runnable() {
                @Override public void run() {
                    if (v.isAttachedToWindow() && getHeight() > 0) reveal(v);
                }
            });
            return;
        }
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        offsetDescendantRectToMyCoords(v, r);
        View content = getChildAt(0);
        int maxScroll = content.getHeight() + getPaddingTop() + getPaddingBottom() - getHeight();
        // r is now in content coordinates (the child's own offset, padding included, is added; this view's scroll is not).
        int target = centreTarget(r.centerY(), getHeight(), maxScroll);
        glideTo(target, !jumpNext);
        jumpNext = false;
    }

    void glideTo(int target, boolean animate) {
        if (glide != null) glide.cancel();
        glide = null;
        int from = getScrollY();
        if (!animate || from == target) {
            scrollTo(0, target);
            return;
        }
        long now = System.currentTimeMillis();
        boolean repeating = now - lastGlideAt < 140;
        lastGlideAt = now;
        glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(repeating ? GLIDE_REPEAT_MS : GLIDE_MS);
        glide.setInterpolator(new DecelerateInterpolator(1.6f));
        glide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                scrollTo(0, (Integer) a.getAnimatedValue());
            }
        });
        glide.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (glide != null) glide.cancel();
        super.onDetachedFromWindow();
    }
}
