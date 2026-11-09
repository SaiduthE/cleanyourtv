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
    /** "Fetch a new photo now": one new photo into the ring (the oldest shown goes), on any connection. */
    static final String REFRESH = "photos_refresh";
    /** The launcher rotated to the next picture: one new photo in the background, and which one is on screen. */
    static final String ADVANCE = "photos_advance";
    static final String STATUS = "photos_status";

    /** Request extra of {@link #SET_ENABLED}: boolean. */
    static final String EXTRA_ENABLED = "enabled";
    /** Request extra of {@link #ADVANCE}: the file name of the photo now on screen (optional). */
    static final String EXTRA_SHOWN = "shown";
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

        /** The last fetch's plain note ("No network"); empty after one that worked. */
        String note();

        /** How long the fetch in flight has been going (ms); negative when none is. */
        long flightMs();

        /** True while the server is making the fetch wait. */
        boolean busy();

        /**
         * Starts one new photo on a worker thread and returns at once (at most one fetch is ever in flight).
         * {@code shown} is the photo on screen, if known; {@code manual} is the owner's "fetch a new photo now".
         */
        void advance(String shown, boolean manual);

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
                b.advance(null, true);
                return settings(b);
            case ADVANCE: {
                if (!b.enabled()) return error(b, ERR_OFF);
                Object v = extras == null ? null : extras.get(EXTRA_SHOWN);
                b.advance(v instanceof String ? (String) v : null, false);
                return settings(b);
            }
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
        long flight = b.flightMs();
        out.put(R_REFRESHING, flight >= 0 && flight < PhotoPolicy.FETCHING_MAX_MS);
        out.put(R_STATUS, PhotoPolicy.status(b.enabled(), flight, b.busy(), b.count(), b.note()));
        return out;
    }

    static Map<String, Object> error(Backend b, String code) {
        Map<String, Object> out = settings(b);
        out.put(R_OK, Boolean.FALSE);
        out.put(R_ERROR, code);
        return out;
    }
}
