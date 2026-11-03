package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Top Shelf shows and when, with no Android in it so it runs in JVM tests: the setting, which of the three
 * pictures a focused app gets, the timings, how the Continue watching rows become a shelf, and the checks on the
 * addresses the add-on hands back.
 */
final class TopShelfLogic {

    /** The Top Shelf row of Launcher settings. Layered is the default. */
    enum Mode {
        LAYERED("layered"), OFF("off");

        final String key;

        Mode(String key) {
            this.key = key;
        }

        static Mode parse(String saved) {
            for (Mode m : values()) if (m.key.equals(saved)) return m;
            return LAYERED;
        }
    }

    /** What the shelf shows: the dimmed wallpaper, the show to resume, or the focused app's own artwork. */
    enum Content { REST, RESUME, ARTWORK }

    /** Focus has to rest on an app this long before the shelf changes. */
    static final long DWELL_MS = 600;
    /** Focus has to stay away from the dock this long before the shelf goes back to the wallpaper. */
    static final long LEAVE_MS = 1500;
    /** How long the answer for one app is reused. */
    static final long CACHE_MS = 60_000;
    /** Apps kept in that cache. */
    static final int CACHE_SIZE = 8;
    /** The largest picture decoded (the canvas of a 4K TV at 4x density). */
    static final int MAX_W = 960, MAX_H = 540;
    /** The up-next thumbnails are decoded at twice their on-screen size at most. */
    static final int THUMB_W = 244, THUMB_H = 138;
    /** At most two up-next items next to the one to resume. */
    static final int MAX_UP_NEXT = 2;
    /** The room the resume block needs between the header and the dock. */
    static final int RESUME_MIN_DP = 150;

    static final String AUTHORITY = "com.cleanlook.continuewatching.shelf";
    static final String ITEMS_URI = "content://" + AUTHORITY + "/items";

    private TopShelfLogic() { }

    /** One row of the add-on's feed, trimmed to what the shelf uses. */
    static final class Entry {
        final String kind, title, subtitle, pkg, intentUri, backdrop, poster;
        final int progress;

        Entry(String kind, String title, String subtitle, int progress, String pkg, String intentUri,
              String backdrop, String poster) {
            this.kind = kind == null ? "" : kind;
            this.title = title == null ? "" : title.trim();
            this.subtitle = subtitle == null ? "" : subtitle.trim();
            this.progress = Math.max(0, Math.min(100, progress));
            this.pkg = pkg == null ? "" : pkg;
            this.intentUri = intentUri;
            this.backdrop = backdrop;
            this.poster = poster;
        }

        boolean resume() {
            return "resume".equals(kind);
        }

        /** The picture to show for this row: its backdrop, else its poster, only when it is the add-on's own. */
        String image() {
            if (ownUri(backdrop)) return backdrop;
            return ownUri(poster) ? poster : null;
        }
    }

    /** The show to resume and what comes after it, from one app's rows. */
    static final class Shelf {
        final Entry main;
        final List<Entry> upNext;

        Shelf(Entry main, List<Entry> upNext) {
            this.main = main;
            this.upNext = upNext;
        }
    }

    /**
     * The shelf for one app's rows: the first row partway through (else the first row), then up to two of the
     * "next episode" rows after it. Rows without a title, and rows of another app, are left out. Null when none is left.
     */
    static Shelf shelf(List<Entry> rows, String pkg) {
        List<Entry> usable = new ArrayList<>();
        if (rows != null) {
            for (Entry e : rows) {
                if (e != null && !e.title.isEmpty() && (pkg == null || pkg.equals(e.pkg))) usable.add(e);
            }
        }
        if (usable.isEmpty()) return null;
        Entry main = usable.get(0);
        for (Entry e : usable) {
            if (e.resume()) {
                main = e;
                break;
            }
        }
        List<Entry> next = new ArrayList<>();
        for (Entry e : usable) {
            if (e != main && !e.resume() && next.size() < MAX_UP_NEXT) next.add(e);
        }
        return new Shelf(main, next);
    }

    /**
     * What to show for the app that has focus. The dock is where the shelf lives: focus anywhere else, a mode that is
     * off, a shelf with nothing to say or too little room for the resume block all leave the wallpaper (or the
     * artwork, which needs no room).
     */
    static Content select(Mode mode, boolean onDock, boolean hasShelf, boolean roomForResume, boolean hasArtwork) {
        if (mode != Mode.LAYERED || !onDock) return Content.REST;
        if (hasShelf && roomForResume) return Content.RESUME;
        return hasArtwork ? Content.ARTWORK : Content.REST;
    }

    static boolean roomForResume(float availableDp) {
        return availableDp >= RESUME_MIN_DP;
    }

    /** "Resume" for a show partway through, "Play" for the next episode of one. */
    static boolean isResume(Entry main) {
        return main.resume() || main.progress > 0;
    }

    /** Only the add-on's own cached pictures are opened; the address came from another app's process. */
    static boolean ownUri(String uri) {
        return uri != null && uri.toLowerCase(Locale.ROOT).startsWith("content://" + AUTHORITY + "/");
    }
}
