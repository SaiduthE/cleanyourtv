package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One reading from the Weather add-on's content provider (docs/specs/weather-widget.md in the add-on's tree), validated.
 * Pure Java so it runs in JVM tests: no Android classes. The provider is another app's, so every value is checked
 * and a row that fails any check is dropped whole (the widget then shows nothing rather than something wrong).
 * Temperatures are always Celsius here; {@link #displayTemp} converts for the owner's unit.
 */
final class WeatherData {

    /** What the art and the label show. Day or night is a separate flag, so one value serves both. */
    enum Condition { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, SLEET, THUNDER }

    enum Freshness { FRESH, STALE, ABSENT }

    static final int SPEC = 1;
    static final long STALE_MS = 90L * 60_000L;
    static final long ABSENT_MS = 6L * 60L * 60_000L;
    /** A clock a few minutes apart between the two apps is normal; further ahead than this is a bad row. */
    static final long FUTURE_SLACK_MS = 5L * 60_000L;
    static final double TEMP_MIN = -90, TEMP_MAX = 60;
    static final int MAX_TEXT = 40;
    static final int MAX_DAILY = 8;
    /** How far a high may sit under a low before the row counts as inconsistent (rounding, in degrees). */
    private static final double HIGH_LOW_SLACK = 1.0;

    /** One row of the forecast. */
    static final class Day {
        final String date;       // yyyy-MM-dd
        final int code;
        final double highC, lowC;
        final int precipPct;     // -1 unknown

        private Day(String date, int code, double highC, double lowC, int precipPct) {
            this.date = date;
            this.code = code;
            this.highC = highC;
            this.lowC = lowC;
            this.precipPct = precipPct;
        }
    }

    final long updatedMs;
    final String place;          // "" unless the owner opted in
    final double tempC, feelsC, highC, lowC;
    final int code;
    final boolean day;
    final int humidity, windKmh, aqi;
    final boolean fahrenheit;
    final boolean animated;
    final List<Day> daily;

    private WeatherData(long updatedMs, String place, double tempC, double feelsC, double highC, double lowC,
                        int code, boolean day, int humidity, int windKmh, int aqi, boolean fahrenheit,
                        boolean animated, List<Day> daily) {
        this.updatedMs = updatedMs;
        this.place = place;
        this.tempC = tempC;
        this.feelsC = feelsC;
        this.highC = highC;
        this.lowC = lowC;
        this.code = code;
        this.day = day;
        this.humidity = humidity;
        this.windKmh = windKmh;
        this.aqi = aqi;
        this.fahrenheit = fahrenheit;
        this.animated = animated;
        this.daily = daily;
    }

    // ---- parsing ------------------------------------------------------------------------------------------------

    /**
     * The /current row (column name to Long, Double or String), or null when it is not spec 1 or any value is
     * missing or out of range. The forecast rows come separately and are attached with {@link #withDaily}.
     */
    static WeatherData fromCurrent(Map<String, Object> row) {
        if (row == null) return null;
        Long spec = integer(row.get("spec"));
        if (spec == null || spec != SPEC) return null;
        Long updated = integer(row.get("updated_ms"));
        if (updated == null || updated <= 0) return null;
        Double temp = temperature(row.get("temp_c"));
        Double feels = temperature(row.get("feels_c"));
        Double high = temperature(row.get("high_c"));
        Double low = temperature(row.get("low_c"));
        if (temp == null || feels == null || high == null || low == null) return null;
        if (high < low - HIGH_LOW_SLACK) return null;
        Long code = integer(row.get("code"));
        if (code == null || code < 0 || code > 99) return null;
        Boolean day = flag(row.get("is_day"));
        Boolean showPlace = flag(row.get("show_place"));
        Boolean animated = flag(row.get("animated"));
        if (day == null || showPlace == null || animated == null) return null;
        Long humidity = integer(row.get("humidity"));
        if (humidity == null || humidity < 0 || humidity > 100) return null;
        Long wind = integer(row.get("wind_kmh"));
        if (wind == null || wind < 0 || wind > 500) return null;
        Long aqi = integer(row.get("aqi"));
        if (aqi == null || aqi < -1 || aqi > 1000) return null;
        Object unitValue = row.get("unit");
        if (!(unitValue instanceof String)) return null;
        String unit = ((String) unitValue).trim().toLowerCase(java.util.Locale.ROOT);
        if (!unit.equals("c") && !unit.equals("f")) return null;
        String place = "";
        if (showPlace && row.get("place") instanceof String) place = clean((String) row.get("place"));
        return new WeatherData(updated, place, temp, feels, high, low, code.intValue(), day,
                humidity.intValue(), wind.intValue(), aqi.intValue(), unit.equals("f"), animated,
                Collections.emptyList());
    }

    /** A copy with the forecast attached: bad rows dropped, at most {@link #MAX_DAILY} kept. */
    WeatherData withDaily(List<Map<String, Object>> rows) {
        List<Day> days = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Day day = parseDay(row);
                if (day != null) days.add(day);
                if (days.size() >= MAX_DAILY) break;
            }
        }
        return new WeatherData(updatedMs, place, tempC, feelsC, highC, lowC, code, day, humidity, windKmh, aqi,
                fahrenheit, animated, Collections.unmodifiableList(days));
    }

    static Day parseDay(Map<String, Object> row) {
        if (row == null || !(row.get("date") instanceof String)) return null;
        String date = (String) row.get("date");
        if (date.length() != 10) return null;
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
        Long code = integer(row.get("code"));
        if (code == null || code < 0 || code > 99) return null;
        Double high = temperature(row.get("high_c"));
        Double low = temperature(row.get("low_c"));
        if (high == null || low == null || high < low - HIGH_LOW_SLACK) return null;
        Long precip = integer(row.get("precip_pct"));
        if (precip == null || precip < -1 || precip > 100) return null;
        return new Day(date, code.intValue(), high, low, precip.intValue());
    }

    private static Long integer(Object value) {
        if (value instanceof Long || value instanceof Integer || value instanceof Short) return ((Number) value).longValue();
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            // A real column that holds a whole number is fine; 1.5 for an integer column is not.
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return (long) d;
        }
        return null;
    }

    private static Double temperature(Object value) {
        if (!(value instanceof Number)) return null;
        double d = ((Number) value).doubleValue();
        if (Double.isNaN(d) || d < TEMP_MIN || d > TEMP_MAX) return null;
        return d;
    }

    private static Boolean flag(Object value) {
        Long v = integer(value);
        if (v == null || (v != 0 && v != 1)) return null;
        return v == 1;
    }

    /** Plain text for one line: control characters removed, at most {@link #MAX_TEXT} characters, trimmed. */
    static String clean(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = 0; i < text.length() && count < MAX_TEXT; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            // Controls, format characters (bidi overrides), line and paragraph separators, unassigned and surrogates.
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.UNASSIGNED
                    || type == Character.SURROGATE || type == Character.PRIVATE_USE) {
                continue;
            }
            out.appendCodePoint(cp);
            count++;
        }
        return out.toString().trim();
    }

    // ---- freshness ----------------------------------------------------------------------------------------------

    /** Fresh, stale (older than 90 minutes), or absent (older than 6 hours, or dated in the future). */
    Freshness freshness(long nowMs) {
        long age = nowMs - updatedMs;
        if (age < -FUTURE_SLACK_MS) return Freshness.ABSENT;
        if (age > ABSENT_MS) return Freshness.ABSENT;
        if (age > STALE_MS) return Freshness.STALE;
        return Freshness.FRESH;
    }

    /** Whole hours since the reading, at least 1 (the "Updated 3 h ago" line shows it only when stale). */
    int ageHours(long nowMs) {
        long hours = Math.max(0, nowMs - updatedMs) / 3_600_000L;
        return (int) Math.max(1, hours);
    }

    // ---- display ------------------------------------------------------------------------------------------------

    /** Celsius to Fahrenheit. */
    static double toFahrenheit(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }

    /** The whole number to show for a Celsius value in the owner's unit. */
    int displayTemp(double celsius) {
        return displayTemp(celsius, fahrenheit);
    }

    static int displayTemp(double celsius, boolean fahrenheit) {
        double value = fahrenheit ? toFahrenheit(celsius) : celsius;
        // Math.round(-0.4) is 0, not -0, so no "-0°".
        return (int) Math.round(value);
    }

    /** The WMO code's condition. A code the table does not list falls back to its tens group. */
    static Condition condition(int code) {
        switch (code) {
            case 0: case 1: return Condition.CLEAR;
            case 2: return Condition.PARTLY;
            case 3: return Condition.CLOUDY;
            case 45: case 48: return Condition.FOG;
            case 51: case 53: case 55: return Condition.DRIZZLE;
            case 56: case 57: return Condition.SLEET;
            case 61: case 63: case 65: return Condition.RAIN;
            case 66: case 67: return Condition.SLEET;
            case 71: case 73: case 75: case 77: return Condition.SNOW;
            case 80: case 81: case 82: return Condition.RAIN;
            case 85: case 86: return Condition.SNOW;
            case 95: case 96: case 99: return Condition.THUNDER;
            default: break;
        }
        if (code >= 95) return Condition.THUNDER;
        if (code >= 85) return Condition.SNOW;
        if (code >= 80) return Condition.RAIN;
        if (code >= 70) return Condition.SNOW;
        if (code >= 60) return Condition.RAIN;
        if (code >= 50) return Condition.DRIZZLE;
        if (code >= 40) return Condition.FOG;
        return Condition.CLOUDY;
    }

    Condition condition() {
        return condition(code);
    }
}
