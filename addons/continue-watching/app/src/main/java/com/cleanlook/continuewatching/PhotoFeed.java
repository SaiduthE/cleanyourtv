package com.cleanlook.continuewatching;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Top Shelf photos: scenic pictures from Wikimedia Commons' featured-picture categories, kept as a small rolling
 * buffer of files in this app and offered to the launcher by {@link ShelfProvider}. The ring is topped up one photo at
 * a time (see {@link PhotoTopUp}); each new photo is written to a temporary name and renamed, so the provider only ever
 * lists whole files. Only https to commons.wikimedia.org (the API, no key) and upload.wikimedia.org (the pictures),
 * with a generic User-Agent. The top-ups run on their own worker threads; nothing here blocks the caller.
 */
final class PhotoFeed {
    private PhotoFeed() {}

    private static final String USER_AGENT = "CleanTV-Photos/1.0 (Android TV)";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(PhotoFilter.API_HOST, PhotoFilter.UPLOAD_HOST));
    /** The API answers fast; a 1920 px picture rendered for the first time can take its time. */
    private static final int JSON_READ_MS = 12000;
    private static final int IMAGE_READ_MS = 15000;
    private static final int MIN_DECODED_WIDTH = 1200;

    /** Why a top-up is asked for. */
    enum Trigger {
        /** The add-on was opened, or the launcher read the list: fills a ring that is below its target. */
        OPEN,
        /** The scheduled job: only on an unmetered network. */
        JOB,
        /** The launcher rotated to the next picture: one new photo. */
        ADVANCE,
        /** The owner asked for a new photo now: one new photo, on any connection, whatever the rest after a failure. */
        MANUAL
    }

    /** What asking for a top-up led to. */
    enum Outcome { STARTED, ALREADY_RUNNING, NOT_NOW, NO_NETWORK, OFF }

    private static final PhotoTopUp TOP_UP = new PhotoTopUp();
    /** The kept state of {@link PhotoRun} (page ids, checked candidates); null until the first fetch. */
    private static PhotoRun run;
    /** Everything that reads and writes the record of the ring takes this lock. */
    private static final Object RECORD = new Object();

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        d.mkdirs();
        return d;
    }

    /** The names of the finished photo files in the folder (a download in progress has another name and is not listed). */
    static Set<String> listing(Context c) {
        Set<String> out = new HashSet<>();
        File[] all = dir(c).listFiles();
        if (all == null) return out;
        for (File f : all) if (f.isFile() && f.length() > 0 && f.getName().matches(PhotoCache.NAME_PATTERN)) out.add(f.getName());
        return out;
    }

    /** The photos the provider offers, newest first: the record, minus every photo whose file is gone. */
    static List<PhotoCache.Photo> available(Context c) {
        synchronized (RECORD) {
            return PhotoCache.available(PhotoCache.decode(App.prefs(c).photosRecord()), listing(c));
        }
    }

    /** Removes every photo and its record (the owner switched the feed off). */
    static void clear(Context c) {
        Context app = c.getApplicationContext();
        synchronized (RECORD) {
            File[] all = dir(app).listFiles();
            if (all != null) for (File f : all) f.delete();
            App.prefs(app).setPhotosRecord("");
        }
        app.getContentResolver().notifyChange(Contract.PHOTOS_URI, null);
    }

    /** How long the fetch in flight has been going (ms), or -1 when none is. */
    static long flightMs() {
        return TOP_UP.flightMs(System.currentTimeMillis());
    }

    static boolean busy() {
        return TOP_UP.busy();
    }

    // ---- when

    /** {connected, unmetered}: a validated internet connection, and whether it is not metered. */
    static boolean[] network(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return new boolean[] {false, false};
            boolean up = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return new boolean[] {up, nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)};
        } catch (RuntimeException e) {
            return new boolean[] {false, false};
        }
    }

    /**
     * Fills the ring up to its target when it is below it. Called when the add-on opens and, when the ring has fewer
     * than {@link PhotoCache#MIN_READY} photos, when the launcher reads the list. Returns at once.
     */
    static void kick(Context c) {
        start(c, Trigger.OPEN, null, false);
    }

    /**
     * The launcher showed the next picture ({@code shown} is that photo's file name, or null): remember it as shown
     * and add one new photo in the background. Returns at once.
     */
    static Outcome advance(Context c, String shown, boolean manual) {
        return start(c, manual ? Trigger.MANUAL : Trigger.ADVANCE, shown, false);
    }

    /** The scheduled job: tops the ring up on an unmetered network and returns when that is done. */
    static void job(Context c) {
        start(c, Trigger.JOB, null, true);
    }

    private static Outcome start(Context c, Trigger trigger, String shown, boolean blocking) {
        final Context app = c.getApplicationContext();
        final Prefs prefs = App.prefs(app);
        if (!prefs.photosEnabled()) return Outcome.OFF;
        if (shown != null) markShown(app, shown);
        boolean manual = trigger == Trigger.MANUAL;
        boolean[] net = network(app);
        int ready = available(app).size();
        long now = System.currentTimeMillis();
        if (trigger == Trigger.OPEN && !PhotoCache.belowTarget(ready)) return Outcome.NOT_NOW;
        if (!net[0]) {
            if (manual) prefs.setPhotosNote("No network");
            return Outcome.NO_NETWORK;
        }
        boolean allowed = trigger == Trigger.JOB ? net[1]
                : PhotoPolicy.mayFetch(true, net[0], net[1], manual, ready);
        if (!allowed) return Outcome.NOT_NOW;
        final PhotoTopUp.Mode mode = trigger == Trigger.ADVANCE || manual ? PhotoTopUp.Mode.ADVANCE : PhotoTopUp.Mode.FILL;
        if (!TOP_UP.claim(mode, manual, now)) {
            return TOP_UP.flightMs(now) >= 0 ? Outcome.ALREADY_RUNNING : Outcome.NOT_NOW;
        }
        if (manual) prefs.setPhotosNote("");
        Runnable job = new Runnable() {
            @Override public void run() {
                work(app, mode);
            }
        };
        if (blocking) {
            job.run();
        } else {
            try {
                Thread t = new Thread(job, "cw-photos");
                t.start();
            } catch (RuntimeException | OutOfMemoryError e) {
                TOP_UP.release();
                return Outcome.NOT_NOW;
            }
        }
        return Outcome.STARTED;
    }

    // ---- the top-up

    /** The claimed top-up, to its end. Always ends the flight and leaves a plain note behind. */
    private static void work(final Context app, PhotoTopUp.Mode mode) {
        Prefs prefs = App.prefs(app);
        try {
            trim(app, PhotoCache.MAX_PHOTOS);
            PhotoTopUp.Report report = TOP_UP.run(mode, source(app, prefs), new PhotoTopUp.Clock() {
                @Override public long now() {
                    return System.currentTimeMillis();
                }

                @Override public boolean sleep(long ms) {
                    try {
                        Thread.sleep(ms);
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            });
            PhotoRun.Result last = report.last;
            if (last != null && last.failure != null) Log.w(Contract.TAG, "photo: " + last.failure.kind + " " + last.failure.code);
            prefs.setPhotosNote(last == null || report.added > 0 ? "" : PhotoRun.note(last));
        } catch (RuntimeException e) {
            Log.w(Contract.TAG, "photo top-up crashed: " + e.getClass().getSimpleName());
            prefs.setPhotosNote("Something went wrong, retrying");
        }
    }

    private static PhotoTopUp.Source source(final Context app, final Prefs prefs) {
        return new PhotoTopUp.Source() {
            @Override public int count() {
                return available(app).size();
            }

            @Override public PhotoRun.Result fetchOne() {
                if (!prefs.photosEnabled()) {
                    PhotoRun.Result off = new PhotoRun.Result();
                    off.stopped = true;
                    return off;
                }
                reconcile(app);
                Set<String> have = new HashSet<>();
                for (PhotoCache.Photo p : PhotoCache.decode(prefs.photosRecord())) have.add(p.name);
                return runner(app).fetchOne(PhotoPolicy.enabled(prefs.photosOff()), have, new Random());
            }
        };
    }

    private static synchronized PhotoRun runner(final Context app) {
        if (run != null) return run;
        PhotoRun.Net net = new PhotoRun.Net() {
            @Override public byte[] get(String url, boolean image) throws IOException {
                return Http.getGuarded(url, headers(image ? "image/jpeg" : "application/json"),
                        image ? PhotoCache.MAX_FILE_BYTES : Http.JSON_MAX, HOSTS, image ? IMAGE_READ_MS : JSON_READ_MS).body;
            }
        };
        PhotoRun.Env env = new PhotoRun.Env() {
            @Override public boolean sleep(long ms) {
                try {
                    Thread.sleep(ms);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override public long now() {
                return System.currentTimeMillis();
            }

            @Override public void waiting(boolean busy) {
                TOP_UP.setBusy(busy);
            }
        };
        PhotoRun.Saver saver = new PhotoRun.Saver() {
            @Override public PhotoCache.Photo store(PhotoFilter.Candidate cand, String name, byte[] body) throws IOException {
                PhotoCache.Photo p = save(app, cand, name, body);
                if (p != null) add(app, p);
                return p;
            }
        };
        run = new PhotoRun(net, env, saver);
        return run;
    }

    /** Makes the record match the folder: files nobody lists (and half-written ones) and records without a file go. */
    private static void reconcile(Context app) {
        Prefs prefs = App.prefs(app);
        synchronized (RECORD) {
            Set<String> files = listing(app);
            List<PhotoCache.Photo> record = PhotoCache.decode(prefs.photosRecord());
            List<PhotoCache.Photo> keep = new ArrayList<>();
            Set<String> known = new HashSet<>();
            for (PhotoCache.Photo p : record) {
                if (files.contains(p.name) && known.add(p.name)) keep.add(p);
            }
            File[] all = dir(app).listFiles();
            if (all != null) for (File f : all) if (!known.contains(f.getName())) f.delete();
            if (keep.size() != record.size()) prefs.setPhotosRecord(PhotoCache.encode(keep));
        }
    }

    /** Adds the photo to the record, drops what no longer fits, saves, and tells the launcher. */
    private static void add(Context app, PhotoCache.Photo p) {
        Prefs prefs = App.prefs(app);
        synchronized (RECORD) {
            List<PhotoCache.Photo> next = PhotoCache.decode(prefs.photosRecord());
            next.add(p);
            prefs.setPhotosRecord(PhotoCache.encode(drop(app, next, PhotoCache.TARGET)));
        }
        app.getContentResolver().notifyChange(Contract.PHOTOS_URI, null);
    }

    /** Cuts an over-full ring back to {@code target} (the photo shown longest ago first). */
    private static void trim(Context app, int over) {
        Prefs prefs = App.prefs(app);
        boolean changed = false;
        synchronized (RECORD) {
            List<PhotoCache.Photo> record = PhotoCache.decode(prefs.photosRecord());
            if (record.size() > over) {
                prefs.setPhotosRecord(PhotoCache.encode(drop(app, record, PhotoCache.TARGET)));
                changed = true;
            }
        }
        if (changed) app.getContentResolver().notifyChange(Contract.PHOTOS_URI, null);
    }

    /** The record without the photos that go (their files are deleted). Call with the record lock held. */
    private static List<PhotoCache.Photo> drop(Context app, List<PhotoCache.Photo> record, int target) {
        List<String> gone = PhotoCache.evict(record, target, PhotoCache.MAX_BYTES);
        List<PhotoCache.Photo> kept = new ArrayList<>();
        for (PhotoCache.Photo q : record) {
            if (gone.contains(q.name)) new File(dir(app), q.name).delete();
            else kept.add(q);
        }
        return kept;
    }

    /** Remembers that the launcher showed this photo now, so it is the last one to be deleted. */
    private static void markShown(Context app, String name) {
        if (!name.matches(PhotoCache.NAME_PATTERN)) return;
        Prefs prefs = App.prefs(app);
        synchronized (RECORD) {
            List<PhotoCache.Photo> record = PhotoCache.decode(prefs.photosRecord());
            boolean known = false;
            for (PhotoCache.Photo p : record) known |= p.name.equals(name);
            if (known) prefs.setPhotosRecord(PhotoCache.encode(PhotoCache.markShown(record, name, System.currentTimeMillis())));
        }
    }

    private static Map<String, String> headers(String accept) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", accept);
        return h;
    }

    /**
     * Keeps one downloaded picture in the folder: written under a temporary name and renamed when whole, so a
     * half-written file is never listed. Null when it is not a JPEG, too narrow or not the right shape.
     */
    private static PhotoCache.Photo save(Context app, PhotoFilter.Candidate cand, String name, byte[] body)
            throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(body, 0, body.length, bounds);
        if (!"image/jpeg".equals(bounds.outMimeType) || bounds.outWidth < MIN_DECODED_WIDTH
                || !PhotoFilter.aspectOk(bounds.outWidth, bounds.outHeight)) {
            return null;
        }
        File out = new File(dir(app), name);
        File tmp = new File(dir(app), name + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(body);
            os.getFD().sync();
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            return null;
        }
        return new PhotoCache.Photo(name, cand.title, bounds.outWidth, bounds.outHeight, cand.credit,
                System.currentTimeMillis(), body.length);
    }
}
