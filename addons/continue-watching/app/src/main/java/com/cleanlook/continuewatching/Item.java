package com.cleanlook.continuewatching;

import org.json.JSONException;
import org.json.JSONObject;

/** One thing the owner is partway through, or is up next, in one app. Plain data, kept in state.json. */
final class Item {
    static final int RESUME = 0;
    static final int NEXT = 1;

    static final String SRC_WATCH_NEXT = "wn";
    static final String SRC_JELLYFIN = "jf";
    /** A program of a preview channel (Plex's, Netflix's "Continue watching" row). */
    static final String SRC_CHANNEL = "ch";

    static final String JELLYFIN_PKG = "org.jellyfin.androidtv";

    /** "wn:" or "jf:" plus a stable key; this is what the shelf and the RESUME action pass around. */
    String id = "";
    String src = SRC_WATCH_NEXT;
    String pkg = "";
    int kind = RESUME;
    /** The show for an episode, the film for a movie. */
    String title = "";
    String episodeTitle = "";
    int season = -1;
    int episode = -1;
    /** 0..1, or -1 when the app did not say. */
    float progress = -1f;
    long positionMs = -1;
    long durationMs = -1;
    /** Epoch ms of the last time the owner watched it (approximate for next-up rows). */
    long updatedMs;
    /** Card image URL (https, http or content). Never holds a token. */
    String image = "";
    /** Landscape art for a big card, or empty. */
    String backdrop = "";
    /** Portrait art, or empty. */
    String poster = "";
    /** Title logo with transparency (Jellyfin only), or empty. */
    String logo = "";
    /** Watch Next only: the row's intent_uri, kept private to this app. */
    String intentUri = "";
    /** Jellyfin only: the item id as the server prints it. */
    String jellyfinId = "";
    String overview = "";
    int year = -1;
    /** "New episode" and the like, or empty. */
    String tag = "";
    /** Channel rows only: the display name of the preview channel it came from. */
    String channel = "";
    /** Channel rows only: the program's weight in its channel (higher shows first). */
    int weight;

    /** "S2 · E4", the episode's name, or the year for a film. */
    String subtitle() {
        String e = Text.episodeLabel(season, episode);
        if (e != null) return e;
        if (!episodeTitle.isEmpty()) return episodeTitle;
        if (year > 0) return String.valueOf(year);
        return "";
    }

    long remainingMs() {
        if (durationMs > 0 && positionMs >= 0 && positionMs <= durationMs) return durationMs - positionMs;
        return -1;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("src", src);
        o.put("pkg", pkg);
        o.put("kind", kind);
        o.put("title", title);
        if (!episodeTitle.isEmpty()) o.put("ep", episodeTitle);
        if (season >= 0) o.put("season", season);
        if (episode >= 0) o.put("episode", episode);
        if (progress >= 0) o.put("prog", (double) progress);
        if (positionMs >= 0) o.put("pos", positionMs);
        if (durationMs >= 0) o.put("dur", durationMs);
        o.put("upd", updatedMs);
        if (!image.isEmpty()) o.put("img", image);
        if (!backdrop.isEmpty()) o.put("bd", backdrop);
        if (!poster.isEmpty()) o.put("pst", poster);
        if (!logo.isEmpty()) o.put("logo", logo);
        if (!intentUri.isEmpty()) o.put("intent", intentUri);
        if (!jellyfinId.isEmpty()) o.put("jfid", jellyfinId);
        if (!overview.isEmpty()) o.put("over", overview);
        if (year > 0) o.put("year", year);
        if (!tag.isEmpty()) o.put("tag", tag);
        if (!channel.isEmpty()) o.put("chan", channel);
        if (weight != 0) o.put("w", weight);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item it = new Item();
        it.id = o.optString("id", "");
        it.src = o.optString("src", SRC_WATCH_NEXT);
        it.pkg = o.optString("pkg", "");
        it.kind = o.optInt("kind", RESUME);
        it.title = o.optString("title", "");
        it.episodeTitle = o.optString("ep", "");
        it.season = o.optInt("season", -1);
        it.episode = o.optInt("episode", -1);
        it.progress = (float) o.optDouble("prog", -1);
        it.positionMs = o.optLong("pos", -1);
        it.durationMs = o.optLong("dur", -1);
        it.updatedMs = o.optLong("upd", 0);
        it.image = o.optString("img", "");
        it.backdrop = o.optString("bd", "");
        it.poster = o.optString("pst", "");
        it.logo = o.optString("logo", "");
        it.intentUri = o.optString("intent", "");
        it.jellyfinId = o.optString("jfid", "");
        it.overview = o.optString("over", "");
        it.year = o.optInt("year", -1);
        it.tag = o.optString("tag", "");
        it.channel = o.optString("chan", "");
        it.weight = o.optInt("w", 0);
        return it;
    }
}
