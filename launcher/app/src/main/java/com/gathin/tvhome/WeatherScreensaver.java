package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Weather on the clock screensaver (design-spec.md section 4): the condition art, the temperature, and the condition
 * over the high and low, in a row under the date, so it drifts with the clock as one block. Dim and desaturated for
 * a projector, art redrawn at 15 fps, no focus and no click. Read when the screensaver starts and every 15 minutes
 * (plus the add-on's change notifications while it is up). Nothing shows without the add-on, a city, or recent data.
 *
 * The spec's hourly strip is left out: the add-on's contract publishes a daily forecast and no hours.
 */
final class WeatherScreensaver {

    private static final long TURN_MS = 15L * 60_000L;
    private static final float NIGHT_ALPHA = 0.62f;

    private final Context ctx;
    private final LinearLayout content;
    private final Runnable resized;
    private final LinearLayout row;
    private final WeatherArtView art;
    private final TextView temp, condition, highLow;
    private final WeatherSource source;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean animations, reducedMotion;
    private boolean started;

    private final Runnable turn = new Runnable() {
        @Override
        public void run() {
            source.refresh();
            handler.postDelayed(this, TURN_MS);
        }
    };

    /** Null when the screensaver's weather is off or there is no add-on; content is the drifting block. */
    static WeatherScreensaver create(LinearLayout content, Runnable resized) {
        Context ctx = content.getContext();
        try {
            if (!WeatherSource.enabled(ctx, WeatherSource.KEY_SCREENSAVER) || !WeatherSource.available(ctx)) return null;
            Config cfg = Config.load(ctx);
            return new WeatherScreensaver(ctx, content, resized, cfg.animations, cfg.reducedMotion);
        } catch (RuntimeException e) {
            Log.w(WeatherSource.TAG, "screensaver weather not created", e);
            return null;
        }
    }

    private WeatherScreensaver(Context ctx, LinearLayout content, Runnable resized, boolean animations,
                               boolean reducedMotion) {
        this.ctx = ctx;
        this.content = content;
        this.resized = resized;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setVisibility(View.GONE);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        art = new WeatherArtView(ctx);
        art.setPalette(WeatherArtView.PALETTE_SCREENSAVER);
        art.setArtAlpha(0.62f);
        art.setFrameIntervalMs(66);
        art.setRequireWindowFocus(false);
        row.addView(art, new LinearLayout.LayoutParams(dp(92), dp(92)));

        temp = text(R.string.font_extralight, 52, 0x8FFFFFFF);
        temp.setIncludeFontPadding(false);
        temp.setLetterSpacing(-0.01f);
        LinearLayout.LayoutParams tlp = wrap();
        tlp.setMarginStart(dp(14));
        row.addView(temp, tlp);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        condition = text(R.string.font_regular, 18, 0x70FFFFFF);
        highLow = text(R.string.font_regular, 15, 0x52FFFFFF);
        highLow.setMaxWidth(dp(320));
        column.addView(condition);
        column.addView(highLow);
        LinearLayout.LayoutParams clp = wrap();
        clp.setMarginStart(dp(22));
        row.addView(column, clp);

        LinearLayout.LayoutParams rlp = wrap();
        rlp.topMargin = dp(34);
        content.addView(row, rlp);
        source = new WeatherSource(ctx, this::onWeather);
    }

    void start() {
        if (started) return;
        started = true;
        source.resume();
        handler.removeCallbacks(turn);
        handler.postDelayed(turn, TURN_MS);
    }

    void stop() {
        started = false;
        handler.removeCallbacks(turn);
        source.pause();
    }

    private void onWeather(WeatherData d) {
        long now = System.currentTimeMillis();
        boolean show = d != null && d.freshness(now) != WeatherData.Freshness.ABSENT;
        if (show) bind(d, now);
        if (show != (row.getVisibility() == View.VISIBLE)) {
            row.setVisibility(show ? View.VISIBLE : View.GONE);
            // The block grew or shrank: put it back where it fits once it has been measured.
            content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    content.removeOnLayoutChangeListener(this);
                    resized.run();
                }
            });
        }
    }

    private void bind(WeatherData d, long now) {
        WeatherData.Condition c = d.condition();
        art.setWeather(c, d.day);
        art.setAnimationAllowed(WeatherWidget.artMoves(ctx, d, now, animations, reducedMotion));
        temp.setText(d.displayTemp(d.tempC) + "°");
        condition.setText(WeatherWidget.label(ctx, c));
        highLow.setText(d.freshness(now) == WeatherData.Freshness.STALE
                ? ctx.getString(R.string.weather_updated_ago, d.ageHours(now))
                : WeatherWidget.highLow(d, true));
        // Night, 22:00 to 07:00: dimmer still.
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        row.setAlpha(hour >= 22 || hour < 7 ? NIGHT_ALPHA : 1f);
    }

    private TextView text(int font, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(UiFont.get(ctx, font));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
