package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the parts that make Launcher's glass settings show on Home: bake keys, dock opacity, frost strength. */
public class GlassSettingsApplyTest {

    private static GlassSurface.Spec spec(int top, int bottom, int stroke, float strokePx, float light) {
        return new GlassSurface.Spec(top, bottom, stroke, strokePx, 20f, light);
    }

    private static String key(GlassSurface.Spec s, int w, int h, float r, int grain, boolean edge, boolean frost) {
        return GlassSurface.cacheKey(s, w, h, r, grain, edge, frost);
    }

    @Test
    public void bakeKeyChangesWithEveryInput() {
        GlassSurface.Spec base = spec(0x2EFFFFFF, 0x14FFFFFF, 0x38FFFFFF, 2f, 1f);
        String k = key(base, 100, 40, 20f, 3, true, true);
        assertEquals(k, key(base, 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(spec(0x2FFFFFFF, 0x14FFFFFF, 0x38FFFFFF, 2f, 1f), 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(spec(0x2EFFFFFF, 0x15FFFFFF, 0x38FFFFFF, 2f, 1f), 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(spec(0x2EFFFFFF, 0x14FFFFFF, 0x39FFFFFF, 2f, 1f), 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(spec(0x2EFFFFFF, 0x14FFFFFF, 0x38FFFFFF, 3f, 1f), 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(spec(0x2EFFFFFF, 0x14FFFFFF, 0x38FFFFFF, 2f, 0.5f), 100, 40, 20f, 3, true, true));
        assertNotEquals(k, key(base, 101, 40, 20f, 3, true, true));
        assertNotEquals(k, key(base, 100, 41, 20f, 3, true, true));
        assertNotEquals(k, key(base, 100, 40, 19f, 3, true, true));
        assertNotEquals(k, key(base, 100, 40, 20f, 4, true, true));
        assertNotEquals(k, key(base, 100, 40, 20f, 3, false, true));
        assertNotEquals(k, key(base, 100, 40, 20f, 3, true, false));
    }

    @Test
    public void aDockBandAtAnotherOpacityBakesAnotherBitmap() {
        // The band's fill and edge alphas and its light scale with the opacity, so the key moves with it.
        GlassSurface.Spec at100 = new GlassSurface.Spec(DockDecoration.bandColor(false, 100), DockDecoration.bandColor(false, 100),
                DockDecoration.edgeColor(false, false, 100), 1f, 0f, 1f);
        GlassSurface.Spec at50 = new GlassSurface.Spec(DockDecoration.bandColor(false, 50), DockDecoration.bandColor(false, 50),
                DockDecoration.edgeColor(false, false, 50), 1f, 0f, 0.5f);
        assertNotEquals(key(at100, 600, 120, 0f, 3, true, true), key(at50, 600, 120, 0f, 3, true, true));
    }

    @Test
    public void dockOpacityMapsToFillAlphaAndFrostAlpha() {
        assertEquals(0f, DockDecoration.frostAlphaFor(0), 0f);
        assertEquals(0.5f, DockDecoration.frostAlphaFor(50), 0f);
        assertEquals(1f, DockDecoration.frostAlphaFor(100), 0f);
        assertEquals(1f, DockDecoration.frostAlphaFor(250), 0f);
        assertEquals(0f, DockDecoration.frostAlphaFor(-4), 0f);
        // The band's own alpha follows the same percent.
        assertEquals(0, DockDecoration.bandColor(false, 0) >>> 24);
        assertEquals(0xFF, DockDecoration.bandColor(false, 100) >>> 24);
        assertEquals(Math.round(0xFF * 0.3f), DockDecoration.bandColor(false, 30) >>> 24);
    }

    @Test
    public void aBandAtZeroDrawsNoFrostAndFullDrawsAll() {
        assertEquals(0, Frost.paintAlpha(DockDecoration.frostAlphaFor(0), 60));
        assertEquals(255, Frost.paintAlpha(DockDecoration.frostAlphaFor(100), 60));
        assertEquals(Math.round(255 * 0.4f), Frost.paintAlpha(DockDecoration.frostAlphaFor(40), 100));
    }

    @Test
    public void frostStrengthIsVisibleAcrossTheSlider() {
        assertEquals(1f, Frost.opacity(60), 0f);
        assertEquals(1f, Frost.opacity(100), 0f);
        float last = 0f;
        for (int s = 0; s <= 60; s += 5) {
            float o = Frost.opacity(s);
            assertTrue("rises at " + s, o > last || (s == 0 && o > 0f));
            last = o;
        }
        assertTrue(Frost.opacity(20) < Frost.opacity(40));
        assertTrue(Frost.opacity(0) < 0.5f);
        // And it still darkens above that.
        assertTrue(Frost.darken(100) > Frost.darken(60));
    }

    @Test
    public void aSpecWithNoFrostAlphaDrawsNone() {
        GlassSurface.Spec s = new GlassSurface.Spec(0, 0, 0, 0f, 0f, 1f, true, 0f);
        assertEquals(0f, s.frostAlpha, 0f);
        assertEquals(1f, spec(0, 0, 0, 0f, 1f).frostAlpha, 0f);
        assertEquals(1f, new GlassSurface.Spec(0, 0, 0, 0f, 0f, 1f, true, 9f).frostAlpha, 0f);
    }

    @org.junit.Test
    public void glassOpacityFollowsTheSettingWithAFloor() {
        org.junit.Assert.assertEquals(1f, GlassLook.surfaceOpacity(100), 0.001f);
        org.junit.Assert.assertEquals(0.65f, GlassLook.surfaceOpacity(65), 0.001f);
        org.junit.Assert.assertEquals(GlassLook.MIN_SURFACE_OPACITY, GlassLook.surfaceOpacity(0), 0.001f);
        org.junit.Assert.assertEquals(GlassLook.MIN_SURFACE_OPACITY, GlassLook.surfaceOpacity(-5), 0.001f);
        org.junit.Assert.assertEquals(1f, GlassLook.surfaceOpacity(140), 0.001f);
    }
}
