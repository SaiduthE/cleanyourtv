package com.cleanlook.continuewatching;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Reads Jellyfin's JSON. Pure: strings in, Items out. */
final class JellyfinParser {
    private JellyfinParser() {}

    /** "nas:8096" -> "http://nas:8096"; trailing slashes and a /web path are dropped; null when unusable. */
    static String normalizeBase(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty() || s.indexOf(' ') >= 0) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (lower.contains("://")) return null;
            s = "http://" + s;
        }
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int host = s.indexOf("://") + 3;
        while (s.length() > host && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.toLowerCase(Locale.ROOT).endsWith("/web/index.html")) s = s.substring(0, s.length() - "/web/index.html".length());
        else if (s.toLowerCase(Locale.ROOT).endsWith("/web")) s = s.substring(0, s.length() - 4);
        if (host >= s.length()) return null;
        return s;
    }

    /** Resume or NextUp response. {@code kind} is Item.RESUME or Item.NEXT; {@code now} orders next-up rows. */
    static List<Item> parseItems(String json, int kind, String base, long now) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("Items");
        List<Item> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Item it = parseOne(o, kind, base, now, i);
            if (it != null) out.add(it);
        }
        return out;
    }

    static Item parseOne(JSONObject o, int kind, String base, long now, int rank) {
        String type = o.optString("Type", "");
        if (!type.equals("Episode") && !type.equals("Movie") && !type.equals("Video")) return null;
        String id = o.optString("Id", "");
        if (id.isEmpty()) return null;

        Item it = new Item();
        it.src = Item.SRC_JELLYFIN;
        it.pkg = Item.JELLYFIN_PKG;
        it.jellyfinId = id;
        it.id = "jf:" + id;
        it.kind = kind;
        String name = o.optString("Name", "");
        if (type.equals("Episode")) {
            String series = o.optString("SeriesName", "");
            it.title = series.isEmpty() ? name : series;
            it.episodeTitle = series.isEmpty() ? "" : name;
            it.season = o.has("ParentIndexNumber") ? o.optInt("ParentIndexNumber", -1) : -1;
            it.episode = o.has("IndexNumber") ? o.optInt("IndexNumber", -1) : -1;
        } else {
            it.title = name;
        }
        if (it.title.trim().isEmpty()) return null;
        it.year = o.optInt("ProductionYear", -1);
        it.overview = o.optString("Overview", "");

        long runMs = ticksToMs(o.optLong("RunTimeTicks", 0));
        JSONObject ud = o.optJSONObject("UserData");
        long posMs = -1;
        double pct = -1;
        long played = 0;
        if (ud != null) {
            if (ud.has("PlaybackPositionTicks")) posMs = ticksToMs(ud.optLong("PlaybackPositionTicks", 0));
            if (ud.has("PlayedPercentage")) pct = ud.optDouble("PlayedPercentage", -1);
            played = parseDate(ud.optString("LastPlayedDate", ""));
        }
        if (runMs > 0) it.durationMs = runMs;
        if (kind == Item.RESUME) {
            float p = -1f;
            if (runMs > 0 && posMs >= 0) p = (float) posMs / (float) runMs;
            else if (pct >= 0) p = (float) (pct / 100.0);
            if (p >= WatchNextFilter.DONE) return null;
            if (p >= 0) it.progress = p;
            if (posMs >= 0) it.positionMs = posMs;
            it.updatedMs = played > 0 ? played : now - rank * 1000L;
        } else {
            // Next up carries no date of its own: the server sorts by recent activity, so keep its order.
            it.updatedMs = now - rank * 60_000L;
        }

        String primaryTag = tagOf(o.optJSONObject("ImageTags"), "Primary");
        String backdropId = "";
        String backdropTag = "";
        JSONArray bt = o.optJSONArray("BackdropImageTags");
        if (bt != null && bt.length() > 0) {
            backdropId = id;
            backdropTag = bt.optString(0, "");
        } else {
            JSONArray pbt = o.optJSONArray("ParentBackdropImageTags");
            String pid = o.optString("ParentBackdropItemId", "");
            if (pbt != null && pbt.length() > 0 && !pid.isEmpty()) {
                backdropId = pid;
                backdropTag = pbt.optString(0, "");
            }
        }
        String primaryUrl = primaryTag.isEmpty() ? "" : imageUrl(base, id, "Primary", primaryTag, 700);
        String backdropUrl = backdropTag.isEmpty() ? "" : imageUrl(base, backdropId, "Backdrop", backdropTag, 960);
        // An episode's own still is the best 16:9 card; a film's poster is not, so films use the backdrop.
        it.backdrop = !backdropUrl.isEmpty() ? backdropUrl : primaryUrl;
        it.image = type.equals("Episode") && !primaryUrl.isEmpty() ? primaryUrl : it.backdrop;
        if (it.image.isEmpty()) it.image = primaryUrl;

        // Portrait art: the series' poster for an episode, else the item's own.
        String seriesTag = o.optString("SeriesPrimaryImageTag", "");
        String seriesId = o.optString("SeriesId", "");
        if (type.equals("Episode") && !seriesTag.isEmpty() && !seriesId.isEmpty()) {
            it.poster = imageUrl(base, seriesId, "Primary", seriesTag, 400);
        } else if (!primaryUrl.isEmpty()) {
            it.poster = imageUrl(base, id, "Primary", primaryTag, 400);
        }
        // The title logo: the item's own, or its series'.
        String logoTag = tagOf(o.optJSONObject("ImageTags"), "Logo");
        if (!logoTag.isEmpty()) {
            it.logo = imageUrl(base, id, "Logo", logoTag, 480);
        } else {
            String pLogoTag = o.optString("ParentLogoImageTag", "");
            String pLogoId = o.optString("ParentLogoItemId", "");
            if (!pLogoTag.isEmpty() && !pLogoId.isEmpty()) it.logo = imageUrl(base, pLogoId, "Logo", pLogoTag, 480);
        }
        return it;
    }

    static String imageUrl(String base, String id, String type, String tag, int maxWidth) {
        String u = base + "/Items/" + id + "/Images/" + type + (type.equals("Backdrop") ? "/0" : "")
                + "?maxWidth=" + maxWidth + "&quality=85";
        if (tag != null && !tag.isEmpty()) u += "&tag=" + tag;
        return u;
    }

    private static String tagOf(JSONObject tags, String key) {
        return tags == null ? "" : tags.optString(key, "");
    }

    static long ticksToMs(long ticks) {
        return ticks / 10_000L;
    }

    /** "2026-10-30T20:11:12.0000000Z" to epoch ms; 0 when it does not parse. */
    static long parseDate(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s.substring(0, 19)).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }

    // ---- sign-in responses

    static final class Auth {
        String token = "";
        String userId = "";
        String userName = "";
    }

    static Auth parseAuth(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Auth a = new Auth();
        a.token = o.optString("AccessToken", "");
        JSONObject u = o.optJSONObject("User");
        if (u != null) {
            a.userId = u.optString("Id", "");
            a.userName = u.optString("Name", "");
        }
        return a;
    }

    /** {Id, Name} pairs of /Users. */
    static List<String[]> parseUsers(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("Id", "");
            if (!id.isEmpty()) out.add(new String[] {id, o.optString("Name", id)});
        }
        return out;
    }

    static String parseServerName(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String n = o.optString("ServerName", "");
            return n.isEmpty() ? "Jellyfin" : n;
        } catch (JSONException e) {
            return "Jellyfin";
        }
    }

    /** Jellyfin prints ids as 32 hex digits; its Android TV app parses UUIDs. */
    static String dashedUuid(String id) {
        if (id == null || id.length() != 32 || !id.matches("[0-9a-fA-F]{32}")) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-"
                + id.substring(16, 20) + "-" + id.substring(20);
    }
}
