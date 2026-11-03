package com.cleanlook.continuewatching;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the sources, saves the result, makes the shelf pictures and tells the launcher. Blocking: use a worker thread. */
final class Refresher {
    private Refresher() {}

    private static final Object LOCK = new Object();
    private static volatile long lastStartMs;

    /** Watch Next and Jellyfin, as the hub does on open. */
    static void refreshAll(Context c) {
        refresh(c, true, true);
    }

    /** True when a refresh started less than {@code maxAgeMs} ago, in this process, so the caller can skip. */
    static boolean recent(long maxAgeMs) {
        return System.currentTimeMillis() - lastStartMs < maxAgeMs;
    }

    /** Remembers which packages publish Watch Next rows, once; the Sources screen shows the answer. */
    static void probeOnce(Context c) {
        Context app = c.getApplicationContext();
        Prefs prefs = App.prefs(app);
        if (prefs.probeAt() != 0 || !WatchNextReader.granted(app)) return;
        probe(app);
    }

    /** Reads every app's rows and saves the per-package counts. Logs counts only. */
    static void probe(Context app) {
        try {
            Map<String, Probe.Stat> stats = Probe.summarize(WatchNextReader.readSamples(app));
            for (String line : Probe.logLines(stats, true)) Log.i(Contract.PROBE_TAG, line);
            App.prefs(app).saveProbe(Probe.encode(stats), System.currentTimeMillis());
        } catch (SecurityException e) {
            Log.w(Contract.PROBE_TAG, "READ_TV_LISTINGS refused");
        } catch (RuntimeException e) {
            Log.w(Contract.PROBE_TAG, "probe failed: " + e.getClass().getSimpleName());
        }
    }

    static void refresh(Context c, boolean watchNext, boolean jellyfin) {
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            lastStartMs = now;
            Prefs prefs = App.prefs(app);
            Store.State s = Store.read(app);
            Store.State next = new Store.State();
            next.wn = s.wn;
            next.jf = s.jf;
            next.wnAt = s.wnAt;
            next.jfAt = s.jfAt;
            next.wnStatus = s.wnStatus;
            next.ch = s.ch;
            next.chans = s.chans;
            next.chStatus = s.chStatus;

            if (watchNext) {
                if (!WatchNextReader.granted(app)) {
                    next.wn = new ArrayList<>();
                    next.wnStatus = "denied";
                    next.ch = new ArrayList<>();
                    next.chans = new ArrayList<>();
                    next.chStatus = "denied";
                } else {
                    try {
                        List<WatchNextFilter.Row> rows = WatchNextReader.readWatchNext(app);
                        next.wn = WatchNextFilter.filter(rows, now, null, app.getPackageName());
                        next.wnAt = now;
                        next.wnStatus = "ok";
                    } catch (SecurityException e) {
                        next.wn = new ArrayList<>();
                        next.wnStatus = "denied";
                    } catch (RuntimeException e) {
                        Log.w(Contract.TAG, "watch next read failed: " + e.getClass().getSimpleName());
                        next.wnStatus = "error";
                    }
                    readChannels(app, next);
                }
            }

            if (jellyfin) {
                if (!prefs.jellyfinConfigured()) {
                    next.jf = new ArrayList<>();
                    prefs.setJellyfinStatus(Prefs.JF_NONE);
                } else {
                    try {
                        JellyfinClient jc = new JellyfinClient(prefs.serverUrl(), prefs.token(), prefs.userId(),
                                prefs.deviceId());
                        List<Item> all = new ArrayList<>();
                        if (prefs.showResume()) all.addAll(jc.resume(now));
                        if (prefs.showNextUp()) all.addAll(jc.nextUp(now));
                        next.jf = all;
                        next.jfAt = now;
                        prefs.setJellyfinStatus(Prefs.JF_OK);
                    } catch (JellyfinClient.AuthException e) {
                        prefs.setJellyfinStatus(Prefs.JF_SIGN_IN);
                    } catch (IOException | org.json.JSONException e) {
                        Log.w(Contract.TAG, "jellyfin refresh failed: " + e.getClass().getSimpleName());
                        prefs.setJellyfinStatus(Prefs.JF_UNREACHABLE);
                    }
                }
            }

            try {
                Store.write(app, next);
            } catch (IOException e) {
                Log.w(Contract.TAG, "could not save state");
                return;
            }
            publish(app, next);
        }
    }

    /** Preview channels (Plex, Netflix): all of them are saved; the owner's switches pick at display time. */
    private static void readChannels(Context app, Store.State next) {
        try {
            List<ChannelFilter.Info> infos = new ArrayList<>();
            next.ch = ChannelFilter.convert(WatchNextReader.readChannels(app), WatchNextReader.readChannelPrograms(app),
                    app.getPackageName(), infos);
            next.chans = infos;
            next.chStatus = "ok";
        } catch (SecurityException e) {
            next.ch = new ArrayList<>();
            next.chans = new ArrayList<>();
            next.chStatus = "denied";
        } catch (RuntimeException e) {
            Log.w(Contract.TAG, "channel read failed: " + e.getClass().getSimpleName());
            next.chStatus = "error";
        }
    }

    /** After the owner switches an app or the feed on or off: the shelf follows at once, without new queries. */
    static void republish(Context c) {
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            publish(app, Store.read(app));
        }
    }

    private static void publish(Context app, Store.State s) {
        Prefs prefs = App.prefs(app);
        // Switched off: no rows and no files left behind.
        ShelfImages.sync(app, prefs.shelfEnabled() ? Store.visible(app, s, prefs.disabledPackages())
                : new ArrayList<Item>());
        app.getContentResolver().notifyChange(Contract.ITEMS_URI, null);
    }
}
