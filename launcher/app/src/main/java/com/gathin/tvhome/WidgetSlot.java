package com.gathin.tvhome;

/**
 * The places on Home a widget can dock. Each is a spot inside the header's own layout (the clock row or the top
 * bar), so a widget makes room by pushing its neighbours instead of covering them, and none can land on the Top
 * Shelf, the greeting, the big app name or the dock. Pure, so the JVM tests cover the persistence.
 */
enum WidgetSlot {
    /** Beside the date, in the clock row: where the weather chip has always been. */
    CLOCK_SIDE("clock_side"),
    /** The clock row, right after the Beside-the-clock chip (or right after the clock block when that slot is empty). */
    BESIDE_WEATHER("beside_weather"),
    /** The top bar, before the Inputs pill. */
    TOP_BAR_START("top_bar_start"),
    /** The top bar, after the Settings gear. */
    TOP_BAR_END("top_bar_end");

    final String key;

    WidgetSlot(String key) {
        this.key = key;
    }

    private static final WidgetSlot[] CLOCK_ROW = {CLOCK_SIDE, BESIDE_WEATHER};
    private static final WidgetSlot[] BEFORE_BAR = {CLOCK_SIDE, BESIDE_WEATHER, TOP_BAR_START};

    /** The slots in the clock row, left to right. */
    static WidgetSlot[] clockRow() {
        return CLOCK_ROW.clone();
    }

    /** The slots left of the top-bar buttons, in D-pad order from the clock. */
    static WidgetSlot[] beforeBar() {
        return BEFORE_BAR.clone();
    }

    boolean inClockRow() {
        return rowOrder() >= 0;
    }

    /** Its place among the clock row's slots, or -1 outside the row. */
    int rowOrder() {
        for (int i = 0; i < CLOCK_ROW.length; i++) if (CLOCK_ROW[i] == this) return i;
        return -1;
    }

    /** The next slot to the right among those before the buttons, or null (the buttons come next). */
    WidgetSlot nextBeforeBar() {
        for (int i = 0; i < BEFORE_BAR.length - 1; i++) if (BEFORE_BAR[i] == this) return BEFORE_BAR[i + 1];
        return null;
    }

    /** The next slot to the left among those before the buttons, or null. */
    WidgetSlot previousBeforeBar() {
        for (int i = 1; i < BEFORE_BAR.length; i++) if (BEFORE_BAR[i] == this) return BEFORE_BAR[i - 1];
        return null;
    }

    /** The slot stored under this key, or null. */
    static WidgetSlot fromKey(String key) {
        if (key == null) return null;
        for (WidgetSlot slot : values()) if (slot.key.equals(key)) return slot;
        return null;
    }
}
