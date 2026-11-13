package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The Top Shelf's wallpaper: one picture at a time, crossfading to the next after the interval chosen in Launcher
 * settings > Top Shelf > Change every (read at every change, so a new choice applies at once; 15 s by default).
 * The pool is the add-on's photos when it has any (listed off the main thread, and listed again at every change since
 * the add-on swaps one photo per change; a list of fewer than two photos is looked at again after about 20 s), else
 * the launcher's wallpapers; the artwork of shows is never in it. Each change tells the add-on ("photos_advance": which
 * photo is on screen) so it fetches one new photo in the background and drops the one shown longest ago; at most one
 * such call is in flight. Wallpapers are decoded no larger than 960x540 (cover),
 * photos no larger than the screen (1920x1080 at most), off the main thread, and at most two bitmaps are held: the
 * one on screen and the one fading in. The credit of the photo on screen is passed to the shelf. Nothing runs while it is stopped (Home hidden) or told not to
 * rotate (a real backdrop covers it), and the bitmaps are let go on stop.
 */
final class ShelfWallpaper {

    private static final int W = TopShelfLogic.MAX_W, H = TopShelfLogic.MAX_H;

    private final ContentResolver resolver;
    /**
     * False when something else is the background (a Horizon scene): no views, no photo listing, no advancing the
     * add-on's photos, nothing runs.
     */
    private final boolean enabled;
    private final List<String> files = new ArrayList<>();
    private final boolean animations, reducedMotion;
    private final ImageView back, front;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Set<String> bad = new HashSet<>();

    private Bitmap backBitmap, frontBitmap;
    /** Photo address to its credit line; replaced as a whole by the worker, read on the main thread. */
    private volatile java.util.Map<String, String> credits = Collections.emptyMap();
    private List<String> photoUris = Collections.emptyList();
    private long photosAt = -TopShelfLogic.PHOTOS_RECHECK_MS;
    /** How many photos the last list had (worker thread only; see photosShort). */
    private int photoCount;
    private final int screenW, screenH;
    private final Listener listener;
    private final LongSupplier interval;
    private String shownKey;
    private boolean running, rotating = true, loading, closed;
    /** Bumped on every stop, so a decode that finishes after it drops its picture. */
    private int generation;
    /** At most one "advance" call to the add-on is in flight; false after the add-on said it will not do photos. */
    private final AtomicBoolean advancing = new AtomicBoolean(false);
    private volatile boolean advanceOff;
    private final ExecutorService advancer = Executors.newSingleThreadExecutor();

    private final Runnable tick = this::next;
    private final Runnable recheck = this::recheckPhotos;

    /** Told on the main thread which credit line goes with the picture now showing ("" for none). */
    interface Listener {
        void onCredit(String credit);
    }

