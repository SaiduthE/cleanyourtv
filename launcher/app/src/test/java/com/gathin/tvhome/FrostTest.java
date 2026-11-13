package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the frost's pure parts: sizes, the darkening and the stack blur, and the edge light's colour scaling. */
public class FrostTest {

    @Test
    public void sizesAreASixteenthAndAQuarterOfTheScreen() {
        assertArrayEquals(new int[] {120, 67}, Frost.smallSize(1920, 1080));
        assertArrayEquals(new int[] {480, 270}, Frost.frostSize(1920, 1080));
    }

    @Test
    public void sizesNeverGoUnderEightPixels() {
        assertArrayEquals(new int[] {8, 8}, Frost.smallSize(64, 64));
        assertArrayEquals(new int[] {16, 16}, Frost.frostSize(64, 64));
        assertArrayEquals(new int[] {8, 8}, Frost.frostSize(10, 10));
    }

    @Test
    public void darkeningFollowsTheStrengthAndStopsShortOfBlack() {
        assertEquals(0f, Frost.darken(0), 0f);
        assertEquals(0.48f, Frost.darken(60), 0.001f);
        assertEquals(0.8f, Frost.darken(100), 0.001f);
        assertEquals(0.8f, Frost.darken(500), 0.001f);
        assertEquals(0f, Frost.darken(-5), 0f);
    }

    @Test
    public void blurLeavesAFlatImageFlat() {
        int[] px = new int[20 * 12];
        java.util.Arrays.fill(px, 0xFF336699);
        Frost.stackBlur(px, 20, 12, 4);
        for (int p : px) assertEquals(0xFF336699, p);
    }

    @Test
    public void blurSpreadsASpikeAndKeepsTheLight() {
        int w = 31, h = 31;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        px[15 * w + 15] = 0xFFFFFFFF;
        Frost.stackBlur(px, w, h, 4);
        int centre = px[15 * w + 15] & 0xFF;
        int near = px[15 * w + 17] & 0xFF;
        assertTrue("the spike is softened, " + centre, centre < 255 && centre > near);
        assertTrue("and has spread, " + near, near > 0);
        double sum = 0;
        for (int p : px) sum += p & 0xFF;
        assertEquals(255.0, sum, 255.0 * 0.15);
    }

    @Test
    public void blurHandlesTinyImages() {
        int[] px = {0xFF000000, 0xFFFFFFFF};
        Frost.stackBlur(px, 2, 1, 4);   // too small to blur: left alone, no exception
        Frost.stackBlur(new int[] {0xFF123456}, 1, 1, 4);
    }

    @Test
    public void edgeLightScalesAlphaOnly() {
        assertEquals(0x33FFFFFF, GlassEdge.scaleAlpha(0x66FFFFFF, 0.5f));
        assertEquals(0x00FFFFFF, GlassEdge.scaleAlpha(0x66FFFFFF, 0f));
        assertEquals(0x66FFFFFF, GlassEdge.scaleAlpha(0x66FFFFFF, 2f));
        assertEquals(0x80112233, GlassEdge.scaleAlpha(0x80112233, 1f));
    }
}
