package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the raw Watch Next rows into Items. The provider cannot filter for us (a selection is a
 * SecurityException without ACCESS_ALL_EPG_DATA), so all of it happens here, in plain Java.
 */
final class WatchNextFilter {
    private WatchNextFilter() {}

    static final long MAX_AGE_MS = 60L * 24 * 60 * 60 * 1000;
    static final float DONE = 0.95f;

    /** TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*: continue, next, new, watchlist. */
    static final int TYPE_CONTINUE = 0;
    static final int TYPE_NEXT = 1;
    static final int TYPE_NEW = 2;

    /** One row as the provider returned it. Missing values are null, -1 or 0. */
    static final class Row {
        long rowId;
        String pkg;
        String title;
        String episodeTitle;
        String season;
        String episode;
        String poster;
        String thumb;
        String intentUri;
        String description;
        int type = -1;
        long engagedMs;
        long positionMs = -1;
        long durationMs = -1;
    }

    static List<Item> filter(List<Row> rows, long now, Set<String> disabledPkgs, String selfPkg) {
        Map<String, Item> best = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (Row r : rows) {
            Item it = convert(r, now, disabledPkgs, selfPkg);
            if (it == null) continue;
            String key = it.pkg + "|" + Text.normalizeTitle(it.title) + "|" + it.season + "|" + it.episode;
            Item old = best.get(key);
            if (old == null) {
                order.add(key);
                best.put(key, it);
            } else if (better(it, old)) {
                best.put(key, it);
            }
        }
        List<Item> out = new ArrayList<>(order.size());
        for (String k : order) out.add(best.get(k));
        return out;
    }

    /** Null when the row should not show. */
    static Item convert(Row r, long now, Set<String> disabledPkgs, String selfPkg) {
        if (r == null || isEmpty(r.pkg) || isEmpty(r.title) || isEmpty(r.intentUri)) return null;
        if (r.pkg.equals(selfPkg)) return null;
        if (disabledPkgs != null && disabledPkgs.contains(r.pkg)) return null;
        if (Text.normalizeTitle(r.title).isEmpty()) return null;
        if (r.type != TYPE_CONTINUE && r.type != TYPE_NEXT && r.type != TYPE_NEW) return null;
        // Engaged within 60 days. A row with no date cannot be aged, so it is dropped. A day of
        // clock skew into the future is allowed.
        if (r.engagedMs <= 0 || now - r.engagedMs > MAX_AGE_MS || r.engagedMs - now > 24L * 3600 * 1000) return null;

        Item it = new Item();
        it.src = Item.SRC_WATCH_NEXT;
        it.pkg = r.pkg;
        it.title = r.title.trim();
        it.episodeTitle = r.episodeTitle == null ? "" : r.episodeTitle.trim();
        it.season = Text.digits(r.season);
        it.episode = Text.digits(r.episode);
        it.updatedMs = Math.min(r.engagedMs, now);
        it.intentUri = r.intentUri;
        it.overview = r.description == null ? "" : r.description.trim();
        String img = !isEmpty(r.thumb) ? r.thumb : r.poster;
        it.image = img == null ? "" : img;
        it.poster = isEmpty(r.poster) ? "" : r.poster;
        // The thumbnail is the app's landscape picture; a poster alone is not known to be landscape (ShelfImages checks).
        it.backdrop = isEmpty(r.thumb) ? "" : r.thumb;

        if (r.type == TYPE_CONTINUE) {
            it.kind = Item.RESUME;
            if (r.durationMs > 0 && r.positionMs >= 0) {
                float p = (float) r.positionMs / (float) r.durationMs;
                if (p >= DONE) return null;
                it.progress = Math.max(0f, p);
                it.positionMs = r.positionMs;
                it.durationMs = r.durationMs;
            } else if (r.durationMs > 0) {
                it.durationMs = r.durationMs;
            }
        } else {
            it.kind = Item.NEXT;
            if (r.durationMs > 0) it.durationMs = r.durationMs;
            if (r.type == TYPE_NEW) it.tag = "New episode";
        }
        it.id = "wn:" + Text.sha1Hex(it.pkg + "|" + Text.normalizeTitle(it.title) + "|" + it.season + "|" + it.episode)
                .substring(0, 16);
        return it;
    }

    /** The newer row wins; on a tie the one further along. */
    private static boolean better(Item a, Item b) {
        if (a.updatedMs != b.updatedMs) return a.updatedMs > b.updatedMs;
        return a.positionMs > b.positionMs;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
