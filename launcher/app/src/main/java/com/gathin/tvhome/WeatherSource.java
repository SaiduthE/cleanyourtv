package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The launcher's side of the Weather add-on's widget contract: finds the add-on, reads its read-only content
 * provider off the main thread, and watches it only while Home or the screensaver is on screen (no polling, no
 * service). The launcher stays offline; the add-on does the reading. Every failure ends in "no weather".
 *
 * Opt-in: the package named by the overlay key weather_widget_package (default {@value #DEFAULT_PACKAGE}) must be
 * installed and enabled, and the provider authority "package.widget" must belong to that package.
 */
final class WeatherSource {

    static final String TAG = "TvHomeWeather";
    static final String DEFAULT_PACKAGE = "io.github.sumyabazar1.weather";
    static final String ACTION_SETTINGS = "io.github.sumyabazar1.weather.action.SETTINGS";
    static final String KEY_PACKAGE = "weather_widget_package";
    static final String KEY_CHIP = "weather_widget";
    static final String KEY_SCREENSAVER = "weather_on_clock";
    static final String KEY_ANIMATED = "weather_animated";
    private static final long DEBOUNCE_MS = 300L;

    /** Delivered on the main thread: the validated reading, or null when there is none to show. */
    interface Listener {
        void onWeather(WeatherData data);
    }

    /** The one query thread, shared by every source; it ends itself after ten idle seconds. */
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "weather-query");
                t.setDaemon(true);
                return t;
            });

    private final Context ctx;
    private final String pkg;
    private final Uri current, daily;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable refresh = this::query;
    private final ContentObserver observer;
    private boolean watching;
    /** Bumped by pause(): a query that finishes after it is dropped. Main thread only. */
    private int generation;

    WeatherSource(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        this.listener = listener;
        this.pkg = configuredPackage(ctx);
        String authority = pkg + ".widget";
        current = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("current").build();
        daily = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath("daily").build();
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                // The add-on notifies both URIs after a refresh: one read for the pair.
                main.removeCallbacks(refresh);
                main.postDelayed(refresh, DEBOUNCE_MS);
            }
        };
    }

    // ---- the owner's choices -------------------------------------------------------------------------------------

    /** The package to trust: the overlay key weather_widget_package, else the add-on's own. */
    static String configuredPackage(Context ctx) {
        String wanted = ThemeOverlay.string(ctx, KEY_PACKAGE, DEFAULT_PACKAGE).trim();
        return validPackageName(wanted) ? wanted : DEFAULT_PACKAGE;
    }

    static boolean validPackageName(String name) {
        return name != null && name.length() >= 3 && name.length() <= 120 && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    /**
     * A boolean choice: the theme overlay's key, then the launcher's own preference of the same name (a later
     * settings page writes there), either one false turns the feature off. Default true: the add-on being
     * installed and set to a city is the owner's opt-in, so nothing changes on a TV without it.
     */
    static boolean enabled(Context ctx, String key) {
        JSONObject overlay = ThemeOverlay.read(ctx);
        if (overlay != null && !overlay.optBoolean(key, true)) return false;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(key, true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** weather_animated (default true); the add-on's own "animated" flag is checked on the reading. */
    static boolean animatedAllowed(Context ctx) {
        return enabled(ctx, KEY_ANIMATED);
    }

    // ---- the add-on ----------------------------------------------------------------------------------------------

    /** True when the trusted package is installed and enabled and its provider is really its own. */
    static boolean available(Context ctx) {
        try {
            String pkg = configuredPackage(ctx);
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            if (!info.enabled) return false;
            ProviderInfo provider = pm.resolveContentProvider(pkg + ".widget", 0);
            return provider != null && pkg.equals(provider.packageName) && provider.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "weather add-on lookup failed", e);
            return false;
        }
    }

    /** The add-on's settings, by the fixed action and its package only (never anything read from the provider). */
    static Intent settingsIntent(Context ctx) {
        return new Intent(ACTION_SETTINGS).setPackage(configuredPackage(ctx)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---- watching ------------------------------------------------------------------------------------------------

    /** Home (or the screensaver) is on screen: read now and watch for the add-on's change notifications. */
    void resume() {
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(current, false, observer);
                ctx.getContentResolver().registerContentObserver(daily, false, observer);
                watching = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot watch the weather provider", e);
            }
        }
        refresh();
    }

    /** Home left the screen: stop watching, and drop a query still running. */
    void pause() {
        main.removeCallbacks(refresh);
        generation++;
        if (watching) {
            watching = false;
            try {
                ctx.getContentResolver().unregisterContentObserver(observer);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot stop watching the weather provider", e);
            }
        }
    }

    /** Read again now (a screensaver's 15 minute turn). Returns at once; the answer comes to the listener. */
    void refresh() {
        main.removeCallbacks(refresh);
        query();
    }

    private void query() {
        if (!queued.compareAndSet(false, true)) return;
        final int turn = generation;
        try {
            IO.execute(() -> {
                queued.set(false);
                WeatherData data = null;
                try {
                    data = read();
                } catch (Exception | LinkageError e) {
                    // A dead or misbehaving provider, a bad column, a security exception: all mean "no weather".
                    Log.w(TAG, "weather read failed: " + e.getClass().getSimpleName());
                }
                final WeatherData result = data;
                main.post(() -> {
                    if (turn == generation) listener.onWeather(result);
                });
            });
        } catch (RejectedExecutionException e) {
            queued.set(false);
        }
    }

    /** On the query thread: /current, then /daily. Null for an empty cursor (no city yet) or an invalid row. */
    private WeatherData read() {
        ContentResolver resolver = ctx.getContentResolver();
        List<Map<String, Object>> now = rows(resolver, current, 1);
        if (now.isEmpty()) return null;
        WeatherData data = WeatherData.fromCurrent(now.get(0));
        if (data == null) {
            Log.w(TAG, "weather row rejected");
            return null;
        }
        try {
            return data.withDaily(rows(resolver, daily, WeatherData.MAX_DAILY));
        } catch (Exception e) {
            // The forecast is optional: today's reading still shows.
            Log.w(TAG, "forecast unreadable: " + e.getClass().getSimpleName());
            return data;
        }
    }

    private static List<Map<String, Object>> rows(ContentResolver resolver, Uri uri, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Cursor c = resolver.query(uri, null, null, null, null)) {
            if (c == null) return out;
            String[] names = c.getColumnNames();
            while (c.moveToNext() && out.size() < max) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 0; i < names.length; i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: row.put(names[i], c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(names[i], c.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_STRING: row.put(names[i], c.getString(i)); break;
                        default: break;   // null and blobs are missing values
                    }
                }
                out.add(row);
            }
        }
        return out;
    }
}
