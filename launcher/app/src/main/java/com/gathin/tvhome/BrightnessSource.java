package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The launcher's side of the Brightness add-on's quick provider (addons/brightness, QuickProvider): reads the
 * level in use and steps it, off the main thread, by the provider's fixed authority. The add-on does the dimming;
 * the launcher needs no permission for it. Every failure ends in "no level".
 */
final class BrightnessSource {

    static final String TAG = "TvHomeBrightness";
    static final String PACKAGE = "io.github.maxhayim.brightness";
    static final String AUTHORITY = PACKAGE + ".quick";

    /** What the add-on reports: always a validated level. */
    static final class Level {
        final int level;
        final boolean on, night, canDraw;

        Level(int level, boolean on, boolean night, boolean canDraw) {
            this.level = level;
            this.on = on;
            this.night = night;
            this.canDraw = canDraw;
        }
    }

    /** Delivered on the main thread: the level, or null when the add-on cannot be read. */
    interface Listener {
        void onLevel(Level level);
    }

    /** A level from the provider's four values, or null when the level is outside 1 to 100. */
    static Level validated(int level, int on, int night, int canDraw) {
        if (level < 1 || level > 100) return null;
        return new Level(level, on != 0, night != 0, canDraw != 0);
    }

    /** The step size sent with a press: 0 is the add-on's own steps, else that many percent. */
    static int stepSize(String optionValue) {
        if ("5".equals(optionValue)) return 5;
        if ("10".equals(optionValue)) return 10;
        return 0;
    }

    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "brightness-query");
                t.setDaemon(true);
                return t;
            });

    private final ContentResolver resolver;
    private final Uri level = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
            .appendPath("level").build();
    private final Handler main = new Handler(Looper.getMainLooper());

    BrightnessSource(Context ctx) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        resolver = app.getContentResolver();
    }

    /** True when the add-on is installed and enabled and its provider is really its own. */
    static boolean available(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(PACKAGE, 0);
            if (!info.enabled) return false;
            ProviderInfo provider = pm.resolveContentProvider(AUTHORITY, 0);
            return provider != null && PACKAGE.equals(provider.packageName) && provider.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "brightness add-on lookup failed", e);
            return false;
        }
    }

    /** Reads the level in use now. Returns at once; the answer comes to the listener. */
    void read(Listener listener) {
        run(() -> {
            try (Cursor c = resolver.query(level, null, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                return validated(c.getInt(c.getColumnIndexOrThrow("level")), c.getInt(c.getColumnIndexOrThrow("on")),
                        c.getInt(c.getColumnIndexOrThrow("night")), c.getInt(c.getColumnIndexOrThrow("can_draw")));
            }
        }, listener);
    }

    /** One press: direction -1 or 1, size as {@link #stepSize}. The answer is the level after it. */
    void step(int direction, int size, Listener listener) {
        run(() -> {
            Bundle extras = new Bundle();
            extras.putInt("direction", direction < 0 ? -1 : 1);
            extras.putInt("size", size);
            Bundle out = resolver.call(Uri.parse("content://" + AUTHORITY), "step", null, extras);
            if (out == null) return null;
            return validated(out.getInt("level"), out.getInt("on"), out.getInt("night"), out.getInt("can_draw"));
        }, listener);
    }

    private interface Query {
        Level run() throws Exception;
    }

    private void run(Query query, Listener listener) {
        try {
            IO.execute(() -> {
                Level result = null;
                try {
                    result = query.run();
                } catch (Exception | LinkageError e) {
                    // A missing add-on, a caller it does not answer (SecurityException), a bad column: no level.
                    Log.w(TAG, "brightness read failed: " + e.getClass().getSimpleName());
                }
                final Level answer = result;
                main.post(() -> listener.onLevel(answer));
            });
        } catch (RejectedExecutionException e) {
            main.post(() -> listener.onLevel(null));
        }
    }
}
