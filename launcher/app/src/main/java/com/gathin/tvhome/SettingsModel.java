package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Launcher settings panel lists, with no Android in it so it runs in JVM tests: the groups and their rows
 * in order, the options of each, the on-TV choices "Reset Theme" clears, and the right-to-left order of the
 * numeric lists. Labels that come from string resources are looked up by the activity.
 */
final class SettingsModel {

    enum Group { HOME_LAYOUT, LOOK, BUTTONS, SYSTEM }

    enum Row {
        COLUMNS, ROWS_BELOW_DOCK, DOCK_OPACITY, OPEN_ON_START,
        STYLE, WALLPAPER, TOP_SHELF, CLOCK, WEATHER_ON_HOME, WEATHER_SETTINGS, ANIMATIONS, ADVANCED,
        REMOTE_BUTTONS, REORDER, EDIT_DOCK,
        DEFAULT_HOME, TV_SETTINGS, RESET_THEME
    }

    /** How a row answers OK. */
    enum Kind { OPTIONS, NAVIGATE, ACTION, CONFIRM }

    static final int[] COLUMNS = {4, 5, 6, 7};
    static final int[] ROWS_BELOW_DOCK = {0, 1, 2, 3};
    static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    static final String[] CLOCK_FORMATS = {"system", "12", "24"};

    /**
     * The saved on-TV choices "Reset Theme" clears (keys of Config.SETTINGS_NAME), and the ones that count as
     * "changes". One list, so the button and its "N changes" value cannot disagree. weather_widget is the Weather
     * on Home switch.
     */
    static final String[] RESET_KEYS = {
            "columns", Config.ROWS_BELOW_DOCK_KEY, "animations", "advanced_effects", Config.WALLPAPER_CHOICE_KEY,
            Config.DOCK_OPACITY_KEY, Config.CLOCK_FORMAT_KEY, OpenOnStart.KEY, "weather_widget",
    };

    private SettingsModel() { }

    static Group[] groups() {
        return Group.values();
    }

    /**
     * The rows of a group, in order. Open on start needs open_on_start_choice, the weather rows need the weather
     * source, Edit dock needs the dock to be a row of its own.
     */
    static List<Row> rows(Group group, boolean openOnStartChoice, boolean weatherAvailable, boolean dockRow) {
        List<Row> rows = new ArrayList<>();
        switch (group) {
            case HOME_LAYOUT:
                rows.add(Row.COLUMNS);
                rows.add(Row.ROWS_BELOW_DOCK);
                rows.add(Row.DOCK_OPACITY);
                if (openOnStartChoice) rows.add(Row.OPEN_ON_START);
                break;
            case LOOK:
                rows.add(Row.STYLE);
                rows.add(Row.WALLPAPER);
                rows.add(Row.TOP_SHELF);
                rows.add(Row.CLOCK);
                if (weatherAvailable) {
                    rows.add(Row.WEATHER_ON_HOME);
                    rows.add(Row.WEATHER_SETTINGS);
                }
                rows.add(Row.ANIMATIONS);
                rows.add(Row.ADVANCED);
                break;
            case BUTTONS:
                rows.add(Row.REMOTE_BUTTONS);
                rows.add(Row.REORDER);
                if (dockRow) rows.add(Row.EDIT_DOCK);
                break;
            default:
                rows.add(Row.DEFAULT_HOME);
                rows.add(Row.TV_SETTINGS);
                rows.add(Row.RESET_THEME);
                break;
        }
        return rows;
    }

    static Kind kind(Row row) {
        switch (row) {
            case REMOTE_BUTTONS:
            case DEFAULT_HOME:
            case TV_SETTINGS:
            case WEATHER_SETTINGS:
                return Kind.NAVIGATE;
            case REORDER:
            case EDIT_DOCK:
                return Kind.ACTION;
            case RESET_THEME:
                return Kind.CONFIRM;
            default:
                return Kind.OPTIONS;
        }
    }

    // ---- options ------------------------------------------------------------------------------------------------

    static String percent(int value) {
        return String.format(Locale.ROOT, "%d%%", value);
    }

    static String[] numberLabels(int[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = String.valueOf(values[i]);
        return out;
    }

    static String[] percentLabels(int[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = percent(values[i]);
        return out;
    }

    static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return -1;
    }

    static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    /** The lists Android's compatibility RTL mode would leave left-to-right: they run reversed under rtl_app_grid. */
    static boolean reversesInRtl(Row row) {
        return row == Row.COLUMNS || row == Row.ROWS_BELOW_DOCK || row == Row.DOCK_OPACITY || row == Row.CLOCK;
    }

    /** Which option sits at each position of a list of n: 0..n-1, or n-1..0 when reversed. */
    static int[] order(int n, boolean reversed) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = reversed ? n - 1 - i : i;
        return out;
    }

    // ---- Reset Theme --------------------------------------------------------------------------------------------

    /** How many of the reset keys are set; {@code has} answers whether a key is in the saved choices. */
    static int resetCount(KeyCheck has) {
        int n = 0;
        for (String key : RESET_KEYS) if (has.has(key)) n++;
        return n;
    }

    interface KeyCheck {
        boolean has(String key);
    }

    // ---- motion -------------------------------------------------------------------------------------------------

    /** A panel animation's length: instant with Animations off, capped at 90 ms with reduced motion. */
    static int duration(boolean animations, boolean reducedMotion, int baseMs) {
        if (!animations) return 0;
        return reducedMotion ? Math.min(baseMs, 90) : baseMs;
    }
}
