package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.util.UUID;

/**
 * Launcher settings > Themes: switch the look from the couch, without a computer. The presets are bundled in the
 * APK (assets/themes). Applying one does what tools/theme.sh does over ADB: it writes config.json, the wallpaper
 * and a new theme_revision into the launcher's files folder, so the launcher drops the on-TV choices that belong to
 * the old look, and then Home starts again. The owner's own choices (dock apps, columns, remote buttons, VPN
 * button, hidden apps) are carried over, so a theme only changes how things look.
 */
public final class ThemesActivity extends Activity {

    /** id (the assets folder), name, one line about it. */
    private static final String[][] PRESETS = {
            {"cinematic", "Cinematic", "OLED and cinema look: true-colour wallpaper, frosted-glass dock, idle clock after two minutes"},
            {"glass", "Glass", "Dark navy with soft teal and violet light, a frosted dock and icon-only buttons"},
            {"aurora", "Aurora", "A dark aurora wallpaper in teal and violet"},
            {"minimal", "Minimal", "Flat graphite, no wallpaper or glow: the tiles float on their own"},
            {"six-columns", "Six columns", "The built-in look with six smaller tiles per row"},
    };

    /** Keys that are the owner's, not the theme's: kept across a switch, and never taken from a preset. */
    private static final String[] KEEP = {
            "dock_apps", "columns", "fill_dock", "app_keys", "vpn_pill", "icon_only_vpn", "vpn_app",
            "show_inputs_pill", "hidden_apps", "live_tv_package", "greeting_name", "clock_format",
    };

    private LinearLayout content;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        content = new LinearLayout(this);
        setContentView(PanelUi.scroller(this, content));
        SettingsPanel.apply(this);
        content.addView(PanelUi.title(this, "Themes"));
        content.addView(PanelUi.note(this, "Pick a look. Your dock apps, columns, remote buttons and VPN button are "
                + "kept, so a theme only changes how things look. Home restarts to apply it."));
        TextView first = null;
        for (String[] preset : PRESETS) {
            if (!hasAsset(preset[0])) continue;
            TextView row = PanelUi.row(this, preset[1]);
            row.setOnClickListener(v -> showPreset(preset));
            content.addView(row);
            if (first == null) first = row;
        }
        content.addView(PanelUi.section(this, "Other"));
        TextView builtin = PanelUi.row(this, "Built-in look (no theme)");
        builtin.setOnClickListener(v -> apply(null, "the built-in look"));
        content.addView(builtin);
        TextView hint = PanelUi.note(this, "More themes (wallpaper collections, seasonal skins) can still be pushed "
                + "from a computer with tools/theme.sh.");
        content.addView(hint);
        if (first != null) first.requestFocus(); else builtin.requestFocus();
    }

    private boolean hasAsset(String id) {
        try {
            String[] files = getAssets().list("themes/" + id);
            return files != null && files.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void showPreset(String[] preset) {
        content.removeAllViews();
        content.addView(PanelUi.title(this, preset[1]));
        content.addView(PanelUi.note(this, preset[2]));
        TextView apply = PanelUi.row(this, "Apply this theme");
        ((LinearLayout.LayoutParams) apply.getLayoutParams()).topMargin = PanelUi.dp(this, 26);
        apply.setOnClickListener(v -> apply(preset[0], preset[1]));
        content.addView(apply);
        TextView back = PanelUi.row(this, "Back");
        back.setOnClickListener(v -> recreate());
        content.addView(back);
        apply.requestFocus();
    }

    // ---- applying ----------------------------------------------------------------------------------------------

    /** A theme from assets, or null for the built-in look, written where tools/theme.sh would put it. */
    private void apply(String id, String name) {
        File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, "No storage to write the theme to.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File config = new File(dir, Config.FILE_NAME);
            File wallpaper = new File(dir, Config.WALLPAPER_NAME);
            JSONObject current = readJson(config);
            // The look: a preset from assets, or nothing at all for the built-in look.
            JSONObject next = id == null ? new JSONObject() : new JSONObject(readAsset("themes/" + id + "/config.json"));
            // A preset never sets the owner's own keys: they come from what is on the TV now, and without any
            // there the built-in config.xml values apply.
            for (String key : KEEP) {
                next.remove(key);
                if (current != null && current.has(key)) next.put(key, current.get(key));
            }
            // Choices made in Launcher settings belong to the old look and the launcher drops them when the theme
            // changes. These are the owner's, not the theme's, so they move into the new config and survive.
            SharedPreferences chosen = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
            if (chosen.contains("columns")) next.put("columns", chosen.getInt("columns", 6));
            if (chosen.contains(Config.ROWS_BELOW_DOCK_KEY)) next.put("rows_below_dock", chosen.getInt(Config.ROWS_BELOW_DOCK_KEY, 1));
            if (chosen.contains(Config.DOCK_OPACITY_KEY)) next.put("dock_opacity", chosen.getInt(Config.DOCK_OPACITY_KEY, 100));
            if (chosen.contains(Config.CLOCK_FORMAT_KEY)) next.put("clock_format", chosen.getString(Config.CLOCK_FORMAT_KEY, "system"));
            if (next.length() == 0) config.delete();
            else writeText(config, next.toString(2));
            if (id != null && hasAssetFile("themes/" + id + "/wallpaper.jpg")) copyAsset("themes/" + id + "/wallpaper.jpg", wallpaper);
            else wallpaper.delete();
            clearCollection(new File(dir, ThemeFiles.WALLPAPERS));
            // The launcher compares this to what it saw last and drops the on-TV choices of the old look.
            writeText(new File(dir, "theme_revision"), UUID.randomUUID().toString().replace("-", ""));
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Could not apply the theme: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Applying " + name + "…", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private static void clearCollection(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile()) f.delete();
    }

    private boolean hasAssetFile(String path) {
        try (InputStream in = getAssets().open(path)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path, AssetManager.ACCESS_STREAMING)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    private void copyAsset(String path, File to) throws IOException {
        try (InputStream in = getAssets().open(path); FileOutputStream out = new FileOutputStream(to)) {
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
}
