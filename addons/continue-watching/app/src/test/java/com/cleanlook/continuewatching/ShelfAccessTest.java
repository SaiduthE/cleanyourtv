package com.cleanlook.continuewatching;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfAccessTest {
    @Test public void thisAppTheLauncherAndSameKeyAppsMayRead() {
        assertTrue(ShelfAccess.allowed(true, false, null));
        assertTrue(ShelfAccess.allowed(false, true, "some.launcher.build"));
        assertTrue(ShelfAccess.allowed(false, false, "com.gathin.tvhome"));
    }

    @Test public void anyoneElseMayNot() {
        assertFalse(ShelfAccess.allowed(false, false, "com.example.evil"));
        assertFalse(ShelfAccess.allowed(false, false, null));
        assertFalse(ShelfAccess.allowed(false, false, "com.gathin.tvhome.evil"));
    }
}
