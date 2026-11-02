package io.github.sumyabazar1.weather;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The widget snapshot, its rows and the refresh decisions: plain Java, no Android. */
public class WidgetTest {
    private static List<Snapshot.Day> days(int n) {
        List<Snapshot.Day> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Snapshot.Day("2026-11-" + String.format("%02d", i + 3), 3, 5.5 - i, -4.25 - i, i == 1 ? -1 : 10 * i));
        }
        return out;
    }

    private static Snapshot sample(int dayCount) {
        return new Snapshot(1_700_000_000_000L, "Ulaanbaatar", 47.9, 106.9, -5.8, -9.04, 5.9, -5.8, 3, true, 61, 14, 42,
                days(dayCount));
    }

    @Test
    public void snapshotRoundTrips() {
        Snapshot s = sample(7);
        Snapshot back = Snapshot.parse(s.serialize());
        assertEquals(s.serialize(), back.serialize());
        assertEquals(1_700_000_000_000L, back.updatedMs);
        assertEquals("Ulaanbaatar", back.place);
        assertEquals(-9.04, back.feelsC, 0);
        assertEquals(47.9, back.latitude, 0);
        assertEquals(7, back.daily.size());
        assertEquals(-1, back.daily.get(1).precipPct);
        assertEquals("2026-11-03", back.daily.get(0).date);
    }

    @Test
    public void placeWithTabsNewlinesAndNonLatinTextSurvives() {
        Snapshot s = new Snapshot(1, "Улаанбаатар\tA\nB %", 1, 2, 3, 4, 5, 6, 0, false, 0, 0, -1, days(0));
        assertEquals("Улаанбаатар\tA\nB %", Snapshot.parse(s.serialize()).place);
    }

    @Test
    public void damagedOrForeignTextParsesToNull() {
        assertNull(Snapshot.parse(null));
        assertNull(Snapshot.parse(""));
        assertNull(Snapshot.parse("w2\t1"));
        String ok = sample(2).serialize();
        assertNull(Snapshot.parse(ok.replace("w1", "w9")));
        assertNull(Snapshot.parse(ok.substring(0, ok.indexOf('\n') - 3)));
        assertNull(Snapshot.parse(ok.replace("-5.8", "NaN")));
        assertNull(Snapshot.parse(ok + "\nbad\trow"));
    }

    @Test
    public void atMostSevenDailyRows() {
        assertEquals(7, sample(9).daily.size());
        assertEquals(7, Snapshot.parse(sample(9).serialize()).daily.size());
        assertEquals(7, WidgetRows.daily(sample(9)).size());
        assertEquals(0, WidgetRows.daily(sample(0)).size());
    }

    @Test
    public void snapshotFromAReportPutsTodayFirst() {
        Forecast.Day today = new Forecast.Day(LocalDate.of(2026, 11, 3), 3, 5.9, -5.8, 0);
        Forecast.Day next = new Forecast.Day(LocalDate.of(2026, 11, 4), 85, 0.3, -8.7, 55);
        Forecast.Report r = new Forecast.Report(-1.2, -4.0, 71, false, today, Arrays.asList(next), 30, 80, 12);
        Snapshot s = Snapshot.of(r, "Ulaanbaatar", 47.9, 106.9, 99L);
        assertEquals(99L, s.updatedMs);
        assertEquals(5.9, s.highC, 0);
        assertEquals(-5.8, s.lowC, 0);
        assertEquals(2, s.daily.size());
        assertEquals("2026-11-03", s.daily.get(0).date);
        assertEquals(55, s.daily.get(1).precipPct);
        // No daily values for today: the reading stands in for the high and low.
        Snapshot noToday = Snapshot.of(new Forecast.Report(-1.2, -4.0, 71, false, null, Arrays.asList(next), 30, 80, 12),
                "x", 0, 0, 1);
        assertEquals(-1.2, noToday.highC, 0);
        assertEquals(1, noToday.daily.size());
    }

    @Test
    public void currentRowFollowsTheContract() {
        Object[] row = WidgetRows.current(sample(3), new WidgetRows.Display(false, true, false));
        assertEquals(WidgetRows.CURRENT_COLUMNS.length, row.length);
        assertArrayEquals(new Object[] {1, 1_700_000_000_000L, "Ulaanbaatar", -5.8, -9.0, 5.9, -5.8, 3, 1, 61, 14, 42,
                "c", 1, 0}, row);
        Object[] f = WidgetRows.current(sample(3), new WidgetRows.Display(true, false, true));
        // The city's name is published only when the owner opted in.
        assertEquals("", f[2]);
        assertEquals("f", f[12]);
        assertEquals(0, f[13]);
        assertEquals(1, f[14]);
        // The temperatures stay Celsius whatever the unit is: the reader converts.
        assertEquals(-5.8, f[3]);
    }

    @Test
    public void rowValuesAreClampedToTheirRanges() {
        Snapshot wild = new Snapshot(5, "p", 0, 0, 1.26, 1.24, 2, 0, 150, false, 250, -3, 900,
                Arrays.asList(new Snapshot.Day("2026-01-01", -4, 1, 0, 400)));
        Object[] row = WidgetRows.current(wild, new WidgetRows.Display(false, false, false));
        assertEquals(1.3, row[3]);
        assertEquals(1.2, row[4]);
        assertEquals(99, row[7]);
        assertEquals(100, row[9]);
        assertEquals(0, row[10]);
        assertEquals(500, row[11]);
        Object[] d = WidgetRows.daily(wild).get(0);
        assertEquals(0, d[1]);
        assertEquals(100, d[4]);
        // An unknown air quality stays -1.
        Snapshot unknown = new Snapshot(5, "p", 0, 0, 0, 0, 0, 0, 0, true, 0, 0, -1, days(0));
        assertEquals(-1, WidgetRows.current(unknown, new WidgetRows.Display(false, false, false))[11]);
    }

    @Test
    public void placeIsAtMostFortyCharactersWithoutBrokenPairs() {
        assertEquals("Oslo", WidgetRows.place("  Oslo "));
        assertEquals("", WidgetRows.place(null));
        assertEquals("a b", WidgetRows.place("a\nb"));
        String forty = "x".repeat(40);
        assertEquals(forty, WidgetRows.place(forty + "yyy"));
        String pair = "x".repeat(39) + "🌤";   // the pair would start at 39 and end at 41
        assertEquals("x".repeat(39), WidgetRows.place(pair));
    }

    @Test
    public void projectionKeepsTheCallersOrderAndRejectsUnknownColumns() {
        String[] cols = WidgetRows.projection(WidgetRows.DAILY_COLUMNS, new String[] {"low_c", "date"});
        Object[] row = WidgetRows.daily(sample(1)).get(0);
        assertArrayEquals(new Object[] {-4.2, "2026-11-03"}, WidgetRows.pick(WidgetRows.DAILY_COLUMNS, row, cols));
        assertTrue(WidgetRows.projection(WidgetRows.DAILY_COLUMNS, null) == WidgetRows.DAILY_COLUMNS);
        assertTrue(WidgetRows.projection(WidgetRows.DAILY_COLUMNS, new String[0]) == WidgetRows.DAILY_COLUMNS);
        try {
            WidgetRows.projection(WidgetRows.DAILY_COLUMNS, new String[] {"date", "intent"});
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // fine
        }
    }

    @Test
    public void noColumnCarriesAnIntentOrUri() {
        for (String c : WidgetRows.CURRENT_COLUMNS) assertFalse(c, c.contains("intent") || c.contains("uri"));
        for (String c : WidgetRows.DAILY_COLUMNS) assertFalse(c, c.contains("intent") || c.contains("uri"));
    }

    @Test
    public void staleDataAfterThirtyFiveMinutesAsksForARefresh() {
        long now = 10_000_000_000L;
        long min = 60_000L;
        assertFalse(RefreshPolicy.shouldKick(now, now - 35 * min, 0, true));
        assertTrue(RefreshPolicy.shouldKick(now, now - 35 * min - 1, 0, true));
        assertFalse(RefreshPolicy.shouldKick(now, now - 5 * min, 0, true));
        assertTrue(RefreshPolicy.shouldKick(now, -1, 0, true));          // nothing saved yet
        assertFalse(RefreshPolicy.shouldKick(now, -1, 0, false));        // no city, nothing to read
    }

    @Test
    public void aRefreshIsAskedForAtMostOnceInTenMinutes() {
        long now = 10_000_000_000L;
        long min = 60_000L;
        long old = now - 120 * min;
        assertFalse(RefreshPolicy.shouldKick(now, old, now - 9 * min, true));
        assertTrue(RefreshPolicy.shouldKick(now, old, now - 10 * min, true));
        assertTrue(RefreshPolicy.shouldKick(now, old, now + 5 * min, true));   // the clock was set back
        assertTrue(RefreshPolicy.shouldKick(now, now + 120 * min, 0, true));  // data from the future is not fresh
    }

    @Test
    public void backgroundReadingRunsOnlyWithinADayOfTheLastQuery() {
        long now = 10_000_000_000L;
        long hour = 3_600_000L;
        assertFalse(RefreshPolicy.inUse(now, 0));                    // no reader has ever asked
        assertTrue(RefreshPolicy.inUse(now, now - 23 * hour));
        assertTrue(RefreshPolicy.inUse(now, now - 24 * hour));
        assertFalse(RefreshPolicy.inUse(now, now - 24 * hour - 1));
        assertFalse(RefreshPolicy.inUse(now, now + hour));           // the clock was set back
    }

    @Test
    public void aQuerySavesItsTimeAtMostEveryFiveMinutes() {
        long now = 10_000_000_000L;
        long min = 60_000L;
        assertTrue(RefreshPolicy.shouldNoteQuery(now, 0));
        assertFalse(RefreshPolicy.shouldNoteQuery(now, now - 4 * min));
        assertTrue(RefreshPolicy.shouldNoteQuery(now, now - 5 * min));
        assertTrue(RefreshPolicy.shouldNoteQuery(now, now + min));
    }

    @Test
    public void intervalChoicesAreFifteenThirtyAndSixty() {
        assertEquals(15, RefreshPolicy.minutes(15));
        assertEquals(60, RefreshPolicy.minutes(60));
        assertEquals(30, RefreshPolicy.minutes(7));
        assertEquals(30, RefreshPolicy.minutes(0));
        assertEquals(30, RefreshPolicy.nextMinutes(15));
        assertEquals(60, RefreshPolicy.nextMinutes(30));
        assertEquals(15, RefreshPolicy.nextMinutes(60));
        assertEquals(30, RefreshPolicy.nextMinutes(99));
    }
}
