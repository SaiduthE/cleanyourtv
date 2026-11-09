package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class MergerAndProbeTest {
    private static Item item(String id, String src, String pkg, String title, int kind, long upd, float progress) {
        Item it = new Item();
        it.id = id;
        it.src = src;
        it.pkg = pkg;
        it.title = title;
        it.kind = kind;
        it.updatedMs = upd;
        it.progress = progress;
        return it;
    }

    @Test public void aTitleInTwoAppsIsOneEntryWithTheNasFirst() {
        List<Item> in = new ArrayList<>();
        in.add(item("wn:1", Item.SRC_WATCH_NEXT, "com.netflix.ninja", "Severance", Item.RESUME, 900, 0.8f));
        in.add(item("jf:1", Item.SRC_JELLYFIN, Item.JELLYFIN_PKG, "severance", Item.RESUME, 100, 0.3f));
        List<Merger.Entry> all = Merger.merge(in);
        assertEquals(1, all.size());
        Merger.Entry e = all.get(0);
        assertEquals(2, e.alts.size());
        assertEquals("jf:1", e.alts.get(0).id);
        assertEquals("the newest app is the default", "wn:1", e.primary.id);
    }

    @Test public void theSameTitleFromJellyfinAndItsWatchNextRowCollapses() {
        List<Item> in = new ArrayList<>();
        in.add(item("wn:j", Item.SRC_WATCH_NEXT, Item.JELLYFIN_PKG, "Heat", Item.RESUME, 500, 0.5f));
        in.add(item("jf:h", Item.SRC_JELLYFIN, Item.JELLYFIN_PKG, "Heat", Item.RESUME, 500, 0.5f));
        List<Merger.Entry> all = Merger.merge(in);
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).alts.size());
        assertEquals("the API row is richer", "jf:h", all.get(0).primary.id);
    }

    @Test public void resumeBeatsNextForTheSameTitleAndResumeComesFirst() {
        List<Item> in = new ArrayList<>();
        in.add(item("a", Item.SRC_WATCH_NEXT, "p", "Show", Item.NEXT, 900, -1f));
        in.add(item("b", Item.SRC_WATCH_NEXT, "q", "Show", Item.RESUME, 100, 0.4f));
        in.add(item("c", Item.SRC_WATCH_NEXT, "p", "Other", Item.NEXT, 800, -1f));
        List<Merger.Entry> all = Merger.merge(in);
        assertEquals(2, all.size());
        assertEquals("b", all.get(0).primary.id);
        assertEquals("c", all.get(1).primary.id);
    }

    @Test public void entriesAreNewestFirst() {
        List<Item> in = new ArrayList<>();
        in.add(item("a", Item.SRC_WATCH_NEXT, "p", "A", Item.RESUME, 100, 0.1f));
        in.add(item("b", Item.SRC_WATCH_NEXT, "p", "B", Item.RESUME, 300, 0.1f));
        in.add(item("c", Item.SRC_WATCH_NEXT, "p", "C", Item.RESUME, 200, 0.1f));
        List<Merger.Entry> all = Merger.merge(in);
        assertEquals("B", all.get(0).primary.title);
        assertEquals("C", all.get(1).primary.title);
        assertEquals("A", all.get(2).primary.title);
    }

    @Test public void probeCountsPerPackageAndNamesSchemes() {
        List<Probe.Sample> s = new ArrayList<>();
        s.add(sample(true, "com.netflix.ninja", "https://www.netflix.com/title/1", "https://img/x.jpg", 10, true));
        s.add(sample(true, "com.netflix.ninja", "https://www.netflix.com/title/2", "https://img/y.jpg", 20, false));
        s.add(sample(false, "com.netflix.ninja", "netflix://x", "content://p/q", 0, false));
        s.add(sample(true, "org.jellyfin.androidtv", "intent://x#Intent;scheme=jf;component=a.b/.C;end", "content://img/1", 5, false));
        s.add(sample(false, null, null, null, 0, false));
        Map<String, Probe.Stat> m = Probe.summarize(s);
        assertEquals(3, m.size());
        Probe.Stat n = m.get("com.netflix.ninja");
        assertEquals(2, n.watchNext);
        assertEquals(1, n.preview);
        assertEquals(1, n.withPosition);
        assertEquals(20L, n.newestEngaged);
        assertTrue(n.intentKinds.contains("https") && n.intentKinds.contains("netflix"));
        assertTrue(n.posterSchemes.contains("https") && n.posterSchemes.contains("content"));
        assertTrue(m.get("org.jellyfin.androidtv").intentKinds.contains("intent (scheme=jf, component)"));
        assertTrue(m.containsKey("(unknown)"));
        for (String line : Probe.logLines(m, true)) {
            assertTrue(line, !line.contains("title/1") && !line.contains("img/"));
        }
    }

    @Test public void probeResultSurvivesTheTripThroughPreferences() {
        List<Probe.Sample> s = new ArrayList<>();
        s.add(sample(true, "com.netflix.ninja", "https://x", null, 10, false));
        s.add(sample(false, "com.netflix.ninja", "https://x", null, 10, false));
        s.add(sample(false, "com.google.android.youtube.tv", "https://x", null, 0, false));
        Map<String, int[]> back = Probe.decode(Probe.encode(Probe.summarize(s)));
        assertEquals(2, back.size());
        assertEquals(1, back.get("com.netflix.ninja")[0]);
        assertEquals(1, back.get("com.netflix.ninja")[1]);
        assertEquals(0, back.get("com.google.android.youtube.tv")[0]);
        assertEquals(0, Probe.decode("not json").size());
        assertEquals(0, Probe.decode("").size());
    }

    private static Probe.Sample sample(boolean wn, String pkg, String intent, String poster, long engaged, boolean pos) {
        Probe.Sample x = new Probe.Sample();
        x.watchNext = wn;
        x.pkg = pkg;
        x.intentUri = intent;
        x.posterUri = poster;
        x.engagedMs = engaged;
        x.hasPosition = pos;
        x.title = "Secret title";
        return x;
    }

    @Test public void textHelpers() {
        assertEquals("thebear2022", Text.normalizeTitle("The Bear (2022)"));
        assertEquals(2, Text.digits("Season 2"));
        assertEquals(-1, Text.digits("Special"));
        assertEquals("S2 · E4", Text.episodeLabel(2, 4));
        assertEquals("S1 · E14", Text.episodeLabel(1, 14));
        assertEquals("Special", Text.episodeLabel(0, 1));
        assertEquals("Special", Text.episodeLabel(0, -1));
        assertNull(Text.episodeLabel(2, 0));
        assertNull(Text.episodeLabel(2, -1));
        assertNull(Text.episodeLabel(-1, 3));
        assertNull(Text.episodeLabel(-1, -1));
        assertEquals("23 min left", Text.left(23 * 60_000L - 10));
        assertEquals("1 h 11 min left", Text.left(71 * 60_000L));
        assertEquals("2 h left", Text.left(120 * 60_000L));
        assertEquals("just now", Text.ago(20_000L));
        assertEquals("5 min ago", Text.ago(5 * 60_000L + 1));
        assertEquals("3 h ago", Text.ago(3 * 3_600_000L));
        assertEquals("2 d ago", Text.ago(49 * 3_600_000L));
        assertEquals("https", Text.scheme("HTTPS://x"));
        assertEquals("", Text.scheme("no scheme"));
    }
}
