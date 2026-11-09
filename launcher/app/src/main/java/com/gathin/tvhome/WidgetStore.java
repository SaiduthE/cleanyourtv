package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Where the Widgets manager keeps its choices: the launcher's own preferences (Config.SETTINGS_NAME, the file every
 * Launcher settings choice lives in), one string for the placements and one per widget option. SharedPreferences
 * writes the file atomically (a backup copy, then a rename), and a placement is a single string, so a write
 * cannot leave two widgets half moved.
 */
final class WidgetStore implements WidgetOption.Values {

    static final String PLACEMENTS_KEY = "widget_placements";
    /** Before the Widgets manager, "Weather on Home" was this on/off preference. */
    private static final String LEGACY_WEATHER_KEY = "weather_widget";

    private final Context ctx;
    private final SharedPreferences prefs;

    WidgetStore(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** What is docked where; the weather keeps its place beside the clock unless the owner moved or removed it. */
    WidgetPlacements placements() {
        String text = prefs.getString(PLACEMENTS_KEY, null);
        if (text == null && prefs.contains(LEGACY_WEATHER_KEY)) {
            // The old switch carries over once: Off was "not on Home".
            boolean wasOn = prefs.getBoolean(LEGACY_WEATHER_KEY, true);
            text = wasOn ? null : WidgetRegistry.WEATHER + "=" + WidgetPlacements.NONE;
            SharedPreferences.Editor edit = prefs.edit().remove(LEGACY_WEATHER_KEY);
            if (text != null) edit.putString(PLACEMENTS_KEY, text);
            edit.apply();
        }
        return WidgetPlacements.parse(text, WidgetRegistry.defaults(), WidgetRegistry.RULES);
    }

    void save(WidgetPlacements placements) {
        prefs.edit().putString(PLACEMENTS_KEY, placements.serialize()).apply();
    }

    @Override
    public String get(String storageKey) {
        return prefs.getString(storageKey, null);
    }

    @Override
    public void put(String storageKey, String value) {
        prefs.edit().putString(storageKey, value).apply();
    }

    /** The value of one of a widget's options (its default when never set). */
    String option(WidgetDef def, WidgetOption option) {
        return option.valueOrDefault(get(WidgetOption.storageKey(def.id, option.key)));
    }

    /** The Launcher settings row's value: how many widgets are on Home. */
    static String summary(Context ctx) {
        WidgetPlacements p = new WidgetStore(ctx).placements();
        int n = 0;
        for (WidgetDef def : WidgetRegistry.all()) if (p.slotOf(def.id) != null) n++;
        return n == 0 ? ctx.getString(R.string.widgets_none) : ctx.getString(R.string.widgets_count, n);
    }
}
