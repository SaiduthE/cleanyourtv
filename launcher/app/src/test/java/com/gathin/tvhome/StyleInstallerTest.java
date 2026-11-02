package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class StyleInstallerTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void ownerKeysComeFromTheCurrentConfigNotThePreset() {
        Map<String, Object> preset = map("dock_apps", "preset", "columns", 6, "background_color", "#000000");
        Map<String, Object> current = map("dock_apps", "mine", "weather_widget", true);
        Map<String, Object> next = StyleInstaller.mergeMaps(preset, current, null, "glass");
        assertEquals("mine", next.get("dock_apps"));
        assertEquals(true, next.get("weather_widget"));
        assertFalse(next.containsKey("columns"));   // not set on the TV, so the built-in value applies
        assertEquals("#000000", next.get("background_color"));
    }

    @Test
    public void newKeepKeysSurvive() {
        Map<String, Object> current = map("top_bar_apps", "a", "weather_widget_package", "p", "weather_on_clock", true,
                "weather_animated", false, "launcher_settings_button", true, "reorder_button", true,
                "home_idle_clock_minutes", 5);
        Map<String, Object> preset = map("home_idle_clock_minutes", 2);
        Map<String, Object> next = StyleInstaller.mergeMaps(preset, current, null, "cinematic");
        for (String key : current.keySet()) assertEquals(key, current.get(key), next.get(key));
    }

    @Test
    public void presetCannotSetAKeepKey() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map("top_bar_apps", "x", "reorder_button", true),
                map(), null, "minimal");
        assertFalse(next.containsKey("top_bar_apps"));
        assertFalse(next.containsKey("reorder_button"));
    }

    @Test
    public void onTvChoicesWin() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map("columns", 6, "dock_opacity", 65),
                map("columns", 5), map("columns", 7, "dock_opacity", 80), "glass");
        assertEquals(7, next.get("columns"));
        assertEquals(80, next.get("dock_opacity"));
    }

    @Test
    public void markersNameTheStyle() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map("panel_accent", "#5ED6C8"), null, null, "glass");
        assertEquals("Clean Look", next.get("theme"));
        assertEquals("glass", next.get("style"));
        assertEquals("#5ED6C8", next.get("panel_accent"));
    }

    @Test
    public void builtInLookHasNoMarkersOrPreset() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), map("dock_apps", "mine"), null, null);
        assertEquals(1, next.size());
        assertNull(next.get("style"));
        assertNull(next.get("theme"));
    }

    @Test
    public void anOldStyleMarkerIsNotCarriedOver() {
        Map<String, Object> next = StyleInstaller.mergeMaps(map(), map("style", "aurora", "theme", "old"), null, "glass");
        assertEquals("glass", next.get("style"));
        assertEquals("Clean Look", next.get("theme"));
    }

    @Test
    public void onlyAShippedCollectionReplacesThePictures() {
        assertFalse(StyleInstaller.shipsCollection(null));
        assertFalse(StyleInstaller.shipsCollection(new String[0]));
        assertTrue(StyleInstaller.shipsCollection(new String[] {"a.jpg"}));
    }

    @Test
    public void keepListHasTheWeatherAndTopBarKeys() {
        for (String key : new String[] {"top_bar_apps", "weather_widget", "weather_widget_package", "weather_on_clock",
                "weather_animated", "launcher_settings_button", "reorder_button", "home_idle_clock_minutes",
                "dock_apps", "clock_format"}) {
            assertTrue(key, StyleInstaller.keepKeys().contains(key));
        }
    }
}
