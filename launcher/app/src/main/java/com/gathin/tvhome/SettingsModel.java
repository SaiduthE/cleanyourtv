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

    enum Group { HOME_LAYOUT, LOOK, TOP_SHELF, BUTTONS, SYSTEM }

    enum Row {
        COLUMNS, ROWS_BELOW_DOCK, DOCK_OPACITY, OPEN_ON_START,
        STYLE, WALLPAPER, CLOCK, WIDGETS, ICONS, ANIMATIONS, ADVANCED,
        TOP_SHELF, APP_ARTWORK, PHOTOS, PHOTO_THEMES, REFRESH_PHOTOS, CHANGE_EVERY, PHOTO_CREDIT,
        REMOTE_BUTTONS, TOP_BAR_BUTTONS, REORDER, EDIT_DOCK,
        DEFAULT_HOME, TV_SETTINGS, RESET_THEME
    }

    /** How a row answers OK. */
    enum Kind { OPTIONS, NAVIGATE, ACTION, CONFIRM }

    static final int[] COLUMNS = {4, 5, 6, 7};
    static final int[] ROWS_BELOW_DOCK = {0, 1, 2, 3};
    static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    /** The Icons row: Config.ICONS_ROUNDED (the launcher's look) or Config.ICONS_DEFAULT (the original icons). */
    static final String[] ICON_STYLES = {Config.ICONS_ROUNDED, Config.ICONS_DEFAULT};

    // ---- Top Shelf > Change every ----------------------------------------------------------------------------------

    /** How long each idle-shelf picture stays before the next crossfade, in the order the picker lists them. */
    static final long[] SHELF_INTERVALS_MS = {10_000L, 15_000L, 30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L};
    static final String[] SHELF_INTERVAL_LABELS = {"10 s", "15 s", "30 s", "1 min", "5 min", "15 min", "1 hour"};
    static final long SHELF_INTERVAL_DEFAULT_MS = 15_000L;

    /** Reads one saved number (SharedPreferences::getLong fits); a test hands in a map. */
    interface LongReader {
        long get(String key, long fallback);
    }

    /** The saved interval, or the default when nothing is saved or the saved value is not one of the choices. */
    static long shelfInterval(long stored) {
        for (long ms : SHELF_INTERVALS_MS) if (ms == stored) return ms;
        return SHELF_INTERVAL_DEFAULT_MS;
    }

    /** The interval saved under {@link Config#TOP_SHELF_INTERVAL_KEY}; the default for a launcher that never saved one. */
    static long shelfInterval(LongReader saved) {
        return shelfInterval(saved.get(Config.TOP_SHELF_INTERVAL_KEY, SHELF_INTERVAL_DEFAULT_MS));
    }

    static int shelfIntervalIndex(long ms) {
        for (int i = 0; i < SHELF_INTERVALS_MS.length; i++) if (SHELF_INTERVALS_MS[i] == shelfInterval(ms)) return i;
        return 1;
    }

    /**
     * The rows of the Top Shelf group that live in the Continue watching add-on (read and written through its
     * provider, see PhotoSettingsClient): they are greyed while the add-on is missing.
     */
    static boolean isAddOnRow(Row row) {
        return row == Row.PHOTOS || row == Row.PHOTO_THEMES || row == Row.REFRESH_PHOTOS;
    }

    /**
     * The saved on-TV choices "Reset Theme" clears (keys of Config.SETTINGS_NAME), and the ones that count as
     * "changes". One list, so the button and its "N changes" value cannot disagree. widget_placements is where the
     * Widgets manager keeps what sits on Home.
     */
    static final String[] RESET_KEYS = {
            "columns", Config.ROWS_BELOW_DOCK_KEY, "animations", "advanced_effects", Config.WALLPAPER_CHOICE_KEY,
            Config.DOCK_OPACITY_KEY, Config.CLOCK_FORMAT_KEY, Config.ICON_STYLE_KEY, OpenOnStart.KEY, WidgetStore.PLACEMENTS_KEY,
    };

    private SettingsModel() { }

    static Group[] groups() {
        return Group.values();
    }

    /**
     * The rows of a group, in order. Open on start needs open_on_start_choice, Edit dock needs the dock to be a row
     * of its own.
     */
    static List<Row> rows(Group group, boolean openOnStartChoice, boolean dockRow) {
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
                rows.add(Row.CLOCK);
                rows.add(Row.WIDGETS);
                rows.add(Row.ICONS);
                rows.add(Row.ANIMATIONS);
                rows.add(Row.ADVANCED);
                break;
            case TOP_SHELF:
                rows.add(Row.TOP_SHELF);
                rows.add(Row.APP_ARTWORK);
                rows.add(Row.PHOTOS);
                rows.add(Row.PHOTO_THEMES);
                rows.add(Row.REFRESH_PHOTOS);
                rows.add(Row.CHANGE_EVERY);
                rows.add(Row.PHOTO_CREDIT);
                break;
            case BUTTONS:
                rows.add(Row.REMOTE_BUTTONS);
                rows.add(Row.TOP_BAR_BUTTONS);
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
                return Kind.NAVIGATE;
            case WIDGETS:
            case REORDER:
            case EDIT_DOCK:
            case REFRESH_PHOTOS:
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
        return row == Row.COLUMNS || row == Row.ROWS_BELOW_DOCK || row == Row.CLOCK;
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
