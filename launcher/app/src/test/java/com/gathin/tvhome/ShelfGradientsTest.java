package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfGradientsTest {

    @Test public void positionsStartAtZeroEndAtOneAndIncrease() {
        float[] p = ShelfGradients.positions();
        assertEquals(ShelfGradients.STOPS, p.length);
        assertTrue(ShelfGradients.STOPS >= 5);
        assertEquals(0f, p[0], 0f);
        assertEquals(1f, p[p.length - 1], 0f);
        for (int i = 1; i < p.length; i++) assertTrue(p[i] > p[i - 1]);
    }

    @Test public void aFadeStartsAtThePeakEndsAtZeroAndNeverRises() {
        for (float peak : new float[] {0.2f, 0.34f, 0.45f, 0.5f}) {
            float[] a = ShelfGradients.fadeAlphas(peak);
            assertEquals(peak, a[0], 1e-6f);
            assertEquals(0f, a[a.length - 1], 0f);
            for (int i = 1; i < a.length; i++) assertTrue(a[i] < a[i - 1]);
        }
    }

    @Test public void theFadeIsEasedNotLinear() {
        float[] a = ShelfGradients.fadeAlphas(1f);
        // Flat at the start and at the end: the first and last steps are smaller than a linear 1/5.
        assertTrue(a[0] - a[1] < 0.2f);
        assertTrue(a[a.length - 2] - a[a.length - 1] < 0.2f);
    }

    @Test public void colorsCarryTheTintAndTheAlpha() {
        int[] c = ShelfGradients.colors(0x121014, new float[] {1f, 0.5f, 0f});
        assertEquals(0xFF121014, c[0]);
        assertEquals(0x80121014, c[1]);
        assertEquals(0x00121014, c[2]);
    }

    @Test public void shadesStayLightEnoughForTheTextZone() {
        assertTrue(ShelfGradients.LEFT_SHELF_PEAK <= 0.45f);
        assertTrue(ShelfGradients.LEFT_REST_PEAK <= ShelfGradients.LEFT_SHELF_PEAK);
        assertTrue(ShelfGradients.TOP_PEAK <= 0.45f);
    }

    @Test public void theBottomVeilFollowsTheDockBandOpacity() {
        assertEquals(ShelfGradients.BOTTOM_MIN_PEAK, ShelfGradients.bottomPeak(0, true), 1e-6f);
        assertEquals(ShelfGradients.BOTTOM_MAX_PEAK, ShelfGradients.bottomPeak(100, true), 1e-6f);
        assertEquals(ShelfGradients.BOTTOM_MIN_PEAK, ShelfGradients.bottomPeak(100, false), 1e-6f);
        assertTrue(ShelfGradients.bottomPeak(50, true) > ShelfGradients.bottomPeak(30, true));
        assertEquals(ShelfGradients.BOTTOM_MAX_PEAK, ShelfGradients.bottomPeak(900, true), 1e-6f);
    }
}
