package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.WidgetPlacements.Result;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class WidgetsTest {

    private static final WidgetPlacements.SlotRules ANYWHERE = (id, slot) -> true;

    private static Map<String, WidgetSlot> defaults() {
        Map<String, WidgetSlot> d = new LinkedHashMap<>();
        d.put("weather", WidgetSlot.CLOCK_SIDE);
        d.put("brightness", null);
        return d;
    }

    // ---- the registry --------------------------------------------------------------------------------------------

    @Test public void registryIdsAreUniqueAndFindable() {
        Set<String> seen = new HashSet<>();
        for (WidgetDef def : WidgetRegistry.all()) {
            assertTrue(def.id, def.id.matches("[a-z][a-z0-9_]*"));
            assertTrue("duplicate id " + def.id, seen.add(def.id));
            assertEquals(def, WidgetRegistry.get(def.id));
        }
        assertNull(WidgetRegistry.get("nope"));
        assertNotNull(WidgetRegistry.get(WidgetRegistry.WEATHER));
        assertNotNull(WidgetRegistry.get(WidgetRegistry.BRIGHTNESS));
    }

    @Test public void everyWidgetHasASlotAndADefaultThatIsOneOfThem() {
        Set<WidgetSlot> claimed = new HashSet<>();
        for (WidgetDef def : WidgetRegistry.all()) {
            assertFalse(def.id, def.slots.isEmpty());
            if (def.defaultSlot != null) {
                assertTrue(def.id, def.supports(def.defaultSlot));
                assertTrue("two widgets default to " + def.defaultSlot, claimed.add(def.defaultSlot));
            }
        }
    }

    @Test public void theWeatherKeepsItsPlaceBesideTheClock() {
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetRegistry.get(WidgetRegistry.WEATHER).defaultSlot);
        assertNull("brightness is opt-in", WidgetRegistry.get(WidgetRegistry.BRIGHTNESS).defaultSlot);
        WidgetPlacements fresh = WidgetPlacements.parse(null, WidgetRegistry.defaults());
        assertEquals(WidgetSlot.CLOCK_SIDE, fresh.slotOf("weather"));
        assertNull(fresh.slotOf("brightness"));
    }

    @Test public void everyOptionIsConsistent() {
        for (WidgetDef def : WidgetRegistry.all()) {
            Set<String> keys = new HashSet<>();
            for (WidgetOption o : def.options) {
                assertTrue(def.id + "." + o.key, keys.add(o.key));
                if (o.isAction()) continue;
                assertEquals(o.values.length, o.valueLabelRes.length);
                assertTrue(o.indexOf(o.defaultValue) >= 0);
            }
        }
    }

    @Test public void registryRulesAnswerFromTheDefinitions() {
        assertTrue(WidgetRegistry.RULES.supports("weather", WidgetSlot.TOP_BAR_END));
        assertFalse(WidgetRegistry.RULES.supports("unknown", WidgetSlot.CLOCK_SIDE));
    }

    // ---- persistence ---------------------------------------------------------------------------------------------

    @Test public void placementsRoundTripThroughTheirText() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.PLACED, p.place("brightness", WidgetSlot.TOP_BAR_START, ANYWHERE));
        String text = p.serialize();
        assertEquals("brightness=top_bar_start;weather=clock_side", text);
        WidgetPlacements back = WidgetPlacements.parse(text, defaults());
        assertEquals(WidgetSlot.TOP_BAR_START, back.slotOf("brightness"));
        assertEquals(WidgetSlot.CLOCK_SIDE, back.slotOf("weather"));
        assertEquals(text, back.serialize());
    }

    @Test public void aRemovedWidgetStaysRemovedAndIsNotBroughtBackByItsDefault() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        p.remove("weather");
        WidgetPlacements back = WidgetPlacements.parse(p.serialize(), defaults());
        assertNull(back.slotOf("weather"));
        assertNull(back.widgetIn(WidgetSlot.CLOCK_SIDE));
    }

    @Test public void garbageIsIgnoredAndTheDefaultsStand() {
        WidgetPlacements p = WidgetPlacements.parse("weather=nowhere;;=x;brightness;junk=", defaults());
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
        assertNull(p.slotOf("brightness"));
        assertEquals(WidgetSlot.CLOCK_SIDE, WidgetPlacements.parse("", defaults()).slotOf("weather"));
    }

    @Test public void twoClaimsOnOneSlotLeaveItToTheFirst() {
        WidgetPlacements p = WidgetPlacements.parse("brightness=clock_side;weather=clock_side", defaults());
        assertEquals("brightness", p.widgetIn(WidgetSlot.CLOCK_SIDE));
        assertNull(p.slotOf("weather"));
    }

    // ---- one widget per slot -------------------------------------------------------------------------------------

    @Test public void aFreeSlotTakesTheWidgetAndFreesTheOldOne() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.PLACED, p.place("weather", WidgetSlot.TOP_BAR_END, ANYWHERE));
        assertEquals(WidgetSlot.TOP_BAR_END, p.slotOf("weather"));
        assertNull(p.widgetIn(WidgetSlot.CLOCK_SIDE));
    }

    @Test public void placingWhereItAlreadyIsChangesNothing() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.UNCHANGED, p.place("weather", WidgetSlot.CLOCK_SIDE, ANYWHERE));
    }

    @Test public void anOccupiedSlotSwapsWhenTheOccupantCanTakeTheOldPlace() {
        WidgetPlacements p = WidgetPlacements.parse("brightness=top_bar_start", defaults());
        assertEquals(Result.SWAPPED, p.check("weather", WidgetSlot.TOP_BAR_START, ANYWHERE));
        assertEquals("check does not move anything", WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
        assertEquals(Result.SWAPPED, p.place("weather", WidgetSlot.TOP_BAR_START, ANYWHERE));
        assertEquals(WidgetSlot.TOP_BAR_START, p.slotOf("weather"));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("brightness"));
    }

    @Test public void anOccupiedSlotIsRefusedWhenTheOccupantCannotTakeTheOldPlace() {
        // Brightness cannot sit beside the clock here, so it cannot take the weather's old slot.
        WidgetPlacements.SlotRules rules = (id, slot) -> !(id.equals("brightness") && slot == WidgetSlot.CLOCK_SIDE);
        WidgetPlacements p = WidgetPlacements.parse("brightness=top_bar_start", defaults());
        assertEquals(Result.OCCUPIED, p.place("weather", WidgetSlot.TOP_BAR_START, rules));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
        assertEquals(WidgetSlot.TOP_BAR_START, p.slotOf("brightness"));
    }

    @Test public void aWidgetWithNoSlotCannotDisplaceAnOccupant() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.OCCUPIED, p.place("brightness", WidgetSlot.CLOCK_SIDE, ANYWHERE));
        assertEquals("weather", p.widgetIn(WidgetSlot.CLOCK_SIDE));
        assertNull(p.slotOf("brightness"));
    }

    @Test public void aSlotTheWidgetCannotDockInIsRefused() {
        WidgetPlacements.SlotRules rules = (id, slot) -> slot != WidgetSlot.TOP_BAR_END;
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        assertEquals(Result.NOT_SUPPORTED, p.place("weather", WidgetSlot.TOP_BAR_END, rules));
        assertEquals(WidgetSlot.CLOCK_SIDE, p.slotOf("weather"));
    }

    @Test public void noSequenceOfMovesEverPutsTwoWidgetsInOneSlot() {
        WidgetPlacements p = WidgetPlacements.parse(null, defaults());
        WidgetSlot[] slots = WidgetSlot.values();
        for (int i = 0; i < 40; i++) {
            p.place(i % 2 == 0 ? "weather" : "brightness", slots[(i * 7 + i / 3) % slots.length], WidgetRegistry.RULES);
            if (i % 11 == 10) p.remove("weather");
            Set<WidgetSlot> used = new HashSet<>();
            for (String id : new String[] {"weather", "brightness"}) {
                WidgetSlot s = p.slotOf(id);
                if (s != null) assertTrue("slot reused after move " + i, used.add(s));
            }
        }
    }

    // ---- options -------------------------------------------------------------------------------------------------

    @Test public void optionsStepThroughTheirValuesAndFallBackToTheDefault() {
        WidgetOption unit = WidgetRegistry.WEATHER_UNIT;
        assertEquals("add_on", unit.valueOrDefault(null));
        assertEquals("add_on", unit.valueOrDefault("kelvin"));
        assertEquals("c", unit.next("add_on"));
        assertEquals("add_on", unit.next("f"));
        assertEquals("f", unit.previous("add_on"));
        assertEquals("c", unit.next("junk"));
        assertTrue(WidgetRegistry.WEATHER_CITY.isAction());
        assertEquals("", WidgetRegistry.WEATHER_CITY.next("x"));
    }

    @Test public void aToggleIsOnOrOff() {
        WidgetOption place = WidgetRegistry.WEATHER_PLACE;
        assertEquals(WidgetOption.ON, place.valueOrDefault(null));
        assertEquals(WidgetOption.OFF, place.next(WidgetOption.ON));
        assertEquals(WidgetOption.ON, place.next(WidgetOption.OFF));
    }

    @Test public void optionsLiveUnderTheirWidgetsOwnKeys() {
        assertEquals("widget_weather_unit", WidgetOption.storageKey("weather", "unit"));
        Map<String, String> store = new HashMap<>();
        WidgetOption.Values values = new WidgetOption.Values() {
            @Override public String get(String key) { return store.get(key); }
            @Override public void put(String key, String value) { store.put(key, value); }
        };
        values.put(WidgetOption.storageKey("brightness", "step"), "10");
        assertEquals("10", WidgetRegistry.BRIGHTNESS_STEP.valueOrDefault(
                values.get(WidgetOption.storageKey("brightness", "step"))));
        assertEquals("auto", WidgetRegistry.BRIGHTNESS_STEP.valueOrDefault(
                values.get(WidgetOption.storageKey("weather", "step"))));
    }

    // ---- the grid and the brightness reading ---------------------------------------------------------------------

    @Test public void gridMovesWithoutWrapping() {
        // 5 tiles in 3 columns: 0 1 2 / 3 4
        assertEquals(1, WidgetGridNav.move(0, 1, 0, 3, 5));
        assertEquals(0, WidgetGridNav.move(0, -1, 0, 3, 5));
        assertEquals(2, WidgetGridNav.move(2, 1, 0, 3, 5));
        assertEquals("no wrap to the next row", 3, WidgetGridNav.move(3, -1, 0, 3, 5));
        assertEquals(4, WidgetGridNav.move(4, 1, 0, 3, 5));
        assertEquals(3, WidgetGridNav.move(0, 0, 1, 3, 5));
        assertEquals("short row: its last tile", 4, WidgetGridNav.move(2, 0, 1, 3, 5));
        assertEquals(0, WidgetGridNav.move(3, 0, -1, 3, 5));
        assertEquals(1, WidgetGridNav.move(1, 0, -1, 3, 5));
        assertEquals(4, WidgetGridNav.move(4, 0, 1, 3, 5));
        assertEquals(0, WidgetGridNav.move(0, 1, 0, 3, 0));
    }

    @Test public void brightnessReadingsAreValidated() {
        assertNull(BrightnessSource.validated(0, 1, 0, 1));
        assertNull(BrightnessSource.validated(101, 1, 0, 1));
        BrightnessSource.Level l = BrightnessSource.validated(70, 1, 0, 0);
        assertEquals(70, l.level);
        assertTrue(l.on);
        assertFalse(l.night);
        assertFalse(l.canDraw);
    }

    @Test public void brightnessStepSizes() {
        assertEquals(0, BrightnessSource.stepSize("auto"));
        assertEquals(0, BrightnessSource.stepSize(null));
        assertEquals(5, BrightnessSource.stepSize("5"));
        assertEquals(10, BrightnessSource.stepSize("10"));
        assertArrayEquals(new String[] {"auto", "5", "10"}, WidgetRegistry.BRIGHTNESS_STEP.values);
    }
}
