package com.cleanlook.continuewatching;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.net.URISyntaxException;

/** Starts the app that owns an item. */
final class Launcher {
    private Launcher() {}

    enum Result { TITLE, APP_ONLY, FAILED }

    /**
     * Jellyfin for Android TV's start activity and the extras its own Watch Next and channel rows use
     * (StartupActivity, "ItemId", "ItemIsUserView"). Not documented as a public API and not verified on a
     * device by this project: if the app ignores or refuses it, the fallback opens the app.
     */
    static final String JELLYFIN_START = "org.jellyfin.androidtv.ui.startup.StartupActivity";
    static final String JELLYFIN_EXTRA_ITEM = "ItemId";
    static final String JELLYFIN_EXTRA_USER_VIEW = "ItemIsUserView";

    /** The intent for a Watch Next row, pinned to the row's package; null if the uri must not be launched. */
    static Intent watchNextIntent(String intentUri, String pkg) {
        String clean = IntentPolicy.check(intentUri);
        if (clean == null) return null;
        try {
            Intent i;
            if (clean.regionMatches(true, 0, "intent:", 0, 7)) {
                i = Intent.parseUri(IntentPolicy.strip(clean, pkg), Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(clean));
            }
            // Never let a row carry a selector, a foreign component or its own flags.
            i.setSelector(null);
            i.setSourceBounds(null);
            i.setClipData(null);
            ComponentName cn = i.getComponent();
            if (cn != null && !pkg.equals(cn.getPackageName())) i.setComponent(null);
            i.setPackage(pkg);
            Uri data = i.getData();
            if (data != null && data.getScheme() != null && !IntentPolicy.schemeAllowed(data.getScheme())) return null;
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    static Intent jellyfinIntent(Item it) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName(Item.JELLYFIN_PKG, JELLYFIN_START));
        i.putExtra(JELLYFIN_EXTRA_ITEM, JellyfinParser.dashedUuid(it.jellyfinId));
        i.putExtra(JELLYFIN_EXTRA_USER_VIEW, false);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** The intent that opens the title itself, or null when the item has no usable link. */
    static Intent titleIntent(Item it) {
        return Item.SRC_JELLYFIN.equals(it.src) ? jellyfinIntent(it) : watchNextIntent(it.intentUri, it.pkg);
    }

    /** The app's own start intent, or null when it is not installed. */
    static Intent appIntent(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** What the shelf publishes for an item: the title's intent, else the app's. Null when neither exists. */
    static String shelfIntentUri(Context c, Item it) {
        Intent i = titleIntent(it);
        if (i == null) i = appIntent(c, it.pkg);
        return i == null ? null : i.toUri(Intent.URI_INTENT_SCHEME);
    }

    static Result launch(Context c, Item it) {
        Intent title = titleIntent(it);
        if (title != null) {
            try {
                c.startActivity(title);
                return Result.TITLE;
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(Contract.TAG, "title intent refused for " + it.pkg + ": " + e.getClass().getSimpleName());
            }
        }
        return openApp(c, it.pkg) ? Result.APP_ONLY : Result.FAILED;
    }

    static boolean openApp(Context c, String pkg) {
        Intent i = appIntent(c, pkg);
        if (i == null) return false;
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** What to tell the owner after a launch, or null when the title itself opened. */
    static String message(Context c, Item it, Result r) {
        String app = App.label(c, it.pkg);
        switch (r) {
            case APP_ONLY: return "Opened " + app + ". Look for \"" + it.title + "\" there.";
            case FAILED: return App.installed(c, it.pkg) ? app + " could not be opened." : app + " is not installed.";
            default: return null;
        }
    }
}
