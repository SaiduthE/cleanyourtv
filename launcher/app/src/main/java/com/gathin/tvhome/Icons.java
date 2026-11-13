package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The launcher's icon library: every screen (Home, menus, Quick settings, widgets, the screensaver) gets its icons
 * from here, and nothing else looks up an {@code R.drawable.ic_*} resource. A new icon is added here first: a constant
 * for the original, a rounded twin in {@link #TWINS}, and a {@code res/drawable/ic_r_*} file. With icon_style
 * "default" every call returns the original drawable; with "rounded" (the default) it returns the Material Symbols
 * Rounded twin, and the icons that carry state (Wi-Fi bars, Ethernet, Brightness) pick the twin that says so.
 */
final class Icons {

    /** What the network pill is connected through, for {@link #network}. */
    enum Link { OFFLINE, WIFI, ETHERNET, ETHERNET_IDLE }

    // ---- the icons: ask for these, never for R.drawable.ic_* directly ---------------------------------------------

    static final int WIFI = R.drawable.ic_wifi;
    static final int ETHERNET = R.drawable.ic_ethernet;
    static final int APPS = R.drawable.ic_apps;
    static final int APPS_PILL = R.drawable.ic_apps_pill;
    static final int AV = R.drawable.ic_av;
    static final int DISCOVER = R.drawable.ic_discover;
    static final int HOME_SETTINGS = R.drawable.ic_home_settings;
    static final int HOME_SETTINGS_PILL = R.drawable.ic_home_settings_pill;
    static final int INPUT = R.drawable.ic_input;
    static final int LIVE_TV = R.drawable.ic_livetv;
    static final int LOCK = R.drawable.ic_lock;
    static final int LOCK_OPEN = R.drawable.ic_lock_open;
    static final int PICTURE = R.drawable.ic_picture;
    static final int SETTINGS = R.drawable.ic_settings;
    static final int SHELF_PLAY = R.drawable.ic_shelf_play;
    static final int SWAP_VERT = R.drawable.ic_swap_vert;
    static final int WIDGET_BRIGHTNESS = R.drawable.ic_widget_brightness;
    static final int WIDGET_WEATHER = R.drawable.ic_widget_weather;

    /** Brightness 0 to 100 as a drawable level (0 to 10000), for the level-fill icon. */
    static final int MAX_LEVEL = 10000;

    private static final int[] WIFI_BARS = {R.drawable.ic_r_signal_wifi_0_bar, R.drawable.ic_r_network_wifi_1_bar,
            R.drawable.ic_r_network_wifi_2_bar, R.drawable.ic_r_network_wifi_3_bar, R.drawable.ic_r_signal_wifi_4_bar};

    private static final Map<Integer, Integer> TWINS = new HashMap<>();

    static {
        TWINS.put(WIFI, R.drawable.ic_r_signal_wifi_4_bar);
        TWINS.put(ETHERNET, R.drawable.ic_r_lan_fill);
        TWINS.put(APPS, R.drawable.ic_r_apps);
        TWINS.put(APPS_PILL, R.drawable.ic_r_apps_pill);
        TWINS.put(AV, R.drawable.ic_r_settings_input_component);
        TWINS.put(DISCOVER, R.drawable.ic_r_explore);
        TWINS.put(HOME_SETTINGS, R.drawable.ic_r_tune);
        TWINS.put(HOME_SETTINGS_PILL, R.drawable.ic_r_tune);
        TWINS.put(INPUT, R.drawable.ic_r_settings_input_hdmi);
        TWINS.put(LIVE_TV, R.drawable.ic_r_live_tv);
        TWINS.put(LOCK, R.drawable.ic_r_lock_fill);
        TWINS.put(LOCK_OPEN, R.drawable.ic_r_lock_open);
        TWINS.put(PICTURE, R.drawable.ic_r_image);
        TWINS.put(SETTINGS, R.drawable.ic_r_settings);
        TWINS.put(SHELF_PLAY, R.drawable.ic_r_play_arrow_fill);
        TWINS.put(SWAP_VERT, R.drawable.ic_r_swap_vert);
        TWINS.put(WIDGET_BRIGHTNESS, R.drawable.ic_r_light_mode);
        TWINS.put(WIDGET_WEATHER, R.drawable.ic_r_partly_cloudy_day);
    }

    /** -1 until the style is known, then 0 (default) or 1 (rounded); set by {@link Config#load}. */
    private static volatile int style = -1;

    private Icons() { }

    /** Config.load calls this with the icon style it resolved (theme, then the owner's Icons choice). */
    static void remember(String iconStyle) {
        style = Config.ICONS_ROUNDED.equals(iconStyle) ? 1 : 0;
    }

    static boolean rounded(Context ctx) {
        if (style < 0) Config.load(ctx);   // a screen reached before Home has read the config: reads it, and remembers
        return style == 1;
    }

    /** The drawable resource to show for {@code res}: itself, or its rounded twin with icon_style rounded. */
    static int res(Context ctx, int res) {
        return res(rounded(ctx), res);
    }

    static int res(boolean rounded, int res) {
        if (!rounded) return res;
        Integer twin = TWINS.get(res);
        return twin != null ? twin : res;
    }

    static Drawable get(Context ctx, int res) {
        return ctx.getDrawable(res(ctx, res));
    }

    /** Sets a pill's start icon to {@code res} in the current style (the pills' layouts name no icon). */
    static void pill(TextView pill, int res) {
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(res(pill.getContext(), res), 0, 0, 0);
    }

    // ---- icons that carry state ----------------------------------------------------------------------------------

    /**
     * The network pill's icon. Default is today's: the Wi-Fi fan, or the Ethernet chevrons. Rounded picks the Wi-Fi
     * bars for a signal {@code level} of 0 to 4, Wi-Fi off while offline, and Ethernet filled while it has internet
     * and outlined while it has not.
     */
    static int network(Context ctx, Link link, int level) {
        return network(rounded(ctx), link, level);
    }

    static int network(boolean rounded, Link link, int level) {
        boolean ethernet = link == Link.ETHERNET || link == Link.ETHERNET_IDLE;
        if (!rounded) return ethernet ? ETHERNET : WIFI;
        switch (link) {
            case OFFLINE: return R.drawable.ic_r_wifi_off;
            case ETHERNET: return R.drawable.ic_r_lan_fill;
            case ETHERNET_IDLE: return R.drawable.ic_r_lan;
            default: return WIFI_BARS[bars(level)];
        }
    }

    /** A signal level clamped to the five Wi-Fi icons, 0 to 4. */
    static int bars(int level) {
        return Math.max(0, Math.min(WIFI_BARS.length - 1, level));
    }

    /** The Brightness chip's icon: the sun, or with icon_style rounded the sun that fills ({@link #fillLevel}). */
    static int brightness(Context ctx) {
        return rounded(ctx) ? R.drawable.ic_r_brightness_level : WIDGET_BRIGHTNESS;
    }

    /** A brightness of 0 to 100 percent as the level of {@code ic_r_brightness_level}. */
    static int fillLevel(int percent) {
        return Math.max(0, Math.min(100, percent)) * (MAX_LEVEL / 100);
    }
}
