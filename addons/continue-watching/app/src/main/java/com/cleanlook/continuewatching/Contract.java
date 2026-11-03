package com.cleanlook.continuewatching;

import android.net.Uri;

/** The names the launcher (or anything else) can rely on. Written up in the add-on's README. */
public final class Contract {
    private Contract() {}

    public static final String AUTHORITY = "com.cleanlook.continuewatching.shelf";
    public static final Uri ITEMS_URI = Uri.parse("content://" + AUTHORITY + "/items");
    public static final String PATH_ITEMS = "items";
    public static final String PATH_IMG = "img";
    /** Optional query parameter of /items: only rows from this source package. */
    public static final String PARAM_PKG = "pkg";

    /** Opens the app. With {@link #EXTRA_ITEM_ID} it starts that item at once instead. */
    public static final String ACTION_RESUME = "com.cleanlook.continuewatching.action.RESUME";
    public static final String EXTRA_ITEM_ID = "item_id";
    /** Optional int extra the launcher passes to the app: the style's accent colour (ARGB). */
    public static final String EXTRA_ACCENT = "accent";

    public static final String COL_ID = "_id";
    public static final String COL_ITEM_ID = "item_id";
    public static final String COL_PACKAGE = "package";
    /** "resume" (partway through) or "next" (the next episode). */
    public static final String COL_KIND = "kind";
    public static final String COL_TITLE = "title";
    /** "S2 · E4", "Special", the episode's name, the year or the length of a film; empty when unknown. */
    public static final String COL_SUBTITLE = "subtitle";
    /** Whole percent, 0 to 100; 0 for "next" rows and for apps that did not say. */
    public static final String COL_PROGRESS = "progress";
    public static final String COL_REMAINING_MS = "remaining_ms";
    public static final String COL_UPDATED_MS = "updated_ms";
    /** The best single picture: the backdrop, else the poster. content:// URI of a file this app made, or null. */
    public static final String COL_IMAGE = "image";
    public static final String COL_BACKDROP = "backdrop";
    public static final String COL_POSTER = "poster";
    public static final String COL_LOGO = "logo";
    /** An "intent:" URI that opens the title in its own app (or the app itself when the title has no link). */
    public static final String COL_INTENT_URI = "intent_uri";
    public static final String COL_SOURCE_LABEL = "source_label";
    public static final String COL_FETCHED_MS = "fetched_ms";

    public static final String[] COLUMNS = {
        COL_ID, COL_ITEM_ID, COL_PACKAGE, COL_KIND, COL_TITLE, COL_SUBTITLE, COL_PROGRESS,
        COL_REMAINING_MS, COL_UPDATED_MS, COL_IMAGE, COL_BACKDROP, COL_POSTER, COL_LOGO, COL_INTENT_URI,
        COL_SOURCE_LABEL, COL_FETCHED_MS,
    };

    static final String IMG_BACKDROP = "backdrop";
    static final String IMG_POSTER = "poster";
    static final String IMG_LOGO = "logo";

    /** Logcat tags: the probe's counts, and everything else. Neither ever prints a title, address or token. */
    static final String PROBE_TAG = "CWProbe";
    static final String TAG = "CW";
}
