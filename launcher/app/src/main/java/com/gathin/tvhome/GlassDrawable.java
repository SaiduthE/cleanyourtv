package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A view background drawn by a {@link GlassSurface}: the stock glass (a card, a pill, a menu) with the grain, rim light
 * and frost of the glass switches. A pill passes its original selector as {@code states}: focused, pressed and activated
 * keep that drawable's look, and only the resting state is glass.
 *
 * The frost slice behind the view depends on where the view is on the screen, so the drawable redraws itself when the
 * view moves (a layout change, a popup sliding in) and when a new frost is built.
 */
final class GlassDrawable extends Drawable implements Drawable.Callback, Frost.Listener {

    /** A radius that depends on the size drawn (tiles scale theirs with the tile). */
    interface Radius {
        float of(int w, int h);
    }

    private final GlassSurface surface;
    @Nullable private final Drawable states;
    @Nullable private final Radius radius;
    private boolean resting = true;

    private final int[] where = new int[2];
    private int seenX = Integer.MIN_VALUE, seenY = Integer.MIN_VALUE;
    private float seenScaleX = 1f, seenScaleY = 1f;
    @Nullable private View watching;
    private final ViewTreeObserver.OnPreDrawListener watcher = () -> {
        // Cheap: two ints. A moved view needs the slice of frost that is now behind it.
        if (watching != null && hostView() != watching) {
            stopWatching();   // the view has another background now
        } else if (watching != null && watching.isAttachedToWindow()) {
            watching.getLocationOnScreen(where);
            if (where[0] != seenX || where[1] != seenY || watching.getScaleX() != seenScaleX
                    || watching.getScaleY() != seenScaleY) invalidateSelf();
        }
        return true;
    };
    private final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) { }

        @Override
        public void onViewDetachedFromWindow(@NonNull View v) {
            stopWatching();
        }
    };

    GlassDrawable(GlassSurface.Spec spec, @Nullable Drawable states) {
        this(spec, states, null);
    }

    GlassDrawable(GlassSurface.Spec spec, @Nullable Drawable states, @Nullable Radius radius) {
        this.surface = new GlassSurface(spec);
        this.states = states;
        this.radius = radius;
        if (states != null) states.setCallback(this);
        if (spec.frost) Frost.listen(this);
    }

    private float radiusFor(int w, int h) {
        return radius != null ? radius.of(w, h) : surface.spec().radiusPx;
    }

    /** The view this drawable is the background of, found through any layer drawables around it. */
    @Nullable
    private View hostView() {
        Callback cb = getCallback();
        while (cb instanceof Drawable) cb = ((Drawable) cb).getCallback();
        return cb instanceof View ? (View) cb : null;
    }

    private void startWatching(View v) {
        if (watching == v) return;
        stopWatching();
        watching = v;
        v.addOnAttachStateChangeListener(attach);
        v.getViewTreeObserver().addOnPreDrawListener(watcher);
    }

    private void stopWatching() {
        View v = watching;
        watching = null;
        if (v == null) return;
        v.removeOnAttachStateChangeListener(attach);
        ViewTreeObserver o = v.getViewTreeObserver();
        if (o.isAlive()) o.removeOnPreDrawListener(watcher);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (!resting && states != null) {
            states.setBounds(b);
            states.draw(canvas);
            return;
        }
        float sx = Float.NaN, sy = Float.NaN;
        float scaleX = 1f, scaleY = 1f;
        View v = hostView();
        if (v != null && v.isAttachedToWindow() && surface.frosted()) {
            v.getLocationOnScreen(where);
            seenX = where[0];
            seenY = where[1];
            // getLocationOnScreen is where the view's top left really is, scaled views included (a focus zoom, a popup
            // opening): the point (b.left, b.top) of the scaled canvas is scale * b.left further, not b.left.
            scaleX = v.getScaleX();
            scaleY = v.getScaleY();
            if (!(scaleX > 0.01f)) scaleX = 1f;
            if (!(scaleY > 0.01f)) scaleY = 1f;
            seenScaleX = v.getScaleX();
            seenScaleY = v.getScaleY();
            sx = where[0] + b.left * scaleX;
            sy = where[1] + b.top * scaleY;
            startWatching(v);
        }
        surface.setViewScale(scaleX, scaleY);
        surface.draw(canvas, b.left, b.top, b.width(), b.height(), radiusFor(b.width(), b.height()), sx, sy);
    }

    @Override
    public void onFrostChanged() {
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return states != null;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (states == null) return false;
        boolean plain = true;
        for (int s : state) {
            if (s == android.R.attr.state_focused || s == android.R.attr.state_activated
                    || s == android.R.attr.state_pressed) {
                plain = false;
            }
        }
        states.setState(state);
        boolean changed = plain != resting;
        resting = plain;
        return changed;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        Rect b = getBounds();
        outline.setRoundRect(b, Math.min(radiusFor(b.width(), b.height()), Math.min(b.width(), b.height()) / 2f));
        outline.setAlpha(surface.spec().opaque() ? 1f : 0f);
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
        unscheduleSelf(what);
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) { }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
