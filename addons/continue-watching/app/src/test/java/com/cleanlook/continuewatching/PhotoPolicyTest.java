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

    @Test public void aFetchNeedsTheSwitchAndAConnection() {
        assertFalse(PhotoPolicy.mayFetch(false, true, true, true, 0));
        assertFalse(PhotoPolicy.mayFetch(true, false, false, true, 0));
        assertTrue(PhotoPolicy.mayFetch(true, true, true, false, 4));
    }

    @Test public void automaticFetchesWaitForAnUnmeteredLineUnlessThereIsNothingToShow() {
        assertFalse("metered, ring has photos", PhotoPolicy.mayFetch(true, true, false, false, 4));
        assertFalse("metered, two photos is enough", PhotoPolicy.mayFetch(true, true, false, false, PhotoCache.MIN_READY));
        assertTrue("metered but nearly empty", PhotoPolicy.mayFetch(true, true, false, false, PhotoCache.MIN_READY - 1));
        assertTrue("the owner asked", PhotoPolicy.mayFetch(true, true, false, true, 4));
    }

    @Test public void aFailureRestsAutomaticFetchesLongerEachTime() {
        assertEquals(0, PhotoPolicy.failWaitMs(0, 5000));
        assertEquals(PhotoPolicy.FAIL_WAIT_MS, PhotoPolicy.failWaitMs(1, 0));
        assertEquals(2 * PhotoPolicy.FAIL_WAIT_MS, PhotoPolicy.failWaitMs(2, 0));
        assertEquals(4 * PhotoPolicy.FAIL_WAIT_MS, PhotoPolicy.failWaitMs(3, 0));
        assertEquals("the server's Retry-After counts", 60_000, PhotoPolicy.failWaitMs(1, 60_000));
        assertEquals(PhotoPolicy.FAIL_WAIT_MAX_MS, PhotoPolicy.failWaitMs(50, 0));
    }

    @Test public void requestsAreSpacedAtLeastFiveSeconds() {
        assertTrue(PhotoPolicy.SPACING_MS >= 5000);
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

    @Test public void statusSaysFetchingForAtMostThirtySeconds() {
        assertEquals("Fetching...", PhotoPolicy.status(true, 0, false, 4, ""));
        assertEquals("Fetching...", PhotoPolicy.status(true, 29_999, false, 4, "No network"));
        assertEquals("Ready: 4 photos", PhotoPolicy.status(true, 30_000, false, 4, ""));
    }

    @Test public void aFetchPastThirtySecondsSaysWhatItIsWaitingFor() {
        assertEquals("Server busy, retrying", PhotoPolicy.status(true, 31_000, true, 4, ""));
        assertEquals("Fetching...", PhotoPolicy.status(true, 5_000, true, 4, ""));
    }

    @Test public void statusEndsInReadyOrAPlainFailure() {
        assertEquals("Off", PhotoPolicy.status(false, 1000, false, 4, "No network"));
        assertEquals("No network", PhotoPolicy.status(true, -1, false, 3, "No network"));
        assertEquals("No photos yet", PhotoPolicy.status(true, -1, false, 0, ""));
        assertEquals("Ready: 1 photo", PhotoPolicy.status(true, -1, false, 1, null));
        assertEquals("Ready: 4 photos", PhotoPolicy.status(true, -1, false, 4, ""));
    }
}
