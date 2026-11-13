package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Clean Look styles: the presets bundled in assets/themes, applied from the couch. Applying one does what
 * tools/theme.sh does over ADB: it writes config.json, the wallpaper and a new theme_revision into the launcher's
 * files folder, so the launcher drops the on-TV choices that belong to the old look, and then Home starts again.
 *
 * The new config starts from the current one: only the keys that make up a look (LOOK, plus every key any bundled
 * preset sets) are replaced, so every other key the owner or a TV's own config.json has set survives.
 */
final class StyleInstaller {

    static final String THEME_NAME = "Clean Look";
    static final String KEY_THEME = "theme";
    static final String KEY_STYLE = "style";

    /** id (the assets folder), name, one line about it. */
    private static final String[][] STYLES = {
            {"cinematic", "Cinematic", "OLED and cinema look: true-colour wallpaper, frosted-glass dock, idle clock after two minutes"},
            {"glass", "Glass", "Dark navy with soft teal and violet light, a frosted dock and icon-only buttons"},
            {"aurora", "Aurora", "A dark aurora wallpaper in teal and violet"},
            {"minimal", "Minimal", "Flat graphite, no wallpaper or glow: the tiles float on their own"},
            {"six-columns", "Six columns", "The built-in look with six smaller tiles per row"},
    };

    /**
     * Keys that are the owner's even when a preset sets them: the current value is kept and the preset's is never
     * used (a preset that sets one, such as the dock apps, leaves the owner's alone).
     */
    static final String[] KEEP = {
            "dock_apps", "fill_dock", "app_keys", "vpn_pill", "icon_only_vpn", "vpn_app",
            "show_inputs_pill", "hidden_apps", "live_tv_package", "greeting_name", "clock_format",
            "top_bar_apps", "weather_widget", "weather_widget_package", "weather_on_clock", "weather_animated",
            "launcher_settings_button", "reorder_button",
    };

    /** Keys that belong to a look and are cleared before a preset is laid on top. */
    private static final String[] LOOK = {
            "background_color", "glow_style", "app_glow", "ambient_glow", "glass_style", "dock_glass", "dock_band",
            "dock_opacity", "dock_title", "dock_band_all", "dock_even_padding", "cinematic_home", "icon_only_buttons",
            "icon_only_inputs", "uniform_tiles", "focus_overshoot", "advanced_effects", "panel_accent",
            "grain", "glass_edge", "glass_frost", KEY_STYLE, KEY_THEME,
    };
    private static final String[] LOOK_PREFIXES = {"wallpaper", "sky_"};

    private StyleInstaller() { }

    /** The bundled styles that are really in this APK: id, name, blurb. */
    static List<String[]> styles(Context ctx) {
        List<String[]> out = new ArrayList<>();
        for (String[] style : STYLES) if (hasAsset(ctx, style[0])) out.add(style);
        return out;
    }

    /** The style id the current overlay names, or null for a custom or built-in look. */
    static String activeStyleId(Context ctx) {
        JSONObject overlay = ThemeOverlay.read(ctx);
        if (overlay == null) return null;
        String id = overlay.optString(KEY_STYLE, "").trim();
        return id.isEmpty() ? null : id;
    }

    // ---- the merge ----------------------------------------------------------------------------------------------

    /** Whether a key is part of a look: listed, or set by any bundled preset, and not one of the owner's KEEP keys. */
    static boolean isLookKey(String key, Set<String> presetKeys) {
        if (keepKeys().contains(key)) return false;
        if (presetKeys != null && presetKeys.contains(key)) return true;
        for (String look : LOOK) if (look.equals(key)) return true;
        for (String prefix : LOOK_PREFIXES) if (key.startsWith(prefix)) return true;
        return false;
    }

