package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;

/** The owner's choices that the widget provider publishes, read with the same defaults as the page (options.xml). */
final class WidgetSettings {
    static final String OPTIONS = "options";
    static final String KEY_FAHRENHEIT = "fahrenheit";
    static final String KEY_SHOW_PLACE = "widget_show_place";
    static final String KEY_ANIMATED = "widget_animated";
    static final String KEY_REFRESH_MINUTES = "refresh_minutes";

    private WidgetSettings() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(OPTIONS, Context.MODE_PRIVATE);
    }

    static boolean fahrenheit(Context c) {
        return prefs(c).getBoolean(KEY_FAHRENHEIT, "f".equalsIgnoreCase(c.getString(R.string.temperature_unit).trim()));
    }

    static boolean showPlace(Context c) {
        return prefs(c).getBoolean(KEY_SHOW_PLACE, c.getResources().getBoolean(R.bool.widget_show_place));
    }

    static boolean animated(Context c) {
        return prefs(c).getBoolean(KEY_ANIMATED, c.getResources().getBoolean(R.bool.widget_animated));
    }

    static int refreshMinutes(Context c) {
        return RefreshPolicy.minutes(prefs(c).getInt(KEY_REFRESH_MINUTES,
                c.getResources().getInteger(R.integer.refresh_minutes)));
    }

    static WidgetRows.Display display(Context c) {
        return new WidgetRows.Display(fahrenheit(c), showPlace(c), animated(c));
    }
}
