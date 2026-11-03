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
                Row.PHOTO_CREDIT), SettingsModel.rows(Group.TOP_SHELF, false, false));
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

    @Test public void motionIsInstantWithoutAnimationsAndShortWithReducedMotion() {
        assertEquals(0, SettingsModel.duration(false, false, 200));
        assertEquals(200, SettingsModel.duration(true, false, 200));
        assertEquals(90, SettingsModel.duration(true, true, 200));
        assertEquals(60, SettingsModel.duration(true, true, 60));
    }
}
