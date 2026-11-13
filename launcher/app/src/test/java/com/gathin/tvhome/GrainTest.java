package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the grain noise: deterministic, mid-grey centred, opaque, and the percent helpers. */
public class GrainTest {

    @Test
    public void sameSeedSamePixels() {
        assertArrayEquals(Grain.noisePixels(32, 7L), Grain.noisePixels(32, 7L));
    }

    @Test
    public void anotherSeedAnotherLook() {
        assertFalse(java.util.Arrays.equals(Grain.noisePixels(32, 1L), Grain.noisePixels(32, 2L)));
    }

    @Test
    public void tileIsSizeSquared() {
        assertEquals(128 * 128, Grain.noisePixels(Grain.TILE, 1L).length);
    }

    @Test
    public void meanIsAboutMidGrey() {
        int[] px = Grain.noisePixels(Grain.TILE, 0xC1EA9L);
        double sum = 0;
        for (int p : px) sum += p & 0xFF;
        assertEquals(128.0, sum / px.length, 2.0);
    }

    @Test
    public void greyAndOpaque() {
        for (int p : Grain.noisePixels(16, 3L)) {
            assertEquals(0xFF, p >>> 24);
            assertEquals((p >> 16) & 0xFF, (p >> 8) & 0xFF);
            assertEquals((p >> 8) & 0xFF, p & 0xFF);
        }
    }

    @Test
    public void noiseHasSomeSpread() {
        int[] px = Grain.noisePixels(Grain.TILE, 5L);
        double var = 0;
        for (int p : px) var += Math.pow((p & 0xFF) - 128.0, 2);
        double sd = Math.sqrt(var / px.length);
        assertTrue("fine grain, sd " + sd, sd > 20 && sd < 35);
    }

    @Test
    public void alphaScalesWithPercentAndClamps() {
        assertEquals(0, Grain.alphaFor(0));
        assertEquals(255, Grain.alphaFor(100));
        assertEquals(0, Grain.alphaFor(-5));
        assertEquals(20, Grain.alphaFor(8));
    }

    @Test
    public void glassGrainFollowsTheSliderInVisibleSteps() {
        assertEquals(0, Grain.glassPercent(0));
        assertEquals(1, Grain.glassPercent(1));
        assertEquals(3, Grain.glassPercent(6));
        assertEquals(15, Grain.glassPercent(30));
        int last = 0;
        for (int g = 0; g <= 30; g++) {
            int p = Grain.glassPercent(g);
            assertTrue("never goes down at " + g, p >= last);
            last = p;
        }
        // The default (6) and the top (30) no longer look the same.
        assertTrue(Grain.glassAlpha(30) > 4 * Grain.glassAlpha(6) - 1);
    }

    @Test
    public void glassOverlayAlphaComesFromThePercent() {
        assertEquals(0, Grain.glassAlpha(0));
        assertEquals(Grain.alphaFor(3), Grain.glassAlpha(6));
        assertEquals(Grain.alphaFor(Grain.GLASS_MAX), Grain.glassAlpha(30));
        assertEquals(Grain.glassAlpha(30), Grain.glassAlpha(99));
    }
}
