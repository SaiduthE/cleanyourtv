package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the refraction maths: the lens keeps the centre still, the bevel pulls the edge inward and fades out. */
public class RefractionTest {

    @Test
    public void lensIsAFewPercent() {
        assertTrue(Refraction.LENS >= 1.03f && Refraction.LENS <= 1.06f);
        assertTrue(Refraction.BAND_DP >= 3f && Refraction.BAND_DP <= 6f);
    }

    /** Screen point shown at canvas point (u, v): invert the mapping the matrix does (frost px -> canvas). */
    private static float[] sampledScreen(float[] m, float scaleX, float scaleY, float offX, float offY, float u, float v) {
        // canvas = frostpx * m[0] + m[2]  ->  frostpx = (canvas - m[2]) / m[0]; the screen point is frostpx * scale
        float fx = (u - m[2]) / m[0], fy = (v - m[3]) / m[1];
        return new float[] {fx * scaleX, fy * scaleY};
    }

    @Test
    public void noMagnificationIsThePlainSliceMapping() {
        float[] m = new float[4];
        Refraction.mapping(4f, 4f, 1f, 1f, 500f, 300f, 100f, 50f, m);
        assertEquals(4f, m[0], 0f);
        assertEquals(4f, m[1], 0f);
        assertEquals(-100f, m[2], 0f);
        assertEquals(-50f, m[3], 0f);
    }

    @Test
    public void theCentreOfALensShowsTheSameFrostPoint() {
        float[] m = new float[4];
        float screenX = 200f, screenY = 100f, left = 0f, top = 0f, w = 300f, h = 80f;
        Refraction.mapping(4f, 4f, Refraction.LENS, Refraction.LENS, screenX + w / 2, screenY + h / 2, screenX - left,
                screenY - top, m);
        float[] c = sampledScreen(m, 4f, 4f, screenX - left, screenY - top, w / 2, h / 2);
        // The centre canvas point (150, 40) shows the screen point under it: (350, 140).
        assertEquals(screenX + w / 2, c[0], 0.01f);
        assertEquals(screenY + h / 2, c[1], 0.01f);
    }

    @Test
    public void aLensPullsTheEdgeInwardByTheMagnification() {
        float[] m = new float[4];
        float screenX = 200f, screenY = 100f, w = 300f, h = 80f;
        float cx = screenX + w / 2, cy = screenY + h / 2;
        Refraction.mapping(4f, 4f, 1.05f, 1.05f, cx, cy, screenX, screenY, m);
        float[] left = sampledScreen(m, 4f, 4f, screenX, screenY, 0f, h / 2);
        // The left edge (screen x 200) shows frost from 150/1.05 px from the centre: further in than the edge.
        assertEquals(cx - (w / 2) / 1.05f, left[0], 0.01f);
        assertTrue(left[0] > screenX);
        float[] right = sampledScreen(m, 4f, 4f, screenX, screenY, w, h / 2);
        assertTrue(right[0] < screenX + w);
    }

    @Test
    public void theBevelPullIsStrongestAtTheRimAndFadesToNothingAtTheBandsInnerEdge() {
        float band = 12f, bend = 14f;
        assertEquals(bend, Refraction.pull(0f, band, bend), 1e-5f);
        assertEquals(0f, Refraction.pull(band, band, bend), 1e-5f);
        assertEquals(0f, Refraction.pull(band * 3f, band, bend), 0f);
        float last = Float.MAX_VALUE;
        for (float depth = 0f; depth <= band; depth += 0.5f) {
            float p = Refraction.pull(depth, band, bend);
            assertTrue("falls monotonically", p <= last + 1e-6f);
            last = p;
        }
        assertEquals(0f, Refraction.pull(3f, 0f, bend), 0f);
    }

    @Test
    public void theRingNextToTheLensShowsWhatTheLensShows() {
        // Past the band the ring's magnification is the lens's: no step where the bevel ends.
        assertEquals(Refraction.LENS, Refraction.ringMag(300f, 40f, 12f, 14f), 1e-5f);
        assertEquals(Refraction.LENS, Refraction.ringMag(300f, 12f, 12f, 14f), 1e-5f);
    }

    @Test
    public void theBevelRampsSmoothlyAndEveryRingPullsAtLeastTheLens() {
        // Each ring (STEPS of them, 2 px wide) samples the frost a little further in than the lens: the steps between
        // neighbouring rings are small, and never more than the bend over the number of steps (times two).
        float half = 300f, ring = 2f, band = ring * Refraction.STEPS, bend = 14f;
        float previousSource = Float.NaN;
        for (int k = 0; k < Refraction.STEPS; k++) {
            float depth = k * ring + ring / 2f;
            float mag = Refraction.ringMag(half, depth, band, bend);
            assertTrue(mag >= Refraction.LENS - 1e-5f);
            float dist = half - depth;
            float source = dist / mag;   // how far from the centre the ring samples, in screen px
            assertEquals(dist / Refraction.LENS - Refraction.pull(depth, band, bend), source, 1e-3f);
            if (!Float.isNaN(previousSource)) {
                float step = Math.abs((previousSource - ring / Refraction.LENS) - source);   // the jump beyond the lens's own slope
                assertTrue("step " + step, step <= 2f * bend / Refraction.STEPS + 0.5f);
            }
            previousSource = source;
        }
    }

    @Test
    public void aTinyOrZeroSizedSurfaceIsNeverTurnedInsideOut() {
        assertEquals(1.7f, Refraction.magFor(2f, 40f), 0f);
        assertEquals(1f, Refraction.magFor(0f, 10f), 0f);
        assertEquals(1f, Refraction.magFor(30f, 0f), 0f);
        assertEquals(Refraction.LENS, Refraction.ringMag(0f, 0f, 12f, 14f), 0f);
        assertTrue(Refraction.ringMag(6f, 1f, 12f, 40f) <= 1.7f + 1e-5f);
    }

    @Test
    public void bandWidthIsInDpButNeverMoreThanAFifthOfTheShortSide() {
        assertEquals(12f, Refraction.bandPx(2f, 200f), 0f);
        assertEquals(8f, Refraction.bandPx(2f, 40f), 0f);
        assertEquals(1f, Refraction.bandPx(2f, 2f), 0f);
    }
}