    /**
     * The new config as a plain map (kept free of org.json so it runs in JVM tests). Start from the current config,
     * drop its look keys, lay the preset on top (its KEEP keys excluded), then the choices made on the TV, then the
     * theme and style markers for a real style (id not null). So an on-TV choice beats the preset, which beats the
     * old look, and every other current key survives.
     */
    static Map<String, Object> mergeMaps(Map<String, Object> preset, Map<String, Object> current,
                                         Map<String, Object> onTvChoices, String id, Set<String> presetKeys) {
        Map<String, Object> next = new LinkedHashMap<>();
        if (current != null) {
            for (Map.Entry<String, Object> e : current.entrySet()) {
                if (!isLookKey(e.getKey(), presetKeys)) next.put(e.getKey(), e.getValue());
            }
        }
        if (preset != null) {
            for (Map.Entry<String, Object> e : preset.entrySet()) {
                if (!keepKeys().contains(e.getKey())) next.put(e.getKey(), e.getValue());
            }
        }
        if (onTvChoices != null) next.putAll(onTvChoices);
        if (id != null) {
            next.put(KEY_THEME, THEME_NAME);
            next.put(KEY_STYLE, id);
        }
        return next;
    }

    /** {@link #mergeMaps} on JSON objects; either of preset and current may be null. */
    static JSONObject merge(JSONObject preset, JSONObject current, Map<String, Object> onTvChoices, String id,
                            Set<String> presetKeys) throws JSONException {
        Map<String, Object> merged = mergeMaps(toMap(preset), toMap(current), onTvChoices, id, presetKeys);
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> e : merged.entrySet()) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static Map<String, Object> toMap(JSONObject json) throws JSONException {
        if (json == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
            String key = keys.next();
            map.put(key, json.get(key));
        }
        return map;
    }

    /** Whether the style ships its own wallpaper collection, so the owner's collection may be replaced. */
    static boolean shipsCollection(String[] assetFiles) {
        return assetFiles != null && assetFiles.length > 0;
    }

    /** Every key any bundled preset sets, so the look keys cannot drift from the presets. */
    private static Set<String> presetKeys(Context ctx) {
        Set<String> keys = new HashSet<>();
        for (String[] style : styles(ctx)) {
            try {
                JSONObject preset = new JSONObject(readAsset(ctx, "themes/" + style[0] + "/config.json"));
                for (Iterator<String> it = preset.keys(); it.hasNext(); ) keys.add(it.next());
            } catch (IOException | JSONException e) {
                // a broken preset simply adds no keys
            }
        }
        return keys;
    }

    // ---- applying -----------------------------------------------------------------------------------------------

