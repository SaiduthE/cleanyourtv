package com.cleanlook.continuewatching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.Test;

public class AddedSourcesTest {
    private static ChannelFilter.Info chan(String pkg, String name, int rows) {
        ChannelFilter.Info n = new ChannelFilter.Info();
        n.pkg = pkg;
        n.name = name;
        n.rows = rows;
        n.total = rows;
        return n;
    }

    private static Map<String, int[]> probe(Object... kv) {
        Map<String, int[]> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 3) m.put((String) kv[i], new int[] {(Integer) kv[i + 1], (Integer) kv[i + 2]});
        return m;
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }

    // ---- the saved list and its order

    @Test public void listKeepsTheOrderItWasAddedInWithoutDuplicates() {
        List<String> l = new ArrayList<>();
        l = AddedSources.with(l, "b");
        l = AddedSources.with(l, "a");
        l = AddedSources.with(l, "b");
        l = AddedSources.with(l, "");
        assertEquals(Arrays.asList("b", "a"), l);
        assertEquals(Arrays.asList("b", "a"), AddedSources.parse(AddedSources.join(l)));
        assertEquals(Collections.singletonList("a"), AddedSources.without(l, "b"));
        assertTrue(AddedSources.parse(null).isEmpty());
        assertEquals(Arrays.asList("x", "y"), AddedSources.parse("x\n\n y \nx"));
    }

    @Test public void switchEntriesOfARemovedPackageAreDropped() {
        Set<String> in = set("a|continue", "a|trending", "ab|continue", "b|x");
        assertEquals(set("ab|continue", "b|x"), AddedSources.withoutPackage(in, "a"));
    }

    // ---- migration

    @Test public void freshInstallMigratesToNothing() {
        assertTrue(AddedSources.migrate(false, new TreeMap<String, int[]>(), new ArrayList<String>(),
                new ArrayList<ChannelFilter.Info>(), set(), set(), set(), "me").isEmpty());
    }

    @Test public void existingAppsWithRowsAndNotSwitchedOffStayAdded_mostRowsFirst_jellyfinFirst() {
        Map<String, int[]> p = probe("small", 1, 0, "big", 9, 0, "off", 5, 0, "none", 0, 0, "me", 3, 0, "(unknown)", 2, 0);
        List<String> out = AddedSources.migrate(true, p, new ArrayList<String>(), new ArrayList<ChannelFilter.Info>(),
                set("off"), set(), set(), "me");
        assertEquals(Arrays.asList(AddedSources.JELLYFIN, "big", "small"), out);
    }

    @Test public void anAppWithAnEnabledChannelCountsAndAnAppWithOnlyOffChannelsDoesNot() {
        List<ChannelFilter.Info> chans = Arrays.asList(chan("plex", "Continue Watching", 4), chan("plex", "Trending", 8),
                chan("netflix", "Top picks", 6), chan("hulu", "Top picks", 6));
        Set<String> forcedOn = set(ChannelFilter.key("hulu", "Top picks"));
        List<String> out = AddedSources.migrate(false, new TreeMap<String, int[]>(), null, chans, set(), forcedOn, set(), "me");
        assertEquals(Arrays.asList("hulu", "plex"), out);
        Set<String> forcedOff = set(ChannelFilter.key("plex", "Continue Watching"));
        assertEquals(Collections.singletonList("hulu"),
                AddedSources.migrate(false, new TreeMap<String, int[]>(), null, chans, set(), forcedOn, forcedOff, "me"));
    }

    @Test public void appsKnownOnlyFromTheSavedRowsStayAddedToo() {
        List<String> out = AddedSources.migrate(false, new TreeMap<String, int[]>(), Arrays.asList("a", "a", "b"),
                new ArrayList<ChannelFilter.Info>(), set("b"), set(), set(), "me");
        assertEquals(Collections.singletonList("a"), out);
    }

    // ---- the added-sources model decides what shows

    private static Item item(String id, String pkg, String channel) {
        Item it = new Item();
        it.id = id;
        it.pkg = pkg;
        it.title = id;
        it.intentUri = "link-" + id;
        it.channel = channel;
        return it;
    }

    @Test public void onlyAddedSourcesShowAndTheirOwnSwitchHidesAllOfThem() {
        Store.State s = new Store.State();
        s.wn.add(item("w1", "a", ""));
        s.wn.add(item("w2", "b", ""));
        s.ch.add(item("c1", "b", "Continue watching"));
        s.ch.add(item("c2", "c", "Continue watching"));
        s.jf.add(item("j1", Item.JELLYFIN_PKG, ""));
        assertEquals(5, Store.visible(s, set(), null, null).size());
        List<Item> some = Store.visible(s, set(), null, null, Arrays.asList("b", AddedSources.JELLYFIN));
        assertEquals(3, some.size());
        assertTrue(Store.find(some, "w2") != null && Store.find(some, "j1") != null);
        assertEquals("nothing added, nothing shown", 0, Store.visible(s, set(), null, null, new ArrayList<String>()).size());
        assertEquals(1, Store.visible(s, set("b", AddedSources.JELLYFIN), null, null, Arrays.asList("b", AddedSources.JELLYFIN, "a")).size());
    }

    // ---- the picker

    @Test public void pickerListsAppsWithRowsMostFirstAndMarksAddedOnes() {
        Map<String, int[]> p = probe("amazon", 2, 0, "plex", 0, 30, "empty", 0, 0, "gone", 9, 9, "me", 5, 5, "z", 2, 0);
        List<ChannelFilter.Info> chans = Arrays.asList(chan("plex", "Continue", 5), chan("netflix", "Continue", 7));
        Map<String, String> labels = new HashMap<>();
        labels.put("amazon", "Prime Video");
        labels.put("plex", "Plex");
        labels.put("netflix", "Netflix");
        labels.put("z", "Apple TV");
        Set<String> installed = set("amazon", "plex", "empty", "me", "netflix", "z");
        List<AddedSources.Candidate> out = AddedSources.candidates(p, chans, Collections.singletonList("plex"), installed, labels, "me");
        assertEquals(4, out.size());
        assertEquals("netflix", out.get(0).pkg);
        assertEquals("plex", out.get(1).pkg);
        assertTrue(out.get(1).added);
        assertEquals("channel rows win over the raw preview count", 5, out.get(1).rows);
        assertEquals("ties by name", "Apple TV", out.get(2).name);
        assertEquals("Prime Video", out.get(3).name);
        assertFalse(out.get(0).added);
    }

    @Test public void showAllListsTheRestByNameWithoutRepeats() {
        Map<String, String> labels = new HashMap<>();
        labels.put("b", "Beta");
        labels.put("a", "alpha");
        List<AddedSources.Candidate> out = AddedSources.others(Arrays.asList("b", "a", "listed", "me", "c"),
                Collections.singletonList("listed"), Collections.singletonList("b"), labels, "me");
        assertEquals(3, out.size());
        assertEquals("alpha", out.get(0).name);
        assertEquals("Beta", out.get(1).name);
        assertTrue(out.get(1).added);
        assertEquals("a name falls back to the package", "c", out.get(2).name);
    }

    // ---- status lines

    @Test public void appStatusIsHonest() {
        assertEquals(AddedSources.Level.ROWS, AddedSources.appStatus(true, true, true, 3, false).level);
        assertEquals("3 rows", AddedSources.appStatus(true, true, true, 3, false).text);
        assertEquals("1 row", AddedSources.appStatus(true, true, true, 1, false).text);
        assertEquals("No rows found", AddedSources.appStatus(true, true, true, 0, false).text);
        assertEquals(AddedSources.Level.NONE, AddedSources.appStatus(true, true, true, 0, false).level);
        assertEquals(AddedSources.Level.ERROR, AddedSources.appStatus(true, true, true, 0, true).level);
        assertEquals(AddedSources.Level.OFF, AddedSources.appStatus(false, true, true, 3, false).level);
        assertEquals(AddedSources.Level.ERROR, AddedSources.appStatus(true, false, true, 0, false).level);
        assertEquals("Not scanned", AddedSources.appStatus(true, true, false, 0, false).text);
    }

    @Test public void jellyfinStatus() {
        assertEquals("4 titles", AddedSources.jellyfinStatus(true, true, Prefs.JF_OK, 4).text);
        assertEquals(AddedSources.Level.NONE, AddedSources.jellyfinStatus(true, true, Prefs.JF_OK, 0).level);
        assertEquals(AddedSources.Level.ERROR, AddedSources.jellyfinStatus(true, true, Prefs.JF_SIGN_IN, 0).level);
        assertEquals("Not reachable", AddedSources.jellyfinStatus(true, true, Prefs.JF_UNREACHABLE, 0).text);
        assertEquals(AddedSources.Level.OFF, AddedSources.jellyfinStatus(false, true, Prefs.JF_OK, 4).level);
        assertEquals(AddedSources.Level.ERROR, AddedSources.jellyfinStatus(true, false, Prefs.JF_NONE, 0).level);
    }

    // ---- pictures and placement

    @Test public void imagesAreNeverEnlargedPastOneAndAHalfTimes() {
        assertEquals(96, AddedSources.fitPx(192, 96));
        assertEquals(72, AddedSources.fitPx(48, 96));
        assertEquals(96, AddedSources.fitPx(0, 96));
        assertTrue(AddedSources.bannerFits(640, 200));
        assertTrue("exactly 1.5 times is allowed", AddedSources.bannerFits(100, 150));
        assertFalse(AddedSources.bannerFits(100, 151));
        assertFalse(AddedSources.bannerFits(0, 200));
    }

    @Test public void popupSitsUnderTheTileAndFlipsAtTheEdges() {
        // 960 x 540 screen, 12 margin, 6 gap, popup 264 x 200
        assertArrayEquals(new int[] {100, 186}, AddedSources.popupOrigin(100, 80, 228, 180, 264, 200, 960, 540, 12, 6));
        int[] right = AddedSources.popupOrigin(800, 80, 928, 180, 264, 200, 960, 540, 12, 6);
        assertEquals("flips left: right edges line up", 928 - 264, right[0]);
        int[] low = AddedSources.popupOrigin(100, 400, 228, 500, 264, 200, 960, 540, 12, 6);
        assertEquals("flips above the tile", 400 - 6 - 200, low[1]);
        int[] tall = AddedSources.popupOrigin(100, 100, 228, 200, 264, 600, 960, 540, 12, 6);
        assertEquals("never leaves the screen", 12, tall[1]);
        int[] corner = AddedSources.popupOrigin(900, 500, 958, 530, 264, 200, 960, 540, 12, 6);
        assertTrue(corner[0] >= 12 && corner[0] + 264 <= 960 - 12);
        assertTrue(corner[1] >= 12 && corner[1] + 200 <= 540 - 12);
    }
}
