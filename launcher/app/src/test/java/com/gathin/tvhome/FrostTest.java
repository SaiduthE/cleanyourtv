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
        assertEquals(0.27f, Frost.darken(60), 0.001f);
        assertEquals(0.45f, Frost.darken(100), 0.001f);
        assertEquals(0.45f, Frost.darken(500), 0.001f);
        assertEquals(0f, Frost.darken(-5), 0f);
    }

    @Test
    public void darkFillsKeepAThirdOfTheirAlphaOverFrost() {
        // The opaque card of Quick settings, a menu: 0xFF drops to about 0.32 of it.
        assertEquals(Math.round(255 * Frost.FILL_KEEP), Frost.fillOnFrost(0xFF2A2A2E) >>> 24);
        assertEquals(0x2A2A2E, Frost.fillOnFrost(0xFF2A2A2E) & 0xFFFFFF);
        assertTrue(Frost.FILL_KEEP >= 0.25f && Frost.FILL_KEEP <= 0.35f);
    }

    @Test
    public void dockBandStillFollowsItsOpacityOverFrost() {
        int at100 = Frost.fillOnFrost(DockDecoration.bandColor(false, 100)) >>> 24;
        int at65 = Frost.fillOnFrost(DockDecoration.bandColor(false, 65)) >>> 24;
        int at30 = Frost.fillOnFrost(DockDecoration.bandColor(false, 30)) >>> 24;
        int at0 = Frost.fillOnFrost(DockDecoration.bandColor(false, 0)) >>> 24;
        assertTrue(at100 > at65 && at65 > at30 && at30 > at0);
        assertEquals(0, at0);
        assertEquals(Math.round(166 * Frost.FILL_KEEP), at65, 1);   // 65 percent of 255 is 166
    }

    @Test
    public void whiteGlassIsLeftAsDesigned() {
        assertEquals(0x2EFFFFFF, Frost.fillOnFrost(0x2EFFFFFF));
        assertEquals(0x14FFFFFF, Frost.fillOnFrost(0x14FFFFFF));
        assertTrue(Frost.isDarkFill(0xFF141416));
        assertTrue(!Frost.isDarkFill(0x24FFFFFF));
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

    @Test
    public void centerCropOfAWiderPictureCutsTheSides() {
        // 4000x1000 on a 16:9 screen: the full height, 1778 px wide, centred.
        assertArrayEquals(new int[] {1111, 0, 1778, 1000}, Frost.centerCrop(4000, 1000, 1920, 1080));
    }

    @Test
    public void centerCropOfATallerPictureCutsTopAndBottom() {
        // 1000x1000 on 16:9: the full width, 563 px tall, centred.
        assertArrayEquals(new int[] {0, 218, 1000, 563}, Frost.centerCrop(1000, 1000, 1920, 1080));
    }

    @Test
    public void centerCropOfAMatchingPictureIsTheWholePicture() {
        assertArrayEquals(new int[] {0, 0, 1920, 1080}, Frost.centerCrop(1920, 1080, 1920, 1080));
        assertArrayEquals(new int[] {0, 0, 960, 540}, Frost.centerCrop(960, 540, 1920, 1080));
    }

    @Test
    public void centerCropNeverLeavesThePicture() {
        int[][] sizes = {{1, 1}, {3, 900}, {5000, 7}, {1921, 1080}, {1920, 1081}};
        for (int[] s : sizes) {
            int[] r = Frost.centerCrop(s[0], s[1], 1920, 1080);
            assertTrue(r[2] >= 1 && r[3] >= 1);
            assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= s[0] && r[1] + r[3] <= s[1]);
        }
    }

    @Test
    public void bakeSizesRoundUpToAStepSoAnimationsReuseOneBake() {
        assertEquals(1472, GlassSurface.snap(1409, 64));
        assertEquals(1472, GlassSurface.snap(1472, 64));
        assertEquals(1536, GlassSurface.snap(1473, 64));
        assertEquals(256, GlassSurface.snap(241, 16));
        assertEquals(241, GlassSurface.snap(241, 1));
        assertEquals(0, GlassSurface.snap(0, 16));
        // Every height an animation passes through in one step bakes to the same size.
        for (int h = 241; h <= 256; h++) assertEquals(256, GlassSurface.snap(h, 16));
    }
}
