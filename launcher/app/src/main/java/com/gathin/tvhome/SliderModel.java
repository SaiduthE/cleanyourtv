package com.gathin.tvhome;

/**
 * The arithmetic of a numeric slider, with no Android in it so it runs in JVM tests: clamping, the grid the value
 * snaps to, the step for a held key and the pace of key repeats. {@link SliderPopup} draws it.
 */
final class SliderModel {

    /** After this long on a held arrow the step doubles. */
    static final long FAST_AFTER_MS = 1000;
    /** Held-key repeats arrive faster than a slider is readable; one is taken per this many ms. */
    static final long REPEAT_GAP_MS = 90;

    private SliderModel() { }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The step for a key held {@code heldMs}: {@code step}, doubled after FAST_AFTER_MS. */
    static int stepFor(long heldMs, int step) {
        return heldMs >= FAST_AFTER_MS ? step * 2 : step;
    }

    /**
     * One step up (+1) or down (-1) from {@code value}. An off-grid value first lands on the next grid point in that
     * direction (72 up by 5 is 75, down is 70), so a stored 72 does not stay off the grid; the result is clamped.
     */
    static int move(int value, int direction, int step, int min, int max) {
        int v = clamp(value, min, max);
        int rem = Math.floorMod(v - min, step);
        int to;
        if (direction > 0) to = v - rem + step;
        else to = rem == 0 ? v - step : v - rem;
        return clamp(to, min, max);
    }

    /** The nearest grid point (ties go up), clamped; where the slider opens on an off-grid stored value. */
    static int snap(int value, int step, int min, int max) {
        int v = clamp(value, min, max);
        int rem = Math.floorMod(v - min, step);
        int to = rem * 2 >= step ? v - rem + step : v - rem;
        return clamp(to, min, max);
    }

    /** Whether a key event takes effect: the first press always, repeats once the gap since the last one has passed. */
    static boolean accept(int repeatCount, long nowMs, long lastMs) {
        return repeatCount == 0 || nowMs - lastMs >= REPEAT_GAP_MS;
    }

    /** 0..1 along the track for a value. */
    static float fraction(int value, int min, int max) {
        return max <= min ? 0f : (clamp(value, min, max) - min) / (float) (max - min);
    }
}
