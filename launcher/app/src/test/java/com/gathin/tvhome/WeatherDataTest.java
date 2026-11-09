package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeatherDataTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    private static Map<String, Object> row() {
        Map<String, Object> m = new HashMap<>();
        m.put("spec", 1L);
        m.put("updated_ms", NOW - 10 * MIN);
        m.put("place", "Riverside");
        m.put("temp_c", 18.4);
        m.put("feels_c", 17.0);
        m.put("high_c", 21.0);
        m.put("low_c", 12.0);
        m.put("code", 2L);
        m.put("is_day", 1L);
        m.put("humidity", 55L);
        m.put("wind_kmh", 12L);
        m.put("aqi", -1L);
        m.put("unit", "c");
        m.put("show_place", 1L);
        m.put("animated", 1L);
        return m;
    }

    private static Map<String, Object> day(String date) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("code", 61L);
        m.put("high_c", 14.0);
        m.put("low_c", 8.0);
        m.put("precip_pct", 70L);
        return m;
    }

    private static WeatherData parse(Map<String, Object> m) {
        return WeatherData.fromCurrent(m);
    }

    // ---- the current row -------------------------------------------------------------------------------------------

    @Test public void goodRowParses() {
        WeatherData d = parse(row());
        assertNotNull(d);
        assertEquals("Riverside", d.place);
        assertEquals(18.4, d.tempC, 1e-9);
        assertEquals(2, d.code);
        assertTrue(d.day);
        assertFalse(d.fahrenheit);
        assertTrue(d.animated);
        assertEquals(-1, d.aqi);
    }

    @Test public void anotherSpecIsIgnored() {
        Map<String, Object> m = row();
        m.put("spec", 2L);
        assertNull(parse(m));
        m.remove("spec");
        assertNull(parse(m));
    }

    @Test public void temperatureRange() {
        for (String key : new String[]{"temp_c", "feels_c", "high_c", "low_c"}) {
            Map<String, Object> hot = row();
            hot.put(key, 60.5);
            assertNull(key, parse(hot));
            Map<String, Object> cold = row();
            cold.put(key, -90.5);
            assertNull(key, parse(cold));
            Map<String, Object> missing = row();
            missing.remove(key);
            assertNull(key, parse(missing));
            Map<String, Object> nan = row();
            nan.put(key, Double.NaN);
            assertNull(key, parse(nan));
        }
        Map<String, Object> edge = row();
        edge.put("temp_c", -90.0);
        edge.put("low_c", -90.0);
        edge.put("feels_c", 60.0);
        edge.put("high_c", 60.0);
        assertNotNull(parse(edge));
    }

    @Test public void highBelowLowBeyondRoundingIsRejected() {
        Map<String, Object> m = row();
        m.put("high_c", 10.0);
        m.put("low_c", 12.0);
        assertNull(parse(m));
        m.put("high_c", 11.5);   // within a degree: rounding
        assertNotNull(parse(m));
    }

    @Test public void codeAndFlagsAreRangeChecked() {
        Map<String, Object> m = row();
        m.put("code", 100L);
        assertNull(parse(m));
        m = row();
        m.put("code", -1L);
        assertNull(parse(m));
        m = row();
        m.put("is_day", 2L);
        assertNull(parse(m));
        m = row();
        m.put("show_place", 3L);
        assertNull(parse(m));
        m = row();
        m.put("animated", -1L);
        assertNull(parse(m));
        m = row();
        m.put("humidity", 101L);
        assertNull(parse(m));
        m = row();
        m.put("aqi", -2L);
        assertNull(parse(m));
        m = row();
        m.put("unit", "k");
        assertNull(parse(m));
        m = row();
        m.put("unit", null);
        assertNull(parse(m));
        m = row();
        m.put("code", 1.5);   // not a whole number
        assertNull(parse(m));
    }

    @Test public void placeIsHiddenUnlessOptedIn() {
        Map<String, Object> m = row();
        m.put("show_place", 0L);
        assertEquals("", parse(m).place);
    }

    @Test public void placeIsPlainTextOfAtMostFortyCharacters() {
        Map<String, Object> m = row();
        m.put("place", "Ri\nver\u0007side‮  ");
        assertEquals("Riverside", parse(m).place);
        StringBuilder long50 = new StringBuilder();
        for (int i = 0; i < 50; i++) long50.append('a');
        m.put("place", long50.toString());
        assertEquals(40, parse(m).place.length());
        m.put("place", null);
        assertEquals("", parse(m).place);
    }

    @Test public void fahrenheitUnitIsAChoiceNotAConversionOfTheData() {
        Map<String, Object> m = row();
        m.put("unit", "f");
        WeatherData d = parse(m);
        assertTrue(d.fahrenheit);
        assertEquals(18.4, d.tempC, 1e-9);
        assertEquals(65, d.displayTemp(d.tempC));
    }

    // ---- units --------------------------------------------------------------------------------------------------

    @Test public void celsiusToFahrenheit() {
        assertEquals(32.0, WeatherData.toFahrenheit(0), 1e-9);
        assertEquals(212.0, WeatherData.toFahrenheit(100), 1e-9);
        assertEquals(-40.0, WeatherData.toFahrenheit(-40), 1e-9);
        assertEquals(98.6, WeatherData.toFahrenheit(37), 1e-9);
        assertEquals(68, WeatherData.displayTemp(20.0, true));
        assertEquals(20, WeatherData.displayTemp(20.0, false));
        assertEquals(-4, WeatherData.displayTemp(-20.0, true));
    }

    @Test public void displayRoundsAndNeverShowsMinusZero() {
        assertEquals(0, WeatherData.displayTemp(-0.4, false));
        assertEquals("0", String.valueOf(WeatherData.displayTemp(-0.4, false)));
        assertEquals(19, WeatherData.displayTemp(18.5, false));
        assertEquals(-18, WeatherData.displayTemp(-17.6, false));
    }

    // ---- WMO codes ----------------------------------------------------------------------------------------------

    @Test public void wmoCodesMapToConditions() {
        assertEquals(WeatherData.Condition.CLEAR, WeatherData.condition(0));
        assertEquals(WeatherData.Condition.CLEAR, WeatherData.condition(1));
        assertEquals(WeatherData.Condition.PARTLY, WeatherData.condition(2));
        assertEquals(WeatherData.Condition.CLOUDY, WeatherData.condition(3));
        assertEquals(WeatherData.Condition.FOG, WeatherData.condition(45));
        assertEquals(WeatherData.Condition.FOG, WeatherData.condition(48));
        for (int c : new int[]{51, 53, 55}) assertEquals(WeatherData.Condition.DRIZZLE, WeatherData.condition(c));
        for (int c : new int[]{56, 57, 66, 67}) assertEquals(WeatherData.Condition.SLEET, WeatherData.condition(c));
        for (int c : new int[]{61, 63, 65, 80, 81, 82}) assertEquals(WeatherData.Condition.RAIN, WeatherData.condition(c));
        for (int c : new int[]{71, 73, 75, 77, 85, 86}) assertEquals(WeatherData.Condition.SNOW, WeatherData.condition(c));
        for (int c : new int[]{95, 96, 99}) assertEquals(WeatherData.Condition.THUNDER, WeatherData.condition(c));
    }

    @Test public void everyValidCodeHasACondition() {
        for (int c = 0; c <= 99; c++) assertNotNull("code " + c, WeatherData.condition(c));
        assertEquals(WeatherData.Condition.CLOUDY, WeatherData.condition(10));
        assertEquals(WeatherData.Condition.RAIN, WeatherData.condition(62));
    }

    @Test public void dayAndNightComeFromTheRow() {
        Map<String, Object> m = row();
        m.put("is_day", 0L);
        assertFalse(parse(m).day);
    }

    // ---- staleness ----------------------------------------------------------------------------------------------

    private static WeatherData aged(long ageMs) {
        Map<String, Object> m = row();
        m.put("updated_ms", NOW - ageMs);
        return parse(m);
    }

    @Test public void freshStaleAndAbsent() {
        assertEquals(WeatherData.Freshness.FRESH, aged(0).freshness(NOW));
        assertEquals(WeatherData.Freshness.FRESH, aged(90 * MIN).freshness(NOW));
        assertEquals(WeatherData.Freshness.STALE, aged(90 * MIN + 1).freshness(NOW));
        assertEquals(WeatherData.Freshness.STALE, aged(6 * 60 * MIN).freshness(NOW));
        assertEquals(WeatherData.Freshness.ABSENT, aged(6 * 60 * MIN + 1).freshness(NOW));
    }

    @Test public void futureDatedReadingsAreAbsent() {
        assertEquals(WeatherData.Freshness.FRESH, aged(-2 * MIN).freshness(NOW));    // a few minutes of clock skew
        assertEquals(WeatherData.Freshness.ABSENT, aged(-6 * MIN).freshness(NOW));
        assertEquals(WeatherData.Freshness.ABSENT, aged(-24 * 60 * MIN).freshness(NOW));
    }

    @Test public void ageInHoursIsAtLeastOne() {
        assertEquals(1, aged(100 * MIN).ageHours(NOW));
        assertEquals(4, aged(4 * 60 * MIN + 5 * MIN).ageHours(NOW));
        assertEquals(1, aged(-MIN).ageHours(NOW));
    }

    @Test public void nonPositiveTimestampIsRejected() {
        Map<String, Object> m = row();
        m.put("updated_ms", 0L);
        assertNull(parse(m));
    }

    // ---- the forecast -------------------------------------------------------------------------------------------

    @Test public void dailyRowsAreValidatedAndBadOnesDropped() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(day("2026-11-02"));
        rows.add(day("2026-13-40"));                 // not a date
        rows.add(day("2026-11-3"));                  // wrong shape
        Map<String, Object> badCode = day("2026-11-04");
        badCode.put("code", 120L);
        rows.add(badCode);
        Map<String, Object> badPrecip = day("2026-11-05");
        badPrecip.put("precip_pct", 101L);
        rows.add(badPrecip);
        Map<String, Object> unknownPrecip = day("2026-11-06");
        unknownPrecip.put("precip_pct", -1L);
        rows.add(unknownPrecip);
        Map<String, Object> badTemp = day("2026-11-07");
        badTemp.put("high_c", 99.0);
        rows.add(badTemp);
        Map<String, Object> inverted = day("2026-11-08");
        inverted.put("high_c", 3.0);
        inverted.put("low_c", 9.0);
        rows.add(inverted);
        WeatherData d = parse(row()).withDaily(rows);
        assertEquals(2, d.daily.size());
        assertEquals("2026-11-02", d.daily.get(0).date);
        assertEquals(-1, d.daily.get(1).precipPct);
    }

    @Test public void dailyKeepsAtMostEightRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) rows.add(day(String.format("2026-11-%02d", i)));
        assertEquals(WeatherData.MAX_DAILY, parse(row()).withDaily(rows).daily.size());
    }

    @Test public void noForecastIsFine() {
        assertTrue(parse(row()).daily.isEmpty());
        assertTrue(parse(row()).withDaily(null).daily.isEmpty());
    }

    // ---- text ---------------------------------------------------------------------------------------------------

    @Test public void cleanStripsControlsAndTruncatesByCodePoint() {
        assertEquals("ab", WeatherData.clean("a\u0000b\r\n"));
        assertEquals("", WeatherData.clean(null));
        StringBuilder emoji = new StringBuilder();
        for (int i = 0; i < 45; i++) emoji.appendCodePoint(0x1F600);
        String cut = WeatherData.clean(emoji.toString());
        assertEquals(40, cut.codePointCount(0, cut.length()));
    }
}
