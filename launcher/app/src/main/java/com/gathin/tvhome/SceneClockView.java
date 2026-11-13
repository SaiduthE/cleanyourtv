package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;

import java.io.File;
import java.time.LocalTime;

/**
 * scene_clock: the big frosted-glass clock set into the wallpaper (the lock-screen look). Four plain passes of the same
 * text, no blur and no network: the fill (the wallpaper's blurred {@code _glass} copy, or the wallpaper itself brightened,
 * seen through the digits by a BitmapShader laid over the screen), a sheen gradient, the rim (a thin white stroke over a
 * faint white fill) and a soft drop shadow. With wallpaper_depth the wallpaper's {@code _fg} cut-out is drawn last, over the
 * digits. The date and, when the Weather add-on is there, today's weather sit in one line above it.
 *
 * Never focusable and never animated: it redraws once a minute, and fades when the wallpaper changes.
 */
final class SceneClockView extends View {

    /** Antonio's baseline below the top of a 0.82 line box, in em: ((0.82 - (asc + desc)) / 2) + asc from its metrics. */
    private static final float BASELINE_EM = 0.9178f;
    private static final float TRACKING_EM = -0.02f;
    /** 2025-01-01: a TV with no clock battery shows the epoch until network time arrives, so nothing is drawn before. */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private static final long MARGIN_MS = 250L;
    /** The reference screen the layout's pixel constants (shadow, stroke, date size) were drawn for. */
    private static final float REF_HEIGHT = 1080f;
    private static final long FADE_OUT_MS = 250L, FADE_IN_MS = 450L;

    /** What one wallpaper contributes. Bitmaps are decoded off the UI thread by {@link #load}. */
    static final class Scene {
        final SceneClockLayout layout;
        final Bitmap glass, fg;
        /** The wallpaper itself, the fill when there is no glass copy; owned by WallpaperPlayer, never recycled here. */
        final Bitmap wallpaper;

        Scene(SceneClockLayout layout, Bitmap glass, Bitmap fg, Bitmap wallpaper) {
            this.layout = layout;
            this.glass = glass;
            this.fg = fg;
            this.wallpaper = wallpaper;
        }

        void recycle() {
            if (glass != null) glass.recycle();
            if (fg != null) fg.recycle();
        }
    }

    private final boolean is24, fade;
    private final Typeface clockFace, dateFace;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint dateInk = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF target = new RectF();
    private final WeatherSource weather;
    private final Runnable tick = this::minute;
    private Scene scene;
    private String time = "", date = "";
    private WeatherData reading;
    private boolean running;
    private int builtFor;
    private float textX, baseline, dateBaseline;

    SceneClockView(Context context, boolean clock24, boolean fade) {
        super(context);
        this.is24 = clock24;
        this.fade = fade;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        forceHasOverlappingRendering(false);
        setAlpha(0f);
        Typeface face = Typeface.DEFAULT;
        try {
            face = context.getResources().getFont(R.font.antonio_light);
        } catch (RuntimeException ignored) {
            // the system font is a fine stand-in
        }
        clockFace = face;
        dateFace = UiFont.get(context, R.string.font_medium);
        WeatherSource source = null;
        if (WeatherSource.enabled(context, WeatherSource.KEY_CHIP)) {
            source = new WeatherSource(context, data -> {
                reading = data;
                refreshText();
                invalidate();
            });
        }
        weather = source;
    }

    // ---- scenes --------------------------------------------------------------------------------------------------

    /**
     * Decodes a wallpaper's companions (off the UI thread): the glass copy and, with wallpaper_depth, the cut-out, both at
     * about half the screen's size (they are scaled up when drawn, and the glass is a blur anyway).
     */
    static Scene load(SceneClockSettings settings, File wallpaper, Bitmap wallpaperBitmap, int screenW, int screenH) {
        Bitmap glass = decode(SceneFiles.glass(wallpaper), screenW, screenH);
        Bitmap fg = settings.depth ? decode(SceneFiles.fg(wallpaper), screenW, screenH) : null;
        return new Scene(settings.layoutFor(wallpaper.getName()), glass, fg, glass == null ? wallpaperBitmap : null);
    }

