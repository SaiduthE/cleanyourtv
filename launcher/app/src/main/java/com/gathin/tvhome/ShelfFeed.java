package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the Continue watching add-on's Top Shelf feed (addons/continue-watching/README.md). The provider checks the
 * caller itself, so no permission is needed; if the add-on is missing, switched off or refuses, every call answers
 * "nothing" and the shelf falls back to the app's own artwork. Run these off the main thread.
 */
final class ShelfFeed {

    private static final int MAX_ROWS = 3;

    private ShelfFeed() { }

    /** The feed's rows for one source app, newest first as the add-on orders them; empty on any failure. */
    static List<TopShelfLogic.Entry> query(ContentResolver resolver, String pkg) {
        Uri uri = Uri.parse(TopShelfLogic.ITEMS_URI).buildUpon().appendQueryParameter("pkg", pkg).build();
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c == null) return Collections.emptyList();
            int kind = c.getColumnIndex("kind"), title = c.getColumnIndex("title"), sub = c.getColumnIndex("subtitle"),
                    progress = c.getColumnIndex("progress"), source = c.getColumnIndex("package"),
                    intent = c.getColumnIndex("intent_uri"), backdrop = c.getColumnIndex("backdrop"),
                    poster = c.getColumnIndex("poster");
            if (title < 0) return Collections.emptyList();
            List<TopShelfLogic.Entry> rows = new ArrayList<>();
            while (c.moveToNext() && rows.size() < MAX_ROWS) {
                rows.add(new TopShelfLogic.Entry(text(c, kind), text(c, title), text(c, sub),
                        progress < 0 || c.isNull(progress) ? 0 : c.getInt(progress), text(c, source),
                        text(c, intent), text(c, backdrop), text(c, poster)));
            }
            return rows;
        } catch (RuntimeException e) {
            // SecurityException (not a caller of the shelf), IllegalArgumentException (no such provider): no shelf.
            return Collections.emptyList();
        }
    }

    private static String text(Cursor c, int column) {
        return column < 0 || c.isNull(column) ? null : c.getString(column);
    }

    /** A picture the add-on cached, decoded no larger than maxW x maxH; null when it is missing or unreadable. */
    static Bitmap decode(ContentResolver resolver, String address, int maxW, int maxH) {
        if (!TopShelfLogic.ownUri(address)) return null;
        Uri uri = Uri.parse(address);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = WallpaperSize.sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = resolver.openInputStream(uri)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * The Intent for a row's intent_uri, pinned to the row's package: parsed from the checked and stripped string,
     * with no selector, no foreign component and no flags of its own but NEW_TASK. Null when it must not launch.
     */
    static Intent launchIntent(String intentUri, String pkg) {
        String clean = ShelfIntent.check(intentUri);
        if (clean == null || pkg == null || pkg.isEmpty()) return null;
        try {
            Intent i;
            if (clean.regionMatches(true, 0, "intent:", 0, 7)) {
                i = Intent.parseUri(ShelfIntent.strip(clean, pkg), Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(clean));
            }
            i.setSelector(null);
            i.setSourceBounds(null);
            i.setClipData(null);
            ComponentName cn = i.getComponent();
            if (cn != null && !pkg.equals(cn.getPackageName())) i.setComponent(null);
            i.setPackage(pkg);
            Uri data = i.getData();
            if (data != null && data.getScheme() != null && !ShelfIntent.schemeAllowed(data.getScheme())) return null;
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
