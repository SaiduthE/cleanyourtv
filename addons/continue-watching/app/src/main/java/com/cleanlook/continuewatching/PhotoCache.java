package com.cleanlook.continuewatching;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The bookkeeping of the Top Shelf photo ring, without any file access: the record of each photo, which one leaves
 * when the ring is over its target, and which names the provider may open. The ring is small (a few photos, a few MB):
 * it is topped up one photo at a time and the photo that was on screen longest ago is the one to go.
 */
final class PhotoCache {
    private PhotoCache() {}

    /** The ring is topped up to this many photos and trimmed back to it after every new photo. */
    static final int TARGET = 4;
    /** Never more than this many photos are kept, whatever happens. */
    static final int MAX_PHOTOS = 6;
    /** Fewer than this many ready photos is "nearly empty": the provider asks for a top-up when it is queried. */
    static final int MIN_READY = 2;
    static final long MAX_BYTES = 6L * 1024 * 1024;
    /** One download is refused above this; 1920 px photos are normally 0.2 to 1 MB. */
    static final int MAX_FILE_BYTES = 3 * 1024 * 1024;

    /** 16 hex digits and ".jpg": the only names the provider serves. */
    static final String NAME_PATTERN = "[0-9a-f]{16}\\.jpg";

    static final class Photo {
        final String name;
        final String title;
        final int width;
        final int height;
        final String credit;
        final long addedMs;
        final long bytes;
        /** When the launcher last said it showed this photo; 0 when it never did. */
        final long shownMs;

        Photo(String name, String title, int width, int height, String credit, long addedMs, long bytes) {
            this(name, title, width, height, credit, addedMs, bytes, 0);
        }

        Photo(String name, String title, int width, int height, String credit, long addedMs, long bytes, long shownMs) {
            this.name = name;
            this.title = title;
            this.width = width;
            this.height = height;
            this.credit = credit;
            this.addedMs = addedMs;
            this.bytes = bytes;
            this.shownMs = shownMs;
        }

        Photo shownAt(long ms) {
            return new Photo(name, title, width, height, credit, addedMs, bytes, ms);
        }
    }

    static String fileName(String title) {
        return Text.sha1Hex("photo1|" + title).substring(0, 16) + ".jpg";
    }

    /** True only for a name of the right shape that is in the cache's listing. Nothing else is ever opened. */
    static boolean validName(String name, Set<String> listing) {
        return name != null && name.matches(NAME_PATTERN) && listing != null && listing.contains(name);
    }

    /** True when the ring has room for one more photo (it never goes over {@link #MAX_PHOTOS}). */
    static boolean hasRoom(int count) {
        return count < MAX_PHOTOS;
    }

    /** True when the ring is below its target and should be filled up. */
    static boolean belowTarget(int count) {
        return count < TARGET;
    }

    /**
     * The names to delete so that at most {@code target} photos and {@code maxBytes} remain. The photo that was on
     * screen longest ago goes first; a photo the launcher never showed goes after every shown one (oldest first). The
     * newest photo and the one shown last (the one on screen) always stay, so with two photos nothing is deleted.
     */
    static List<String> evict(List<Photo> all, int target, long maxBytes) {
        List<Photo> rest = new ArrayList<>(all);
        Photo newest = null;
        Photo onScreen = null;
        for (Photo p : all) {
            if (newest == null || p.addedMs > newest.addedMs) newest = p;
            if (p.shownMs > 0 && (onScreen == null || p.shownMs > onScreen.shownMs)) onScreen = p;
        }
        Collections.sort(rest, new Comparator<Photo>() {
            @Override public int compare(Photo a, Photo b) {
                boolean as = a.shownMs > 0, bs = b.shownMs > 0;
                if (as != bs) return as ? -1 : 1;
                int c = as ? Long.compare(a.shownMs, b.shownMs) : 0;
                return c != 0 ? c : Long.compare(a.addedMs, b.addedMs);
            }
        });
        long total = 0;
        for (Photo p : all) total += p.bytes;
        int count = all.size();
        List<String> out = new ArrayList<>();
        for (Photo p : rest) {
            if (count <= target && total <= maxBytes) break;
            if (p == newest || p == onScreen) continue;
            out.add(p.name);
            count--;
            total -= p.bytes;
        }
        return out;
    }

    /** The record with {@code name} marked as shown at {@code now}; unchanged when it is not in the record. */
    static List<Photo> markShown(List<Photo> record, String name, long now) {
        List<Photo> out = new ArrayList<>();
        for (Photo p : record) out.add(p.name.equals(name) ? p.shownAt(now) : p);
        return out;
    }

    // ---- the record, kept as JSON in the preferences

    static String encode(List<Photo> photos) {
        JSONArray a = new JSONArray();
        try {
            for (Photo p : photos) {
                a.put(new JSONObject().put("n", p.name).put("t", p.title).put("w", p.width).put("h", p.height)
                        .put("c", p.credit).put("a", p.addedMs).put("b", p.bytes).put("s", p.shownMs));
            }
        } catch (JSONException e) {
            return "[]";
        }
        return a.toString();
    }

    static List<Photo> decode(String json) {
        List<Photo> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.optString("n", "");
                if (!name.matches(NAME_PATTERN)) continue;
                out.add(new Photo(name, o.optString("t", ""), o.optInt("w"), o.optInt("h"), o.optString("c", ""),
                        o.optLong("a"), o.optLong("b"), o.optLong("s")));
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return out;
    }

    /** Newest first, only the photos whose file is in the listing: what the provider answers with. */
    static List<Photo> available(List<Photo> record, Set<String> listing) {
        List<Photo> out = new ArrayList<>();
        for (Photo p : record) if (validName(p.name, listing)) out.add(p);
        Collections.sort(out, new Comparator<Photo>() {
            @Override public int compare(Photo a, Photo b) {
                return Long.compare(b.addedMs, a.addedMs);
            }
        });
        return out;
    }
}
