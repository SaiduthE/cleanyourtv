package io.github.sumyabazar1.weather;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rows of the widget contract (docs/specs/weather-widget.md), built from a {@link Snapshot} without Android
 * classes so they can be tested on the JVM. WeatherProvider only puts them in a MatrixCursor.
 */
final class WidgetRows {
    static final int SPEC = 1;
    static final int MAX_PLACE = 40;

    static final String[] CURRENT_COLUMNS = {"spec", "updated_ms", "place", "temp_c", "feels_c", "high_c", "low_c",
            "code", "is_day", "humidity", "wind_kmh", "aqi", "unit", "show_place", "animated"};
    static final String[] DAILY_COLUMNS = {"date", "code", "high_c", "low_c", "precip_pct"};

    private WidgetRows() { }

    /** The owner's display choices, applied when a row is built. */
    static final class Display {
        final boolean fahrenheit;
        final boolean showPlace;
        final boolean animated;

        Display(boolean fahrenheit, boolean showPlace, boolean animated) {
            this.fahrenheit = fahrenheit;
            this.showPlace = showPlace;
            this.animated = animated;
        }
    }

    /** The single /current row, in {@link #CURRENT_COLUMNS} order. */
    static Object[] current(Snapshot s, Display d) {
        return new Object[] {SPEC, s.updatedMs, place(s.place), round1(s.tempC), round1(s.feelsC), round1(s.highC),
                round1(s.lowC), clamp(s.code, 0, 99), s.day ? 1 : 0, clamp(s.humidity, 0, 100), Math.max(0, s.windKmh),
                s.aqi < 0 ? -1 : Math.min(s.aqi, 500), d.fahrenheit ? "f" : "c", d.showPlace ? 1 : 0, d.animated ? 1 : 0};
    }

    /** The /daily rows, in {@link #DAILY_COLUMNS} order, at most seven. */
    static List<Object[]> daily(Snapshot s) {
        List<Object[]> out = new ArrayList<>();
        for (Snapshot.Day d : s.daily) {
            if (out.size() == Snapshot.MAX_DAILY) break;
            out.add(new Object[] {d.date, clamp(d.code, 0, 99), round1(d.highC), round1(d.lowC),
                    d.precipPct < 0 ? -1 : Math.min(d.precipPct, 100)});
        }
        return out;
    }

    /**
     * The columns to return for a projection: all of them when it is null or empty, otherwise those named, in the
     * caller's order. An unknown name is an IllegalArgumentException, as with any provider.
     */
    static String[] projection(String[] all, String[] wanted) {
        if (wanted == null || wanted.length == 0) return all;
        List<String> known = Arrays.asList(all);
        for (String w : wanted) {
            if (!known.contains(w)) throw new IllegalArgumentException("Unknown column " + w);
        }
        return wanted;
    }

    /** One row cut down to the columns of a projection taken from {@link #projection}. */
    static Object[] pick(String[] all, Object[] row, String[] columns) {
        if (columns == all) return row;
        Object[] out = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) out[i] = row[Arrays.asList(all).indexOf(columns[i])];
        return out;
    }

    /** At most 40 characters, never cutting a surrogate pair, with no control characters. */
    static String place(String name) {
        String s = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        if (s.length() <= MAX_PLACE) return s;
        int end = MAX_PLACE;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end).trim();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
