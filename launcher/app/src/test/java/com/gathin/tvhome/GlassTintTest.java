package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for glass_tint: the slider's 0 to 100 maps to a black layer of 0 to 0.7 alpha. */
public class GlassTintTest {

    @Test
    public void zeroAddsNoTintAndAHundredIsSeventyPercentBlack() {
        assertEquals(0, GlassLook.tintAlpha(0));
        assertEquals(Math.round(255 * 0.85f), GlassLook.tintAlpha(100));
        assertEquals(217, GlassLook.tintAlpha(100));
    }

    @Test
    public void everyStepIsDarkerThanTheLastAndOutOfRangeIsClamped() {
        int last = -1;
        for (int p = 0; p <= 100; p += SettingsModel.TINT_STEP) {
            int a = GlassLook.tintAlpha(p);
            assertTrue("tint " + p, a > last);
            last = a;
        }
        assertEquals(GlassLook.tintAlpha(0), GlassLook.tintAlpha(-30));
        assertEquals(GlassLook.tintAlpha(100), GlassLook.tintAlpha(400));
    }

    @Test
    public void theDefaultTwentyIsAboutFourteenPercentBlack() {
        assertEquals(Math.round(255 * 0.85f * 0.2f), GlassLook.tintAlpha(20));
    }

    @Test
    public void theSliderRangeMatchesTheKey() {
        assertEquals(100, SettingsModel.TINT_MAX);
        assertEquals(5, SettingsModel.TINT_STEP);
        assertEquals("glass_tint", Config.TINT_KEY);
        assertTrue(java.util.Arrays.asList(SettingsModel.RESET_KEYS).contains(Config.TINT_KEY));
    }
}
