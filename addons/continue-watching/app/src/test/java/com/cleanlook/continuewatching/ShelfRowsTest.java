package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class ShelfRowsTest {
    private static Item item(String id, String pkg, String title, int kind, long upd, float progress) {
        Item it = new Item();
        it.id = id;
        it.pkg = pkg;
        it.title = title;
        it.kind = kind;
        it.updatedMs = upd;
        it.progress = progress;
        if (progress >= 0) {
            it.durationMs = 3_600_000;
            it.positionMs = (long) (3_600_000 * progress);
        }
        it.season = 2;
        it.episode = 4;
        return it;
    }

    private static final ShelfRows.Images IMAGES = new ShelfRows.Images() {
        @Override public String uriFor(Item it, String kind) {
            if (!it.id.startsWith("jf:") || kind.equals(Contract.IMG_LOGO)) return null;
            return "content://x/img/" + kind + ".jpg";
        }
    };
    private static final ShelfRows.Links LINKS = new ShelfRows.Links() {
        @Override public String intentFor(Item it) {
            return "intent:#Intent;package=" + it.pkg + ";end";
        }
    };
    private static final ShelfRows.Labels LABELS = new ShelfRows.Labels() {
        @Override public String labelFor(String pkg) {
            return "App " + pkg;
        }
    };

    @Test public void buildsTheColumnsTheLauncherReads() {
        List<Item> in = new ArrayList<>();
        in.add(item("jf:1", "org.jellyfin.androidtv", "Severance", Item.RESUME, 5000, 0.25f));
        List<ShelfRows.Row> rows = ShelfRows.build(in, null, 9999, IMAGES, LABELS, LINKS);
        assertEquals(1, rows.size());
        ShelfRows.Row r = rows.get(0);
        assertEquals("jf:1", r.itemId);
        assertEquals("resume", r.kind);
        assertEquals("Severance", r.title);
        assertEquals("S2 · E4", r.subtitle);
        assertEquals(25, r.progress);
        assertEquals(2_700_000L, r.remainingMs);
        assertEquals(5000L, r.updatedMs);
        assertEquals("content://x/img/backdrop.jpg", r.image);
        assertEquals("content://x/img/backdrop.jpg", r.backdrop);
        assertEquals("content://x/img/poster.jpg", r.poster);
        assertNull(r.logo);
        assertEquals("intent:#Intent;package=org.jellyfin.androidtv;end", r.intentUri);
        assertEquals("App org.jellyfin.androidtv", r.sourceLabel);
        assertEquals(9999L, r.fetchedMs);
    }

    @Test public void ordersByWeightWhenNoWatchTimeIsKnown() {
        List<Item> in = new ArrayList<>();
        Item low = item("ch:1", "p", "Low", Item.RESUME, 0, -1f);
        low.weight = 1;
        Item high = item("ch:2", "p", "High", Item.RESUME, 0, -1f);
        high.weight = 9;
        in.add(low);
        in.add(high);
        List<ShelfRows.Row> rows = ShelfRows.build(in, "p", 0, null, null, null);
        assertEquals("High", rows.get(0).title);
        assertEquals(0, rows.get(0).progress);
    }

    @Test public void aPackageQueryReturnsAllItsItemsUpToTheLimit() {
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 15; i++) in.add(item("ch:" + i, "p", "Title " + i, Item.NEXT, 0, -1f));
        assertEquals(ShelfRows.MAX_FOR_PACKAGE, ShelfRows.build(in, "p", 0, null, null, null).size());
        assertEquals(ShelfRows.MAX_PER_PACKAGE, ShelfRows.build(in, null, 0, null, null, null).size());
    }

    @Test public void backdropsNarrowerThan320AreLeftOut() {
        assertEquals(ShelfImages.PICK_BACKDROP, ShelfImages.pickBackdrop(960, 0, 0));
        assertEquals(ShelfImages.PICK_NONE, ShelfImages.pickBackdrop(319, 0, 0));
        assertEquals(ShelfImages.PICK_BACKDROP, ShelfImages.pickBackdrop(320, 0, 0));
        assertEquals("portrait poster is never a backdrop", ShelfImages.PICK_BACKDROP, ShelfImages.pickBackdrop(500, 800, 1200));
        assertEquals("a wider landscape poster wins", ShelfImages.PICK_POSTER, ShelfImages.pickBackdrop(400, 960, 540));
        assertEquals("no backdrop, wide poster", ShelfImages.PICK_POSTER, ShelfImages.pickBackdrop(0, 600, 338));
        assertEquals("small landscape poster", ShelfImages.PICK_NONE, ShelfImages.pickBackdrop(0, 300, 169));
        assertEquals(ShelfImages.PICK_NONE, ShelfImages.pickBackdrop(0, 0, 0));
    }

    @Test public void filtersByPackageAndCapsAtThreePerApp() {
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 6; i++) in.add(item("wn:" + i, "com.netflix.ninja", "Title " + i, Item.RESUME, 1000 + i, 0.1f));
        in.add(item("wn:x", "com.plexapp.android", "Plex title", Item.RESUME, 5, 0.1f));
        assertEquals("a package query returns all of the app's items", 6,
                ShelfRows.build(in, "com.netflix.ninja", 0, IMAGES, LABELS, null).size());
        assertEquals(1, ShelfRows.build(in, "com.plexapp.android", 0, IMAGES, LABELS, null).size());
        assertEquals(0, ShelfRows.build(in, "com.none", 0, IMAGES, LABELS, null).size());
        // Unfiltered: three Netflix rows and the Plex row.
        assertEquals(4, ShelfRows.build(in, null, 0, IMAGES, LABELS, null).size());
    }

    @Test public void newestFirstWithResumeBeforeNext() {
        List<Item> in = new ArrayList<>();
        in.add(item("a", "p", "Old resume", Item.RESUME, 100, 0.5f));
        in.add(item("b", "p", "Next up", Item.NEXT, 900, -1f));
        in.add(item("c", "p", "New resume", Item.RESUME, 500, 0.5f));
        List<ShelfRows.Row> rows = ShelfRows.build(in, "p", 0, null, null, null);
        assertEquals("New resume", rows.get(0).title);
        assertEquals("Old resume", rows.get(1).title);
        assertEquals("next", rows.get(2).kind);
        assertEquals(0, rows.get(2).progress);
        assertEquals(-1L, rows.get(2).remainingMs);
        assertNull(rows.get(2).image);
    }

    @Test public void oneRowPerTitlePerApp() {
        List<Item> in = new ArrayList<>();
        in.add(item("a", "p", "Same Show", Item.NEXT, 900, -1f));
        in.add(item("b", "p", "Same show", Item.RESUME, 100, 0.5f));
        List<ShelfRows.Row> rows = ShelfRows.build(in, "p", 0, null, null, null);
        assertEquals(1, rows.size());
        assertEquals("b", rows.get(0).itemId);
    }

    @Test public void theRowTypeCarriesNoRemoteAddressOrToken() {
        // The row type is the contract: pictures are content:// URIs of cached files, never a server address or token.
        for (java.lang.reflect.Field f : ShelfRows.Row.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertTrue(f.getName(), !n.contains("url") && !n.contains("token") && !n.contains("server"));
        }
    }
}
