package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.SettingsModel.Group;
import com.gathin.tvhome.SettingsModel.Row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class SettingsModelTest {

    private static List<Row> all(boolean openOnStart, boolean dockRow) {
        List<Row> out = new ArrayList<>();
        for (Group g : SettingsModel.groups()) out.addAll(SettingsModel.rows(g, openOnStart, dockRow));
        return out;
    }

    @Test public void everyRowIsReachableWhenAllOptionalOnesAreOn() {
        assertEquals(new HashSet<>(Arrays.asList(Row.values())), new HashSet<>(all(true, true)));
    }

    @Test public void noRowAppearsTwice() {
        List<Row> rows = all(true, true);
        assertEquals(rows.size(), new HashSet<>(rows).size());
    }

    @Test public void optionalRowsFollowTheirSwitch() {
        List<Row> off = all(false, false);
        assertFalse(off.contains(Row.OPEN_ON_START));
        assertTrue("one Widgets row replaces the two weather rows", off.contains(Row.WIDGETS));
        assertFalse(off.contains(Row.EDIT_DOCK));
        assertTrue(off.contains(Row.REORDER));
    }

    @Test public void everyGroupHasRows() {
        for (Group g : SettingsModel.groups()) assertFalse(SettingsModel.rows(g, false, false).isEmpty());
    }

    @Test public void rowKinds() {
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.COLUMNS));
        assertEquals(SettingsModel.Kind.NAVIGATE, SettingsModel.kind(Row.REMOTE_BUTTONS));
        assertEquals(SettingsModel.Kind.ACTION, SettingsModel.kind(Row.REORDER));
        assertEquals(SettingsModel.Kind.CONFIRM, SettingsModel.kind(Row.RESET_THEME));
    }

    @Test public void labelsAndLookups() {
        assertArrayEquals(new String[] {"100%", "70%", "50%", "30%"},
                SettingsModel.percentLabels(DockOpacity.OLD_PRESETS));
        assertArrayEquals(new String[] {"4", "5", "6", "7"}, SettingsModel.numberLabels(SettingsModel.COLUMNS));
        assertEquals(2, SettingsModel.indexOf(SettingsModel.COLUMNS, 6));
        assertEquals(-1, SettingsModel.indexOf(SettingsModel.COLUMNS, 9));
        assertEquals(1, SettingsModel.indexOf(SettingsModel.CLOCK_FORMATS, "12"));
        assertEquals(-1, SettingsModel.indexOf(SettingsModel.CLOCK_FORMATS, "x"));
    }

    @Test public void topShelfHasItsOwnGroupAfterLook() {
        assertEquals(Arrays.asList(Group.HOME_LAYOUT, Group.LOOK, Group.TOP_SHELF, Group.BUTTONS, Group.SYSTEM),
                Arrays.asList(SettingsModel.groups()));
        assertEquals(Arrays.asList(Row.TOP_SHELF, Row.APP_ARTWORK, Row.PHOTOS, Row.PHOTO_THEMES, Row.REFRESH_PHOTOS,
                Row.CHANGE_EVERY, Row.PHOTO_CREDIT), SettingsModel.rows(Group.TOP_SHELF, false, false));
        // the Top Shelf row moved out of Look
        assertFalse(SettingsModel.rows(Group.LOOK, true, true).contains(Row.TOP_SHELF));
    }

    @Test public void topShelfRowsArePopupPickersAndRefreshIsAnAction() {
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.TOP_SHELF));
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.APP_ARTWORK));
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.PHOTOS));
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.PHOTO_THEMES));
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.PHOTO_CREDIT));
        assertEquals(SettingsModel.Kind.ACTION, SettingsModel.kind(Row.REFRESH_PHOTOS));
    }

    @Test public void topShelfChoicesAreNotThemeSettings() {
        // No theme default for these: "Reset Theme" must not clear them.
        for (String key : new String[] {Config.TOP_SHELF_KEY, Config.TOP_SHELF_ARTWORK_KEY, Config.TOP_SHELF_CREDIT_KEY}) {
            assertFalse(key, Arrays.asList(SettingsModel.RESET_KEYS).contains(key));
        }
        assertEquals("top_shelf", Config.TOP_SHELF_KEY);   // the saved key did not change with the move
    }

    @Test public void onlyThePhotoRowsLiveInTheAddOn() {
        assertTrue(SettingsModel.isAddOnRow(Row.PHOTOS));
        assertTrue(SettingsModel.isAddOnRow(Row.PHOTO_THEMES));
        assertTrue(SettingsModel.isAddOnRow(Row.REFRESH_PHOTOS));
        assertFalse(SettingsModel.isAddOnRow(Row.TOP_SHELF));
        assertFalse(SettingsModel.isAddOnRow(Row.APP_ARTWORK));
        assertFalse(SettingsModel.isAddOnRow(Row.PHOTO_CREDIT));
    }

    @Test public void rtlReversesOnlyTheNumericLists() {
        assertTrue(SettingsModel.reversesInRtl(Row.COLUMNS));
        assertTrue(SettingsModel.reversesInRtl(Row.CLOCK));
        assertFalse(SettingsModel.reversesInRtl(Row.ANIMATIONS));
        assertArrayEquals(new int[] {0, 1, 2}, SettingsModel.order(3, false));
        assertArrayEquals(new int[] {2, 1, 0}, SettingsModel.order(3, true));
    }

    @Test public void resetCountCountsOnlyTheResetKeys() {
        Set<String> saved = new HashSet<>(Arrays.asList("columns", "animations", "revision", "hint_hold_seen"));
        assertEquals(2, SettingsModel.resetCount(saved::contains));
        assertEquals(0, SettingsModel.resetCount(k -> false));
        assertEquals(SettingsModel.RESET_KEYS.length, SettingsModel.resetCount(k -> true));
    }

    // ---- Top Shelf > Change every

    @Test public void changeEveryOffersTheSevenIntervalsWithFifteenSecondsAsTheDefault() {
        assertArrayEquals(new long[] {10_000, 15_000, 30_000, 60_000, 300_000, 900_000, 3_600_000},
                SettingsModel.SHELF_INTERVALS_MS);
        assertArrayEquals(new String[] {"10 s", "15 s", "30 s", "1 min", "5 min", "15 min", "1 hour"},
                SettingsModel.SHELF_INTERVAL_LABELS);
        assertEquals(SettingsModel.SHELF_INTERVALS_MS.length, SettingsModel.SHELF_INTERVAL_LABELS.length);
        assertEquals(15_000, SettingsModel.SHELF_INTERVAL_DEFAULT_MS);
        assertEquals("15 s", SettingsModel.SHELF_INTERVAL_LABELS[SettingsModel.shelfIntervalIndex(15_000)]);
    }

    @Test public void changeEverySitsInTheTopShelfGroupAndIsAPlainOptionsRow() {
        List<Row> shelf = SettingsModel.rows(Group.TOP_SHELF, false, false);
        assertTrue(shelf.contains(Row.CHANGE_EVERY));
        assertEquals("after the photo rows", shelf.indexOf(Row.REFRESH_PHOTOS) + 1, shelf.indexOf(Row.CHANGE_EVERY));
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.CHANGE_EVERY));
        assertFalse("it lives in the launcher, not the add-on", SettingsModel.isAddOnRow(Row.CHANGE_EVERY));
        assertFalse("not a theme setting: Reset Theme leaves it", Arrays.asList(SettingsModel.RESET_KEYS)
                .contains(Config.TOP_SHELF_INTERVAL_KEY));
    }

    @Test public void aLauncherThatNeverSavedAnIntervalGetsTheDefault() {
        java.util.Map<String, Long> prefs = new java.util.HashMap<>();
        assertEquals(15_000, SettingsModel.shelfInterval((k, d) -> prefs.containsKey(k) ? prefs.get(k) : d));
    }

    @Test public void everyChoiceSurvivesSavingAndReading() {
        for (int i = 0; i < SettingsModel.SHELF_INTERVALS_MS.length; i++) {
            java.util.Map<String, Long> prefs = new java.util.HashMap<>();
            prefs.put(Config.TOP_SHELF_INTERVAL_KEY, SettingsModel.SHELF_INTERVALS_MS[i]);
            long read = SettingsModel.shelfInterval((k, d) -> prefs.containsKey(k) ? prefs.get(k) : d);
            assertEquals(SettingsModel.SHELF_INTERVALS_MS[i], read);
            assertEquals(i, SettingsModel.shelfIntervalIndex(read));
        }
    }

    @Test public void aSavedValueThatIsNotAChoiceFallsBackToTheDefault() {
        assertEquals(15_000, SettingsModel.shelfInterval(12_000));
        assertEquals(15_000, SettingsModel.shelfInterval(0));
        assertEquals(15_000, SettingsModel.shelfInterval(-5));
        assertEquals(1, SettingsModel.shelfIntervalIndex(12_000));
    }

    @Test public void followingAFetchStopsAfterThirtySeconds() {
        assertFalse(PhotoSettingsClient.pollGivenUp(29_999, 0));
        assertFalse(PhotoSettingsClient.pollGivenUp(10_000 + 29_999, 10_000));
        assertTrue(PhotoSettingsClient.pollGivenUp(10_000 + 30_000, 10_000));
    }

    @Test public void motionIsInstantWithoutAnimationsAndShortWithReducedMotion() {
        assertEquals(0, SettingsModel.duration(false, false, 200));
        assertEquals(200, SettingsModel.duration(true, false, 200));
        assertEquals(90, SettingsModel.duration(true, true, 200));
        assertEquals(60, SettingsModel.duration(true, true, 60));
    }
}
