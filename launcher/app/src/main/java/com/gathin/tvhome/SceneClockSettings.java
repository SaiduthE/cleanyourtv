package com.gathin.tvhome;

import java.util.Collections;
import java.util.Map;

/** The scene_clock and wallpaper_depth switches with the theme's per-wallpaper layouts (see {@link SceneClockLayout}). */
final class SceneClockSettings {
    static final SceneClockSettings OFF = new SceneClockSettings(false, false, Collections.emptyMap());

    /** Draw the big glass clock over the wallpaper in place of the top bar's clock, greeting and date. */
    final boolean enabled;
    /** Draw each wallpaper's cut-out over the clock, when it has one. */
    final boolean depth;
    private final Map<String, SceneClockLayout> layouts;

    SceneClockSettings(boolean enabled, boolean depth, Map<String, SceneClockLayout> layouts) {
        this.enabled = enabled;
        this.depth = depth;
        this.layouts = Collections.unmodifiableMap(layouts);
    }

    /** The layout for a wallpaper file by its name without extension, else the "default" entry, else the built-in one. */
    SceneClockLayout layoutFor(String wallpaperFileName) {
        SceneClockLayout own = layouts.get(SceneFiles.stem(wallpaperFileName));
        if (own != null) return own;
        SceneClockLayout fallback = layouts.get("default");
        return fallback != null ? fallback : SceneClockLayout.DEFAULT;
    }
}
