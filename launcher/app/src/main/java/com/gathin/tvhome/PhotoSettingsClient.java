package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads and writes the Top Shelf photo settings of the Continue watching add-on. The launcher has no network and
 * cannot see the add-on's preferences, so this asks the add-on's provider with {@code ContentResolver.call()}
 * (README of the add-on, "Photo settings"; the provider applies the same caller check as for the feed). Every call
 * runs on a background thread, one at a time and in order, never on the main thread; each answer reaches the
 * {@link Listener} on the main thread, and an add-on that does not answer within {@link #TIMEOUT_MS} is reported as
 * "no answer" (a late answer is still delivered). "Fetch a new photo" is followed by status polls until the add-on says
 * it is done, but never for longer than {@link #FOLLOW_MS}: then one last plain status is asked for and shown (the
 * add-on itself stops saying "Fetching..." after the same 30 s), so the row cannot stay on "Fetching..." for good.
 */
final class PhotoSettingsClient {

    interface Listener {
        /** The newest state of the photo settings (also the "add-on not installed" kinds), on the main thread. */
        void onPhotoSettings(PhotoSettings settings);
    }

    static final String AUTHORITY = TopShelfLogic.AUTHORITY;
    static final String PACKAGE = "com.cleanlook.continuewatching";
    static final long TIMEOUT_MS = 8_000;
    static final long POLL_MS = 1_500;
    /** A fetch that has not ended after this long is no longer followed; the add-on's own limit is the same. */
    static final long FOLLOW_MS = 30_000;

    private static final String GET_SETTINGS = "photos_get_settings";
    private static final String SET_ENABLED = "photos_set_enabled";
    private static final String SET_CATEGORIES = "photos_set_categories";
    private static final String REFRESH = "photos_refresh";
    private static final String STATUS = "photos_status";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "photo-settings");
            t.setDaemon(true);
            return t;
        }
    });
    private boolean closed;
    private boolean pollWaiting;
    /** When the refresh being followed began (uptime ms); 0 when none is. */
    private long pollStarted;

    PhotoSettingsClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Stops everything: no more answers are delivered. */
    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    void load() {
        submit(GET_SETTINGS, null);
    }

    void setEnabled(boolean on) {
        Bundle extras = new Bundle();
        extras.putBoolean("enabled", on);
        submit(SET_ENABLED, extras);
    }

    /** {@code on} is the keys that stay on; the add-on refuses an empty set. */
    void setCategories(Set<String> on) {
        Bundle extras = new Bundle();
        extras.putStringArray("categories_on", on.toArray(new String[0]));
        submit(SET_CATEGORIES, extras);
    }

    /** Asks for a refresh now; the status is polled until the add-on says it is finished. */
    void refresh() {
        submit(REFRESH, null);
    }

    // ---- the calls --------------------------------------------------------------------------------------------

    private void submit(final String method, final Bundle extras) {
        if (closed) return;
        final AtomicBoolean delivered = new AtomicBoolean(false);
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!closed && delivered.compareAndSet(false, true)) {
                    listener.onPhotoSettings(PhotoSettings.unavailable(PhotoSettings.State.NO_ANSWER));
                }
            }
        };
        main.postDelayed(timeout, TIMEOUT_MS);
        try {
            io.submit(new Callable<Void>() {
                @Override public Void call() {
                    final PhotoSettings result = ask(method, extras);
                    main.post(new Runnable() {
                        @Override public void run() {
                            main.removeCallbacks(timeout);
                            delivered.set(true);   // a late answer after the timeout is still shown
                            if (closed) return;
                            listener.onPhotoSettings(result);
                            if (result.available() && result.refreshing) poll();
                            else pollStarted = 0;
                        }
                    });
                    return null;
                }
            });
        } catch (RuntimeException e) {   // RejectedExecutionException after close()
            main.removeCallbacks(timeout);
        }
    }

    /** One status check a moment from now, while a refresh runs; at most one is waiting at a time. */
    private void poll() {
        if (closed || pollWaiting) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (pollStarted == 0) pollStarted = now;
        if (pollGivenUp(now, pollStarted)) {
            pollStarted = 0;
            submit(GET_SETTINGS, null);   // one last plain status; the add-on no longer calls it "Fetching..." by now
            return;
        }
        pollWaiting = true;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                pollWaiting = false;
                if (!closed) submit(STATUS, null);
            }
        }, POLL_MS);
    }

    /** True once a fetch has been followed for {@link #FOLLOW_MS}. */
    static boolean pollGivenUp(long now, long started) {
        return started != 0 && now - started >= FOLLOW_MS;
    }

    /** One blocking call; never on the main thread. */
    private PhotoSettings ask(String method, Bundle extras) {
        try {
            if (context.getPackageManager().resolveContentProvider(AUTHORITY, 0) == null) {
                return PhotoSettings.unavailable(PhotoSettings.State.NOT_INSTALLED);
            }
            ContentResolver resolver = context.getContentResolver();
            Bundle answer = resolver.call(Uri.parse("content://" + AUTHORITY), method, null, extras);
            if (answer == null) return PhotoSettings.unavailable(PhotoSettings.State.OUTDATED);
            return fromBundle(answer);
        } catch (SecurityException e) {
            return PhotoSettings.unavailable(PhotoSettings.State.DENIED);
        } catch (IllegalArgumentException e) {
            return PhotoSettings.unavailable(PhotoSettings.State.NOT_INSTALLED);
        } catch (RuntimeException e) {
            return PhotoSettings.unavailable(PhotoSettings.State.NO_ANSWER);
        }
    }

    private static PhotoSettings fromBundle(Bundle b) {
        String[] keys = b.getStringArray("category_keys");
        String[] labels = b.getStringArray("category_labels");
        String[] on = b.getStringArray("categories_on");
        if (keys == null || labels == null || keys.length != labels.length) {
            return PhotoSettings.unavailable(PhotoSettings.State.OUTDATED);
        }
        List<String> keyList = new ArrayList<>(Arrays.asList(keys));
        Set<String> onSet = new LinkedHashSet<>(on == null ? new ArrayList<String>() : Arrays.asList(on));
        return PhotoSettings.ready(b.getBoolean("enabled", true), keyList, Arrays.asList(labels), onSet,
                b.getInt("count", 0), b.getBoolean("refreshing", false), b.getString("status", ""),
                b.getBoolean("ok", true) ? "" : b.getString("error", ""));
    }
}
