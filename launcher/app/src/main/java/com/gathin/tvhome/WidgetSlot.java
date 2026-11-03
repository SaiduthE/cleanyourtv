package com.gathin.tvhome;

/**
 * The places on Home a widget can dock. Each is a spot inside the header's own layout (the clock row or the top
 * bar), so a widget makes room by pushing its neighbours instead of covering them, and none can land on the Top
 * Shelf, the greeting, the big app name or the dock. Pure, so the JVM tests cover the persistence.
 */
enum WidgetSlot {
    /** Beside the date, in the clock row: where the weather chip has always been. */
    CLOCK_SIDE("clock_side"),
    /** The top bar, before the Inputs pill. */
    TOP_BAR_START("top_bar_start"),
    /** The top bar, after the Settings gear. */
    TOP_BAR_END("top_bar_end");

    final String key;

    WidgetSlot(String key) {
        this.key = key;
    }

    /** The slot stored under this key, or null. */
    static WidgetSlot fromKey(String key) {
        if (key == null) return null;
        for (WidgetSlot slot : values()) if (slot.key.equals(key)) return slot;
        return null;
    }
}