    /**
     * Writes a style from assets (or the built-in look for a null id) where tools/theme.sh would put it, then
     * restarts Home and finishes the activity. Everything is staged in .tmp files first and moved into place only
     * when every copy has worked, config.json and theme_revision last, with the old config kept as config.json.bak.
     * False, with a toast, when nothing could be written.
     */
    static boolean apply(Activity activity, String id, String name) {
        File dir = Config.dir(activity);
        if (dir == null) {
            Toast.makeText(activity, "No storage to write the style to.", Toast.LENGTH_LONG).show();
            return false;
        }
        File configTmp = new File(dir, Config.FILE_NAME + ".tmp");
        File wallpaperTmp = new File(dir, Config.WALLPAPER_NAME + ".tmp");
        File revisionTmp = new File(dir, "theme_revision.tmp");
        File collectionTmp = new File(dir, ThemeFiles.WALLPAPERS + ".tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File config = new File(dir, Config.FILE_NAME);
            File wallpaper = new File(dir, Config.WALLPAPER_NAME);
            JSONObject current = readJson(config);
            // A config that is there but will not parse: use the copy the launcher kept, never write a config
            // that drops the owner's keys.
            if (current == null && config.isFile()) current = ThemeOverlay.read(activity);
            if (current == null && config.isFile()) throw new IOException("config.json cannot be read");
            JSONObject preset = id == null ? new JSONObject()
                    : new JSONObject(readAsset(activity, "themes/" + id + "/config.json"));
            // Choices made in Launcher settings belong to the old look and the launcher drops them when the theme
            // changes. These are the owner's, not the style's, so they move into the new config and survive.
            SharedPreferences chosen = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            Map<String, Object> onTv = new LinkedHashMap<>();
            if (chosen.contains("columns")) onTv.put("columns", chosen.getInt("columns", 6));
            if (chosen.contains(Config.ROWS_BELOW_DOCK_KEY)) onTv.put("rows_below_dock", chosen.getInt(Config.ROWS_BELOW_DOCK_KEY, 1));
            if (chosen.contains(Config.DOCK_OPACITY_KEY)) onTv.put("dock_opacity", chosen.getInt(Config.DOCK_OPACITY_KEY, 100));
            if (chosen.contains(Config.CLOCK_FORMAT_KEY)) onTv.put("clock_format", chosen.getString(Config.CLOCK_FORMAT_KEY, "system"));
            JSONObject next = merge(preset, current, onTv, id, presetKeys(activity));

            // Stage everything first.
            boolean writeConfig = next.length() > 0;
            if (writeConfig) writeText(configTmp, next.toString(2));
            boolean copyWallpaper = id != null && hasAssetFile(activity, "themes/" + id + "/wallpaper.jpg");
            if (copyWallpaper) copyAsset(activity, "themes/" + id + "/wallpaper.jpg", wallpaperTmp);
            // The owner's own pictures stay unless the style brings a collection of its own.
            String[] collection = id == null ? null
                    : activity.getAssets().list("themes/" + id + "/" + ThemeFiles.WALLPAPERS);
            boolean copyCollection = shipsCollection(collection);
            if (copyCollection) {
                clearCollection(collectionTmp);
                if (!collectionTmp.isDirectory() && !collectionTmp.mkdirs()) throw new IOException("cannot create " + collectionTmp);
                for (String file : collection) {
                    copyAsset(activity, "themes/" + id + "/" + ThemeFiles.WALLPAPERS + "/" + file, new File(collectionTmp, file));
                }
            }
            writeText(revisionTmp, UUID.randomUUID().toString().replace("-", ""));

            // Move into place; config.json and theme_revision last.
            if (config.isFile()) {
                Files.copy(config.toPath(), new File(dir, Config.FILE_NAME + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (copyCollection) {
                File folder = new File(dir, ThemeFiles.WALLPAPERS);
                clearCollection(folder);
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
                File[] staged = collectionTmp.listFiles();
                if (staged != null) for (File f : staged) move(f, new File(folder, f.getName()));
                collectionTmp.delete();
            }
            if (copyWallpaper) move(wallpaperTmp, wallpaper);
            else wallpaper.delete();
            if (writeConfig) move(configTmp, config);
            else config.delete();
            // The launcher compares this to what it saw last and drops the on-TV choices of the old look.
            move(revisionTmp, new File(dir, "theme_revision"));
        } catch (IOException | JSONException e) {
            configTmp.delete();
            wallpaperTmp.delete();
            revisionTmp.delete();
            clearCollection(collectionTmp);
            collectionTmp.delete();
            Toast.makeText(activity, "Could not apply the style: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        Toast.makeText(activity, "Applying " + name + "…", Toast.LENGTH_SHORT).show();
        activity.startActivity(new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
        return true;
    }

    private static void move(File from, File to) throws IOException {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void clearCollection(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile()) f.delete();
    }

    private static boolean hasAsset(Context ctx, String id) {
        try {
            String[] files = ctx.getAssets().list("themes/" + id);
            return files != null && files.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasAssetFile(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readAsset(Context ctx, String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path, AssetManager.ACCESS_STREAMING)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private static void copyAsset(Context ctx, String path, File to) throws IOException {
        try (InputStream in = ctx.getAssets().open(path); FileOutputStream out = new FileOutputStream(to)) {
            out.write(readAll(in));
        }
    }

    /** The parsed file (a leading BOM, which Windows editors add, is ignored), or null when absent or unparseable. */
    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(stripBom(new String(readAll(in), StandardCharsets.UTF_8)));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    /** KEEP as a list. */
    static List<String> keepKeys() {
        return Arrays.asList(KEEP);
    }
}