    private static Bitmap decode(File file, int screenW, int screenH) {
        if (file == null) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int w = options.outWidth, h = options.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = WallpaperSize.sampleSize(w, h, Math.max(1, screenW / 2), Math.max(1, screenH / 2));
            if (!WallpaperSize.accepted(w / sample, h / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Shows a scene, fading the old one out and this one in; null removes the clock (the wallpaper went away). */
    void setScene(Scene next) {
        animate().cancel();
        Scene old = scene;
        if (!fade || old == null || next == null || getAlpha() == 0f) {
            apply(next, old);
            if (next != null) fadeIn();
            return;
        }
        animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            apply(next, old);
            fadeIn();
        }).start();
    }

    private void fadeIn() {
        if (fade) animate().alpha(1f).setDuration(FADE_IN_MS).start();
        else setAlpha(1f);
    }

    private void apply(Scene next, Scene old) {
        scene = next;
        builtFor = 0;
        if (old != null && old != next) old.recycle();
        if (next == null) setAlpha(0f);
        refreshText();
        invalidate();
    }

    /** Lets go of the bitmaps (Home is hidden and memory is short). */
    void release() {
        animate().cancel();
        setAlpha(0f);
        Scene old = scene;
        scene = null;
        builtFor = 0;
        if (old != null) old.recycle();
    }

    // ---- the minute ----------------------------------------------------------------------------------------------

    /** Home is on screen: show the time now and redraw at every minute change. */
    void start() {
        running = true;
        if (weather != null && WeatherSource.available(getContext())) weather.resume();
        minute();
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
        if (weather != null) weather.pause();
    }

    /** The wall clock was set: line the redraw up with the new minute. */
    void timeChanged() {
        if (running) minute();
    }

    private void minute() {
        removeCallbacks(tick);
        refreshText();
        invalidate();
        if (running) postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + MARGIN_MS);
    }

    private void refreshText() {
        long now = System.currentTimeMillis();
        if (now < TIME_VALID_AFTER_MS) {
            time = "";
            date = "";
            return;
        }
        LocalTime t = LocalTime.now();
        int hour = is24 ? t.getHour() : (t.getHour() % 12 == 0 ? 12 : t.getHour() % 12);
        // Plain ASCII digits whatever the locale: the bundled face holds only 0-9 and the colon.
        time = hour + ":" + (t.getMinute() < 10 ? "0" : "") + t.getMinute();
        date = DateFormat.format(getContext().getString(R.string.date_format_home), now).toString();
        builtFor = 0;
    }

