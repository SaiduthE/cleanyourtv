package com.gathin.tvhome;

import java.io.File;
import java.util.Locale;

/**
 * The companion files a wallpaper can carry for the scene clock: {@code <name>_glass.jpg} (the clock's frosted fill) and
 * {@code <name>_fg.webp} (the scenery cut out, drawn over the clock). They sit in the same folder as the wallpaper and
 * are never wallpapers themselves, so {@link WallpaperRotation#images} leaves them out.
 */
final class SceneFiles {
    static final String FG = "_fg";
    static final String GLASS = "_glass";
    private static final String[] FG_TYPES = {".webp", ".png"};
    private static final String[] GLASS_TYPES = {".jpg", ".jpeg", ".webp", ".png"};

    private SceneFiles() {}

    /** The file name without its last extension: the key of a wallpaper's clock layout and the start of its companions' names. */
    static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** True for a name such as "01-night_fg.webp" or "01-night_glass.jpg": a scene's companion, not a picture to show. */
    static boolean isCompanion(String fileName) {
        String stem = stem(fileName).toLowerCase(Locale.ROOT);
        return stem.endsWith(FG) || stem.endsWith(GLASS);
    }

    static File fg(File wallpaper) {
        return find(wallpaper, FG, FG_TYPES);
    }

    static File glass(File wallpaper) {
        return find(wallpaper, GLASS, GLASS_TYPES);
    }

    private static File find(File wallpaper, String role, String[] types) {
        if (wallpaper == null) return null;
        String stem = stem(wallpaper.getName());
        for (String type : types) {
            File candidate = new File(wallpaper.getParentFile(), stem + role + type);
            if (candidate.isFile() && candidate.length() > 0) return candidate;
        }
        return null;
    }
}
