package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The weather chip (design-spec.md 3.1 to 3.3): art, temperature, then the condition over the high, low and (when the
 * owner allows it) the place. Borderless at rest; when focused it becomes the white pill the round buttons use, ringed
 * in the panel accent. OK opens the add-on's settings. It docks in whichever slot the Widgets manager gives it, by default beside
 * the clock. Hidden (and the layout left exactly as it was) when the add-on is missing or disabled, has no city,
 * cannot be read, has data older than six hours, or the theme turned weather_widget off.
 *
 * The data comes from {@link WeatherSource}; nothing here touches the network.
 */
final class WeatherWidget implements HomeWidget {

    private final Context ctx;
    private final WidgetContext wc;
    private final WidgetMount mount;
    private final Chip chip;
    private final WeatherSource source;
    private final boolean animations, reducedMotion;
    private boolean addOn;
    private boolean shown;
    private WeatherData data;

    private WeatherWidget(WidgetContext wc) {
        this.wc = wc;
        this.ctx = wc.activity;
        this.mount = wc.mount;
        this.animations = wc.animations;
        this.reducedMotion = wc.reducedMotion;
        chip = new Chip(wc.activity);
        chip.setVisibility(View.GONE);
        // The clock row's chip is 52 dp and tucks in under the divider; in the top bar it matches the 42 dp pills.
        boolean inClockRow = wc.slot.inClockRow();
        mount.add(chip, inClockRow ? 52 : 42, -10);
        chip.setOnClickListener(v -> openSettings());
        source = new WeatherSource(wc.activity, this::onWeather);
    }

