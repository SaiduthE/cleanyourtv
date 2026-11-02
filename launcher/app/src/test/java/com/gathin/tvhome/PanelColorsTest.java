package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelColorsTest {

    @Test
    public void derivesTheFillsFromTheAccent() {
        PanelColors c = PanelColors.from(0xFF4DAE6D);
        assertEquals(0xFF4DAE6D, c.accent);
        assertEquals(0x554DAE6D, c.focusFill);
        assertEquals(0x334DAE6D, c.selectedFill);
        assertEquals(0xFF4DAE6D, c.selectedStroke);
    }

    @Test
    public void accentWithoutAlphaBecomesOpaque() {
        assertEquals(0xFF123456, PanelColors.from(0x00123456).accent);
    }

    @Test
    public void strokeAndHeadingAreLighterThanTheAccent() {
        PanelColors c = PanelColors.from(0xFF4DAE6D);
        assertTrue(brightness(c.focusStroke) > brightness(c.heading));
        assertTrue(brightness(c.heading) > brightness(c.accent));
        assertEquals(0xFF, c.focusStroke >>> 24);
    }

    @Test
    public void mixEndsAndMiddle() {
        assertEquals(0xFF000000, PanelColors.mix(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, PanelColors.mix(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFF808080, PanelColors.mix(0xFF000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0xFFFFFFFF, PanelColors.mix(0xFF000000, 0xFFFFFFFF, 7f));   // clamped
    }

    @Test
    public void lightenKeepsAlpha() {
        assertEquals(0x80FFFFFF, PanelColors.lighten(0x80FFFFFF, 0.5f));
        assertEquals(0xFF808080, PanelColors.lighten(0xFF000000, 0.5f));
    }

    @Test
    public void alphaReplacesOnlyTheAlpha() {
        assertEquals(0x33ABCDEF, PanelColors.alpha(0xFFABCDEF, 0x33));
    }

    @Test
    public void parsesHexColours() {
        assertEquals(0xFFD9A55B, PanelColors.parse("#D9A55B"));
        assertEquals(0xFFD9A55B, PanelColors.parse(" #d9a55b "));
        assertEquals(0xFF112233, PanelColors.parse("#80112233"));
        assertEquals(0, PanelColors.parse("auto"));
        assertEquals(0, PanelColors.parse("#12345"));
        assertEquals(0, PanelColors.parse("#GGGGGG"));
        assertEquals(0, PanelColors.parse(null));
    }

    @Test
    public void picksTheVividColourOverTheDullMajority() {
        int[] pixels = new int[100];
        for (int i = 0; i < 90; i++) pixels[i] = 0xFF808080;   // grey: no hue at all
        for (int i = 90; i < 100; i++) pixels[i] = 0xFF3AA0D0; // a clear blue
        assertEquals(0xFF3AA0D0, PanelColors.fromPixels(pixels));
    }

    @Test
    public void skipsTooDarkAndTooBrightPixels() {
        int[] pixels = {0xFF050014, 0xFF0A0A3C, 0xFFFFF0F0, 0xFFFFFFFF, 0x00FF0000};
        assertEquals(0, PanelColors.fromPixels(pixels));
    }

    @Test
    public void largestSaturatedBucketWins() {
        int[] pixels = new int[10];
        for (int i = 0; i < 7; i++) pixels[i] = 0xFFC83C3C;   // red
        for (int i = 7; i < 10; i++) pixels[i] = 0xFF3CC83C;  // green
        assertEquals(0xFFC83C3C, PanelColors.fromPixels(pixels));
    }

    @Test
    public void emptyInputGivesNothing() {
        assertEquals(0, PanelColors.fromPixels(new int[0]));
        assertEquals(0, PanelColors.fromPixels(null));
    }

    private static int brightness(int c) {
        return ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
    }
}
