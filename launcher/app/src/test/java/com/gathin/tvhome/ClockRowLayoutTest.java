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

    @Test public void noSequenceOfMovesPutsTwoWidgetsInOneOfTheFiveSlots() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        WidgetSlot[] slots = WidgetSlot.values();
        assertEquals(5, slots.length);
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

    // ---- the gap before the first chip on screen -----------------------------------------------------------------

    @Test public void aHiddenFirstSlotDoesNotTakeTheFirstChipsGap() {
        // Weather (slot 0) has nothing to show; Brightness (slot 1) is the first chip and tucks in like the first.
        assertArrayEquals(new int[] {8, -10}, ClockRowLayout.chipMargins(new boolean[] {false, true}, new int[] {-10, -10}, true, 16, 8));
        assertArrayEquals(new int[] {8, 16}, ClockRowLayout.chipMargins(new boolean[] {false, true}, new int[] {-10, -10}, false, 16, 8));
    }

    @Test public void bothChipsShownGiveTheFirstItsOwnGapAndTheSecondThePlainOne() {
        assertArrayEquals(new int[] {-10, 8}, ClockRowLayout.chipMargins(new boolean[] {true, true}, new int[] {-10, 8}, true, 16, 8));
        assertArrayEquals(new int[] {16, 8}, ClockRowLayout.chipMargins(new boolean[] {true, true}, new int[] {-10, 8}, false, 16, 8));
        assertArrayEquals(new int[] {8, 8}, ClockRowLayout.chipMargins(new boolean[] {false, false}, new int[] {-10, 8}, true, 16, 8));
    }

    // ---- the top bar's chips -------------------------------------------------------------------------------------

    @Test public void barChipsShowWhileTheBarLeavesTheClockItsRoom() {
        // 848 wide header, the time and AM/PM need 190, 16 between: the bar may take 642. Buttons take 360.
        boolean[] out = ClockRowLayout.barFit(848, 190, 16, 360, new int[] {120, 120}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {true, true}, out);
    }

    @Test public void textStyleButtonsMakeTheBarWiderSoAChipYields() {
        // The same chips beside buttons that grew to 470: only the first still fits (470 + 120 <= 642, +120 is not).
        boolean[] out = ClockRowLayout.barFit(848, 190, 16, 470, new int[] {120, 120}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {true, false}, out);
        // And with no room at all, the buttons stay and every chip goes.
        assertArrayEquals(new boolean[] {false, false},
                ClockRowLayout.barFit(848, 190, 16, 600, new int[] {120, 120}, new boolean[] {true, true}));
    }

    @Test public void aBarChipTooWideIsSkippedOnItsOwnAndANarrowerOneStillShows() {
        boolean[] out = ClockRowLayout.barFit(848, 190, 16, 400, new int[] {300, 90}, new boolean[] {true, true});
        assertArrayEquals(new boolean[] {false, true}, out);
    }

    @Test public void aSlotWithNoWidgetOnScreenTakesNoRoomInTheBar() {
        assertArrayEquals(new boolean[] {false, true},
                ClockRowLayout.barFit(848, 190, 16, 500, new int[] {200, 100}, new boolean[] {false, true}));
    }

    @Test public void shownBarChipsNeverCrowdTheClockBlock() {
        for (int base = 100; base < 700; base += 37) {
            for (int need = 40; need < 300; need += 23) {
                boolean[] out = ClockRowLayout.barFit(848, 190, 16, base, new int[] {need, need}, new boolean[] {true, true});
                int taken = base + (out[0] ? need : 0) + (out[1] ? need : 0);
                if (out[0] || out[1]) assertTrue("bar " + taken + " leaves the clock block no room", taken <= 848 - 16 - 190);
            }
        }
    }

    // ---- the slot picker's boxes ---------------------------------------------------------------------------------

    @Test public void pickerBoxesKeepTheirPreferredWidthWhenTheRowHasRoom() {
        // Row 600, 200 fixed, two boxes 16 and 8 after it: 600 - 200 - 24 = 376 >= 2 * 160.
        int[] w = ClockRowLayout.shrinkToFit(600, 200, 0, 40, new int[] {16, 8}, 160, 48);
        assertArrayEquals(new int[] {0, 160, 160}, w);
    }

    @Test public void pickerBoxesShrinkTogetherToFitTheRow() {
        int[] w = ClockRowLayout.shrinkToFit(400, 200, 0, 40, new int[] {16, 8}, 160, 48);
        assertEquals(88, w[1]);
        assertEquals(88, w[2]);
        assertTrue(200 + 16 + 8 + w[1] + w[2] <= 400);
    }

    @Test public void pickerBoxesNeverGoBelowTheirMinimum() {
        int[] w = ClockRowLayout.shrinkToFit(250, 200, 0, 40, new int[] {16, 8}, 160, 48);
        assertEquals(48, w[1]);
        assertEquals(48, w[2]);
    }

    @Test public void theDateBesideTheTimeGivesWayOnlyAfterTheBoxesAreAtTheirMinimum() {
        // Plenty of date to give: boxes get what is left after the date, down to the minimum.
        int[] roomy = ClockRowLayout.shrinkToFit(700, 200, 200, 40, new int[] {6, 8}, 160, 48);
        assertEquals(200, roomy[0]);
        assertEquals(143, roomy[1]);
        // The boxes cannot even hold the minimum next to the full date: the date shrinks to make room.
        int[] tight = ClockRowLayout.shrinkToFit(500, 200, 200, 40, new int[] {6, 8}, 160, 48);
        assertEquals(48, tight[1]);
        assertEquals(48, tight[2]);
        assertEquals(190, tight[0]);
        assertTrue(200 + tight[0] + 14 + tight[1] + tight[2] <= 500);
        // And never below its own minimum.
        int[] cramped = ClockRowLayout.shrinkToFit(300, 200, 200, 40, new int[] {6, 8}, 160, 48);
        assertEquals(40, cramped[0]);
    }

    @Test public void noBoxesLeavesTheDateAlone() {
        assertArrayEquals(new int[] {200}, ClockRowLayout.shrinkToFit(100, 50, 200, 40, new int[0], 160, 48));
    }

    @Test public void captionsSitUnderTheirBoxesWhenThereIsRoom() {
        int[] lefts = ClockRowLayout.captionLefts(new int[] {200, 500}, new int[] {100, 100}, 8, 952, 6);
        assertArrayEquals(new int[] {150, 450}, lefts);
    }

    @Test public void captionsNeverOverlapOrLeaveTheScreen() {
        // Four boxes close together: the clock chips near the left, the two bar boxes near the right.
        int[] centers = {120, 190, 790, 900};
        int[] widths = {190, 190, 190, 190};
        int[] lefts = ClockRowLayout.captionLefts(centers, widths, 8, 952, 6);
        assertTrue(lefts[0] >= 8);
        for (int i = 1; i < lefts.length; i++) assertTrue("caption " + i + " overlaps", lefts[i] >= lefts[i - 1] + widths[i - 1] + 6);
        assertTrue(lefts[3] + widths[3] <= 952);
    }

    // ---- the top bar's slots -------------------------------------------------------------------------------------

    @Test public void theTopBarHasItsOwnSlotOrderAndGroups() {
        assertArrayEquals(new WidgetSlot[] {WidgetSlot.TOP_BAR_START, WidgetSlot.BESIDE_NETWORK, WidgetSlot.TOP_BAR_END},
                WidgetSlot.bar());
        assertEquals(0, WidgetSlot.TOP_BAR_START.barOrder());
        assertEquals(1, WidgetSlot.BESIDE_NETWORK.barOrder());
        assertEquals(2, WidgetSlot.TOP_BAR_END.barOrder());
        assertEquals(WidgetSlot.TOP_BAR_START, WidgetSlot.BESIDE_NETWORK.groupLead());
        assertFalse(WidgetSlot.BESIDE_NETWORK.inClockRow());
        assertNull(WidgetSlot.BESIDE_NETWORK.nextBeforeBar());
        assertNull(WidgetSlot.BESIDE_NETWORK.previousBeforeBar());
        assertEquals(WidgetSlot.BESIDE_NETWORK, WidgetSlot.fromKey("beside_network"));
        assertEquals(-1, WidgetSlot.CLOCK_SIDE.barOrder());
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetSlot.BESIDE_WEATHER.groupLead());
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetSlot.CLOCK_SIDE.groupLead());
        assertEquals(WidgetSlot.TOP_BAR_START, WidgetSlot.TOP_BAR_END.groupLead());
    }

    // ---- Horizon's widget defaults -------------------------------------------------------------------------------

    @Test public void aSceneClockLookDocksNoWeatherAndBrightnessBeforeTheNetworkPill() {
        WidgetPlacements p = WidgetPlacements.parse(null, WidgetRegistry.defaults(true), WidgetRegistry.RULES);
        assertNull(p.slotOf("weather"));
        assertEquals(WidgetSlot.BESIDE_NETWORK, p.slotOf("brightness"));
        assertEquals("brightness=beside_network;weather=none", p.serialize());
    }

    @Test public void otherLooksKeepTheWeatherBesideTheClock() {
        WidgetPlacements p = WidgetPlacements.parse(null, WidgetRegistry.defaults(), WidgetRegistry.RULES);
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
        assertNull(p.slotOf("brightness"));
    }

    @Test public void theOwnersSavedChoicesBeatTheSceneClockDefaults() {
        WidgetPlacements p = WidgetPlacements.parse("weather=clock_side;brightness=top_bar_end",
                WidgetRegistry.defaults(true), WidgetRegistry.RULES);
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
        assertEquals(WidgetSlot.TOP_BAR_END, p.slotOf("brightness"));
    }

    @Test public void bothWidgetsCanDockBeforeTheNetworkPillSlotAndSwap() {
        WidgetPlacements p = WidgetPlacements.parse("weather=clock_side;brightness=beside_network", defaults());
        assertEquals(Result.SWAPPED, p.place("weather", WidgetSlot.BESIDE_NETWORK, WidgetRegistry.RULES));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("brightness"));
    }

    // ---- stored placements that can never be shown ---------------------------------------------------------------

    @Test public void aWidgetTheAppNoLongerListsDoesNotHoldASlot() {
        // "clock" was a widget once; its claim on the top bar must not keep Brightness out of it.
        WidgetPlacements p = WidgetPlacements.parse("clock=top_bar_start;weather=clock_side", defaults());
        assertNull(p.widgetIn(WidgetSlot.TOP_BAR_START));
        assertEquals(Result.PLACED, p.place("brightness", WidgetSlot.TOP_BAR_START, WidgetRegistry.RULES));
        assertFalse(p.serialize().contains("clock="));
    }

    @Test public void aSlotTheWidgetCanNoLongerDockInDoesNotHoldItEither() {
        WidgetPlacements.SlotRules noBarForWeather = (id, slot) -> !(id.equals("weather") && slot == WidgetSlot.TOP_BAR_END);
        WidgetPlacements p = WidgetPlacements.parse("weather=top_bar_end;brightness=none", defaults(), noBarForWeather);
        assertNull(p.slotOf("weather"));
        assertNull(p.widgetIn(WidgetSlot.TOP_BAR_END));
        assertEquals(Result.PLACED, p.place("brightness", WidgetSlot.TOP_BAR_END, noBarForWeather));
    }

    @Test public void withoutRulesOrADefaultsListTheStoredTextIsKeptAsItWas() {
        assertEquals(WidgetSlot.TOP_BAR_END, WidgetPlacements.parse("weather=top_bar_end", defaults()).slotOf("weather"));
        assertEquals("other=top_bar_end", WidgetPlacements.parse("other=top_bar_end", null).serialize());
    }
}
