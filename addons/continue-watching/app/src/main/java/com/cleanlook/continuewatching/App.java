package com.cleanlook.continuewatching;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

/** Process-wide singletons: settings and the image loader. */
public final class App extends Application {
    private static Prefs prefs;
    private static ImageLoader images;

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        images = new ImageLoader(this);
        // Before anything else can save a probe or a choice: an install from before "Add source" keeps what it showed.
        if (!prefs.addedMigrated()) prefs.migrateAdded(Store.read(this));
        refreshImageAuth(this);
        PhotoJobService.schedule(this);
    }

    static Prefs prefs(Context c) {
        if (prefs == null) prefs = new Prefs(c);
        return prefs;
    }

    static ImageLoader images(Context c) {
        if (images == null) images = new ImageLoader(c);
        return images;
    }

    /** Points the image loader's Jellyfin headers at the configured server (or clears them). */
    static void refreshImageAuth(Context c) {
        Prefs p = prefs(c);
        if (p.jellyfinConfigured()) {
            JellyfinClient jc = new JellyfinClient(p.serverUrl(), p.token(), p.userId(), p.deviceId());
            images(c).setAuth(p.serverUrl(), jc.imageHeaders());
        } else {
            images(c).setAuth("", null);
        }
    }

    // ---- other apps' names and icons

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return Item.JELLYFIN_PKG.equals(pkg) ? "Jellyfin" : pkg;
        }
    }

    static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** The app's TV banner (launcher activity's, else the app's), or null. */
    static Drawable banner(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
            Drawable d = pm.getActivityBanner(launcher);
            return d != null ? d : pm.getApplicationBanner(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
