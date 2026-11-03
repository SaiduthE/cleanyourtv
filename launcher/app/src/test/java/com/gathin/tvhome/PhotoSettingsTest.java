package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PhotoSettingsTest {

    private static final List<String> KEYS = Arrays.asList("landscapes", "mountains", "coasts", "lakes", "forests",
            "skies", "sunsets");
    private static final List<String> LABELS = Arrays.asList("Landscapes", "Mountains", "Coasts and seas", "Lakes",
            "Forests", "Clouds and skies", "Sunsets");

    private static Set<String> set(String... keys) {
        return new LinkedHashSet<>(Arrays.asList(keys));
    }

    private static PhotoSettings ready(boolean enabled, Set<String> on, int count, boolean refreshing, String status) {
        return PhotoSettings.ready(enabled, KEYS, LABELS, on, count, refreshing, status, "");
    }

    private static final Set<String> ALL = set("landscapes", "mountains", "coasts", "lakes", "forests", "skies", "sunsets");

    // ---- at least one category stays on

    @Test public void aCategoryCanBeSwitchedOffAndOnAgain() {
        Set<String> off = PhotoSettings.toggled(KEYS, ALL, "lakes");
        assertEquals(6, off.size());
        assertFalse(off.contains("lakes"));
        Set<String> back = PhotoSettings.toggled(KEYS, off, "lakes");
        assertEquals(ALL, back);
        assertEquals("the add-on's order is kept", KEYS, Arrays.asList(back.toArray(new String[0])));
    }

    @Test public void theLastCategoryOnCannotBeSwitchedOff() {
        assertNull(PhotoSettings.toggled(KEYS, set("sunsets"), "sunsets"));
        assertEquals(set("sunsets", "lakes").size(), PhotoSettings.toggled(KEYS, set("sunsets"), "lakes").size());
    }

    @Test public void anUnknownCategoryChangesNothing() {
        assertNull(PhotoSettings.toggled(KEYS, ALL, "cats"));
    }

    @Test public void toggleDoesNotChangeTheInput() {
        Set<String> on = set("lakes", "forests");
        PhotoSettings.toggled(KEYS, on, "lakes");
        assertEquals(set("lakes", "forests"), on);
    }

    // ---- row values

    @Test public void photosRowShowsOnOrOff() {
        assertEquals("On", ready(true, ALL, 12, false, "").photosValue());
        assertEquals("Off", ready(false, ALL, 0, false, "Off").photosValue());
    }

    @Test public void themesRowSaysAllOrHowMany() {
        assertEquals("All", ready(true, ALL, 12, false, "").themesValue());
        assertEquals("3 of 7", ready(true, set("lakes", "forests", "skies"), 12, false, "").themesValue());
        assertEquals("1 of 7", ready(true, set("lakes"), 12, false, "").themesValue());
    }

    @Test public void refreshRowShowsTheAddOnsStatusLine() {
        assertEquals("Ready: 4 photos",
                ready(true, ALL, 12, false, "Ready: 4 photos").refreshValue());
        assertEquals("No network", ready(true, ALL, 0, false, "No network").refreshValue());
        assertEquals("Timed out", ready(true, ALL, 3, false, "Timed out").refreshValue());
    }

    @Test public void refreshRowSaysFetchingWhileItRuns() {
        assertEquals("Fetching...", ready(true, ALL, 12, true, "Ready: 4 photos").refreshValue());
        assertEquals("Fetching...", ready(true, ALL, 12, false, "x").withRefreshing().refreshValue());
    }

    @Test public void refreshRowWithoutAStatusCountsThePhotos() {
        assertEquals("No photos yet", ready(true, ALL, 0, false, "").refreshValue());
        assertEquals("Ready: 1 photo", ready(true, ALL, 1, false, "").refreshValue());
        assertEquals("Ready: 4 photos", ready(true, ALL, 4, false, "").refreshValue());
    }

    @Test public void refreshRowIsOffWithPhotosOff() {
        assertEquals("Off", ready(false, ALL, 0, false, "").refreshValue());
    }

    @Test public void everyPhotoRowSaysWhyWhenTheAddOnIsMissing() {
        PhotoSettings missing = PhotoSettings.unavailable(PhotoSettings.State.NOT_INSTALLED);
        assertFalse(missing.available());
        assertEquals("Add-on not installed", missing.photosValue());
        assertEquals("Add-on not installed", missing.themesValue());
        assertEquals("Add-on not installed", missing.refreshValue());
    }

    @Test public void otherUnavailableStatesHaveTheirOwnText() {
        assertEquals("Update the add-on", PhotoSettings.unavailable(PhotoSettings.State.OUTDATED).photosValue());
        assertEquals("Not allowed", PhotoSettings.unavailable(PhotoSettings.State.DENIED).photosValue());
        assertEquals("No answer", PhotoSettings.unavailable(PhotoSettings.State.NO_ANSWER).photosValue());
        assertEquals("Loading…", PhotoSettings.unavailable(PhotoSettings.State.LOADING).refreshValue());
        assertTrue(ready(true, ALL, 1, false, "").available());
    }

    // ---- shown at once while the add-on is asked

    @Test public void optimisticChangesKeepTheRest() {
        PhotoSettings s = ready(true, ALL, 12, false, "Ready: 4 photos");
        PhotoSettings off = s.withEnabled(false);
        assertFalse(off.enabled);
        assertEquals(12, off.count);
        PhotoSettings fewer = s.withOn(set("lakes"));
        assertEquals(set("lakes"), fewer.on);
        assertTrue(fewer.enabled);
        assertEquals("Ready: 4 photos", fewer.refreshValue());
    }
}
