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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clean Look styles: the presets bundled in assets/themes, applied from the couch. Applying one does what
 * tools/theme.sh does over ADB: it writes config.json, the wallpaper and a new theme_revision into the launcher's
 * files folder, so the launcher drops the on-TV choices that belong to the old look, and then Home starts again.
 * The owner's own choices (dock apps, columns, remote buttons, VPN button, hidden apps, weather and top-bar
 * settings) are carried over, so a style only changes how things look.
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

    /** Keys that are the owner's, not the style's: kept across a switch, and never taken from a preset. */
    static final String[] KEEP = {
            "dock_apps", "columns", "fill_dock", "app_keys", "vpn_pill", "icon_only_vpn", "vpn_app",
            "show_inputs_pill", "hidden_apps", "live_tv_package", "greeting_name", "clock_format",
            "top_bar_apps", "weather_widget", "weather_widget_package", "weather_on_clock", "weather_animated",
            "launcher_settings_button", "reorder_button", "home_idle_clock_minutes",
    };

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

    /**
     * The new config as a plain map (kept free of org.json so it runs in JVM tests): the preset, then the owner's
     * KEEP keys from the current config (a KEEP key the current config lacks is dropped from the preset too), then
     * the choices made on the TV, then the theme and style markers for a real style (id not null).
     */
    static Map<String, Object> mergeMaps(Map<String, Object> preset, Map<String, Object> current,
                                         Map<String, Object> onTvChoices, String id) {
        Map<String, Object> next = new LinkedHashMap<>();
        if (preset != null) next.putAll(preset);
        for (String key : KEEP) {
            next.remove(key);
            if (current != null && current.containsKey(key)) next.put(key, current.get(key));
        }
        if (onTvChoices != null) next.putAll(onTvChoices);
        if (id != null) {
            next.put(KEY_THEME, THEME_NAME);
            next.put(KEY_STYLE, id);
        }
        return next;
    }

    /** {@link #mergeMaps} on JSON objects; either of preset and current may be null. */
    static JSONObject merge(JSONObject preset, JSONObject current, Map<String, Object> onTvChoices, String id)
            throws JSONException {
        Map<String, Object> merged = mergeMaps(toMap(preset), toMap(current), onTvChoices, id);
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

    // ---- applying -----------------------------------------------------------------------------------------------

    /**
     * Writes a style from assets (or the built-in look for a null id) where tools/theme.sh would put it, then
     * restarts Home and finishes the activity. False, with a toast, when it could not be written.
     */
    static boolean apply(Activity activity, String id, String name) {
        File dir = Config.dir(activity);
        if (dir == null) {
            Toast.makeText(activity, "No storage to write the style to.", Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File config = new File(dir, Config.FILE_NAME);
            File wallpaper = new File(dir, Config.WALLPAPER_NAME);
            JSONObject current = readJson(config);
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
            JSONObject next = merge(preset, current, onTv, id);
            if (next.length() == 0) config.delete();
            else writeText(config, next.toString(2));
            if (id != null && hasAssetFile(activity, "themes/" + id + "/wallpaper.jpg")) {
                copyAsset(activity, "themes/" + id + "/wallpaper.jpg", wallpaper);
            } else {
                wallpaper.delete();
            }
            // The owner's own pictures stay unless the style brings a collection of its own.
            if (id != null) {
                String[] collection = activity.getAssets().list("themes/" + id + "/" + ThemeFiles.WALLPAPERS);
                if (shipsCollection(collection)) {
                    File folder = new File(dir, ThemeFiles.WALLPAPERS);
                    clearCollection(folder);
                    if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
                    for (String file : collection) {
                        copyAsset(activity, "themes/" + id + "/" + ThemeFiles.WALLPAPERS + "/" + file, new File(folder, file));
                    }
                }
            }
            // The launcher compares this to what it saw last and drops the on-TV choices of the old look.
            writeText(new File(dir, "theme_revision"), UUID.randomUUID().toString().replace("-", ""));
        } catch (IOException | JSONException e) {
            Toast.makeText(activity, "Could not apply the style: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
        Toast.makeText(activity, "Applying " + name + "…", Toast.LENGTH_SHORT).show();
        activity.startActivity(new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
        return true;
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

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return null;
        }
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

    /** KEEP as a list, for tests. */
    static List<String> keepKeys() {
        return Arrays.asList(KEEP);
    }
}
