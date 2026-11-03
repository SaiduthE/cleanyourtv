package com.cleanlook.continuewatching;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Top Shelf feed for the launcher (contract in README.md): the rows of Continue watching with real artwork
 * as files this app cached, and the Top Shelf photos (/photos, /photo/name). Read-only, except that {@code call()} lets
 * the launcher's settings read and change the photo settings ({@link PhotoCalls}). Exported, but only {@link ShelfAccess} callers get anything.
 */
public final class ShelfProvider extends ContentProvider {
    private static final long REFRESH_AFTER_MS = 10 * 60_000L;

    @Override public boolean onCreate() {
        return true;
    }

    private boolean callerAllowed() {
        Context c = getContext();
        int uid = Binder.getCallingUid();
        boolean sameApp = uid == Process.myUid();
        boolean sameKey = !sameApp && c != null
                && c.getPackageManager().checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
        return ShelfAccess.allowed(sameApp, sameKey, getCallingPackage());
    }

    private void checkCaller() {
        if (!callerAllowed()) throw new SecurityException("not a caller of the shelf");
    }

    /**
     * The photo settings for Launcher settings > Top Shelf (see {@link PhotoCalls}): same caller check as the rest of
     * the feed. Null for a method that is not ours.
     */
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        boolean allowed = callerAllowed();
        if (!allowed || c == null) {
            if (!allowed) throw new SecurityException("not a caller of the shelf");
            return null;
        }
        Map<String, Object> in = new HashMap<>();
        if (extras != null) {
            if (extras.containsKey(PhotoCalls.EXTRA_ENABLED)) in.put(PhotoCalls.EXTRA_ENABLED, extras.getBoolean(PhotoCalls.EXTRA_ENABLED));
            String shown = extras.getString(PhotoCalls.EXTRA_SHOWN);
            if (shown != null) in.put(PhotoCalls.EXTRA_SHOWN, shown);
            String[] on = extras.getStringArray(PhotoCalls.EXTRA_ON);
            if (on != null) in.put(PhotoCalls.EXTRA_ON, on);
        }
        Map<String, Object> out = PhotoCalls.dispatch(true, new PhotoBackend(c), method, in);
        return out == null ? null : toBundle(out);
    }

    private static Bundle toBundle(Map<String, Object> m) {
        Bundle b = new Bundle();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) b.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) b.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) b.putLong(e.getKey(), (Long) v);
            else if (v instanceof String[]) b.putStringArray(e.getKey(), (String[]) v);
            else if (v instanceof String) b.putString(e.getKey(), (String) v);
        }
        return b;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String last = uri.getLastPathSegment();
        if (Contract.PATH_PHOTOS.equals(last) && uri.getPathSegments().size() == 1) return photos();
        if (!Contract.PATH_ITEMS.equals(last)) return null;
        checkCaller();
        final Context c = getContext();
        if (c == null) return null;
        Prefs prefs = App.prefs(c);
        MatrixCursor out = new MatrixCursor(Contract.COLUMNS);
        out.setNotificationUri(c.getContentResolver(), Contract.ITEMS_URI);
        if (!prefs.shelfEnabled()) return out;

        Store.State s = Store.read(c);
        List<Item> visible = Store.visible(c, s, prefs.disabledPackages());
        long fetched = Math.max(s.wnAt, s.jfAt);
        final Context app = c.getApplicationContext();
        List<ShelfRows.Row> rows = ShelfRows.build(visible, uri.getQueryParameter(Contract.PARAM_PKG), fetched,
                new ShelfRows.Images() {
                    @Override public String uriFor(Item it, String kind) {
                        return ShelfImages.uriFor(app, it, kind);
                    }
                }, new ShelfRows.Labels() {
                    @Override public String labelFor(String pkg) {
                        return App.label(app, pkg);
                    }
                }, new ShelfRows.Links() {
                    @Override public String intentFor(Item it) {
                        return Launcher.shelfIntentUri(app, it);
                    }
                });
        long id = 0;
        for (ShelfRows.Row r : rows) {
            out.addRow(new Object[] {
                ++id, r.itemId, r.pkg, r.kind, r.title, r.subtitle, r.progress, r.remainingMs, r.updatedMs, r.image,
                r.backdrop, r.poster, r.logo, r.intentUri, r.sourceLabel, r.fetchedMs,
            });
        }
        // The launcher asks on every Home resume: that is the cue to look again, at most every ten minutes.
        if (!Refresher.recent(REFRESH_AFTER_MS)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    Refresher.refreshAll(app);
                }
            }, "cw-shelf-refresh").start();
        }
        return out;
    }

    /** The Top Shelf photos, newest first (whole files only). A ring with fewer than two photos is the cue to top it up. */
    private Cursor photos() {
        checkCaller();
        Context c = getContext();
        MatrixCursor out = new MatrixCursor(Contract.PHOTO_COLUMNS);
        if (c == null) return out;
        Context app = c.getApplicationContext();
        out.setNotificationUri(app.getContentResolver(), Contract.PHOTOS_URI);
        if (!App.prefs(app).photosEnabled()) return out;
        long id = 0;
        for (PhotoCache.Photo p : PhotoFeed.available(app)) {
            String uri = new Uri.Builder().scheme("content").authority(Contract.AUTHORITY).appendPath(Contract.PATH_PHOTO)
                    .appendPath(p.name).build().toString();
            out.addRow(new Object[] {++id, uri, p.width, p.height, p.credit});
        }
        if (id < PhotoCache.MIN_READY) PhotoFeed.kick(app);
        return out;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        checkCaller();
        List<String> seg = uri.getPathSegments();
        Context c = getContext();
        if (c != null && seg.size() == 2 && Contract.PATH_PHOTO.equals(seg.get(0))) {
            if (!"r".equals(mode) || !App.prefs(c).photosEnabled()
                    || !PhotoCache.validName(seg.get(1), PhotoFeed.listing(c))) {
                throw new FileNotFoundException();
            }
            return ParcelFileDescriptor.open(new File(PhotoFeed.dir(c), seg.get(1)), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (c == null || !"r".equals(mode) || seg.size() != 2 || !Contract.PATH_IMG.equals(seg.get(0))
                || !seg.get(1).matches(ShelfImages.NAME_PATTERN)) {
            throw new FileNotFoundException();
        }
        File f = new File(ShelfImages.dir(c), seg.get(1));
        if (!f.isFile()) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        if (Contract.PATH_ITEMS.equals(last)) return "vnd.android.cursor.dir/vnd." + Contract.AUTHORITY + ".item";
        if (Contract.PATH_PHOTOS.equals(last)) return "vnd.android.cursor.dir/vnd." + Contract.AUTHORITY + ".photo";
        if (last != null && last.endsWith(".png")) return "image/png";
        return last != null && last.endsWith(".jpg") ? "image/jpeg" : null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
