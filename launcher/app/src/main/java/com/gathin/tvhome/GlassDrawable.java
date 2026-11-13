package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A view background drawn by a {@link GlassSurface}: the stock glass (a card, a pill, a menu) with the grain, rim light
 * and frost of the glass switches. A pill passes its original selector as {@code states}: focused, pressed and activated
 * keep that drawable's look, and only the resting state is glass.
 */
final class GlassDrawable extends Drawable implements Drawable.Callback {

    /** A radius that depends on the size drawn (tiles scale theirs with the tile). */
    interface Radius {
        float of(int w, int h);
    }

    private final GlassSurface surface;
    @Nullable private final Drawable states;
    @Nullable private final Radius radius;
    private boolean resting = true;

    GlassDrawable(GlassSurface.Spec spec, @Nullable Drawable states) {
        this(spec, states, null);
    }

    GlassDrawable(GlassSurface.Spec spec, @Nullable Drawable states, @Nullable Radius radius) {
        this.surface = new GlassSurface(spec);
        this.states = states;
        this.radius = radius;
        if (states != null) states.setCallback(this);
    }

    private float radiusFor(int w, int h) {
        return radius != null ? radius.of(w, h) : surface.spec().radiusPx;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (!resting && states != null) {
            states.setBounds(b);
            states.draw(canvas);
            return;
        }
        surface.draw(canvas, b.left, b.top, b.width(), b.height(), radiusFor(b.width(), b.height()));
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
