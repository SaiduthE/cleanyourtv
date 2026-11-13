package com.gathin.tvhome;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * wallpaper_moods: which group of a rotating wallpaper collection fits the time of day. Morning from sunrise to noon,
 * evening until half an hour after sunset, night the rest. The sun is the one at config.xml's latitude and longitude,
 * worked out offline by {@link Sun} as the sky does, or 07:00 and 18:30 without a place. A group that is missing, or
 * has none of the collection's pictures, falls back to the whole collection.
 *
 * <p>A theme may split the day finer: {@code dawn} (the sunrise window), {@code day} (from then until the golden hour),
 * {@code golden} (the last two hours of daylight until half an hour after sunset) and {@code night}. A finer group is
 * used only where the theme has it; without it the time falls back to morning, evening or night as above, so a theme
 * with just those three behaves as it always did.
 *
 * <p>wallpaper_weather: {@code fog}, {@code rain}, {@code snow} and {@code cloudy} groups that take the place of the
 * time's group while that weather is current, at every hour but night (night stays night). The weather comes from the
 * Weather add-on ({@link WeatherData}); without it, or when the theme has no group for it, only the time decides.
 * Pure Java, so the choice runs in JVM tests.
 */
final class WallpaperMood {
    static final String MORNING = "morning", EVENING = "evening", NIGHT = "night";
    static final String DAWN = "dawn", DAY = "day", GOLDEN = "golden";
    /** Every time group a config may name. */
    static final String[] ALL = {MORNING, EVENING, NIGHT, DAWN, DAY, GOLDEN};
    static final String FOG = "fog", RAIN = "rain", SNOW = "snow", CLOUDY = "cloudy";
    /** Every weather group a config may name (wallpaper_weather). */
    static final String[] WEATHER = {FOG, RAIN, SNOW, CLOUDY};
    /** Sunrise and sunset in minutes of the day, without a place (or where the sun does not rise or set that day). */
    static final int DEFAULT_SUNRISE = 7 * 60, DEFAULT_SUNSET = 18 * 60 + 30;
    private static final int NOON = 12 * 60;
    /** Evening lasts this long after sunset, while the sky still glows. */
    private static final int DUSK_MINUTES = 30;
    /** Dawn starts this long before sunrise (the sky is already pale) and lasts this long after it. */
    private static final int DAWN_LEAD = 40, DAWN_LENGTH = 80;
    /** The golden hours start this long before sunset. */
    private static final int GOLDEN_LEAD = 120;

    private WallpaperMood() {
    }

    /** The mood at this minute of the day, for a sunrise and sunset in minutes of the day. */
    static String timeOfDay(int minute, int sunrise, int sunset) {
        if (minute >= sunrise && minute < NOON) return MORNING;
        if (minute >= NOON && minute < sunset + DUSK_MINUTES) return EVENING;
        return NIGHT;
    }

    /** The finer time of day: dawn, day, golden or night. Dawn and golden win where the day is too short for a day. */
    static String phase(int minute, int sunrise, int sunset) {
        if (minute >= sunrise - DAWN_LEAD && minute < sunrise + DAWN_LENGTH) return DAWN;
        if (minute >= sunset - GOLDEN_LEAD && minute < sunset + DUSK_MINUTES) return GOLDEN;
        if (minute >= sunrise + DAWN_LENGTH && minute < sunset - GOLDEN_LEAD) return DAY;
        return NIGHT;
    }

    /** The time group to show: the finer {@link #phase} when {@code moods} has a group for it, else {@link #timeOfDay}. */
    static String mood(int minute, int sunrise, int sunset, Map<String, Set<String>> moods) {
        String fine = phase(minute, sunrise, sunset);
        return moods.containsKey(fine) ? fine : timeOfDay(minute, sunrise, sunset);
    }

    /** The weather group a reading calls for, or null for clear and partly cloudy skies (the time of day decides). */
    static String weather(WeatherData.Condition condition) {
        switch (condition) {
            case FOG: return FOG;
            case CLOUDY: return CLOUDY;
            case DRIZZLE: case RAIN: case SLEET: case THUNDER: return RAIN;
            case SNOW: return SNOW;
            default: return null;
        }
    }

    /** {@link #weather(WeatherData.Condition)} for a reading; null for none, or one too old to trust (absent). */
    static String weather(WeatherData data, long nowMs) {
        if (data == null || data.freshness(nowMs) == WeatherData.Freshness.ABSENT) return null;
        return weather(data.condition());
    }

    /** That day's {sunrise, sunset} at config.xml's latitude and longitude; the defaults when either is unusable. */
    static int[] sun(LocalDate day, String latitude, String longitude, ZoneId zone) {
        int[] times = null;
        try {
            if (!latitude.trim().isEmpty() && !longitude.trim().isEmpty()) {
                times = Sun.riseAndSet(day, Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim()), zone);
            }
        } catch (NumberFormatException ignored) {
            // not a number: the defaults
        }
        return times != null ? times : new int[]{DEFAULT_SUNRISE, DEFAULT_SUNSET};
    }

    /** The pictures in the mood's group, in the collection's order; all of them when the group has none. */
    static List<File> select(List<File> all, Map<String, Set<String>> groups, String mood) {
        Set<String> names = groups.get(mood);
        if (names == null) return all;
        List<File> group = new ArrayList<>();
        for (File file : all) if (names.contains(file.getName())) group.add(file);
        return group.isEmpty() ? all : group;
    }

    /**
     * The pictures to rotate through at this minute: the weather's group when {@code weather} (from {@link #weather},
     * or null) has one in {@code weatherGroups} and it is not night, else the time of day's group in {@code moods}.
     * A weather group with none of the collection's pictures falls back to the time's group, not to everything.
     */
    static List<File> select(List<File> all, Map<String, Set<String>> moods, Map<String, Set<String>> weatherGroups,
                             String weather, int minute, int sunrise, int sunset) {
        if (weather != null && weatherGroups.containsKey(weather) && !NIGHT.equals(phase(minute, sunrise, sunset))) {
            Set<String> names = weatherGroups.get(weather);
            List<File> group = new ArrayList<>();
            for (File file : all) if (names.contains(file.getName())) group.add(file);
            if (!group.isEmpty()) return group;
        }
        return select(all, moods, mood(minute, sunrise, sunset, moods));
    }
}
