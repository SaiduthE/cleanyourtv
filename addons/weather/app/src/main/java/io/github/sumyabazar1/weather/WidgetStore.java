package io.github.sumyabazar1.weather;

import android.content.Context;
import android.content.SharedPreferences;

/** Where the snapshot and the chosen place are kept (the page's own "place" preferences hold the city). */
final class WidgetStore {
    private static final String PREFS = "widget";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final String KEY_LAST_KICK = "last_kick_ms";
    private static final String KEY_LAST_QUERY = "last_query_ms";

    private WidgetStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences place(Context c) {
        return c.getSharedPreferences("place", Context.MODE_PRIVATE);
    }

    static boolean hasPlace(Context c) {
        return place(c).contains("name");
    }

    static String placeName(Context c) {
        return place(c).getString("name", "");
    }

    static double latitude(Context c) {
        return Double.longBitsToDouble(place(c).getLong("lat", 0));
    }

    static double longitude(Context c) {
        return Double.longBitsToDouble(place(c).getLong("lon", 0));
    }

    static void save(Context c, Snapshot s) {
        prefs(c).edit().putString(KEY_SNAPSHOT, s.serialize()).apply();
    }

    /** The saved snapshot if it belongs to the city chosen now, else null. */
    static Snapshot load(Context c) {
        Snapshot s = Snapshot.parse(prefs(c).getString(KEY_SNAPSHOT, null));
        if (s == null || !hasPlace(c)) return null;
        if (Double.compare(s.latitude, latitude(c)) != 0 || Double.compare(s.longitude, longitude(c)) != 0) return null;
        return s;
    }

    /** When a reader last queried the provider (0: never). */
    static long lastQuery(Context c) {
        return prefs(c).getLong(KEY_LAST_QUERY, 0);
    }

    static void setLastQuery(Context c, long ms) {
        prefs(c).edit().putLong(KEY_LAST_QUERY, ms).apply();
    }

    static long lastKick(Context c) {
        return prefs(c).getLong(KEY_LAST_KICK, 0);
    }

    static void setLastKick(Context c, long ms) {
        prefs(c).edit().putLong(KEY_LAST_KICK, ms).apply();
    }
}
