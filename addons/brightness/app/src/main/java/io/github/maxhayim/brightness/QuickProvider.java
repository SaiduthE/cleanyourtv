package io.github.maxhayim.brightness;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * The launcher's Brightness widget talks to the add-on through this provider (authority
 * "io.github.maxhayim.brightness.quick", see the README): it reads the level in use and steps it, exactly as the
 * quick bar does, without the add-on getting any new permission and without the launcher getting one.
 *
 * <ul>
 * <li>query (any path): one row with level (1 to 100, the one in use now), on (dimming switched on), night (night
 * level in use) and can_draw (the overlay permission is granted; without it nothing is drawn).
 * <li>call("step") with extras direction (-1 or 1) and optionally size (percent per step, 0 = the slider's own):
 * returns the same four values and applied (a layer is being drawn for the new level).
 * </ul>
 *
 * Exported, but only {@link QuickAccess} callers get anything; the others get a SecurityException.
 */
public final class QuickProvider extends ContentProvider {
    static final String[] COLUMNS = {"level", "on", "night", "can_draw"};
    static final String METHOD_STEP = "step";
    static final String EXTRA_DIRECTION = "direction";
    static final String EXTRA_SIZE = "size";

    @Override
    public boolean onCreate() {
        return true;
    }

    private void checkCaller() {
        Context c = getContext();
        int uid = Binder.getCallingUid();
        boolean sameApp = uid == Process.myUid();
        boolean sameKey = !sameApp && c != null
                && c.getPackageManager().checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
        if (!QuickAccess.allowed(sameApp, sameKey, getCallingPackage())) {
            throw new SecurityException("not a caller of the brightness widget");
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        checkCaller();
        Context c = getContext();
        if (c == null) return null;
        Bundle b = state(c, Prefs.load(c));
        MatrixCursor out = new MatrixCursor(COLUMNS);
        out.addRow(new Object[] {b.getInt("level"), b.getInt("on"), b.getInt("night"), b.getInt("can_draw")});
        return out;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_STEP.equals(method)) return null;
        checkCaller();
        Context c = getContext();
        if (c == null) return null;
        int direction = extras == null ? 0 : extras.getInt(EXTRA_DIRECTION, 0);
        int size = extras == null ? 0 : extras.getInt(EXTRA_SIZE, 0);
        Dimming.Settings s = Prefs.load(c);
        boolean night = Dimming.nightNow(s, DimService.minuteNow());
        if (direction != 0 && DimService.canDraw(c) && Dimming.quickStep(s, night, direction < 0 ? -1 : 1, size)) {
            Prefs.save(c, s);
            DimService.start(c);
        }
        Bundle out = state(c, s);
        out.putBoolean("applied", DimService.canDraw(c));
        return out;
    }

    private static Bundle state(Context c, Dimming.Settings s) {
        boolean night = Dimming.nightNow(s, DimService.minuteNow());
        Bundle b = new Bundle();
        b.putInt("level", Dimming.levelInUse(s, night));
        b.putInt("on", s.on ? 1 : 0);
        b.putInt("night", night ? 1 : 0);
        b.putInt("can_draw", DimService.canDraw(c) ? 1 : 0);
        return b;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
