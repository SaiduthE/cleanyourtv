package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SceneClockTest {

    private static File touch(File dir, String name) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), new byte[] {1, 2, 3});
        return f;
    }

    @Test
    public void companionsAreRecognisedByName() {
        assertTrue(SceneFiles.isCompanion("01-night_fg.webp"));
        assertTrue(SceneFiles.isCompanion("01-night_glass.jpg"));
        assertTrue(SceneFiles.isCompanion("01-Night_FG.WEBP"));
        assertFalse(SceneFiles.isCompanion("01-night.jpg"));
        assertFalse(SceneFiles.isCompanion("fgreen.jpg"));
        assertEquals("01-night", SceneFiles.stem("01-night.jpg"));
        assertEquals("a.b", SceneFiles.stem("a.b.png"));
    }

    @Test
    public void rotationSkipsCompanionsAndKeepsOrder() throws IOException {
        File dir = Files.createTempDirectory("scene").toFile();
        touch(dir, "02-golden.jpg");
        touch(dir, "01-night.jpg");
        touch(dir, "01-night_fg.webp");
        touch(dir, "01-night_glass.jpg");
        touch(dir, "02-golden_glass.jpg");
        List<File> images = WallpaperRotation.images(dir);
        assertEquals(Arrays.asList("01-night.jpg", "02-golden.jpg"),
                Arrays.asList(images.get(0).getName(), images.get(1).getName()));
        assertEquals(2, images.size());
    }

    @Test
    public void companionLookupFindsTheFilesBesideTheWallpaper() throws IOException {
        File dir = Files.createTempDirectory("scene").toFile();
        File night = touch(dir, "01-night.jpg");
        File water = touch(dir, "03-water.jpg");
        touch(dir, "01-night_fg.webp");
        touch(dir, "01-night_glass.jpg");
        touch(dir, "03-water_glass.jpg");
        assertEquals("01-night_fg.webp", SceneFiles.fg(night).getName());
        assertEquals("01-night_glass.jpg", SceneFiles.glass(night).getName());
        assertNull("water has no cut-out", SceneFiles.fg(water));
        assertEquals("03-water_glass.jpg", SceneFiles.glass(water).getName());
        assertNull(SceneFiles.fg(null));
    }

    @Test
    public void layoutDefaultsAndClamps() {
        SceneClockLayout d = SceneClockLayout.DEFAULT;
        assertEquals(SceneClockLayout.CENTRE, d.align);
        assertEquals(0.15f, d.y, 1e-6);
        assertEquals(0.37f, d.size, 1e-6);
        assertEquals(0.10f, d.dateY, 1e-6);
        assertFalse(d.dark);
        SceneClockLayout l = SceneClockLayout.parse(" Left ", 0.159, 0.435, 0.05, "dark", 0.107, "#FFD7E1", "#16201D");
        assertEquals(SceneClockLayout.LEFT, l.align);
        assertEquals(0.159f, l.y, 1e-6);
        assertTrue(l.dark);
        assertEquals(0xFFD7E1, l.rimColor);
        assertEquals(0xFF16201D, l.dateColor);
        SceneClockLayout bad = SceneClockLayout.parse("middle", 5.0, -1.0, 9.0, "pink", 2.0, "red", "#12");
        assertEquals(SceneClockLayout.CENTRE, bad.align);
        assertEquals(0.15f, bad.y, 1e-6);
        assertEquals(0.37f, bad.size, 1e-6);
        assertEquals(0.05f, bad.inset, 1e-6);
        assertFalse(bad.dark);
        assertEquals(0, bad.rimColor);
        assertEquals(0, bad.dateColor);
        assertEquals(SceneClockLayout.RIGHT, SceneClockLayout.parse("right", Double.NaN, Double.NaN, Double.NaN, null,
                Double.NaN, null, null).align);
    }

    @Test
    public void settingsFindALayoutByWallpaperNameThenDefault() {
        Map<String, SceneClockLayout> map = new HashMap<>();
        SceneClockLayout fog = SceneClockLayout.parse("left", 0.159, 0.435, 0.05, "dark", 0.107, null, null);
        SceneClockLayout other = SceneClockLayout.parse("right", 0.2, 0.3, 0.05, "light", 0.15, null, null);
        map.put("04-fog", fog);
        map.put("default", other);
        SceneClockSettings s = new SceneClockSettings(true, true, map);
        assertSame(fog, s.layoutFor("04-fog.jpg"));
        assertSame(other, s.layoutFor("mine.png"));
        assertSame(SceneClockLayout.DEFAULT, SceneClockSettings.OFF.layoutFor("x.jpg"));
        assertFalse(SceneClockSettings.OFF.enabled);
    }
}