    private String weatherText() {
        WeatherData d = reading;
        if (d == null || d.freshness(System.currentTimeMillis()) == WeatherData.Freshness.ABSENT) return "";
        return "   ·   " + d.displayTemp(d.tempC) + "°  "
                + WeatherWidget.label(getContext(), WeatherData.condition(d.code));
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        builtFor = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Scene s = scene;
        int w = getWidth(), h = getHeight();
        if (s == null || time.isEmpty() || w == 0 || h == 0) return;
        if (builtFor != w * 31 + h) build(s, w, h);
        canvas.drawText(time, textX, baseline, fill);
        if (!s.layout.dark) canvas.drawText(time, textX, baseline, sheen);
        canvas.drawText(time, textX, baseline, rimFill);
        canvas.drawText(time, textX, baseline, rimStroke);
        String line = date + weatherText();
        float room = w - 2 * s.layout.inset * w;
        if (dateInk.measureText(line) > room) line = date;
        line = TextUtils.ellipsize(line, dateInk, room, TextUtils.TruncateAt.END).toString();
        canvas.drawText(line, textX, dateBaseline, dateInk);
        if (s.fg != null) {
            float cover = Math.max((float) w / s.fg.getWidth(), (float) h / s.fg.getHeight());
            float fw = s.fg.getWidth() * cover, fh = s.fg.getHeight() * cover;
            target.set((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f);
            canvas.drawBitmap(s.fg, null, target, picture);
        }
    }

    /** Paints, shaders and positions for this scene at this size; redone when the scene, the size or the time text changes. */
    private void build(Scene s, int w, int h) {
        builtFor = w * 31 + h;
        SceneClockLayout l = s.layout;
        float scale = h / REF_HEIGHT;
        float size = l.size * h;
        float top = l.y * h;
        baseline = top + BASELINE_EM * size;
        Paint.Align align = l.align == SceneClockLayout.LEFT ? Paint.Align.LEFT
                : l.align == SceneClockLayout.RIGHT ? Paint.Align.RIGHT : Paint.Align.CENTER;
        textX = l.align == SceneClockLayout.LEFT ? l.inset * w
                : l.align == SceneClockLayout.RIGHT ? w - l.inset * w : w / 2f;
        for (Paint p : new Paint[] {fill, sheen, rimFill, rimStroke}) {
            p.setTypeface(clockFace);
            p.setTextSize(size);
            p.setLetterSpacing(TRACKING_EM);
            p.setTextAlign(align);
        }
        Bitmap source = s.glass != null ? s.glass : s.wallpaper;
        fill.setShader(null);
        fill.setColorFilter(null);
        fill.setColor(0xE6FFFFFF);
        fill.clearShadowLayer();
        if (source != null) {
            // The fill picture is laid over the screen like the wallpaper (centre crop), so each digit shows the bit of
            // the scene behind it. The glass copy is at about half size, hence the scale.
            BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float cover = Math.max((float) w / source.getWidth(), (float) h / source.getHeight());
            matrix.setScale(cover, cover);
            matrix.postTranslate((w - source.getWidth() * cover) / 2f, (h - source.getHeight() * cover) / 2f);
            shader.setLocalMatrix(matrix);
            fill.setShader(shader);
            fill.setAlpha(255);
        }
        ColorMatrix tone = new ColorMatrix();
        if (l.dark) {
            // A dark smoky glass: toned down and a little desaturated, no shadow.
            tone.setSaturation(0.8f);
            tone.postConcat(scaleColors(0.46f));
            fill.setAlpha(204);
        } else {
            // Without a glass copy the wallpaper itself is brightened to read as frosted.
            if (s.glass == null) tone.postConcat(scaleColors(1.3f));
            fill.setShadowLayer(24f * scale, 0f, 6f * scale, 0x29000000);
        }
        fill.setColorFilter(new ColorMatrixColorFilter(tone));
        int rim = l.rimColor != 0 ? l.rimColor : 0x00FFFFFF;
        rimFill.setStyle(Paint.Style.FILL);
        rimFill.setColor(l.dark ? 0 : withAlpha(rim, 0.10f));
        rimStroke.setStyle(Paint.Style.STROKE);
        rimStroke.setStrokeWidth(Math.max(1f, 1.44f * scale));
        rimStroke.setColor(withAlpha(rim, l.dark ? 0.60f : 0.55f));
        sheen.setStyle(Paint.Style.FILL);
        if (!l.dark) {
            // The sheen's box: the whole width when centred (as on the lock screen), else the text's own.
            float textW = fill.measureText(time);
            float boxL = l.align == SceneClockLayout.CENTRE ? 0 : l.align == SceneClockLayout.LEFT ? textX : textX - textW;
            float boxW = l.align == SceneClockLayout.CENTRE ? w : textW;
            sheen.setShader(sheen(boxL, top, boxW, 0.82f * size));
        }
        dateInk.setTypeface(dateFace);
        dateInk.setTextSize(34f * scale);
        dateInk.setTextAlign(align);
        dateInk.setColor(l.dateColor != 0 ? l.dateColor : l.dark ? 0xFF16201D : 0xEBFFFFFF);
        if (l.dark) dateInk.clearShadowLayer();
        else dateInk.setShadowLayer(9f * scale, 0f, 1f * scale, 0x40000000);
        Paint.FontMetrics fm = dateInk.getFontMetrics();
        dateBaseline = l.dateY * h + 20f * scale - (fm.ascent + fm.descent) / 2f;
    }

    private static ColorMatrix scaleColors(float factor) {
        ColorMatrix m = new ColorMatrix();
        m.setScale(factor, factor, factor, 1f);
        return m;
    }

    private static int withAlpha(int rgb, float alpha) {
        return (Math.round(alpha * 255f) << 24) | (rgb & 0x00FFFFFF);
    }

    /** The lock screen's 160 degree sheen across the box: white 55% at the start, clear from 38% to 70%, white 22% at the end. */
    private static Shader sheen(float left, float top, float width, float height) {
        double angle = Math.toRadians(160);
        float dx = (float) Math.sin(angle), dy = (float) -Math.cos(angle);
        float half = (Math.abs(width * dx) + Math.abs(height * dy)) / 2f;
        float cx = left + width / 2f, cy = top + height / 2f;
        return new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                new int[] {0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x38FFFFFF},
                new float[] {0f, 0.38f, 0.70f, 1f}, Shader.TileMode.CLAMP);
    }
}
