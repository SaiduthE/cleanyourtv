package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DockOpacityTest {
    @Test public void aChoiceMadeOnTheTvWinsOverTheTheme() {
        // Theme says 80, the owner picked 30 in Launcher settings.
        assertEquals(30, Config.chosenDockOpacity(30, 80));
        assertEquals(100, Config.chosenDockOpacity(100, 80));
    }

    @Test public void noChoiceKeepsTheThemeValue() {
        // -1 is what Config passes when nothing is saved (or a theme push cleared it).
        assertEquals(80, Config.chosenDockOpacity(-1, 80));
    }

    @Test public void zeroIsAChoiceAndOutOfRangeIsIgnored() {
        assertEquals(0, Config.chosenDockOpacity(0, 80));
        assertEquals(5, Config.chosenDockOpacity(5, 80));
        assertEquals(80, Config.chosenDockOpacity(101, 80));
        assertEquals(80, Config.chosenDockOpacity(-7, 80));
    }

    @Test public void everyOldPresetKeepsItsPercent() {
        // The old popup saved 100, 70, 50 and 30 as percents under the same key: existing users keep their look.
        for (int preset : DockOpacity.OLD_PRESETS) {
            assertEquals(preset, DockOpacity.fromSaved(preset));
            assertEquals(preset, Config.chosenDockOpacity(preset, 80));
            assertEquals(preset, DockOpacity.forSlider(preset));
        }
    }

    @Test public void anOffGridSavedValueStartsTheSliderOnTheNearestStep() {
        assertEquals(65, DockOpacity.forSlider(66));
        assertEquals(70, DockOpacity.forSlider(68));
        assertEquals(100, DockOpacity.forSlider(99));
        assertEquals(0, DockOpacity.forSlider(2));
    }

    @Test public void percentAndAlphaConvertBothWays() {
        assertEquals(0, DockOpacity.percentToAlpha(0));
        assertEquals(255, DockOpacity.percentToAlpha(100));
        assertEquals(179, DockOpacity.percentToAlpha(70));
        assertEquals(0, DockOpacity.alphaToPercent(0));
        assertEquals(100, DockOpacity.alphaToPercent(255));
        for (int p = 0; p <= 100; p++) assertEquals(p, DockOpacity.alphaToPercent(DockOpacity.percentToAlpha(p)));
        assertEquals(255, DockOpacity.percentToAlpha(500));   // clamped
    }

    @Test public void everyStyleOfBandFollowsThePercent() {
        // Plain band: 100% is the old solid colour, 0% is invisible.
        assertEquals(0xFF141416, DockDecoration.bandColor(false, 100));
        assertEquals(0x00141416, DockDecoration.bandColor(false, 0));
        assertEquals(0xB3141416, DockDecoration.bandColor(false, 70));
        // The cinematic shelf's glass and the frosted glass are scaled too, not left as they were.
        assertEquals(0x8C121014, DockDecoration.bandColor(true, 100));
        assertEquals(0x46121014, DockDecoration.bandColor(true, 50));
        assertEquals(0x24FFFFFF, DockDecoration.glassTop(100));
        assertEquals(0x12FFFFFF, DockDecoration.glassTop(50));
        assertEquals(0x00FFFFFF, DockDecoration.glassBottom(0));
        assertEquals(0x00FFFFFF, DockDecoration.edgeColor(false, true, 0));
        assertEquals(0x38FFFFFF, DockDecoration.edgeColor(false, true, 100));
    }

    @Test public void scalingKeepsTheColourChannels() {
        assertEquals(0x80123456, DockOpacity.scaleColor(0xFF123456, 50));
    }
}
