package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LegibilityTest {

    @Test public void shadowRadiusGrowsWithTheTextAndStaysInRange() {
        assertEquals(8f, Legibility.shadowRadiusDp(10), 1e-4f);
        assertEquals(8f, Legibility.shadowRadiusDp(12), 1e-4f);
        assertEquals(12f, Legibility.shadowRadiusDp(40), 1e-4f);
        assertEquals(12f, Legibility.shadowRadiusDp(96), 1e-4f);
        float last = 0f;
        for (float sp = 8; sp <= 80; sp += 4) {
            float r = Legibility.shadowRadiusDp(sp);
            assertTrue(r >= 8f && r <= 12f);
            assertTrue(r >= last);
            last = r;
        }
    }

    @Test public void shadowDropIsOneOrTwoDp() {
        assertEquals(1f, Legibility.shadowDyDp(12), 0f);
        assertEquals(1f, Legibility.shadowDyDp(20), 0f);
        assertEquals(2f, Legibility.shadowDyDp(52), 0f);
    }

    @Test public void shadowIsBlackAtFortyToFiftyFivePercent() {
        for (float sp : new float[] {10, 13, 16, 26, 34, 52, 120}) {
            float a = Legibility.shadowAlpha(sp);
            assertTrue(sp + " " + a, a >= 0.40f && a <= 0.55f);
            int argb = Legibility.shadowColor(sp);
            assertEquals(0, argb & 0xFFFFFF);
            assertEquals(Math.round(a * 255), argb >>> 24);
        }
        assertTrue(Legibility.shadowAlpha(12) > Legibility.shadowAlpha(52));
    }

    @Test public void lumaOfBlackGreyAndWhite() {
        assertEquals(0f, Legibility.luma(0xFF000000), 1e-6f);
        assertEquals(1f, Legibility.luma(0xFFFFFFFF), 1e-4f);
        assertEquals(0.5f, Legibility.luma(0xFF808080), 0.01f);
        assertTrue(Legibility.luma(0xFF00FF00) > Legibility.luma(0xFFFF0000));
    }

    @Test public void aBrightPatchRaisesTheRegionBrightnessAboveItsMean() {
        assertEquals(0.5f, Legibility.regionLuma(0.5f, 0.5f), 1e-6f);
        assertTrue(Legibility.regionLuma(0.4f, 0.9f) > 0.4f);
        assertEquals(1f, Legibility.regionLuma(1f, 1f), 1e-6f);
        assertEquals(0f, Legibility.regionLuma(0f, 0f), 1e-6f);
    }

    @Test public void theScrimIsClampedAndGrowsWithBrightness() {
        assertEquals(Legibility.SCRIM_MIN, Legibility.scrimAlpha(0f), 1e-6f);
        assertEquals(Legibility.SCRIM_MIN, Legibility.scrimAlpha(Legibility.LUMA_DARK), 1e-6f);
        assertEquals(Legibility.SCRIM_MAX, Legibility.scrimAlpha(Legibility.LUMA_BRIGHT), 1e-6f);
        assertEquals(Legibility.SCRIM_MAX, Legibility.scrimAlpha(1f), 1e-6f);
        assertEquals(0.18f, Legibility.SCRIM_MIN, 0f);
        assertEquals(0.55f, Legibility.SCRIM_MAX, 0f);
        float last = 0f;
        for (float l = -0.2f; l <= 1.2f; l += 0.05f) {
            float a = Legibility.scrimAlpha(l);
            assertTrue(a >= 0.18f - 1e-6f && a <= 0.55f + 1e-6f);
            assertTrue(a >= last - 1e-6f);
            last = a;
        }
    }

    @Test public void theFrostsDarkeningIsUndoneBeforeJudgingThePicture() {
        // A picture of brightness 0.8 blended 0.27 toward the frost's dark colour reads darker; undoing it gives 0.8 back.
        float dark = (0.299f * 16 + 0.587f * 18 + 0.114f * 24) / 255f;
        float t = 0.27f;
        float frost = 0.8f * (1f - t) + dark * t;
        assertTrue(frost < 0.8f);
        assertEquals(0.8f, Legibility.sourceLuma(frost, t), 1e-4f);
        assertEquals(0.5f, Legibility.sourceLuma(0.5f, 0f), 1e-6f);
        // Out of range stays in range.
        assertEquals(0f, Legibility.sourceLuma(0f, 0.45f), 0f);
        assertEquals(1f, Legibility.sourceLuma(1f, 0.45f), 0f);
    }

    @Test public void withoutFrostTheScrimIsTheMiddleValue() {
        assertEquals(Legibility.SCRIM_UNKNOWN, Legibility.scrimFor(Float.NaN, 0.27f), 0f);
        assertTrue(Legibility.SCRIM_UNKNOWN > Legibility.SCRIM_MIN && Legibility.SCRIM_UNKNOWN < Legibility.SCRIM_MAX);
    }

    @Test public void aBrightSkyGetsAStrongerScrimThanANightPhoto() {
        assertTrue(Legibility.scrimFor(0.75f, 0.27f) > Legibility.scrimFor(0.15f, 0.27f));
        assertEquals(Legibility.SCRIM_MIN, Legibility.scrimFor(0.05f, 0.27f), 1e-6f);
    }
}
