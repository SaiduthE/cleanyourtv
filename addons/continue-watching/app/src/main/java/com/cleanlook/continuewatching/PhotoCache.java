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
 * The bookkeeping of the cached Top Shelf photos, without any file access: the record of each photo, which ones
 * leave when the cache is full, how many new ones a refresh fetches, and which names the provider may open.
 */
final class PhotoCache {
    private PhotoCache() {}

    static final int MAX_PHOTOS = 15;
    static final int ROTATE = 5;
    static final long MAX_BYTES = 14L * 1024 * 1024;
    /** One download is refused above this; 1920 px photos are normally 0.3 to 1.5 MB. */
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

        Photo(String name, String title, int width, int height, String credit, long addedMs, long bytes) {
            this.name = name;
            this.title = title;
            this.width = width;
            this.height = height;
            this.credit = credit;
            this.addedMs = addedMs;
            this.bytes = bytes;
        }
    }

    static String fileName(String title) {
        return Text.sha1Hex("photo1|" + title).substring(0, 16) + ".jpg";
    }

    /** True only for a name of the right shape that is in the cache's listing. Nothing else is ever opened. */
    static boolean validName(String name, Set<String> listing) {
        return name != null && name.matches(NAME_PATTERN) && listing != null && listing.contains(name);
    }

    /** How many new photos a refresh fetches: five to rotate, or enough to fill an emptier cache. */
    static int wanted(int cached) {
        return Math.min(MAX_PHOTOS, Math.max(ROTATE, MAX_PHOTOS - Math.max(0, cached)));
    }

    /**
     * The names to delete, oldest first, until at most {@code maxCount} photos and {@code maxBytes} remain. The
     * newest photo always stays.
     */
    static List<String> evict(List<Photo> all, int maxCount, long maxBytes) {
        List<Photo> byAge = new ArrayList<>(all);
        Collections.sort(byAge, new Comparator<Photo>() {
            @Override public int compare(Photo a, Photo b) {
                return Long.compare(a.addedMs, b.addedMs);
            }
        });
        long total = 0;
        for (Photo p : byAge) total += p.bytes;
        List<String> out = new ArrayList<>();
        int count = byAge.size();
        for (Photo p : byAge) {
            if (count <= 1 || (count <= maxCount && total <= maxBytes)) break;
            out.add(p.name);
            count--;
            total -= p.bytes;
        }
        return out;
    }

    // ---- the record, kept as JSON in the preferences

    static String encode(List<Photo> photos) {
        JSONArray a = new JSONArray();
        try {
            for (Photo p : photos) {
                a.put(new JSONObject().put("n", p.name).put("t", p.title).put("w", p.width).put("h", p.height)
                        .put("c", p.credit).put("a", p.addedMs).put("b", p.bytes));
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
                        o.optLong("a"), o.optLong("b")));
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