    /** The chip for the slot, or null when the theme turned the weather off (weather_widget false). */
    static HomeWidget create(WidgetContext wc) {
        try {
            if (!WeatherSource.enabled(wc.activity, WeatherSource.KEY_CHIP)) return null;
            return new WeatherWidget(wc);
        } catch (RuntimeException e) {
            Log.w(WeatherSource.TAG, "weather chip not created", e);
            return null;
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    /** Home is in front: check the add-on, read, and watch for changes. */
    @Override
    public void resume() {
        addOn = WeatherSource.available(ctx);
        if (!addOn) {
            hide();
            return;
        }
        apply();   // the old reading may have gone stale while Home was away
        source.resume();
    }

    @Override
    public void pause() {
        source.pause();
    }

    @Override
    public boolean focus() {
        return shown && chip.requestFocus();
    }

    @Override
    public View shownView() {
        return shown ? chip : null;
    }

    @Override
    public void refresh() {
        apply();
    }

    @Override
    public void detach() {
        source.pause();
        shown = false;
        mount.remove();
    }

    // ---- data ----------------------------------------------------------------------------------------------------

    private void onWeather(WeatherData reading) {
        data = reading;
        apply();
    }

    private void apply() {
        WeatherData d = data;
        if (!addOn || d == null || d.freshness(System.currentTimeMillis()) == WeatherData.Freshness.ABSENT) {
            hide();
            return;
        }
        chip.bind(unitApplied(d), System.currentTimeMillis(), wc.isOn(WidgetRegistry.WEATHER_PLACE));
        show();
    }

    /** The owner's unit for the chip: the add-on's own unless the Widgets settings say C or F. */
    private WeatherData unitApplied(WeatherData d) {
        String unit = wc.option(WidgetRegistry.WEATHER_UNIT);
        return d.withUnit("f".equals(unit) ? Boolean.TRUE : "c".equals(unit) ? Boolean.FALSE : null);
    }

    private void show() {
        if (shown) return;
        shown = true;
        mount.setShown(true);
        chip.setVisibility(View.VISIBLE);
    }

    private void hide() {
        if (!shown) return;
        shown = false;
        if (chip.hasFocus()) wc.host.nav().focusFirstButton();
        chip.setVisibility(View.GONE);
        mount.setShown(false);
    }

    private void openSettings() {
        try {
            ctx.startActivity(WeatherSource.settingsIntent(ctx));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(WeatherSource.TAG, "weather settings did not open: " + e.getClass().getSimpleName());
        }
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ---- text shared with the screensaver ------------------------------------------------------------------------

    /** The condition's name, "Clear" at night as by day. */
    static String label(Context ctx, WeatherData.Condition condition) {
        int id;
        switch (condition) {
            case CLEAR: id = R.string.weather_clear; break;
            case PARTLY: id = R.string.weather_partly; break;
            case FOG: id = R.string.weather_fog; break;
            case DRIZZLE: id = R.string.weather_drizzle; break;
            case RAIN: id = R.string.weather_rain; break;
            case SNOW: id = R.string.weather_snow; break;
            case SLEET: id = R.string.weather_sleet; break;
            case THUNDER: id = R.string.weather_thunder; break;
            default: id = R.string.weather_cloudy; break;
        }
        return ctx.getString(id);
    }

    /** "↑21°  ↓12°" and, when the owner allows the place, "   Riverside". */
    static String highLow(WeatherData d, boolean withPlace) {
        StringBuilder s = new StringBuilder();
        s.append('↑').append(d.displayTemp(d.highC)).append("°  ↓").append(d.displayTemp(d.lowC)).append('°');
        if (withPlace && !d.place.isEmpty()) s.append("   ").append(d.place);
        return s.toString();
    }

    /** Whether the art may move: the owner's animations, the add-on's and the launcher's, and a fresh reading. */
    static boolean artMoves(Context ctx, WeatherData d, long now, boolean animations, boolean reducedMotion) {
        return animations && !reducedMotion && d.animated && WeatherSource.animatedAllowed(ctx)
                && d.freshness(now) == WeatherData.Freshness.FRESH;
    }

    // ---- the chip ------------------------------------------------------------------------------------------------

    private final class Chip extends LinearLayout {
        private final WeatherArtView art;
        private final TextView temp, line1, line2;
        private final int accent;

        Chip(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(8), 0, dp(18), 0);
            setFocusable(true);
            setClickable(true);
            setFocusableInTouchMode(false);
            accent = PanelTheme.get(c).accent;
            setBackground(background());
            setClipChildren(false);

            art = new WeatherArtView(c);
            addView(art, new LayoutParams(dp(38), dp(38)));

            temp = text(c, R.string.font_light, 26, 0xFFFFFFFF);
            temp.setLetterSpacing(-0.01f);
            temp.setIncludeFontPadding(false);
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.setMarginStart(dp(8));
            tlp.setMarginEnd(dp(2));
            addView(temp, tlp);

            LinearLayout column = new LinearLayout(c);
            column.setOrientation(VERTICAL);
            line1 = text(c, R.string.font_medium, 13, 0xDBFFFFFF);
            line2 = text(c, R.string.font_regular, 12, 0x8CFFFFFF);
            line2.setMaxWidth(dp(180));
            column.addView(line1);
            column.addView(line2);
            LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(dp(8));
            addView(column, clp);
        }

        private TextView text(Context c, int font, float sp, int color) {
            TextView t = new TextView(c);
            t.setTypeface(UiFont.get(c, font));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            return t;
        }

        private Drawable background() {
            GradientDrawable focused = pill(0xF5FFFFFF);
            focused.setStroke(dp(2), accent);
            GradientDrawable pressed = pill(0xFFE6E9EE);
            pressed.setStroke(dp(2), accent);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, pressed);
            states.addState(new int[]{android.R.attr.state_focused}, focused);
            states.addState(new int[]{}, pill(Color.TRANSPARENT));
            return states;
        }

        private GradientDrawable pill(int color) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(dp(26));
            return d;
        }

        void bind(WeatherData d, long now, boolean withPlace) {
            boolean stale = d.freshness(now) == WeatherData.Freshness.STALE;
            WeatherData.Condition condition = d.condition();
            art.setWeather(condition, d.day);
            art.setArtAlpha(stale ? 0.5f : 1f);
            art.setAnimationAllowed(artMoves(ctx, d, now, animations, reducedMotion));
            String degrees = d.displayTemp(d.tempC) + "°";
            temp.setText(degrees);
            String name = label(ctx, condition);
            line1.setText(name);
            line2.setText(stale ? ctx.getString(R.string.weather_updated_ago, d.ageHours(now)) : highLow(d, withPlace));
            setContentDescription(ctx.getString(R.string.weather_chip_description,
                    degrees + (d.fahrenheit ? " Fahrenheit" : " Celsius"), name));
        }

        @Override
        protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
            art.setPalette(gainFocus ? WeatherArtView.PALETTE_LIGHT : WeatherArtView.PALETTE_DARK);
            temp.setTextColor(gainFocus ? 0xFF0B0D12 : 0xFFFFFFFF);
            line1.setTextColor(gainFocus ? 0xDB000000 : 0xDBFFFFFF);
            line2.setTextColor(gainFocus ? 0x8C000000 : 0x8CFFFFFF);
            setElevation(gainFocus ? dp(6) : 0f);
            float s = gainFocus ? 1.05f : 1f;
            if (!animations) {
                setScaleX(s);
                setScaleY(s);
            } else {
                animate().scaleX(s).scaleY(s).setDuration(reducedMotion ? Motion.REDUCED_MS : 150).start();
            }
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;   // nothing above it; the focus finder must not jump to a far tile
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    wc.host.nav().focusGrid();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    wc.host.neighbour(wc.slot, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    wc.host.neighbour(wc.slot, 1);
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
    }
}
