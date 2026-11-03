package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.WidgetPlacements.Result;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ClockRowLayoutTest {

    private static final WidgetPlacements.SlotRules ANYWHERE = (id, slot) -> true;

    private static Map<String, WidgetSlot> defaults() {
        Map<String, WidgetSlot> d = new LinkedHashMap<>();
        d.put("weather", WidgetSlot.CLOCK_SIDE);
        d.put("brightness", null);
        return d;
    }

    // ---- slot ordering -------------------------------------------------------------------------------------------

    @Test public void besideWeatherFollowsBesideClockInTheClockRow() {
        assertArrayEquals(new WidgetSlot[] {WidgetSlot.CLOCK_SIDE, WidgetSlot.BESIDE_WEATHER}, WidgetSlot.clockRow());
        assertEquals(0, WidgetSlot.CLOCK_SIDE.rowOrder());
        assertEquals(1, WidgetSlot.BESIDE_WEATHER.rowOrder());
        assertTrue(WidgetSlot.BESIDE_WEATHER.inClockRow());
        assertFalse(WidgetSlot.TOP_BAR_START.inClockRow());
        assertEquals(-1, WidgetSlot.TOP_BAR_END.rowOrder());
    }

    @Test public void headerOrderRunsFromTheClockToTheBar() {
        assertArrayEquals(new WidgetSlot[] {WidgetSlot.CLOCK_SIDE, WidgetSlot.BESIDE_WEATHER, WidgetSlot.TOP_BAR_START},
                WidgetSlot.beforeBar());
        assertEquals(WidgetSlot.BESIDE_WEATHER, WidgetSlot.CLOCK_SIDE.nextBeforeBar());
        assertEquals(WidgetSlot.TOP_BAR_START, WidgetSlot.BESIDE_WEATHER.nextBeforeBar());
        assertNull(WidgetSlot.TOP_BAR_START.nextBeforeBar());
        assertNull(WidgetSlot.TOP_BAR_END.nextBeforeBar());
        assertNull(WidgetSlot.CLOCK_SIDE.previousBeforeBar());
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetSlot.BESIDE_WEATHER.previousBeforeBar());
        assertEquals(WidgetSlot.BESIDE_WEATHER, WidgetSlot.TOP_BAR_START.previousBeforeBar());
        assertNull(WidgetSlot.TOP_BAR_END.previousBeforeBar());
    }

    @Test public void slotKeysAreUniqueAndTheOldOnesAreUnchanged() {
        Set<String> keys = new HashSet<>();
        for (WidgetSlot s : WidgetSlot.values()) assertTrue(keys.add(s.key));
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetSlot.fromKey("clock_side"));
        assertEquals(WidgetSlot.TOP_BAR_START, WidgetSlot.fromKey("top_bar_start"));
        assertEquals(WidgetSlot.TOP_BAR_END, WidgetSlot.fromKey("top_bar_end"));
        assertEquals(WidgetSlot.BESIDE_WEATHER, WidgetSlot.fromKey("beside_weather"));
    }

    // ---- placements ----------------------------------------------------------------------------------------------

    @Test public void weatherAndBrightnessShareTheClockRow() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.PLACED, p.place("brightness", WidgetSlot.BESIDE_WEATHER, WidgetRegistry.RULES));
        assertEquals("weather", p.widgetIn(WidgetSlot.CLOCK_SIDE));
        assertEquals("brightness", p.widgetIn(WidgetSlot.BESIDE_WEATHER));
        WidgetPlacements back = WidgetPlacements.parse(p.serialize(), defaults());
        assertEquals(WidgetSlot.CLOCK_SIDE, back.slotOf("weather"));
        assertEquals(WidgetSlot.BESIDE_WEATHER, back.slotOf("brightness"));
    }

    @Test public void theNewSlotSwapsAndRefusesLikeTheOthers() {
        WidgetPlacements p = WidgetPlacements.parse("weather=clock_side;brightness=beside_weather", defaults());
        assertEquals(Result.SWAPPED, p.check("weather", WidgetSlot.BESIDE_WEATHER, ANYWHERE));
        assertEquals(Result.SWAPPED, p.place("weather", WidgetSlot.BESIDE_WEATHER, WidgetRegistry.RULES));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("brightness"));
        assertEquals(WidgetSlot.BESIDE_WEATHER, p.slotOf("weather"));

        WidgetPlacements q = WidgetPlacements.parse("weather=beside_weather;brightness=none", defaults());
        assertEquals(Result.OCCUPIED, q.place("brightness", WidgetSlot.BESIDE_WEATHER, ANYWHERE));
        assertEquals("weather", q.widgetIn(WidgetSlot.BESIDE_WEATHER));
    }

    @Test public void aSavedPlacementWithoutTheNewSlotLoadsUnchanged() {
        WidgetPlacements p = WidgetPlacements.parse("weather=top_bar_end;brightness=clock_side", defaults());
        assertEquals(WidgetSlot.TOP_BAR_END, p.slotOf("weather"));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("brightness"));
        assertEquals("brightness=clock_side;weather=top_bar_end", p.serialize());
        assertNull(p.widgetIn(WidgetSlot.BESIDE_WEATHER));
    }

    @Test public void twoWidgetsClaimingTheNewSlotKeepOnlyTheFirst() {
        WidgetPlacements p = WidgetPlacements.parse("weather=beside_weather;brightness=beside_weather", defaults());
        assertEquals(WidgetSlot.BESIDE_WEATHER, p.slotOf("weather"));
        assertNull(p.slotOf("brightness"));
    }

    @Test public void noSequenceOfMovesPutsTwoWidgetsInOneOfTheFourSlots() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        WidgetSlot[] slots = WidgetSlot.values();
        assertEquals(4, slots.length);
        for (int i = 0; i < 60; i++) {
            p.place(i % 2 == 0 ? "weather" : "brightness", slots[(i * 5 + i / 3) % slots.length], WidgetRegistry.RULES);
            Set<WidgetSlot> used = new HashSet<>();
            for (String id : new String[] {"weather", "brightness"}) {
                WidgetSlot s = p.slotOf(id);
                if (s != null) assertTrue("slot reused after move " + i, used.add(s));
            }
        }
    }

    // ---- where a view goes ---------------------------------------------------------------------------------------

    @Test public void aSlotsViewsGoAfterTheViewsOfTheSlotsBeforeIt() {
        assertEquals(0, ClockRowLayout.offsetOf(new int[] {0, 0}, 1));
        assertEquals(1, ClockRowLayout.offsetOf(new int[] {1, 0}, 1));
        assertEquals(2, ClockRowLayout.offsetOf(new int[] {2, 1}, 1));
        assertEquals(0, ClockRowLayout.offsetOf(new int[] {2, 1}, 0));
    }

    @Test public void onlyTheFirstChipKeepsItsOwnMargin() {
        assertEquals(-10, ClockRowLayout.marginBefore(true, -10, 8));
        assertEquals(8, ClockRowLayout.marginBefore(false, -10, 8));
        assertEquals(8, ClockRowLayout.marginBefore(false, 8, 8));
    }

    @Test public void withTheDateUnderTheTimeTheFirstChipFollowsTheTimeAtAPlainGap() {
        // Beside the date the weather chip keeps its tuck under the divider; below the time there is none to tuck under.
        assertEquals(-10, ClockRowLayout.firstChipMargin(true, -10, 16));
        assertEquals(16, ClockRowLayout.firstChipMargin(false, -10, 16));
        assertEquals(16, ClockRowLayout.firstChipMargin(false, 8, 16));
    }

    // ---- where the chips stand -----------------------------------------------------------------------------------

    @Test public void chipsStandRightAfterTheTimeInSlotOrder() {
        // Time and PM end at 380; the weather chip is 16 after it and 300 wide, Brightness 8 after that.
        int[] lefts = ClockRowLayout.chipLefts(380, new int[] {16, 8}, new int[] {300, 90});
        assertArrayEquals(new int[] {396, 704}, lefts);
    }

    @Test public void aChipLaidOutFarFromTheTimeIsShiftedBackToIt() {
        int[] lefts = ClockRowLayout.chipLefts(380, new int[] {16}, new int[] {300});
        assertEquals(396 - 1050, ClockRowLayout.shift(lefts[0], 1050));
        assertEquals(0, ClockRowLayout.shift(lefts[0], 396));
    }

    @Test public void noChipsHaveNoPlaces() {
        assertEquals(0, ClockRowLayout.chipLefts(380, new int[0], new int[0]).length);
    }

    @Test public void theChipsStillEndBeforeTheTopButtonsWhenTheyFit() {
        int start = 380, available = 1200;
        int[] margins = {16, 8};
        int[] widths = {300, 90};
        boolean[] fits = ClockRowLayout.fit(available, start, widths, margins, new boolean[] {true, true});
        int[] lefts = ClockRowLayout.chipLefts(start, margins, widths);
        assertArrayEquals(new boolean[] {true, true}, fits);
        assertTrue(lefts[1] + widths[1] <= available);
    }

    // ---- fitting -------------------------------------------------------------------------------------------------

    @Test public void bothChipsShowWhenTheRowHasRoom() {
        boolean[] out = ClockRowLayout.fit(1000, 300, new int[] {220, 90}, new int[] {-10, 8}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {true, true}, out);
    }

    @Test public void theSecondChipYieldsWhenItWouldReachPastTheRow() {
        boolean[] out = ClockRowLayout.fit(560, 300, new int[] {220, 90}, new int[] {-10, 8}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {true, false}, out);
    }

    @Test public void theFirstChipIsNeverHiddenEvenWhenSqueezed() {
        boolean[] out = ClockRowLayout.fit(100, 300, new int[] {220, 90}, new int[] {-10, 8}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {true, false}, out);
    }

    @Test public void withoutTheFirstSlotTheSecondChipIsFirstInTheRow() {
        boolean[] out = ClockRowLayout.fit(100, 300, new int[] {220, 90}, new int[] {-10, 8}, new boolean[] {false, true});
        assertArrayEquals(new boolean[] {false, true}, out);
    }

    @Test public void aSlotWithNothingOnScreenTakesNoRoom() {
        assertArrayEquals(new boolean[] {false, true},
                ClockRowLayout.fit(430, 300, new int[] {220, 90}, new int[] {8, 8}, new boolean[] {false, true}));
        assertArrayEquals(new boolean[] {false, false},
                ClockRowLayout.fit(430, 300, new int[] {220, 90}, new int[] {8, 8}, new boolean[] {false, false}));
    }
}
