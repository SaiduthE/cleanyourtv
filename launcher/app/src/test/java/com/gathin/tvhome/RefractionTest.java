package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the refraction maths: the lens keeps the centre still, the bevel pulls the edge inward. */
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
    public void theBevelPullsTheEdgeInAboutTheBendAndNeverInsideOut() {
        float bend = 11f * 2f;   // BEND_DP at density 2
        float magX = Refraction.magFor(150f, bend);
        assertEquals(150f / (150f - bend), magX, 1e-5f);
        // A tiny surface is capped, not turned inside out.
        assertEquals(1.7f, Refraction.magFor(2f, 40f), 0f);
        assertEquals(1f, Refraction.magFor(0f, 10f), 0f);
        assertEquals(1f, Refraction.magFor(30f, 0f), 0f);
        // The edge of the bevel samples the bend's distance further in (before the lens).
        float[] m = new float[4];
        Refraction.mapping(4f, 4f, magX, magX, 350f, 140f, 200f, 100f, m);
        float[] edge = sampledScreen(m, 4f, 4f, 200f, 100f, 0f, 40f);
        assertEquals(200f + bend, edge[0], 0.01f);
    }

    @Test
    public void bandWidthIsInDpButNeverMoreThanAFifthOfTheShortSide() {
        assertEquals(9f, Refraction.bandPx(2f, 200f), 0f);
        assertEquals(8f, Refraction.bandPx(2f, 40f), 0f);
        assertEquals(1f, Refraction.bandPx(2f, 2f), 0f);
    }
}
