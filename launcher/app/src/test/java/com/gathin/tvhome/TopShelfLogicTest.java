package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.TopShelfLogic.Content;
import com.gathin.tvhome.TopShelfLogic.Entry;
import com.gathin.tvhome.TopShelfLogic.Mode;
import com.gathin.tvhome.TopShelfLogic.Shelf;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class TopShelfLogicTest {

    private static final String PIC = "content://com.cleanlook.continuewatching.shelf/img/a.jpg";

    private static Entry row(String kind, String title, String sub, int progress, String pkg) {
        return new Entry(kind, title, sub, progress, pkg, "intent:#Intent;end", PIC, null);
    }

    @Test public void modeDefaultsToLayeredAndParsesTheSavedKey() {
        assertEquals(Mode.LAYERED, Mode.parse(null));
        assertEquals(Mode.LAYERED, Mode.parse("layered"));
        assertEquals(Mode.LAYERED, Mode.parse("nonsense"));
        assertEquals(Mode.OFF, Mode.parse("off"));
        assertEquals("off", Mode.OFF.key);
    }

    @Test public void offAlwaysShowsTheWallpaper() {
        assertEquals(Content.REST, TopShelfLogic.select(Mode.OFF, true, true, true, true));
    }

    @Test public void focusOffTheDockShowsTheWallpaper() {
        assertEquals(Content.REST, TopShelfLogic.select(Mode.LAYERED, false, true, true, true));
    }

    @Test public void aShowToResumeWinsWhenThereIsRoom() {
        assertEquals(Content.RESUME, TopShelfLogic.select(Mode.LAYERED, true, true, true, true));
        assertEquals(Content.RESUME, TopShelfLogic.select(Mode.LAYERED, true, true, true, false));
    }

    @Test public void withoutRoomOrShowTheAppsArtworkStandsIn() {
        assertEquals(Content.ARTWORK, TopShelfLogic.select(Mode.LAYERED, true, true, false, true));
        assertEquals(Content.ARTWORK, TopShelfLogic.select(Mode.LAYERED, true, false, true, true));
    }

    @Test public void withNothingAtAllTheWallpaperStays() {
        assertEquals(Content.REST, TopShelfLogic.select(Mode.LAYERED, true, false, true, false));
        assertEquals(Content.REST, TopShelfLogic.select(Mode.LAYERED, true, true, false, false));
    }

    @Test public void roomNeedsTheResumeBlockHeight() {
        assertFalse(TopShelfLogic.roomForResume(TopShelfLogic.RESUME_MIN_DP - 1));
        assertTrue(TopShelfLogic.roomForResume(TopShelfLogic.RESUME_MIN_DP));
    }

    @Test public void timingsAreTheOnesTheOwnerAsked() {
        assertEquals(600, TopShelfLogic.DWELL_MS);
        assertEquals(1500, TopShelfLogic.LEAVE_MS);
        assertEquals(60_000, TopShelfLogic.CACHE_MS);
    }

    @Test public void shelfTakesThePartwayRowThenUpToTwoNext() {
        Shelf s = TopShelfLogic.shelf(Arrays.asList(
                row("next", "Show A", "S1 · E2", 0, "p"),
                row("resume", "Show B", "S2 · E4", 62, "p"),
                row("next", "Show C", "S1 · E1", 0, "p"),
                row("next", "Show D", "S3 · E1", 0, "p")), "p");
        assertNotNull(s);
        assertEquals("Show B", s.main.title);
        assertEquals(2, s.upNext.size());
        assertEquals("Show A", s.upNext.get(0).title);
        assertEquals("Show C", s.upNext.get(1).title);
    }

    @Test public void withoutAPartwayRowTheFirstRowLeads() {
        Shelf s = TopShelfLogic.shelf(Arrays.asList(row("next", "A", "", 0, "p"), row("next", "B", "", 0, "p")), "p");
        assertEquals("A", s.main.title);
        assertEquals(1, s.upNext.size());
        assertEquals("B", s.upNext.get(0).title);
        assertFalse(TopShelfLogic.isResume(s.main));
    }

    @Test public void rowsOfAnotherAppOrWithoutATitleAreIgnored() {
        Shelf s = TopShelfLogic.shelf(Arrays.asList(row("resume", "Other", "", 10, "q"), row("resume", "  ", "", 10, "p"),
                row("resume", "Mine", "", 10, "p")), "p");
        assertEquals("Mine", s.main.title);
        assertTrue(s.upNext.isEmpty());
    }

    @Test public void nothingUsableGivesNoShelf() {
        assertNull(TopShelfLogic.shelf(null, "p"));
        assertNull(TopShelfLogic.shelf(Collections.emptyList(), "p"));
        assertNull(TopShelfLogic.shelf(Collections.singletonList(row("resume", "X", "", 1, "q")), "p"));
    }

    @Test public void progressIsClampedAndResumeIsSaidForAnyProgress() {
        assertEquals(100, row("resume", "X", "", 250, "p").progress);
        assertEquals(0, row("resume", "X", "", -5, "p").progress);
        assertTrue(TopShelfLogic.isResume(row("next", "X", "", 40, "p")));
    }

    @Test public void onlyTheAddOnsOwnPicturesAreOpened() {
        assertTrue(TopShelfLogic.ownUri(PIC));
        assertFalse(TopShelfLogic.ownUri("content://com.other.provider/img/a.jpg"));
        assertFalse(TopShelfLogic.ownUri("file:///sdcard/a.jpg"));
        assertFalse(TopShelfLogic.ownUri(null));
        Entry e = new Entry("resume", "T", "", 0, "p", null, "content://evil/x", PIC);
        assertEquals(PIC, e.image());   // the foreign backdrop is skipped, the poster is the add-on's own
        assertNull(new Entry("resume", "T", "", 0, "p", null, null, null).image());
    }

    @Test public void theRowsKeepTheirOrderObject() {
        Entry a = row("resume", "A", "", 1, "p");
        assertSame(a, TopShelfLogic.shelf(Collections.singletonList(a), "p").main);
    }
}
