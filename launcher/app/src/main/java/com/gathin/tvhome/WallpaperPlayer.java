package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    private final ImageView image;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** glass_frost: the frost of the picture decode() just made, published by show() with the picture. Null without frost. */
    private volatile Bitmap pendingFrost;
    /** The frost this player published with its picture, so close() frees it only if it is still the one in use. */
    private Bitmap publishedFrost;
    /** Told when a picture first appears (or none could be shown), so the header can step aside for the scene clock or return. */
    private Runnable sceneListener;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;
    /** scene_clock: the glass clock (and, with wallpaper_depth, the cut-out) over the picture; null when the switch is off. */
    private final SceneClockView sceneClock;
    private final SceneClockSettings sceneSettings;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        sceneSettings = config.sceneClock;
        // Frost is process-wide: a previous wallpaper's frost must not outlive it. A picture publishes its own in show(); with
        // none (wallpaper off, or every file unreadable) there is nothing to frost but the sky, which builds its own after this.
        Frost.release();
        boolean scene = config.wallpaperEnabled && !files.isEmpty() && sceneSettings.enabled;
        sceneClock = scene ? new SceneClockView(context, ClockFormat.is24(context, config.clockFormat),
                config.animations && !config.reducedMotion) : null;
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(image, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (sceneClock != null) {
                // Back to front: the scene, this clock (with its cut-out and date line), the Top Shelf (its text, Resume
                // button and Up next cards, and a show's backdrop), then the header, the glow and the dock. The shelf
                // draws no resting picture over a scene, so it never hides the clock.
                View shelf = root.findViewById(R.id.topShelf);
                root.addView(sceneClock, shelf == null ? 1 : root.indexOfChild(shelf),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
        }
    }

    /** Whether a scene clock exists (scene_clock on with wallpapers): the scene is Home's background, whatever is on screen now. */
    boolean hasSceneClock() {
        return sceneClock != null;
    }

    /**
     * True when Home draws the clock itself, so the top bar's clock, greeting and date step aside: the scene clock exists
     * and a picture is on screen for it (when every file fails to decode there is no scene, and the header keeps its clock).
     */
    boolean sceneClockShown() {
        return sceneClock != null && current != null;
    }

    /** A show's backdrop covers the screen: the scene clock fades out (true) and comes back with the scene (false). */
    void setSceneSuppressed(boolean on) {
        if (sceneClock != null) sceneClock.setSuppressed(on);
    }

    /** {@code listener} runs (on the main thread) whenever a picture is shown or none could be, so the header can follow. */
    void setSceneListener(Runnable listener) {
        sceneListener = listener;
    }

    private void notifyScene() {
        if (sceneListener != null) sceneListener.run();
    }

    /** The wall clock was set (ACTION_TIME_CHANGED). */
    void timeChanged() {
        if (sceneClock != null) sceneClock.timeChanged();
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                showScene(first, bitmap);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
        // No playable file: no picture and no scene clock, so the header's own clock and date stay (or come back).
        notifyScene();
    }

    void start() {
        active = true;
        if (sceneClock != null) sceneClock.start();
        update();
    }

    void stop() {
        active = false;
        if (sceneClock != null) sceneClock.stop();
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        image.setImageDrawable(null);
        if (sceneClock != null) sceneClock.release();
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        if (sceneClock != null) sceneClock.release();
        decoder.shutdownNow();
        // Free this player's frost, unless a newer owner (the next Home, the sky) has already replaced it.
        Frost.releaseIf(publishedFrost);
        publishedFrost = null;
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            SceneClockView.Scene scene = bitmap == null ? null : loadScene(next, bitmap);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    if (scene != null) scene.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    if (!loading) notifyScene();   // nothing left to try: the header may need its clock back
                    return;
                }
                show(bitmap, true);
                if (sceneClock != null) sceneClock.setScene(scene);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** The scene clock's companions for a picture, decoded off the UI thread; null when the clock is off. */
    private SceneClockView.Scene loadScene(File wallpaper, Bitmap bitmap) {
        return sceneClock == null ? null : SceneClockView.load(sceneSettings, wallpaper, bitmap, width, height);
    }

    /** The first picture is on screen already; its clock decodes in the background and fades in. */
    private void showScene(File wallpaper, Bitmap bitmap) {
        if (sceneClock == null) return;
        decoder.execute(() -> {
            SceneClockView.Scene scene = loadScene(wallpaper, bitmap);
            root.post(() -> {
                if (closed || current != bitmap) {
                    if (scene != null) scene.recycle();
                    return;
                }
                sceneClock.setScene(scene);
            });
        });
    }

    /**
     * Puts a picture on screen and releases the previous one after a frame has drawn the new one. With the scene clock on,
     * a change crossfades, in step with the clock fading out and in.
     */
    private void show(Bitmap bitmap, boolean crossfade) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        long keep = 500;
        if (sceneClock != null && crossfade && old != null && old != bitmap) {
            Resources res = root.getResources();
            TransitionDrawable fade = new TransitionDrawable(new Drawable[] {
                    new BitmapDrawable(res, old), new BitmapDrawable(res, bitmap)});
            fade.setCrossFadeEnabled(true);
            image.setImageDrawable(fade);
            fade.startTransition(700);
            // Back to the plain bitmap once the fade is over, so no drawable keeps the old picture alive.
            image.postDelayed(() -> {
                if (current == bitmap) image.setImageBitmap(bitmap);
            }, 750);
            keep = 1000;
        } else {
            image.setImageBitmap(bitmap);
        }
        // The frost behind the glass follows the picture: the new one replaces the old, or none when glass_frost is 0.
        Bitmap frost = pendingFrost;
        pendingFrost = null;
        if (frost != null) Frost.set(frost, width, height);
        else Frost.clearLayer(Frost.LAYER_WALL);
        publishedFrost = frost;
        notifyScene();
        if (old != null && old != bitmap) image.postDelayed(old::recycle, keep);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int sourceW = options.outWidth, sourceH = options.outHeight;   // the file's size, not the screen's
            if (sourceW <= 0 || sourceH <= 0) return null;
            int sample = WallpaperSize.sampleSize(sourceW, sourceH, width, height);
            if (!WallpaperSize.accepted(sourceW / sample, sourceH / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(sourceW / sample, sourceH / sample, width, height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            // The picture's frost (glass_frost) is made from the readable copy here, before it becomes a GPU texture.
            pendingFrost = GlassLook.frost > 0 ? Frost.build(decoded, width, height, GlassLook.frost, 0, 1f, true) : null;
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
