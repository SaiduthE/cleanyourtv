package com.cleanlook.continuewatching;

import java.util.HashSet;
import java.util.Set;

/** When the Top Shelf photos are fetched, which categories they come from, and the plain status line. Pure. */
final class PhotoPolicy {
    private PhotoPolicy() {}

    static final long REFRESH_EVERY_MS = 3L * 24 * 60 * 60 * 1000;
    /** After a try (good or not) the automatic refresh waits at least this long. */
    static final long RETRY_AFTER_MS = 60L * 60 * 1000;
    /** Fewer photos than this in the cache counts as a fresh install: the automatic refresh does not wait for the schedule. */
    static final int FIRST_FILL = 6;
    /** A scheduled job that fires a little early still counts as the three days being up. */
    private static final long DUE_SLACK_MS = 2L * 60 * 60 * 1000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

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
     * Whether to start a refresh now. Manual ("Refresh now") needs only a connection. Automatic needs an unmetered
     * connection, the switch on, three days (less two hours) since the last good refresh (or fewer than six photos
     * cached) and an hour since the last try. A clock that went backwards counts as due.
     */
    static boolean due(boolean enabled, boolean connected, boolean unmetered, boolean manual, long now, long lastOkMs,
            long lastTryMs, int cached) {
        if (!enabled || !connected) return false;
        if (manual) return true;
        if (!unmetered) return false;
        if (lastTryMs != 0 && now >= lastTryMs && now - lastTryMs < RETRY_AFTER_MS) return false;
        return cached < FIRST_FILL || lastOkMs == 0 || now < lastOkMs || now - lastOkMs >= REFRESH_EVERY_MS - DUE_SLACK_MS;
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

    /** "12 photos, updated today"; or the note of the last try ("No network", "Failed: timed out") when there is one. */
    static String status(boolean refreshing, int count, long okMs, long now, String note) {
        if (refreshing) return "Refreshing…";
        if (note != null && !note.isEmpty()) return note;
        if (count == 0) return "No photos yet";
        String n = count + (count == 1 ? " photo" : " photos");
        if (okMs <= 0) return n;
        long days = Math.max(0, (now - okMs) / DAY_MS);
        return n + ", updated " + (days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago");
    }
}
