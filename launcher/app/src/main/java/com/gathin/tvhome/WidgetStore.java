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
    /**
     * The owner's placements as they were when a scene-clock style (Horizon) was applied, set aside while that style is
     * on ("" for none saved), and put back when another look is applied.
     */
    static final String BEFORE_SCENE_KEY = "widget_placements_before_scene";
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
        boolean scene = sceneClockOn();
        if (scene && text != null && !prefs.contains(BEFORE_SCENE_KEY)) {
            // A scene-clock look that was put on without setting the owner's placements aside (an older build, or a
            // theme pushed over ADB): set them aside once now, so the look's own defaults show and leaving it brings
            // them back. Placements the owner chooses while it is on are kept from then on.
            setAsideForScene();
            text = null;
        }
        return WidgetPlacements.parse(text, WidgetRegistry.defaults(scene), WidgetRegistry.RULES);
    }

    /** Whether the look in use draws the scene clock (Horizon), which shows the weather itself. */
    private boolean sceneClockOn() {
        boolean fallback = ctx.getResources().getBoolean(R.bool.scene_clock);
        org.json.JSONObject overlay = ThemeOverlay.read(ctx);
        return overlay == null ? fallback : overlay.optBoolean("scene_clock", fallback);
    }

    /**
     * A scene-clock style is being applied over another look: the owner's placements are kept aside and the style's own
     * defaults show (no weather, brightness before the network pill) until it is left.
     */
    void setAsideForScene() {
        String text = prefs.getString(PLACEMENTS_KEY, null);
        prefs.edit().putString(BEFORE_SCENE_KEY, text == null ? "" : text).remove(PLACEMENTS_KEY).apply();
    }

    /** A scene-clock style is being left: the owner's own placements come back (nothing changes if none were set aside). */
    void restoreAfterScene() {
        if (!prefs.contains(BEFORE_SCENE_KEY)) return;
        String text = prefs.getString(BEFORE_SCENE_KEY, "");
        SharedPreferences.Editor edit = prefs.edit().remove(BEFORE_SCENE_KEY);
        if (text == null || text.isEmpty()) edit.remove(PLACEMENTS_KEY);
        else edit.putString(PLACEMENTS_KEY, text);
        edit.apply();
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
