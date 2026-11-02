package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
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
 * The weather chip in Home's clock row (design-spec.md 3.1 to 3.3): art, temperature, then the condition over
 * the high, low and (when the owner allows it) the place. Borderless at rest; when focused it becomes the white pill
 * the round buttons use, ringed in the panel accent. OK opens the add-on's settings. Hidden (and the layout left
 * exactly as it was) when the add-on is missing or disabled, has no city, cannot be read, has data older than six
 * hours, or the weather_widget choice is off.
 *
 * The data comes from {@link WeatherSource}; nothing here touches the network.
 */
final class WeatherWidget {

    /** What the chip asks of Home for the keys it handles. */
    interface Host {
        /** Down: back to the dock tile Up came from. */
        void focusGrid();

        /** Right: the first top-bar button. */
        void focusButtons();
    }

    private final Context ctx;
    private final Host host;
    private final LinearLayout row;
    private final View dateColumn;
    private final TextView dateText;
    private final View divider;
    private final Chip chip;
    private final WeatherSource source;
    private final boolean animations, reducedMotion;
    private boolean addOn;
    private boolean shown;
    private WeatherData data;

    private WeatherWidget(Activity activity, LinearLayout row, View dateColumn, boolean animations,
                          boolean reducedMotion, Host host) {
        this.ctx = activity;
        this.host = host;
        this.row = row;
        this.dateColumn = dateColumn;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        dateText = activity.findViewById(R.id.date);
        // A second line like the clock's own, in the same colour (white when header_white recoloured it).
        View first = activity.findViewById(R.id.clockDivider);
        divider = new View(activity);
        Drawable bg = first.getBackground();
        divider.setBackgroundColor(bg instanceof ColorDrawable ? ((ColorDrawable) bg).getColor() : 0x33FFFFFF);
        divider.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) first.getLayoutParams());
        int at = row.indexOfChild(dateColumn) + 1;
        row.addView(divider, at, dlp);
        chip = new Chip(activity);
        chip.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
        clp.gravity = Gravity.CENTER_VERTICAL;
        clp.setMarginStart(-dp(10));
        row.addView(chip, at + 1, clp);
        // A focused chip grows and casts a shadow past the row's edges.
        row.setClipChildren(false);
        if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).setClipChildren(false);
        chip.setOnClickListener(v -> openSettings());
        source = new WeatherSource(activity, this::onWeather);
    }

    /**
     * The chip in Home's clock row, or null when it cannot sit there: weather_widget is off, the header is the
     * mirrored Hebrew one, or the date was moved below the clock (date_below_clock).
     */
    static WeatherWidget create(Activity activity, boolean animations, boolean reducedMotion, Host host) {
        try {
            if (!WeatherSource.enabled(activity, WeatherSource.KEY_CHIP)) return null;
            if (activity.getResources().getBoolean(R.bool.rtl_menu_bar)) return null;
            View clock = activity.findViewById(R.id.clock);
            View date = activity.findViewById(R.id.date);
            if (clock == null || date == null || !(clock.getParent() instanceof LinearLayout)) return null;
            LinearLayout row = (LinearLayout) clock.getParent();
            View column = (View) date.getParent();
            if (column.getParent() != row) return null;
            return new WeatherWidget(activity, row, column, animations, reducedMotion, host);
        } catch (RuntimeException e) {
            Log.w(WeatherSource.TAG, "weather chip not created", e);
            return null;
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    /** Home is in front: check the add-on, read, and watch for changes. */
    void resume() {
        addOn = WeatherSource.available(ctx);
        if (!addOn) {
            hide();
            return;
        }
        apply();   // the old reading may have gone stale while Home was away
        source.resume();
    }

    void pause() {
        source.pause();
    }

    /** Left from the leftmost top-bar button: the chip, when it is on screen. */
    boolean focus() {
        return shown && chip.requestFocus();
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
        chip.bind(d, System.currentTimeMillis());
        show();
    }

    private void show() {
        if (shown) return;
        shown = true;
        setCompactDate(true);
        divider.setVisibility(View.VISIBLE);
        chip.setVisibility(View.VISIBLE);
    }

    private void hide() {
        if (!shown) return;
        shown = false;
        if (chip.hasFocus()) host.focusButtons();
        divider.setVisibility(View.GONE);
        chip.setVisibility(View.GONE);
        setCompactDate(false);
    }

    /**
     * The date column normally fills the rest of the row (weight 1) so nothing can come after it. With the chip it
     * is as wide as its text, capped so a long date cannot push the chip into the top buttons. Its texts wrap too,
     * or a longer date tomorrow would keep today's width (as moveDateBelowClock notes).
     */
    private void setCompactDate(boolean compact) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dateColumn.getLayoutParams();
        lp.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
        lp.weight = compact ? 0f : 1f;
        dateColumn.setLayoutParams(lp);
        ViewGroup texts = (ViewGroup) dateColumn;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            ViewGroup.LayoutParams tlp = text.getLayoutParams();
            tlp.width = compact ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
            text.setLayoutParams(tlp);
        }
        dateText.setMaxWidth(compact ? dp(240) : Integer.MAX_VALUE);
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

        void bind(WeatherData d, long now) {
            boolean stale = d.freshness(now) == WeatherData.Freshness.STALE;
            WeatherData.Condition condition = d.condition();
            art.setWeather(condition, d.day);
            art.setArtAlpha(stale ? 0.5f : 1f);
            art.setAnimationAllowed(artMoves(ctx, d, now, animations, reducedMotion));
            String degrees = d.displayTemp(d.tempC) + "°";
            temp.setText(degrees);
            String name = label(ctx, condition);
            line1.setText(name);
            line2.setText(stale ? ctx.getString(R.string.weather_updated_ago, d.ageHours(now)) : highLow(d, true));
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
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;   // nothing above or to its left; the focus finder must not jump to a far tile
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    host.focusGrid();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    host.focusButtons();
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }
    }
}
