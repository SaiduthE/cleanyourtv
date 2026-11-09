package com.cleanlook.continuewatching;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PhotoCallsTest {

    private static final class Fake implements PhotoCalls.Backend {
        boolean enabled = true;
        Set<String> off = new HashSet<>();
        int count = 4;
        long now = 1_000_000L;
        String note = "";
        long flightMs = -1;
        boolean busy;
        int advances;
        int manualAdvances;
        String lastShown;
        int enables;

        @Override public boolean enabled() { return enabled; }
        @Override public void setEnabled(boolean on) { enabled = on; enables++; }
        @Override public Set<String> off() { return off; }
        @Override public void setOff(Set<String> o) { off = new HashSet<>(o); }
        @Override public int count() { return count; }
        @Override public String note() { return note; }
        @Override public long flightMs() { return flightMs; }
        @Override public boolean busy() { return busy; }
        @Override public void advance(String shown, boolean manual) {
            if (manual) manualAdvances++; else advances++;
            lastShown = shown;
            if (flightMs < 0) flightMs = 0;
        }
        @Override public long now() { return now; }
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static String[] strings(Map<String, Object> r, String key) {
        return (String[]) r.get(key);
    }

    // ---- the caller check

    @Test public void aCallerThatFailsTheShelfCheckGetsASecurityException() {
        for (String method : new String[] {PhotoCalls.GET_SETTINGS, PhotoCalls.SET_ENABLED, PhotoCalls.SET_CATEGORIES,
                PhotoCalls.REFRESH, PhotoCalls.ADVANCE, PhotoCalls.STATUS, "something_else", null}) {
            Fake f = new Fake();
            try {
                PhotoCalls.dispatch(false, f, method, with(PhotoCalls.EXTRA_ENABLED, false));
                fail("allowed " + method);
            } catch (SecurityException expected) {
                // the same refusal as query() and openFile()
            }
            assertTrue("nothing changed", f.enabled);
            assertEquals(0, f.manualAdvances + f.advances);
        }
    }

    @Test public void theCheckIsTheShelfOne() {
        // the launcher by package name passes, a stranger does not (ShelfAccess is what ShelfProvider.call uses)
        assertTrue(ShelfAccess.allowed(false, false, "com.gathin.tvhome"));
        assertFalse(ShelfAccess.allowed(false, false, "com.example.other"));
    }

    @Test public void anUnknownMethodAnswersNull() {
        assertNull(PhotoCalls.dispatch(true, new Fake(), "photos_launch_missiles", null));
        assertNull(PhotoCalls.dispatch(true, new Fake(), null, null));
    }

    // ---- reading

    @Test public void getSettingsListsTheRealCategoriesAndWhichAreOn() {
        Fake f = new Fake();
        f.off.add("clouds-not-a-key");
        f.off.add("skies");
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.GET_SETTINGS, null);
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_OK));
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_ENABLED));
        assertArrayEquals(new String[] {"landscapes", "mountains", "coasts", "lakes", "forests", "skies", "sunsets"},
                strings(r, PhotoCalls.R_KEYS));
        assertEquals(PhotoPolicy.CATEGORIES.length, strings(r, PhotoCalls.R_LABELS).length);
        assertEquals(Arrays.asList("landscapes", "mountains", "coasts", "lakes", "forests", "sunsets"),
                Arrays.asList(strings(r, PhotoCalls.R_ON)));
        assertEquals(4, r.get(PhotoCalls.R_COUNT));
        assertEquals("Ready: 4 photos", r.get(PhotoCalls.R_STATUS));
    }

    @Test public void statusIsTheSameAnswer() {
        Fake f = new Fake();
        assertEquals(PhotoCalls.dispatch(true, f, PhotoCalls.GET_SETTINGS, null).get(PhotoCalls.R_STATUS),
                PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null).get(PhotoCalls.R_STATUS));
    }

    // ---- the switch

    @Test public void setEnabledChangesTheSwitchOnce() {
        Fake f = new Fake();
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_ENABLED, with(PhotoCalls.EXTRA_ENABLED, false));
        assertFalse(f.enabled);
        assertEquals(Boolean.FALSE, r.get(PhotoCalls.R_ENABLED));
        assertEquals("Off", r.get(PhotoCalls.R_STATUS));
        PhotoCalls.dispatch(true, f, PhotoCalls.SET_ENABLED, with(PhotoCalls.EXTRA_ENABLED, false));
        assertEquals("already off: nothing to redo", 1, f.enables);
    }

    @Test public void setEnabledWithoutABooleanIsABadRequest() {
        Fake f = new Fake();
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_ENABLED, new HashMap<String, Object>());
        assertEquals(Boolean.FALSE, r.get(PhotoCalls.R_OK));
        assertEquals(PhotoCalls.ERR_BAD_REQUEST, r.get(PhotoCalls.R_ERROR));
        assertTrue(f.enabled);
        r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_ENABLED, with(PhotoCalls.EXTRA_ENABLED, "false"));
        assertEquals(PhotoCalls.ERR_BAD_REQUEST, r.get(PhotoCalls.R_ERROR));
        assertTrue(f.enabled);
    }

    // ---- categories: at least one stays on

    @Test public void setCategoriesStoresTheOnesLeftOut() {
        Fake f = new Fake();
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_CATEGORIES,
                with(PhotoCalls.EXTRA_ON, new String[] {"mountains", "lakes"}));
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_OK));
        assertEquals(new HashSet<>(Arrays.asList("landscapes", "coasts", "forests", "skies", "sunsets")), f.off);
        assertEquals(Arrays.asList("mountains", "lakes"), Arrays.asList(strings(r, PhotoCalls.R_ON)));
    }

    @Test public void allCategoriesOffIsRefusedAndNothingChanges() {
        Fake f = new Fake();
        f.off.add("lakes");
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_CATEGORIES,
                with(PhotoCalls.EXTRA_ON, new String[0]));
        assertEquals(Boolean.FALSE, r.get(PhotoCalls.R_OK));
        assertEquals(PhotoCalls.ERR_AT_LEAST_ONE, r.get(PhotoCalls.R_ERROR));
        assertEquals(new HashSet<>(Arrays.asList("lakes")), f.off);
        // only unknown keys left on counts as none
        r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_CATEGORIES, with(PhotoCalls.EXTRA_ON, new String[] {"cats"}));
        assertEquals(PhotoCalls.ERR_AT_LEAST_ONE, r.get(PhotoCalls.R_ERROR));
        assertEquals(new HashSet<>(Arrays.asList("lakes")), f.off);
    }

    @Test public void oneCategoryOnIsEnough() {
        Fake f = new Fake();
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.SET_CATEGORIES,
                with(PhotoCalls.EXTRA_ON, new String[] {"sunsets"}));
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_OK));
        assertEquals(PhotoPolicy.CATEGORIES.length - 1, f.off.size());
        assertFalse(f.off.contains("sunsets"));
    }

    @Test public void offForReturnsNullWhenNothingIsLeftOn() {
        assertNull(PhotoCalls.offFor(Arrays.<String>asList()));
        assertNotNull(PhotoCalls.offFor(Arrays.asList("forests")));
    }

    // ---- fetch a new photo now, and the launcher's advance

    @Test public void refreshFetchesOneNewPhotoNowAndAnswersFetching() {
        Fake f = new Fake();
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.REFRESH, null);
        assertEquals(1, f.manualAdvances);
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_REFRESHING));
        assertEquals("Fetching...", r.get(PhotoCalls.R_STATUS));
    }

    @Test public void refreshWithPhotosOffIsRefused() {
        Fake f = new Fake();
        f.enabled = false;
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.REFRESH, null);
        assertEquals(PhotoCalls.ERR_OFF, r.get(PhotoCalls.R_ERROR));
        assertEquals(0, f.manualAdvances);
        r = PhotoCalls.dispatch(true, f, PhotoCalls.ADVANCE, null);
        assertEquals(PhotoCalls.ERR_OFF, r.get(PhotoCalls.R_ERROR));
        assertEquals(0, f.advances);
    }

    @Test public void advanceTellsWhichPhotoIsOnScreenAndAsksForOneNewPhoto() {
        Fake f = new Fake();
        PhotoCalls.dispatch(true, f, PhotoCalls.ADVANCE, with(PhotoCalls.EXTRA_SHOWN, "0123456789abcdef.jpg"));
        assertEquals(1, f.advances);
        assertEquals(0, f.manualAdvances);
        assertEquals("0123456789abcdef.jpg", f.lastShown);
        PhotoCalls.dispatch(true, f, PhotoCalls.ADVANCE, null);
        assertEquals(2, f.advances);
        assertNull(f.lastShown);
        PhotoCalls.dispatch(true, f, PhotoCalls.ADVANCE, with(PhotoCalls.EXTRA_SHOWN, 42));
        assertNull("not a string: ignored", f.lastShown);
    }

    // ---- the status line as the answer carries it

    @Test public void theRefreshingFlagFollowsTheThirtySecondRule() {
        Fake f = new Fake();
        f.flightMs = 29_000;
        Map<String, Object> r = PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null);
        assertEquals(Boolean.TRUE, r.get(PhotoCalls.R_REFRESHING));
        assertEquals("Fetching...", r.get(PhotoCalls.R_STATUS));
        f.flightMs = 31_000;
        r = PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null);
        assertEquals("a fetch still running after 30 s is no longer 'refreshing'", Boolean.FALSE, r.get(PhotoCalls.R_REFRESHING));
        assertEquals("Ready: 4 photos", r.get(PhotoCalls.R_STATUS));
        f.busy = true;
        assertEquals("Server busy, retrying", PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null).get(PhotoCalls.R_STATUS));
    }

    @Test public void aFailureNoteShowsInTheAnswer() {
        Fake f = new Fake();
        f.note = "No network";
        f.count = 0;
        assertEquals("No network", PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null).get(PhotoCalls.R_STATUS));
        f.enabled = false;
        assertEquals("Off", PhotoCalls.dispatch(true, f, PhotoCalls.STATUS, null).get(PhotoCalls.R_STATUS));
    }
}
