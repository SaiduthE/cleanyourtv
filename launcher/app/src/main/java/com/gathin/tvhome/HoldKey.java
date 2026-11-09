package com.gathin.tvhome;

/**
 * Hold-OK detection for the Launcher settings rows, with no Android in it so it runs in JVM tests. The caller feeds
 * it the key-down, a clock tick and the key-up; it says when the hold is long enough and what the key-up means.
 * Key repeats of a held key are ignored. A hold that fired is not a click: its key-up is consumed.
 */
final class HoldKey {

    /** The platform's default long-press timeout, used when none is given (the activity passes the real one). */
    static final long HOLD_MS = 500;

    /** What a key-up means. */
    enum Up { NONE, CLICK, CONSUMED }

    private final long holdMs;
    private long downAt = -1;
    private boolean fired;

    HoldKey() {
        this(HOLD_MS);
    }

    /** @param holdMs how long OK must stay down before it counts as a hold */
    HoldKey(long holdMs) {
        this.holdMs = holdMs;
    }

    long holdMs() {
        return holdMs;
    }

    /** DPAD_CENTER (23), ENTER (66) and NUMPAD_ENTER (160). */
    static boolean isHoldKey(int keyCode) {
        return keyCode == 23 || keyCode == 66 || keyCode == 160;
    }

    /** A key-down. True when it starts a hold; a repeat of the held key (repeatCount > 0) never does. */
    boolean down(long now, int repeatCount) {
        if (repeatCount > 0 || downAt >= 0) return false;
        downAt = now;
        fired = false;
        return true;
    }

    boolean tracking() {
        return downAt >= 0;
    }

    /** True once, the first time the hold is long enough. */
    boolean due(long now) {
        if (downAt < 0 || fired || now - downAt < holdMs) return false;
        fired = true;
        return true;
    }

    /** The key went up: a click if the hold never fired, else the up that ends a hold. */
    Up up() {
        if (downAt < 0) return Up.NONE;
        Up result = fired ? Up.CONSUMED : Up.CLICK;
        cancel();
        return result;
    }

    /** Forget the hold (another key was pressed, or the screen went away). */
    void cancel() {
        downAt = -1;
        fired = false;
    }
}
