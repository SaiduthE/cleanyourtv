package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class PhotoPolicyTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 100 * DAY;

    @Test public void automaticRefreshNeedsAnUnmeteredConnectionAndThreeDays() {
        // on, connected, unmetered, automatic, 4 days since the last good one, 2 days since the last try, 15 cached
        assertTrue(PhotoPolicy.due(true, true, true, false, NOW, NOW - 4 * DAY, NOW - 2 * DAY, 15));
        assertFalse("only two days", PhotoPolicy.due(true, true, true, false, NOW, NOW - 2 * DAY, NOW - 2 * DAY, 15));
        assertFalse("metered", PhotoPolicy.due(true, true, false, false, NOW, NOW - 4 * DAY, NOW - 2 * DAY, 15));
        assertFalse("offline", PhotoPolicy.due(true, false, false, false, NOW, NOW - 4 * DAY, NOW - 2 * DAY, 15));
        assertFalse("switched off", PhotoPolicy.due(false, true, true, false, NOW, NOW - 4 * DAY, NOW - 2 * DAY, 15));
    }

    @Test public void aJobThatFiresAFewMinutesEarlyStillCounts() {
        assertTrue(PhotoPolicy.due(true, true, true, false, NOW, NOW - 3 * DAY + 5 * 60_000, NOW - 3 * DAY, 15));
        assertFalse(PhotoPolicy.due(true, true, true, false, NOW, NOW - 3 * DAY + 5 * 3_600_000L, NOW - 3 * DAY, 15));
    }

    @Test public void aFreshInstallFillsAtOnceWithoutWaitingForTheSchedule() {
        assertTrue(PhotoPolicy.due(true, true, true, false, NOW, 0, 0, 0));
        assertTrue("a good refresh an hour ago but only 3 photos", PhotoPolicy.due(true, true, true, false, NOW, NOW - 3_600_000L * 2, 0, 3));
        assertFalse("still needs the network", PhotoPolicy.due(true, false, false, false, NOW, 0, 0, 0));
        assertFalse("six is enough to wait", PhotoPolicy.due(true, true, true, false, NOW, NOW - DAY, NOW - DAY, PhotoPolicy.FIRST_FILL));
    }

    @Test public void aFailedTryWaitsAnHourBeforeTheNext() {
        assertFalse(PhotoPolicy.due(true, true, true, false, NOW, 0, NOW - 10 * 60_000L, 0));
        assertTrue(PhotoPolicy.due(true, true, true, false, NOW, 0, NOW - 61 * 60_000L, 0));
    }

    @Test public void aClockThatWentBackwardsCountsAsDue() {
        assertTrue(PhotoPolicy.due(true, true, true, false, NOW, NOW + 10 * DAY, 0, 15));
        assertTrue("and does not block on a try from the future", PhotoPolicy.due(true, true, true, false, NOW, 0, NOW + DAY, 0));
    }

    @Test public void refreshNowNeedsOnlyAConnectionAndTheSwitch() {
        assertTrue(PhotoPolicy.due(true, true, false, true, NOW, NOW - 1000, NOW - 1000, 15));
        assertFalse(PhotoPolicy.due(true, false, false, true, NOW, 0, 0, 0));
        assertFalse(PhotoPolicy.due(false, true, true, true, NOW, 0, 0, 0));
    }

    // ---- categories

    @Test public void categoryKeysAreUniqueAndPointAtFeaturedPictures() {
        Set<String> keys = new HashSet<>();
        for (PhotoPolicy.Category c : PhotoPolicy.CATEGORIES) {
            assertTrue(keys.add(c.key));
            assertTrue(c.commons, c.commons.startsWith("Category:Featured pictures of "));
        }
    }

    @Test public void theLastCategoryCannotBeSwitchedOff() {
        Set<String> off = new HashSet<>();
        for (int i = 0; i < PhotoPolicy.CATEGORIES.length - 1; i++) off = PhotoPolicy.toggle(off, PhotoPolicy.CATEGORIES[i].key);
        assertEquals(PhotoPolicy.CATEGORIES.length - 1, off.size());
        Set<String> same = PhotoPolicy.toggle(off, PhotoPolicy.CATEGORIES[PhotoPolicy.CATEGORIES.length - 1].key);
        assertEquals(off, same);
        // pressing one that is off turns it back on
        Set<String> back = PhotoPolicy.toggle(off, PhotoPolicy.CATEGORIES[0].key);
        assertEquals(PhotoPolicy.CATEGORIES.length - 2, back.size());
        assertEquals(1, PhotoPolicy.enabled(off).length);
    }

    @Test public void enabledAlwaysHasAtLeastOneCategory() {
        Set<String> all = new HashSet<>();
        for (PhotoPolicy.Category c : PhotoPolicy.CATEGORIES) all.add(c.key);
        assertEquals(1, PhotoPolicy.enabled(all).length);
        assertEquals(PhotoPolicy.CATEGORIES.length, PhotoPolicy.enabled(new HashSet<String>()).length);
    }

    // ---- the status line

    @Test public void statusSaysWhatHappened() {
        assertEquals("Refreshing…", PhotoPolicy.status(true, 12, NOW, NOW, ""));
        assertEquals("No network", PhotoPolicy.status(false, 3, NOW, NOW, "No network"));
        assertEquals("No photos yet", PhotoPolicy.status(false, 0, 0, NOW, ""));
        assertEquals("1 photo, updated today", PhotoPolicy.status(false, 1, NOW - 1000, NOW, ""));
        assertEquals("12 photos, updated yesterday", PhotoPolicy.status(false, 12, NOW - DAY, NOW, null));
        assertEquals("15 photos, updated 3 days ago", PhotoPolicy.status(false, 15, NOW - 3 * DAY, NOW, ""));
        assertEquals("5 photos", PhotoPolicy.status(false, 5, 0, NOW, ""));
    }
}
