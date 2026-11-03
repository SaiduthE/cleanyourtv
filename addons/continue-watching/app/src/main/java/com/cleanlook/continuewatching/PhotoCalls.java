package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The photo settings the launcher reads and writes through {@code ContentProvider.call()} (README, "Photo settings").
 * The launcher has no network and no access to this app's preferences, so Launcher settings > Top Shelf asks here.
 * Pure: {@link ShelfProvider} turns the Bundle into the plain maps used here and the answers back, and supplies the
 * {@link Backend}, so the JVM tests cover the dispatch, the caller check and the rules.
 */
final class PhotoCalls {
    private PhotoCalls() {}

    static final String GET_SETTINGS = "photos_get_settings";
    static final String SET_ENABLED = "photos_set_enabled";
    static final String SET_CATEGORIES = "photos_set_categories";
    static final String REFRESH = "photos_refresh";
    static final String STATUS = "photos_status";

    /** Request extra of {@link #SET_ENABLED}: boolean. */
    static final String EXTRA_ENABLED = "enabled";
    /** Request extra of {@link #SET_CATEGORIES}: String[] of the category keys that are on. */
    static final String EXTRA_ON = "categories_on";

    /** Answer keys. {@code ok} false carries {@code error}. */
    static final String R_OK = "ok";
    static final String R_ERROR = "error";
    static final String R_ENABLED = "enabled";
    static final String R_KEYS = "category_keys";
    static final String R_LABELS = "category_labels";
    static final String R_ON = "categories_on";
    static final String R_COUNT = "count";
    static final String R_REFRESHING = "refreshing";
    static final String R_STATUS = "status";

    static final String ERR_AT_LEAST_ONE = "at_least_one";
    static final String ERR_OFF = "off";
    static final String ERR_BAD_REQUEST = "bad_request";

    /** What the calls read and change; the provider's implementation talks to {@link Prefs} and {@link PhotoFeed}. */
    interface Backend {
        boolean enabled();

        /** Switches the feed on (a first fetch starts) or off (the photos are deleted). */
        void setEnabled(boolean on);

        Set<String> off();

        void setOff(Set<String> off);

        int count();

        long okMs();

        String note();

        boolean refreshing();

        /** Starts a manual refresh on a worker thread and returns at once. */
        void startRefresh();

        long now();
    }

    /**
     * Runs one call. {@code allowed} is the provider's caller check ({@link ShelfAccess}); a caller that fails it gets a
     * SecurityException whatever it asked. Returns null for a method that is not one of ours.
     */
    static Map<String, Object> dispatch(boolean allowed, Backend b, String method, Map<String, Object> extras) {
        if (!allowed) throw new SecurityException("not a caller of the shelf");
        if (method == null) return null;
        switch (method) {
            case GET_SETTINGS:
            case STATUS:
                return settings(b);
            case SET_ENABLED: {
                Object v = extras == null ? null : extras.get(EXTRA_ENABLED);
                if (!(v instanceof Boolean)) return error(b, ERR_BAD_REQUEST);
                boolean on = (Boolean) v;
                if (on != b.enabled()) b.setEnabled(on);
                return settings(b);
            }
            case SET_CATEGORIES: {
                Object v = extras == null ? null : extras.get(EXTRA_ON);
                if (!(v instanceof String[])) return error(b, ERR_BAD_REQUEST);
                Set<String> off = offFor(Arrays.asList((String[]) v));
                if (off == null) return error(b, ERR_AT_LEAST_ONE);
                b.setOff(off);
                return settings(b);
            }
            case REFRESH:
                if (!b.enabled()) return error(b, ERR_OFF);
                if (!b.refreshing()) b.startRefresh();
                return settings(b);
            default:
                return null;
        }
    }

    /**
     * The switched-off keys for the categories the owner wants on: unknown keys are ignored; null when none of the
     * real categories is left on (at least one must stay on, so nothing is changed then).
     */
    static Set<String> offFor(List<String> on) {
        Set<String> off = new HashSet<>();
        int left = 0;
        for (PhotoPolicy.Category c : PhotoPolicy.CATEGORIES) {
            if (on.contains(c.key)) left++;
            else off.add(c.key);
        }
        return left == 0 ? null : off;
    }

    /** The answer every call ends with: the switch, the categories, how many photos and the plain status line. */
    static Map<String, Object> settings(Backend b) {
        Map<String, Object> out = new HashMap<>();
        out.put(R_OK, Boolean.TRUE);
        out.put(R_ENABLED, b.enabled());
        List<String> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> on = new ArrayList<>();
        Set<String> off = b.off();
        for (PhotoPolicy.Category c : PhotoPolicy.CATEGORIES) {
            keys.add(c.key);
            labels.add(c.label);
            if (!off.contains(c.key)) on.add(c.key);
        }
        if (on.isEmpty()) on.add(PhotoPolicy.CATEGORIES[0].key); // as PhotoPolicy.enabled reads a record with all off
        out.put(R_KEYS, keys.toArray(new String[0]));
        out.put(R_LABELS, labels.toArray(new String[0]));
        out.put(R_ON, on.toArray(new String[0]));
        out.put(R_COUNT, b.count());
        boolean refreshing = b.refreshing();
        out.put(R_REFRESHING, refreshing);
        out.put(R_STATUS, statusText(b.enabled(), refreshing, b.count(), b.okMs(), b.now(), b.note()));
        return out;
    }

    static Map<String, Object> error(Backend b, String code) {
        Map<String, Object> out = settings(b);
        out.put(R_OK, Boolean.FALSE);
        out.put(R_ERROR, code);
        return out;
    }

    /** "Off"; "Refreshing…"; the last try's note ("No network"); "No photos yet"; "12 photos, updated today". */
    static String statusText(boolean enabled, boolean refreshing, int count, long okMs, long now, String note) {
        if (!enabled) return "Off";
        return PhotoPolicy.status(refreshing, count, okMs, now, note);
    }
}