    /**
     * Adds the two picture views at the bottom of {@code container}. {@code wallpapers} may be empty.
     * {@code interval} answers how long a picture stays (ms); it is asked each time a change is scheduled.
     * {@code enabled} false makes the whole layer inert (see the field).
     */
    ShelfWallpaper(Context context, FrameLayout container, List<File> wallpapers, boolean animations,
                   boolean reducedMotion, LongSupplier interval, Listener listener, boolean enabled) {
        this.enabled = enabled;
        this.listener = listener;
        this.interval = interval;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.screenW = dm.widthPixels;
        this.screenH = dm.heightPixels;
        this.resolver = context.getContentResolver();
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        if (wallpapers != null) for (File f : wallpapers) files.add(f.getPath());
        back = layer(context);
        front = layer(context);
        front.setAlpha(0f);
        if (enabled) {
            container.addView(back, 0, new FrameLayout.LayoutParams(-1, -1));
            container.addView(front, 1, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static ImageView layer(Context context) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /**
     * Home is on screen: show a picture now, then keep rotating. Called again while running (a setting may have
     * changed meanwhile) it only starts the wait for the next change over, with the interval chosen now.
     */
    void start() {
        if (closed || !enabled) return;
        if (running) {
            rearm();
            return;
        }
        running = true;
        advanceOff = false;
        if (rotating) handler.post(tick);
    }

    /** The interval may have changed: the picture on screen stays and the next change is one new interval away. */
    void rearm() {
        if (closed || !running || !rotating || loading || shownKey == null) return;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, intervalMs());
    }

    private long intervalMs() {
        long ms = interval == null ? 0 : interval.getAsLong();
        return ms > 0 ? ms : SettingsModel.SHELF_INTERVAL_DEFAULT_MS;
    }

    /** Home is hidden: nothing runs and the pictures are released. */
    void stop() {
        running = false;
        generation++;
        loading = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(recheck);
        front.animate().cancel();
        back.animate().cancel();
        front.setAlpha(0f);
        back.setAlpha(1f);
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        retire(frontBitmap);
        retire(backBitmap);
        frontBitmap = backBitmap = null;
        shownKey = null;
        Frost.clearLayer(Frost.LAYER_SHELF);
        if (listener != null) listener.onCredit("");
    }

    /** False while a real backdrop covers the wallpaper: the timer rests; true picks it up again. */
    void setRotating(boolean on) {
        if (rotating == on) return;
        rotating = on;
        handler.removeCallbacks(tick);
        if (on && running && !loading) handler.postDelayed(tick, shownKey == null ? 0 : 1500);
    }

    void close() {
        closed = true;
        stop();
        worker.shutdownNow();
        advancer.shutdownNow();
    }

    // ---- one change of picture -------------------------------------------------------------------------------

    private void next() {
        if (closed || !running || !rotating || loading) return;
        loading = true;
        final int gen = generation;
        final String current = shownKey;
        worker.execute(() -> {
            List<String> pool = readPool();
            Bitmap bitmap = null;
            String key = null;
            for (int attempt = 0; attempt < 3 && bitmap == null && gen == generation; attempt++) {
                int index = TopShelfLogic.nextPick(pool.size(), pool.indexOf(current), random);
                if (index < 0) break;
                key = pool.get(index);
                bitmap = decode(key);
                if (bitmap == null) bad.add(key);
            }
            final Bitmap result = bitmap;
            final String picked = key;
            final int count = pool.size();
            final int photos = photoCount;
            handler.post(() -> arrived(gen, picked, result, count, photos));
        });
    }

    /**
     * The photos, else the wallpapers, minus any that would not decode. The photo list is read again at each change of
     * picture (the add-on swaps a photo per change), and after about 20 s while it has fewer than two photos.
     */
    private List<String> readPool() {
        readPhotos(false);
        List<String> pool = TopShelfLogic.pool(files, photoUris);
        pool.removeAll(bad);
        return pool;
    }

    /** Worker thread only. */
    private void readPhotos(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && !TopShelfLogic.photosStale(now, photosAt, photoCount)) return;
        List<TopShelfLogic.Photo> rows = TopShelfLogic.photos(ShelfFeed.photos(resolver));
        java.util.Map<String, String> map = new java.util.HashMap<>();
        List<String> uris = new ArrayList<>();
        for (TopShelfLogic.Photo p : rows) {
            uris.add(p.uri);
            map.put(p.uri, p.credit);
        }
        bad.removeIf(k -> TopShelfLogic.isPhotoUri(k) && !uris.contains(k));   // a photo the add-on dropped is forgotten
        photoUris = uris;
        credits = map;
        photoCount = uris.size();
        photosAt = now;
    }

    private void arrived(int gen, String key, Bitmap bitmap, int poolSize, int photos) {
        loading = false;
        if (gen != generation || closed || !running) {
            recycle(bitmap);
            return;
        }
        handler.removeCallbacks(recheck);
        if (TopShelfLogic.photosShort(photos) && !advanceOff) handler.postDelayed(recheck, TopShelfLogic.PHOTOS_RECHECK_MS);
        if (bitmap == null) {
            if (poolSize > 0 && rotating) handler.postDelayed(tick, Math.min(intervalMs(), RETRY_AFTER_FAILED_MS));
            return;
        }
        crossfade(bitmap);
        shownKey = key;
        if (listener != null) {
            String credit = key == null ? null : credits.get(key);
            listener.onCredit(credit == null ? "" : credit);
        }
        advance(key);
        // Without animations (a slow TV) the first picture stays; otherwise the next one is due after the interval.
        if (animations && poolSize > 1 && rotating) handler.postDelayed(tick, intervalMs());
    }

    /** A picture that would not decode is replaced sooner than a whole interval. */
    private static final long RETRY_AFTER_FAILED_MS = 15_000;

    /**
     * The picture on screen changed: tell the add-on, so it fetches one new photo in the background and drops the one
     * shown longest ago. One call at a time, off the main thread; nothing waits for the download.
     */
    private void advance(String key) {
        if (advanceOff || closed || !advancing.compareAndSet(false, true)) return;
        final String name = key == null ? null : TopShelfLogic.photoName(key);
        try {
            advancer.execute(() -> {
                try {
                    if (ShelfFeed.advance(resolver, name) == ShelfFeed.Advance.UNAVAILABLE) advanceOff = true;
                } finally {
                    advancing.set(false);
                }
            });
        } catch (RuntimeException e) {   // shut down
            advancing.set(false);
        }
    }

    /**
     * The list had fewer than two photos about 20 s ago: read it again. A first photo that arrived replaces a wallpaper
     * on screen; if there are still fewer than two, look again later.
     */
    private void recheckPhotos() {
        if (closed || !running || advanceOff) return;
        final int gen = generation;
        worker.execute(() -> {
            readPhotos(true);
            final int count = photoCount;
            handler.post(() -> {
                if (gen != generation || closed || !running) return;
                boolean showingPhoto = shownKey != null && TopShelfLogic.isPhotoUri(shownKey);
                if (count > 0 && !showingPhoto && rotating && !loading) {
                    handler.removeCallbacks(tick);
                    next();
                } else if (TopShelfLogic.photosShort(count)) {
                    handler.postDelayed(recheck, TopShelfLogic.PHOTOS_RECHECK_MS);
                }
            });
        });
    }

    /** The new picture fades in over the one on screen; then it becomes the one on screen and the old one goes. */
    private void crossfade(Bitmap bitmap) {
        // The glass frosts this photo, since it is what lies behind the dock and the pills while the shelf rests.
        Frost.buildLayer(Frost.LAYER_SHELF, bitmap, front.getContext(), worker,
                () -> !closed && running && (bitmap == backBitmap || bitmap == frontBitmap));
        int ms = SettingsModel.duration(animations, reducedMotion, TopShelfLogic.FADE_MS);
        front.animate().cancel();
        back.animate().cancel();
        if (backBitmap == null || ms == 0) {
            // Nothing under it yet, or no animation: it replaces the picture at once.
            Bitmap old = backBitmap;
            backBitmap = bitmap;
            back.setImageBitmap(bitmap);
            front.setAlpha(0f);
            front.setImageDrawable(null);
            retire(old);
            if (old == null && ms > 0) {
                back.setAlpha(0f);
                back.animate().alpha(1f).setDuration(ms).start();
            } else {
                back.setAlpha(1f);
            }
            return;
        }
        Bitmap old = frontBitmap;   // nothing is left over from an earlier fade: it ended before the next change
        frontBitmap = bitmap;
        front.setImageBitmap(bitmap);
        front.setAlpha(0f);
        retire(old);
        front.animate().alpha(1f).setDuration(ms).withEndAction(() -> {
            if (frontBitmap != bitmap) return;
            Bitmap shown = backBitmap;
            back.setImageBitmap(bitmap);
            backBitmap = bitmap;
            frontBitmap = null;
            front.setAlpha(0f);
            front.setImageDrawable(null);
            retire(shown);
        }).start();
    }

    /** A picture the views no longer show is recycled after the frame that stops drawing it. */
    private void retire(Bitmap old) {
        if (old != null) handler.postDelayed(() -> recycle(old), 400);
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ---- decoding ---------------------------------------------------------------------------------------------------

    /**
     * A wallpaper file (no larger than 960x540 to cover) or a photo (no larger than the screen, 1920x1080 at most);
     * null if it is unreadable or a photo is not landscape (aspect 1.5, 640 px wide).
     */
    private Bitmap decode(String key) {
        if (TopShelfLogic.isPhotoUri(key)) {
            int[] target = TopShelfLogic.photoTarget(screenW, screenH);
            return decode(() -> resolver.openInputStream(Uri.parse(key)), null, target[0], target[1]);
        }
        return decode(null, new File(key), W, H);
    }

    private interface Opener {
        InputStream open() throws IOException;
    }

    private static Bitmap decode(Opener opener, File file, int targetW, int targetH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (file != null) {
                BitmapFactory.decodeFile(file.getPath(), bounds);
            } else {
                try (InputStream in = opener.open()) {
                    if (in == null) return null;
                    BitmapFactory.decodeStream(in, null, bounds);
                }
            }
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0) return null;
            // A photo is a wallpaper only when it is a genuine landscape picture.
            if (opener != null && !TopShelfLogic.isBackdrop(w, h)) return null;
            int sample = WallpaperSize.sampleSize(w, h, targetW, targetH);
            if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(w / sample, h / sample, targetW, targetH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            // 32-bit: a gradient sky in RGB_565 bands, and two pictures of this size are only 4 MB.
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (file != null) return BitmapFactory.decodeFile(file.getPath(), options);
            try (InputStream in = opener.open()) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, options);
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
