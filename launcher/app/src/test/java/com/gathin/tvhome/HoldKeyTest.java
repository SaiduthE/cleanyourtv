package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HoldKeyTest {

    @Test public void shortPressIsAClick() {
        HoldKey k = new HoldKey();
        assertTrue(k.down(1000, 0));
        assertFalse(k.due(1100));
        assertEquals(HoldKey.Up.CLICK, k.up());
        assertEquals(HoldKey.Up.NONE, k.up());
    }

    @Test public void longPressFiresOnceAndTheReleaseIsConsumed() {
        HoldKey k = new HoldKey();
        k.down(1000, 0);
        assertFalse(k.down(1100, 1));   // a key repeat is not a new hold
        assertTrue(k.due(1000 + HoldKey.HOLD_MS));
        assertFalse(k.due(2000));
        assertEquals(HoldKey.Up.CONSUMED, k.up());
    }

    @Test public void cancelForgetsTheHold() {
        HoldKey k = new HoldKey();
        assertTrue(k.down(1000, 0));
        assertTrue(k.tracking());
        k.cancel();
        assertFalse(k.tracking());
        assertFalse(k.due(9999));
        assertEquals(HoldKey.Up.NONE, k.up());
    }

    @Test public void holdKeys() {
        assertTrue(HoldKey.isHoldKey(23));
        assertTrue(HoldKey.isHoldKey(66));
        assertFalse(HoldKey.isHoldKey(4));
    }
}
