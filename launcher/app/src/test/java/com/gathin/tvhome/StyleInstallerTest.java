package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class StyleInstallerTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Set<String> keys(String... k) {
        return new HashSet<>(Arrays.asList(k));
    }

    @Test
    public void keysTheStyleKnowsNothingAboutSurvive() {
        Map<String, Object> current = map("stock_home_redirect", true, "settings_key_keycodes", "1,2",
                "source_panel_service", "svc", "launch_actions", "x", "app_labels", "y", "locale", "de",
                "lunar_date", true, "library_apps", "z", "live_tv_action", "a", "input_names", "b");
        Map<String, Object> next = StyleInstaller.mergeMaps(map("background_color", "#000000"), current, null,
                "glass", keys("background_color"));
        for (String key : current.keySet()) assertEquals(key, current.get(key), next.get(key));
        assertEquals("#000000", next.get("background_color"));
    }

    @Test
    public void lookKeysOfTheOldLookAreCleared() {
        Map<String, Object> current = map("wallpaper", true, "wallpaper_rotation_minutes", 1, "background_color", "#111111",
                "glow_style", "soft", "dock_glass", true, "sky_intensity", 3, "panel_accent", "#D9A55B",
                "style", "cinematic", "theme", "Clean Look", "glass_style", "rim");
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), current, null, "minimal", keys());
        assertEquals(2, next.size());   // only the new markers
        assertEquals("minimal", next.get("style"));
    }

    @Test
    public void everyKeyAPresetSetsIsALookKey() {
        Map<String, Object> current = map("show_greeting", false, "rows_below_dock", 2, "home_idle_clock_minutes", 9);
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), current, null, null,
                keys("show_greeting", "rows_below_dock", "home_idle_clock_minutes"));
        assertTrue(next.isEmpty());
    }

    @Test
    public void ownerKeysComeFromTheCurrentConfigNotThePreset() {
        Map<String, Object> preset = map("dock_apps", "preset", "fill_dock", false, "background_color", "#000000");
        Map<String, Object> current = map("dock_apps", "mine", "weather_widget", true);
        Map<String, Object> next = StyleInstaller.mergeMaps(preset, current, null, "glass",
                keys("dock_apps", "fill_dock", "background_color"));
        assertEquals("mine", next.get("dock_apps"));
        assertEquals(true, next.get("weather_widget"));
        assertFalse(next.containsKey("fill_dock"));   // not set by the owner, so the built-in value applies
        assertEquals("#000000", next.get("background_color"));
    }

    @Test
    public void presetBeatsTheOldLookAndKeepsItsDefiningKeys() {
        Map<String, Object> preset = map("columns", 6, "home_idle_clock_minutes", 2);
        Map<String, Object> current = map("columns", 5, "home_idle_clock_minutes", 9);
        Set<String> presetKeys = keys("columns", "home_idle_clock_minutes");
        Map<String, Object> next = StyleInstaller.mergeMaps(preset, current, null, "six-columns", presetKeys);
        assertEquals(6, next.get("columns"));
        assertEquals(2, next.get("home_idle_clock_minutes"));
    }

    @Test
    public void onTvChoicesBeatThePresetAndTheOldConfig() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map("columns", 6, "dock_opacity", 65),
                map("columns", 5, "dock_opacity", 40), map("columns", 7, "dock_opacity", 80), "glass",
                keys("columns", "dock_opacity"));
        assertEquals(7, next.get("columns"));
        assertEquals(80, next.get("dock_opacity"));
    }

    @Test
    public void newKeepKeysSurvive() {
        Map<String, Object> current = map("top_bar_apps", "a", "weather_widget_package", "p", "weather_on_clock", true,
                "weather_animated", false, "launcher_settings_button", true, "reorder_button", true);
        Map<String, Object> preset = map("reorder_button", false, "top_bar_apps", "x");
        Map<String, Object> next = StyleInstaller.mergeMaps(preset, current, null, "cinematic",
                keys("reorder_button", "top_bar_apps"));
        for (String key : current.keySet()) assertEquals(key, current.get(key), next.get(key));
    }

    @Test
    public void markersNameTheStyle() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map("panel_accent", "#5ED6C8"), null, null, "glass", keys());
        assertEquals("Clean Look", next.get("theme"));
        assertEquals("glass", next.get("style"));
        assertEquals("#5ED6C8", next.get("panel_accent"));
    }

    @Test
    public void builtInLookHasNoMarkers() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), map("dock_apps", "mine", "style", "glass"), null,
                null, keys());
        assertEquals(1, next.size());
        assertNull(next.get("style"));
        assertNull(next.get("theme"));
    }

    @Test
    public void anOldStyleMarkerIsNotCarriedOver() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), map("style", "aurora", "theme", "old"), null, "glass",
                keys());
        assertEquals("glass", next.get("style"));
        assertEquals("Clean Look", next.get("theme"));
    }

    @Test
    public void lookKeyRules() {
        Set<String> none = keys();
        assertTrue(StyleInstaller.isLookKey("wallpaper_captions", none));
        assertTrue(StyleInstaller.isLookKey("sky_intensity", none));
        assertTrue(StyleInstaller.isLookKey("dock_even_padding", none));
        assertFalse(StyleInstaller.isLookKey("stock_home_redirect", none));
        assertFalse(StyleInstaller.isLookKey("dock_apps", keys("dock_apps")));   // KEEP beats the presets' keys
        assertTrue(StyleInstaller.isLookKey("columns", keys("columns")));
    }

    @Test
    public void stripsAByteOrderMark() {
        assertEquals("{}", StyleInstaller.stripBom("﻿{}"));
        assertEquals("{}", StyleInstaller.stripBom("{}"));
    }

    @Test
    public void onlyAShippedCollectionReplacesThePictures() {
        assertFalse(StyleInstaller.shipsCollection(null));
        assertFalse(StyleInstaller.shipsCollection(new String[0]));
        assertTrue(StyleInstaller.shipsCollection(new String[] {"a.jpg"}));
    }

    @Test
    public void keepListHasTheWeatherAndTopBarKeysButNotTheLookOnes() {
        for (String key : new String[] {"top_bar_apps", "weather_widget", "weather_widget_package", "weather_on_clock",
                "weather_animated", "launcher_settings_button", "reorder_button", "dock_apps", "clock_format"}) {
            assertTrue(key, StyleInstaller.keepKeys().contains(key));
        }
        assertFalse(StyleInstaller.keepKeys().contains("columns"));
        assertFalse(StyleInstaller.keepKeys().contains("home_idle_clock_minutes"));
    }

    @Test
    public void horizonThenBackToTheOwnLookKeepsTheOwnersKeys() {
        Set<String> presetKeys = keys("scene_clock", "wallpaper_depth", "scene_clock_layouts", "background_color", "dock_apps");
        Map<String, Object> mine = map("background_color", "#101010", "dock_apps", "mine", "weather_widget", true);
        Map<String, Object> horizon = map("scene_clock", true, "wallpaper_depth", true, "scene_clock_layouts", "x",
                "background_color", "#090C14", "dock_apps", "preset");
        Map<String, Object> onHorizon = StyleInstaller.mergeMaps(horizon, mine, null, "horizon", presetKeys);
        assertEquals("horizon", onHorizon.get("style"));
        assertEquals(true, onHorizon.get("scene_clock"));
        assertEquals("mine", onHorizon.get("dock_apps"));
        // Back: the set-aside config is the preset, no markers; Horizon's own keys are gone, the owner's stay.
        Map<String, Object> back = StyleInstaller.mergeMaps(mine, onHorizon, null, null, presetKeys);
        assertEquals("#101010", back.get("background_color"));
        assertEquals("mine", back.get("dock_apps"));
        assertEquals(true, back.get("weather_widget"));
        assertFalse(back.containsKey("scene_clock"));
        assertFalse(back.containsKey("wallpaper_depth"));
        assertFalse(back.containsKey("scene_clock_layouts"));
        assertFalse(back.containsKey("style"));
    }
}
