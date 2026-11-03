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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Top Shelf photos: scenic pictures from Wikimedia Commons' featured-picture categories, cached as files in this
 * app and offered to the launcher by {@link ShelfProvider}. Only https to commons.wikimedia.org (the API, no key)
 * and upload.wikimedia.org (the pictures), with a generic User-Agent. Blocking: use a worker thread.
 */
final class PhotoFeed {
    private PhotoFeed() {}

    private static final String USER_AGENT = "CleanTV-Photos/1.0 (Android TV)";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(PhotoFilter.API_HOST, PhotoFilter.UPLOAD_HOST));
    /** The API answers fast; a 1920 px picture rendered for the first time can take its time. */
    private static final int JSON_READ_MS = 15000;
    private static final int IMAGE_READ_MS = 20000;
    private static final int MIN_DECODED_WIDTH = 1200;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    static boolean running() {
        return RUNNING.get();
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        d.mkdirs();
        return d;
    }

    /** The names of the finished photo files in the cache folder. */
    static Set<String> listing(Context c) {
        Set<String> out = new HashSet<>();
        File[] all = dir(c).listFiles();
        if (all == null) return out;
        for (File f : all) if (f.isFile() && f.length() > 0 && f.getName().matches(PhotoCache.NAME_PATTERN)) out.add(f.getName());
        return out;
    }

    /** The photos the provider offers, newest first: the record, minus every photo whose file is gone. */
    static List<PhotoCache.Photo> available(Context c) {
        return PhotoCache.available(PhotoCache.decode(App.prefs(c).photosRecord()), listing(c));
    }

    /** Removes every photo and its record (the owner switched the feed off). */
    static void clear(Context c) {
        Context app = c.getApplicationContext();
        File[] all = dir(app).listFiles();
        if (all != null) for (File f : all) f.delete();
        App.prefs(app).setPhotosRecord("");
        app.getContentResolver().notifyChange(Contract.PHOTOS_URI, null);
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

    /** True when an automatic refresh would run now. Cheap: reads the preferences and the cache folder. */
    static boolean due(Context c, boolean manual) {
        Prefs p = App.prefs(c);
        boolean[] net = network(c);
        return PhotoPolicy.due(p.photosEnabled(), net[0], net[1], manual, System.currentTimeMillis(), p.photosOkMs(),
                p.photosTryMs(), available(c).size());
    }

    /**
     * Starts an automatic refresh on a worker thread when one is due and none is running. Called when the add-on opens,
     * when the launcher asks for the photos, and by the scheduled job.
     */
    static void kick(Context c) {
        final Context app = c.getApplicationContext();
        if (RUNNING.get() || !due(app, false)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                refresh(app, false);
            }
        }, "cw-photos").start();
    }

    // ---- the refresh

    /**
     * Fetches new photos and swaps them for the oldest. {@code manual} (Refresh now) skips the schedule and the
     * unmetered rule but still needs a connection. Each photo is saved and announced as soon as it has arrived.
     */
    static void refresh(Context c, boolean manual) {
        Context app = c.getApplicationContext();
        Prefs prefs = App.prefs(app);
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            if (!prefs.photosEnabled()) return;
            boolean[] net = network(app);
            long now = System.currentTimeMillis();
            List<PhotoCache.Photo> record = reconcile(app);
            if (!PhotoPolicy.due(true, net[0], net[1], manual, now, prefs.photosOkMs(), prefs.photosTryMs(), record.size())) {
                if (manual && !net[0]) prefs.photosDone(0, "No network");
                return;
            }
            prefs.photosTried(now);
            try {
                PhotoRun.Result res = fetch(app, prefs, record);
                if (res.failure != null) Log.w(Contract.TAG, "photos refresh: " + res.failure.kind + " " + res.failure.code);
                // A refresh that brought at least one new photo is a good one; the note says what went wrong on the way.
                prefs.photosDone(res.added > 0 || res.failure == null ? System.currentTimeMillis() : 0, PhotoRun.note(res));
            } catch (RuntimeException e) {
                Log.w(Contract.TAG, "photos refresh crashed: " + e.getClass().getSimpleName());
                prefs.photosDone(0, "Something went wrong, try again later");
            }
        } finally {
            RUNNING.set(false);
        }
    }

    /** Makes the record match the folder: the files nobody lists and the records without a file are dropped. */
    private static List<PhotoCache.Photo> reconcile(Context app) {
        Prefs prefs = App.prefs(app);
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
        return keep;
    }

    private static PhotoRun.Result fetch(final Context app, final Prefs prefs, List<PhotoCache.Photo> record) {
        final List<PhotoCache.Photo> kept = new ArrayList<>(record);
        Set<String> have = new HashSet<>();
        for (PhotoCache.Photo p : record) have.add(p.name);
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
        };
        PhotoRun.Saver saver = new PhotoRun.Saver() {
            @Override public PhotoCache.Photo store(PhotoFilter.Candidate cand, String name, byte[] body) throws IOException {
                PhotoCache.Photo p = save(app, cand, name, body);
                if (p != null) {
                    List<PhotoCache.Photo> next = add(app, prefs, kept, p);
                    kept.clear();
                    kept.addAll(next);
                }
                return p;
            }
        };
        return new PhotoRun(net, env, saver).run(PhotoPolicy.enabled(prefs.photosOff()), PhotoCache.wanted(record.size()),
                have, new Random());
    }

    /** Adds the photo to the record, drops what no longer fits (the oldest first), saves and tells the launcher. */
    private static List<PhotoCache.Photo> add(Context app, Prefs prefs, List<PhotoCache.Photo> record, PhotoCache.Photo p) {
        List<PhotoCache.Photo> next = new ArrayList<>(record);
        next.add(p);
        List<String> gone = PhotoCache.evict(next, PhotoCache.MAX_PHOTOS, PhotoCache.MAX_BYTES);
        List<PhotoCache.Photo> kept = new ArrayList<>();
        for (PhotoCache.Photo q : next) {
            if (gone.contains(q.name)) new File(dir(app), q.name).delete();
            else kept.add(q);
        }
        prefs.setPhotosRecord(PhotoCache.encode(kept));
        app.getContentResolver().notifyChange(Contract.PHOTOS_URI, null);
        return kept;
    }

    private static Map<String, String> headers(String accept) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("Accept", accept);
        return h;
    }

    /** Keeps one downloaded picture in the cache folder; null when it is not a JPEG, too narrow or not the right shape. */
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
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            return null;
        }
        return new PhotoCache.Photo(name, cand.title, bounds.outWidth, bounds.outHeight, cand.credit,
                System.currentTimeMillis(), body.length);
    }
}
