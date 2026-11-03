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
                SettingsModel.percentLabels(SettingsModel.DOCK_OPACITIES));
        assertArrayEquals(new String[] {"4", "5", "6", "7"}, SettingsModel.numberLabels(SettingsModel.COLUMNS));
        assertEquals(2, SettingsModel.indexOf(SettingsModel.COLUMNS, 6));
        assertEquals(-1, SettingsModel.indexOf(SettingsModel.COLUMNS, 9));
        assertEquals(1, SettingsModel.indexOf(SettingsModel.CLOCK_FORMATS, "12"));
        assertEquals(-1, SettingsModel.indexOf(SettingsModel.CLOCK_FORMATS, "x"));
    }

    @Test public void topShelfIsAPopupPickerInTheLookGroup() {
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.TOP_SHELF));
        assertTrue(SettingsModel.rows(Group.LOOK, false, false).contains(Row.TOP_SHELF));
        // A choice with no theme default: "Reset Theme" must not clear it.
        assertFalse(Arrays.asList(SettingsModel.RESET_KEYS).contains(Config.TOP_SHELF_KEY));
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
