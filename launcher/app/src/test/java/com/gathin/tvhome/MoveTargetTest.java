package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MoveTargetTest {

    private static final int L = MoveTarget.LEFT, R = MoveTarget.RIGHT, U = MoveTarget.UP, D = MoveTarget.DOWN;

    @Test public void leftAndRightStepAndWrapAcrossRows() {
        assertEquals(4, MoveTarget.target(L, 5, 14, 6, false, false));
        assertEquals(6, MoveTarget.target(R, 5, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(L, 0, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(R, 13, 14, 6, false, false));
    }

    @Test public void rtlMirrorsLeftAndRight() {
        assertEquals(6, MoveTarget.target(L, 5, 14, 6, false, true));
        assertEquals(4, MoveTarget.target(R, 5, 14, 6, false, true));
    }

    @Test public void upAndDownMoveAWholeRow() {
        assertEquals(2, MoveTarget.target(U, 8, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(U, 3, 14, 6, false, false));
        assertEquals(9, MoveTarget.target(D, 3, 14, 6, false, false));
    }

    @Test public void downIntoAShortLastRowLandsOnItsLastTile() {
        assertEquals(13, MoveTarget.target(D, 10, 14, 6, false, false));   // row 1 -> short row 2 (12..13)
        assertEquals(-1, MoveTarget.target(D, 13, 14, 6, false, false));   // already on the last row
        assertEquals(-1, MoveTarget.target(D, 12, 14, 6, false, false));
    }

    @Test public void aSingleRowHasNoUpOrDown() {
        assertEquals(-1, MoveTarget.target(U, 4, 14, 14, true, false));
        assertEquals(-1, MoveTarget.target(D, 4, 14, 14, true, false));
        assertEquals(5, MoveTarget.target(R, 4, 14, 14, true, false));
    }

    @Test public void badInputStays() {
        assertEquals(-1, MoveTarget.target(R, -1, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(R, 14, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(99, 3, 14, 6, false, false));
        assertEquals(-1, MoveTarget.target(R, 0, 14, 0, false, false));
    }

    @Test public void availableListsOnlyTheDirectionsThatMove() {
        // First tile of a 14-tile grid in rows of 6: nothing left or up.
        int mask = MoveTarget.available(0, 14, 6, false, false);
        assertFalse(MoveTarget.has(mask, L));
        assertTrue(MoveTarget.has(mask, R));
        assertFalse(MoveTarget.has(mask, U));
        assertTrue(MoveTarget.has(mask, D));
        // The last tile: only Left and Up.
        mask = MoveTarget.available(13, 14, 6, false, false);
        assertTrue(MoveTarget.has(mask, L));
        assertFalse(MoveTarget.has(mask, R));
        assertTrue(MoveTarget.has(mask, U));
        assertFalse(MoveTarget.has(mask, D));
        assertEquals(0, MoveTarget.available(0, 1, 6, false, false));
    }
}
