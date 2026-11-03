package com.cleanlook.continuewatching;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the TV provider's Watch Next and preview rows. docs/watch-next.md has the rules: no selection (a
 * SecurityException without ACCESS_ALL_EPG_DATA), a named projection (some firmware has no split_id), and
 * everything else filtered in code.
 */
final class WatchNextReader {
    private WatchNextReader() {}

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    static final String GRANT_COMMAND = "adb shell pm grant com.cleanlook.continuewatching " + PERMISSION;
    private static final int MAX_ROWS = 5000;

    static boolean granted(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static final String[] WATCH_NEXT_COLUMNS = {
        "_id", "package_name", "title", "episode_title", "season_display_number", "episode_display_number",
        "poster_art_uri", "thumbnail_uri", "intent_uri", "watch_next_type", "last_engagement_time_utc_millis",
        "last_playback_position_millis", "duration_millis", "short_description",
    };
    private static final String[] WATCH_NEXT_BASIC = {
        "_id", "package_name", "title", "poster_art_uri", "intent_uri", "watch_next_type",
        "last_engagement_time_utc_millis", "last_playback_position_millis", "duration_millis",
    };
    private static final String[] PREVIEW_COLUMNS = {
        "_id", "package_name", "title", "poster_art_uri", "thumbnail_uri", "intent_uri",
        "last_playback_position_millis", "duration_millis",
    };
    private static final String[] PREVIEW_BASIC = {"_id", "package_name", "title", "poster_art_uri", "intent_uri"};

    /** Raw rows, or a SecurityException when READ_TV_LISTINGS is missing. */
    static List<WatchNextFilter.Row> readWatchNext(Context c) {
        Cursor cur = queryWithFallback(c, TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, WATCH_NEXT_BASIC);
        List<WatchNextFilter.Row> out = new ArrayList<>();
        if (cur == null) return out;
        try {
            int n = 0;
            while (cur.moveToNext() && n++ < MAX_ROWS) {
                WatchNextFilter.Row r = new WatchNextFilter.Row();
                r.rowId = longOf(cur, "_id", 0);
                r.pkg = str(cur, "package_name");
                r.title = str(cur, "title");
                r.episodeTitle = str(cur, "episode_title");
                r.season = str(cur, "season_display_number");
                r.episode = str(cur, "episode_display_number");
                r.poster = str(cur, "poster_art_uri");
                r.thumb = str(cur, "thumbnail_uri");
                r.intentUri = str(cur, "intent_uri");
                r.description = str(cur, "short_description");
                r.type = (int) longOf(cur, "watch_next_type", -1);
                r.engagedMs = longOf(cur, "last_engagement_time_utc_millis", 0);
                r.positionMs = longOf(cur, "last_playback_position_millis", -1);
                r.durationMs = longOf(cur, "duration_millis", -1);
                out.add(r);
            }
        } finally {
            cur.close();
        }
        return out;
    }

    /** Watch Next and preview rows for the first-run probe. */
    static List<Probe.Sample> readSamples(Context c) {
        List<Probe.Sample> out = new ArrayList<>();
        Cursor w = queryWithFallback(c, TvContract.WatchNextPrograms.CONTENT_URI, WATCH_NEXT_COLUMNS, WATCH_NEXT_BASIC);
        if (w != null) {
            try {
                int n = 0;
                while (w.moveToNext() && n++ < MAX_ROWS) {
                    Probe.Sample s = new Probe.Sample();
                    s.watchNext = true;
                    s.pkg = str(w, "package_name");
                    s.title = str(w, "title");
                    s.intentUri = str(w, "intent_uri");
                    String thumb = str(w, "thumbnail_uri");
                    s.posterUri = str(w, "poster_art_uri");
                    if ((s.posterUri == null || s.posterUri.isEmpty()) && thumb != null) s.posterUri = thumb;
                    s.engagedMs = longOf(w, "last_engagement_time_utc_millis", 0);
                    s.hasPosition = longOf(w, "last_playback_position_millis", -1) >= 0
                            && longOf(w, "duration_millis", -1) > 0;
                    out.add(s);
                }
            } finally {
                w.close();
            }
        }
        Cursor p = queryWithFallback(c, TvContract.PreviewPrograms.CONTENT_URI, PREVIEW_COLUMNS, PREVIEW_BASIC);
        if (p != null) {
            try {
                int n = 0;
                while (p.moveToNext() && n++ < MAX_ROWS) {
                    Probe.Sample s = new Probe.Sample();
                    s.watchNext = false;
                    s.pkg = str(p, "package_name");
                    s.title = str(p, "title");
                    s.intentUri = str(p, "intent_uri");
                    s.posterUri = str(p, "poster_art_uri");
                    s.hasPosition = longOf(p, "last_playback_position_millis", -1) >= 0
                            && longOf(p, "duration_millis", -1) > 0;
                    out.add(s);
                }
            } finally {
                p.close();
            }
        }
        return out;
    }

    /** A named projection, then a shorter one if the table lacks a column. SecurityException passes through. */
    private static Cursor queryWithFallback(Context c, Uri uri, String[] full, String[] basic) {
        try {
            return c.getContentResolver().query(uri, full, null, null, null);
        } catch (SecurityException e) {
            throw e;
        } catch (RuntimeException e) {
            Log.w(Contract.TAG, "full projection refused (" + e.getClass().getSimpleName() + "), trying the short one");
            return c.getContentResolver().query(uri, basic, null, null, null);
        }
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return null;
        try {
            return c.getString(i);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long longOf(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return def;
        try {
            return c.getLong(i);
        } catch (RuntimeException e) {
            return def;
        }
    }
}
