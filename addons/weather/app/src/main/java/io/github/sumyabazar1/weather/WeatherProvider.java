package io.github.sumyabazar1.weather;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * The weather for the Clean Your TV launcher (docs/specs/weather-widget.md): /current and /daily, read-only, from the
 * saved snapshot. A query never touches the network; if the data is old it asks the refresh job to run.
 */
public final class WeatherProvider extends ContentProvider {
    private static final int CURRENT = 1;
    private static final int DAILY = 2;
    private UriMatcher matcher;

    @Override
    public boolean onCreate() {
        return true;
    }

    private UriMatcher matcher() {
        if (matcher == null) {
            UriMatcher m = new UriMatcher(UriMatcher.NO_MATCH);
            String authority = Refresher.authority(getContext());
            m.addURI(authority, "current", CURRENT);
            m.addURI(authority, "daily", DAILY);
            matcher = m;
        }
        return matcher;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        int match = matcher().match(uri);
        if (match == UriMatcher.NO_MATCH) throw new IllegalArgumentException("Unknown URI " + uri);
        String[] all = match == CURRENT ? WidgetRows.CURRENT_COLUMNS : WidgetRows.DAILY_COLUMNS;
        String[] columns = WidgetRows.projection(all, projection);
        MatrixCursor cursor = new MatrixCursor(columns);
        Snapshot snapshot = WidgetStore.load(c);
        if (snapshot != null) {
            if (match == CURRENT) {
                cursor.addRow(WidgetRows.pick(all, WidgetRows.current(snapshot, WidgetSettings.display(c)), columns));
            } else {
                for (Object[] row : WidgetRows.daily(snapshot)) cursor.addRow(WidgetRows.pick(all, row, columns));
            }
        }
        Refresher.kickIfStale(c, snapshot);
        cursor.setNotificationUri(c.getContentResolver(), uri);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        String base = "vnd." + getContext().getPackageName();
        switch (matcher().match(uri)) {
            case CURRENT: return "vnd.android.cursor.item/" + base + ".current";
            case DAILY: return "vnd.android.cursor.dir/" + base + ".daily";
            default: return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
