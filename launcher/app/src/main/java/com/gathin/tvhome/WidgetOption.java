package com.gathin.tvhome;

/**
 * One line of a widget's settings popup: either a choice that OK steps through its values (a toggle is a choice of
 * "on" and "off"), or an action (OK runs it, e.g. "Change city" opening the Weather app). The widget's definition
 * lists them; the popup draws them. No Android in it.
 */
final class WidgetOption {

    /** Values every toggle shares. */
    static final String ON = "on";
    static final String OFF = "off";

    final String key;
    final int labelRes;
    /** Empty for an action. */
    final String[] values;
    /** A string resource per value. */
    final int[] valueLabelRes;
    final String defaultValue;

    private WidgetOption(String key, int labelRes, String[] values, int[] valueLabelRes, String defaultValue) {
        this.key = key;
        this.labelRes = labelRes;
        this.values = values;
        this.valueLabelRes = valueLabelRes;
        this.defaultValue = defaultValue;
    }

    static WidgetOption choice(String key, int labelRes, String defaultValue, String[] values, int[] valueLabelRes) {
        return new WidgetOption(key, labelRes, values, valueLabelRes, defaultValue);
    }

    static WidgetOption toggle(String key, int labelRes, boolean defaultOn, int onRes, int offRes) {
        return new WidgetOption(key, labelRes, new String[] {ON, OFF}, new int[] {onRes, offRes}, defaultOn ? ON : OFF);
    }

    /** An action: its {@code key} is what the widget's handler is given. */
    static WidgetOption action(String key, int labelRes) {
        return new WidgetOption(key, labelRes, new String[0], new int[0], "");
    }

    boolean isAction() {
        return values.length == 0;
    }

    /** The stored value if it is one of ours, else the default. */
    String valueOrDefault(String stored) {
        return indexOf(stored) >= 0 ? stored : defaultValue;
    }

    /** The value after {@code current} (wrapping); the second value for an unknown one that is the default. */
    String next(String current) {
        if (values.length == 0) return "";
        int at = indexOf(valueOrDefault(current));
        return values[(at + 1) % values.length];
    }

    /** The value before {@code current} (wrapping). */
    String previous(String current) {
        if (values.length == 0) return "";
        int at = indexOf(valueOrDefault(current));
        return values[(at + values.length - 1) % values.length];
    }

    int indexOf(String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    int labelOf(String value) {
        int i = indexOf(valueOrDefault(value));
        return i < 0 ? 0 : valueLabelRes[i];
    }

    /** Where a widget's option lives in the launcher's preferences. */
    static String storageKey(String widgetId, String optionKey) {
        return "widget_" + widgetId + "_" + optionKey;
    }

    /** A place to keep option values; SharedPreferences in the app, a map in tests. */
    interface Values {
        String get(String storageKey);

        void put(String storageKey, String value);
    }
}
