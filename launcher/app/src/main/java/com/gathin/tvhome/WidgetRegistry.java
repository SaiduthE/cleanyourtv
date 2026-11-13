package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every widget the manager lists. To add one, write its view class (a {@link HomeWidget} built from a
 * {@link WidgetContext}) and add one {@link WidgetDef} to {@link #ALL}: the manager's grid, the slot picker, the
 * settings popup and the saved placements all work from this list.
 */
final class WidgetRegistry {

    static final String WEATHER = "weather";
    static final String BRIGHTNESS = "brightness";

    private WidgetRegistry() { }

    static List<WidgetDef> all() {
        return ALL;
    }

    static WidgetDef get(String id) {
        for (WidgetDef def : ALL) if (def.id.equals(id)) return def;
        return null;
    }

    /** The slot each widget docks in until the owner chooses: the weather keeps its place beside the clock. */
    static Map<String, WidgetSlot> defaults() {
        return defaults(false);
    }

    /**
     * The same, for a look with the scene clock (Horizon): the clock already shows the weather, so the weather widget is
     * not docked, and the brightness chip sits in the top bar right before the network pill. These are only defaults: a
     * choice the owner saves in the Widgets manager beats them, and a bundled style set aside the owner's own placements
     * when it was applied and puts them back when it is left (see {@link WidgetStore#setAsideForScene}).
     */
    static Map<String, WidgetSlot> defaults(boolean sceneClock) {
        Map<String, WidgetSlot> out = new LinkedHashMap<>();
        for (WidgetDef def : ALL) out.put(def.id, def.defaultSlot);
        if (sceneClock) {
            out.put(WEATHER, null);
            out.put(BRIGHTNESS, WidgetSlot.BESIDE_NETWORK);
        }
        return out;
    }

    /** What a slot can host, for {@link WidgetPlacements}. */
    static final WidgetPlacements.SlotRules RULES = (id, slot) -> {
        WidgetDef def = get(id);
        return def != null && def.supports(slot);
    };

    // ---- the widgets --------------------------------------------------------------------------------------------

    static final WidgetOption WEATHER_UNIT = WidgetOption.choice("unit", R.string.widget_weather_unit, "add_on",
            new String[] {"add_on", "c", "f"},
            new int[] {R.string.widget_unit_add_on, R.string.widget_unit_c, R.string.widget_unit_f});
    static final WidgetOption WEATHER_PLACE = WidgetOption.toggle("place", R.string.widget_weather_place, true,
            R.string.on, R.string.off);
    static final WidgetOption WEATHER_CITY = WidgetOption.action("city", R.string.widget_weather_city);

    static final WidgetOption BRIGHTNESS_STEP = WidgetOption.choice("step", R.string.widget_brightness_step, "auto",
            new String[] {"auto", "5", "10"},
            new int[] {R.string.widget_step_auto, R.string.widget_step_5, R.string.widget_step_10});
    static final WidgetOption BRIGHTNESS_STYLE = WidgetOption.choice("style", R.string.widget_brightness_style,
            "icon_level", new String[] {"icon_level", "level", "icon"},
            new int[] {R.string.widget_style_icon_level, R.string.widget_style_level, R.string.widget_style_icon});

    // Declared after the options above: the definitions below use them while this initializer runs.
    private static final List<WidgetDef> ALL;

    static {
        List<WidgetDef> all = new ArrayList<>();
        all.add(weather());
        all.add(brightness());
        ALL = Collections.unmodifiableList(all);
    }

    private static WidgetDef weather() {
        return new WidgetDef(WEATHER, R.string.widget_weather, Icons.WIDGET_WEATHER,
                R.string.widget_weather_missing, EnumSet.allOf(WidgetSlot.class), WidgetSlot.CLOCK_SIDE,
                WeatherSource::available,
                Arrays.asList(WEATHER_UNIT, WEATHER_PLACE, WEATHER_CITY),
                (ctx, key) -> {
                    if ("city".equals(key)) openWeatherSettings(ctx);
                },
                WeatherWidget::create);
    }

    private static WidgetDef brightness() {
        return new WidgetDef(BRIGHTNESS, R.string.widget_brightness, Icons.WIDGET_BRIGHTNESS,
                R.string.widget_brightness_missing, EnumSet.allOf(WidgetSlot.class), null,
                BrightnessSource::available,
                Arrays.asList(BRIGHTNESS_STEP, BRIGHTNESS_STYLE),
                (ctx, key) -> { },
                BrightnessWidget::create);
    }

    /** The Weather app's own settings activity, by its fixed action and package (WeatherSource). */
    private static void openWeatherSettings(Context ctx) {
        try {
            ctx.startActivity(WeatherSource.settingsIntent(ctx));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(WeatherSource.TAG, "weather settings did not open: " + e.getClass().getSimpleName());
        }
    }
}
