package com.cleanlook.continuewatching;

import java.util.HashSet;
import java.util.Set;

/** When a Top Shelf photo may be fetched, which categories they come from, and the plain status line. Pure. */
final class PhotoPolicy {
    private PhotoPolicy() {}

    /** The scheduled job only tops the ring up; it wakes about this often (on an unmetered network). */
    static final long JOB_EVERY_MS = 24L * 60 * 60 * 1000;
    /** Photo requests are spaced at least this far apart (upload.wikimedia.org answers 429 to quick ones). */
    static final long SPACING_MS = 6_000;
    /** The status line says "Fetching..." for at most this long, whatever the fetch is still doing. */
    static final long FETCHING_MAX_MS = 30_000;
    /** After a failed fetch the automatic top-ups wait this long (doubling with each failure in a row, up to the cap). */
    static final long FAIL_WAIT_MS = 20_000;
    static final long FAIL_WAIT_MAX_MS = 10L * 60 * 1000;

    /** A category: its key in the preferences, the name on the TV and the Commons category it reads. */
    static final class Category {
        final String key;
        final String label;
        final String commons;

        Category(String key, String label, String commons) {
            this.key = key;
            this.label = label;
            this.commons = commons;
        }
    }

    /**
     * Commons "Featured pictures of ..." categories, each checked to exist and hold hundreds or dozens of files
     * (there is no category of seascapes or skies as such: coasts and clouds stand in for them).
     */
    static final Category[] CATEGORIES = {
        new Category("landscapes", "Landscapes", "Category:Featured pictures of landscapes"),
        new Category("mountains", "Mountains", "Category:Featured pictures of mountains"),
        new Category("coasts", "Coasts and seas", "Category:Featured pictures of coasts"),
        new Category("lakes", "Lakes", "Category:Featured pictures of lakes"),
        new Category("forests", "Forests", "Category:Featured pictures of forests"),
        new Category("skies", "Clouds and skies", "Category:Featured pictures of clouds"),
        new Category("sunsets", "Sunsets", "Category:Featured pictures of sunsets"),
    };

    /**
     * Whether a fetch may start. Needs the switch on and a connection. "Fetch a new photo now" (manual) and a ring with
     * fewer than {@link PhotoCache#MIN_READY} photos (nothing to show) go on any connection; the rest only on an
     * unmetered one.
     */
    static boolean mayFetch(boolean enabled, boolean connected, boolean unmetered, boolean manual, int ready) {
        if (!enabled || !connected) return false;
        return manual || unmetered || ready < PhotoCache.MIN_READY;
    }

    /** How long automatic fetches rest after {@code streak} failures in a row (the server's Retry-After counts). */
    static long failWaitMs(int streak, long retryAfterMs) {
        if (streak <= 0) return 0;
        long w = FAIL_WAIT_MS;
        for (int i = 1; i < streak && w < FAIL_WAIT_MAX_MS; i++) w *= 2;
        return Math.min(FAIL_WAIT_MAX_MS, Math.max(w, retryAfterMs));
    }

    /** The set of switched-off category keys after the owner presses {@code key}; the last one on cannot be switched off. */
    static Set<String> toggle(Set<String> off, String key) {
        Set<String> next = new HashSet<>(off);
        if (next.remove(key)) return next;
        int on = 0;
        for (Category c : CATEGORIES) if (!next.contains(c.key)) on++;
        if (on > 1) next.add(key);
        return next;
    }

    /** The categories that are on: every one not in {@code off}, and always at least the first. */
    static Category[] enabled(Set<String> off) {
        java.util.List<Category> on = new java.util.ArrayList<>();
        for (Category c : CATEGORIES) if (!off.contains(c.key)) on.add(c);
        if (on.isEmpty()) on.add(CATEGORIES[0]);
        return on.toArray(new Category[0]);
    }

    /**
     * The one plain status line. {@code flightMs} is how long the current fetch has been going (negative: none).
     * "Fetching..." shows for at most {@link #FETCHING_MAX_MS}; after that the line says what is going on instead:
     * "Server busy, retrying" when the server is making it wait, else the last failure or "Ready: 4 photos".
     */
    static String status(boolean enabled, long flightMs, boolean busyNow, int count, String note) {
        if (!enabled) return "Off";
        if (flightMs >= 0 && flightMs < FETCHING_MAX_MS) return "Fetching...";
        if (flightMs >= FETCHING_MAX_MS && busyNow) return "Server busy, retrying";
        if (note != null && !note.isEmpty()) return note;
        if (count <= 0) return "No photos yet";
        return "Ready: " + count + (count == 1 ? " photo" : " photos");
    }
}
