package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** Plain JVM tests for wallpaper_moods: the time of day's group and the pictures it picks from. */
public class WallpaperMoodTest {
    private static final int RISE = 7 * 60 + 20, SET = 18 * 60 + 20;

    private static String at(int hour, int minute) {
        return WallpaperMood.timeOfDay(hour * 60 + minute, RISE, SET);
    }

    @Test
    public void timeOfDayFollowsSunriseNoonAndDusk() {
        assertEquals(WallpaperMood.NIGHT, at(0, 30));
        assertEquals(WallpaperMood.NIGHT, at(7, 19));
        assertEquals(WallpaperMood.MORNING, at(7, 20));
        assertEquals(WallpaperMood.MORNING, at(11, 59));
        assertEquals(WallpaperMood.EVENING, at(12, 0));
        assertEquals(WallpaperMood.EVENING, at(18, 49));   // half an hour after sunset is still evening
        assertEquals(WallpaperMood.NIGHT, at(18, 50));
        assertEquals(WallpaperMood.NIGHT, at(23, 59));
    }

    @Test
    public void sunComesFromTheConfiguredPlaceOrTheDefaults() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        assertArrayEquals(Sun.riseAndSet(day, 37.35, -121.98, zone), WallpaperMood.sun(day, "37.35", " -121.98 ", zone));
        int[] defaults = {WallpaperMood.DEFAULT_SUNRISE, WallpaperMood.DEFAULT_SUNSET};
        assertArrayEquals(defaults, WallpaperMood.sun(day, "", "", zone));
        assertArrayEquals(defaults, WallpaperMood.sun(day, "37.35", "", zone));
        assertArrayEquals(defaults, WallpaperMood.sun(day, "north", "-121.98", zone));
        // the sun does not set at the pole in June: the defaults
        assertArrayEquals(defaults, WallpaperMood.sun(LocalDate.of(2026, 6, 21), "89.9", "0", ZoneId.of("UTC")));
    }

    private static List<File> files(String... names) {
        List<File> out = new ArrayList<>();
        for (String name : names) out.add(new File("/walls", name));
        return out;
    }

    private static List<String> names(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getName());
        return out;
    }

    @Test
    public void selectKeepsTheCollectionsOrderAndFallsBackToAll() {
        List<File> all = files("a.jpg", "b.jpg", "c.jpg", "d.jpg");
        Map<String, Set<String>> groups = new HashMap<>();
        groups.put(WallpaperMood.NIGHT, new HashSet<>(Arrays.asList("d.jpg", "a.jpg")));
        groups.put(WallpaperMood.MORNING, new HashSet<>(Collections.singletonList("gone.jpg")));
        assertEquals(Arrays.asList("a.jpg", "d.jpg"), names(WallpaperMood.select(all, groups, WallpaperMood.NIGHT)));
        assertEquals(all, WallpaperMood.select(all, groups, WallpaperMood.MORNING));   // none of its pictures here
        assertEquals(all, WallpaperMood.select(all, groups, WallpaperMood.EVENING));   // no group at all
        assertEquals(all, WallpaperMood.select(all, Collections.emptyMap(), WallpaperMood.NIGHT));
    }

    // ---- Horizon: dawn, day, golden hour and night, with the weather over the day ----

    private static final String[] HORIZON = {"01-night.jpg", "02-golden.jpg", "03-water.jpg", "04-fog.jpg", "05-dawn.jpg"};

    private static Map<String, Set<String>> horizonMoods() {
        Map<String, Set<String>> m = new HashMap<>();
        m.put(WallpaperMood.NIGHT, Collections.singleton("01-night.jpg"));
        m.put(WallpaperMood.GOLDEN, Collections.singleton("02-golden.jpg"));
        m.put(WallpaperMood.DAY, Collections.singleton("03-water.jpg"));
        m.put(WallpaperMood.DAWN, Collections.singleton("05-dawn.jpg"));
        return m;
    }

    private static Map<String, Set<String>> horizonWeather() {
        Map<String, Set<String>> m = new HashMap<>();
        for (String w : WallpaperMood.WEATHER) m.put(w, Collections.singleton("04-fog.jpg"));
        return m;
    }

    private static String horizon(String weather, int hour, int minute) {
        List<File> got = WallpaperMood.select(files(HORIZON), horizonMoods(), horizonWeather(), weather,
                hour * 60 + minute, RISE, SET);
        assertEquals(1, got.size());
        return got.get(0).getName();
    }

    @Test
    public void phaseSplitsTheDayAroundTheSun() {
        assertEquals(WallpaperMood.NIGHT, WallpaperMood.phase(3 * 60, RISE, SET));
        assertEquals(WallpaperMood.DAWN, WallpaperMood.phase(RISE - 40, RISE, SET));
        assertEquals(WallpaperMood.NIGHT, WallpaperMood.phase(RISE - 41, RISE, SET));
        assertEquals(WallpaperMood.DAWN, WallpaperMood.phase(RISE + 79, RISE, SET));
        assertEquals(WallpaperMood.DAY, WallpaperMood.phase(RISE + 80, RISE, SET));
        assertEquals(WallpaperMood.DAY, WallpaperMood.phase(SET - 121, RISE, SET));
        assertEquals(WallpaperMood.GOLDEN, WallpaperMood.phase(SET - 120, RISE, SET));
        assertEquals(WallpaperMood.GOLDEN, WallpaperMood.phase(SET + 29, RISE, SET));
        assertEquals(WallpaperMood.NIGHT, WallpaperMood.phase(SET + 30, RISE, SET));
    }

    @Test
    public void aThemeWithTheFinerGroupsGetsThemAndOneWithoutKeepsTheOldThree() {
        assertEquals(WallpaperMood.DAY, WallpaperMood.mood(13 * 60, RISE, SET, horizonMoods()));
        Map<String, Set<String>> old = new HashMap<>();
        old.put(WallpaperMood.MORNING, Collections.singleton("m.jpg"));
        assertEquals(WallpaperMood.MORNING, WallpaperMood.mood(8 * 60, RISE, SET, old));
        assertEquals(WallpaperMood.EVENING, WallpaperMood.mood(13 * 60, RISE, SET, old));
        assertEquals(WallpaperMood.NIGHT, WallpaperMood.mood(2 * 60, RISE, SET, old));
    }

    @Test
    public void horizonFollowsTheClockWithClearSkies() {
        assertEquals("01-night.jpg", horizon(null, 2, 0));
        assertEquals("05-dawn.jpg", horizon(null, 7, 0));
        assertEquals("03-water.jpg", horizon(null, 12, 0));
        assertEquals("02-golden.jpg", horizon(null, 17, 30));
        assertEquals("02-golden.jpg", horizon(null, 18, 45));
        assertEquals("01-night.jpg", horizon(null, 22, 0));
    }

    @Test
    public void weatherTakesTheDayButNightStaysNight() {
        for (String weather : WallpaperMood.WEATHER) {
            assertEquals("04-fog.jpg", horizon(weather, 7, 0));    // dawn
            assertEquals("04-fog.jpg", horizon(weather, 12, 0));
            assertEquals("04-fog.jpg", horizon(weather, 17, 30));  // golden hour
            assertEquals("01-night.jpg", horizon(weather, 23, 0));
            assertEquals("01-night.jpg", horizon(weather, 3, 0));
        }
    }

    @Test
    public void aWeatherWithoutAGroupOrWithNoneOfItsPicturesLeavesTheTimeToDecide() {
        Map<String, Set<String>> onlyFog = new HashMap<>();
        onlyFog.put(WallpaperMood.FOG, Collections.singleton("04-fog.jpg"));
        List<File> all = files(HORIZON);
        assertEquals("03-water.jpg", names(WallpaperMood.select(all, horizonMoods(), onlyFog, WallpaperMood.RAIN, 12 * 60, RISE, SET)).get(0));
        Map<String, Set<String>> gone = new HashMap<>();
        gone.put(WallpaperMood.FOG, Collections.singleton("missing.jpg"));
        assertEquals("03-water.jpg", names(WallpaperMood.select(all, horizonMoods(), gone, WallpaperMood.FOG, 12 * 60, RISE, SET)).get(0));
    }

    @Test
    public void conditionsMapToWeatherGroups() {
        assertEquals(WallpaperMood.FOG, WallpaperMood.weather(WeatherData.Condition.FOG));
        assertEquals(WallpaperMood.CLOUDY, WallpaperMood.weather(WeatherData.Condition.CLOUDY));
        assertEquals(WallpaperMood.RAIN, WallpaperMood.weather(WeatherData.Condition.DRIZZLE));
        assertEquals(WallpaperMood.RAIN, WallpaperMood.weather(WeatherData.Condition.RAIN));
        assertEquals(WallpaperMood.RAIN, WallpaperMood.weather(WeatherData.Condition.THUNDER));
        assertEquals(WallpaperMood.SNOW, WallpaperMood.weather(WeatherData.Condition.SNOW));
        assertNull(WallpaperMood.weather(WeatherData.Condition.CLEAR));
        assertNull(WallpaperMood.weather(WeatherData.Condition.PARTLY));
        assertNull(WallpaperMood.weather((WeatherData) null, 0L));
    }
}
