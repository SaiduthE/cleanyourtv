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

    @Test public void holdUsesTheGivenLongPressTime() {
        HoldKey k = new HoldKey(750);
        k.down(1000, 0);
        assertFalse(k.due(1749));
        assertTrue(k.due(1750));
        assertEquals(750, k.holdMs());
        assertEquals(HoldKey.Up.CONSUMED, k.up());   // the release after a tip is shown never clicks
    }

    @Test public void tipSitsUnderTheRowWhenThereIsRoom() {
        // card 0..1000, row 100..144, tip 60 high, 12 inset, 2 gap
        assertEquals(146, HoldTip.top(100, 144, 60, 0, 1000, 12, 2));
    }

    @Test public void tipGoesAboveTheRowNearTheBottom() {
        // row 940..984 leaves no room below: tip ends 2 above the row
        assertEquals(940 - 2 - 60, HoldTip.top(940, 984, 60, 0, 1000, 12, 2));
    }

    @Test public void tipStaysInsideTheCard() {
        assertEquals(12, HoldTip.top(0, 44, 400, 0, 300, 12, 2));        // taller than the card: pinned to the top
        assertEquals(1000 - 12 - 60, HoldTip.top(990, 1034, 60, 0, 1000, 12, 2));
    }
}
