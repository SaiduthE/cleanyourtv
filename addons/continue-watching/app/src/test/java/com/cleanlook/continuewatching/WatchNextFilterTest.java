package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

public class WatchNextFilterTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 3600 * 1000;

    private static WatchNextFilter.Row row(String pkg, String title, int type, long engagedAgo, long pos, long dur) {
        WatchNextFilter.Row r = new WatchNextFilter.Row();
        r.pkg = pkg;
        r.title = title;
        r.type = type;
        r.engagedMs = NOW - engagedAgo;
        r.positionMs = pos;
        r.durationMs = dur;
        r.intentUri = "intent://x#Intent;scheme=app;end";
        r.poster = "https://img.example/p.jpg";
        return r;
    }

    private static List<Item> run(WatchNextFilter.Row... rows) {
        return WatchNextFilter.filter(Arrays.asList(rows), NOW, Collections.<String>emptySet(), "self");
    }

    @Test public void keepsARecentHalfWatchedRow() {
        List<Item> out = run(row("com.netflix.ninja", "Dark", 0, 2 * DAY, 600_000, 3_000_000));
        assertEquals(1, out.size());
        Item it = out.get(0);
        assertEquals(Item.RESUME, it.kind);
        assertEquals(0.2f, it.progress, 0.001f);
        assertEquals(2_400_000L, it.remainingMs());
        assertTrue(it.id.startsWith("wn:"));
    }

    @Test public void dropsRowsOlderThanSixtyDays() {
        assertEquals(0, run(row("a", "Old", 0, 61 * DAY, 1, 100)).size());
        assertEquals(1, run(row("a", "Fresh", 0, 59 * DAY, 1, 100)).size());
    }

    @Test public void dropsRowsWithoutAnEngagementDate() {
        WatchNextFilter.Row r = row("a", "Undated", 0, 0, 1, 100);
        r.engagedMs = 0;
        assertEquals(0, run(r).size());
    }

    @Test public void dropsFinishedEpisodes() {
        assertEquals(0, run(row("a", "Nearly", 0, DAY, 99, 100)).size());
        assertEquals(0, run(row("a", "Exactly", 0, DAY, 95, 100)).size());
        assertEquals(1, run(row("a", "Just under", 0, DAY, 94, 100)).size());
    }

    @Test public void keepsAContinueRowThatHasNoPosition() {
        Item it = run(row("a", "No position", 0, DAY, -1, -1)).get(0);
        assertEquals(-1f, it.progress, 0f);
    }

    @Test public void nextAndNewRowsBecomeUpNext() {
        List<Item> out = run(row("a", "Show", 1, DAY, 0, 100), row("b", "Other", 2, DAY, -1, -1));
        assertEquals(2, out.size());
        assertEquals(Item.NEXT, out.get(0).kind);
        assertEquals("New episode", out.get(1).tag);
    }

    @Test public void dropsWatchlistAndUnknownTypes() {
        assertEquals(0, run(row("a", "List", 3, DAY, -1, -1)).size());
        assertEquals(0, run(row("a", "Odd", -1, DAY, -1, -1)).size());
    }

    @Test public void dedupesTheSameEpisodeKeepingTheNewest() {
        WatchNextFilter.Row older = row("a", "The Show", 0, 5 * DAY, 100, 1000);
        older.season = "2";
        older.episode = "4";
        WatchNextFilter.Row newer = row("a", "The  SHOW!", 0, 1 * DAY, 500, 1000);
        newer.season = "S2";
        newer.episode = "4";
        List<Item> out = run(older, newer);
        assertEquals(1, out.size());
        assertEquals(500, out.get(0).positionMs);
        assertEquals(2, out.get(0).season);
        assertEquals(4, out.get(0).episode);
    }

    @Test public void differentEpisodesOrAppsAreNotDeduped() {
        WatchNextFilter.Row a = row("a", "Show", 0, DAY, 1, 100);
        a.episode = "1";
        WatchNextFilter.Row b = row("a", "Show", 0, DAY, 1, 100);
        b.episode = "2";
        WatchNextFilter.Row c = row("b", "Show", 0, DAY, 1, 100);
        c.episode = "1";
        assertEquals(3, run(a, b, c).size());
    }

    @Test public void skipsDisabledAppsOurOwnRowsAndEmptyRows() {
        WatchNextFilter.Row noIntent = row("a", "No intent", 0, DAY, 1, 100);
        noIntent.intentUri = "  ";
        WatchNextFilter.Row noTitle = row("a", "  ", 0, DAY, 1, 100);
        WatchNextFilter.Row symbols = row("a", "!!!", 0, DAY, 1, 100);
        List<WatchNextFilter.Row> rows = new ArrayList<>(Arrays.asList(
                row("off", "Disabled", 0, DAY, 1, 100), row("self", "Mine", 0, DAY, 1, 100), noIntent, noTitle, symbols,
                row("ok", "Kept", 0, DAY, 1, 100)));
        List<Item> out = WatchNextFilter.filter(rows, NOW, new HashSet<>(Collections.singleton("off")), "self");
        assertEquals(1, out.size());
        assertEquals("Kept", out.get(0).title);
    }

    @Test public void prefersTheThumbnailOverThePoster() {
        WatchNextFilter.Row r = row("a", "Show", 0, DAY, 1, 100);
        r.thumb = "https://img.example/t.jpg";
        assertEquals("https://img.example/t.jpg", run(r).get(0).image);
        r.thumb = null;
        assertEquals("https://img.example/p.jpg", run(r).get(0).image);
    }

    @Test public void aFutureDateBeyondClockSkewIsDropped() {
        WatchNextFilter.Row r = row("a", "Time traveller", 0, -3 * DAY, 1, 100);
        assertEquals(0, run(r).size());
        WatchNextFilter.Row ok = row("a", "Slight skew", 0, -3600_000L, 1, 100);
        Item it = run(ok).get(0);
        assertNotNull(it);
        assertTrue(it.updatedMs <= NOW);
    }

    @Test public void convertReturnsNullForNullRow() {
        assertNull(WatchNextFilter.convert(null, NOW, null, "self"));
    }
}
