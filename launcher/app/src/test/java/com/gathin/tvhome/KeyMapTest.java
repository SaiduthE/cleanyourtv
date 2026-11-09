package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KeyMapTest {
    @Test public void aPressDoubleAndHoldLineEachParse() {
        KeyMap.Entry press = KeyMap.parse("199:com.plexapp.android");
        assertEquals(KeyMap.PRESS, press.gesture);
        assertEquals("199", press.token);
        assertEquals("com.plexapp.android", press.target);
        assertEquals(KeyMap.HOLD, KeyMap.parse("hold:199:mute").gesture);
        assertEquals(KeyMap.DOUBLE, KeyMap.parse("dbl:199:home").gesture);
    }

    @Test public void scancodesAndTargetsWithColonsSurvive() {
        KeyMap.Entry scan = KeyMap.parse("scan:393:com.wbd.hbomax");
        assertEquals("scan:393", scan.token);
        assertEquals("com.wbd.hbomax", scan.target);
        KeyMap.Entry input = KeyMap.parse("hold:12:input:com.tcl.tvinput/.Svc/HW1");
        assertEquals("input:com.tcl.tvinput/.Svc/HW1", input.target);
    }

    @Test public void aLineRoundTrips() {
        for (String line : new String[]{"199:a.b", "hold:scan:393:a.b", "dbl:70:ignore"}) {
            assertEquals(line, KeyMap.parse(line).line());
        }
    }

    @Test public void badLinesAreSkipped() {
        assertNull(KeyMap.parse(null));
        assertNull(KeyMap.parse(""));
        assertNull(KeyMap.parse("199:"));
        assertNull(KeyMap.parse("abc:com.x"));
        assertNull(KeyMap.parse("scan:x:com.x"));
        assertNull(KeyMap.parse("scan:393"));
    }

    @Test public void aPressAndAHoldOfOneButtonAreDifferentEntries() {
        assertFalse(KeyMap.parse("199:a.b").id().equals(KeyMap.parse("hold:199:a.b").id()));
    }

    @Test public void theButtonsThePageNeedsAreProtected() {
        assertTrue(KeyMap.isProtected(19));   // DPAD_UP
        assertTrue(KeyMap.isProtected(23));   // DPAD_CENTER
        assertTrue(KeyMap.isProtected(4));    // BACK
        assertFalse(KeyMap.isProtected(199));
    }

    @Test public void tokenPrefersTheKeycodeAndFallsBackToTheScancode() {
        assertEquals("199", KeyMap.token(199, 393));
        assertEquals("scan:393", KeyMap.token(0, 393));
        assertNull(KeyMap.token(0, 0));
    }
}
