package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShelfIntentTest {

    @Test public void checkRefusesWhatMustNotLaunch() {
        assertNull(ShelfIntent.check(null));
        assertNull(ShelfIntent.check("  "));
        assertNull(ShelfIntent.check("file:///sdcard/x"));
        assertNull(ShelfIntent.check("content://a/b"));
        assertNull(ShelfIntent.check("javascript:alert(1)"));
        assertNull(ShelfIntent.check("no scheme here"));
        assertNull(ShelfIntent.check("netflix://title/1\n2"));
        StringBuilder long1 = new StringBuilder("https://x/");
        while (long1.length() <= ShelfIntent.MAX_LENGTH) long1.append('a');
        assertNull(ShelfIntent.check(long1.toString()));
    }

    @Test public void checkKeepsOrdinaryAddresses() {
        assertEquals("netflix://title/1", ShelfIntent.check("  netflix://title/1 "));
        assertEquals("intent:#Intent;component=a/b;end", ShelfIntent.check("intent:#Intent;component=a/b;end"));
    }

    @Test public void schemeAllowedBlocksLocalSchemes() {
        assertFalse(ShelfIntent.schemeAllowed("FILE"));
        assertFalse(ShelfIntent.schemeAllowed("android-app"));
        assertTrue(ShelfIntent.schemeAllowed("https"));
    }

    @Test public void stripDropsSelectorFlagsPackageAndForeignComponent() {
        String uri = "intent:#Intent;action=android.intent.action.VIEW;launchFlags=0x10000000;package=evil;"
                + "component=evil/.Main;S.browser_fallback_url=http%3A%2F%2Fx;SEL;component=evil/.Sel;end";
        assertEquals("intent:#Intent;action=android.intent.action.VIEW;end", ShelfIntent.strip(uri, "good"));
    }

    @Test public void stripKeepsTheRowsOwnComponentAndExtras() {
        String uri = "intent:#Intent;component=good/.Start;S.ItemId=abc;end";
        assertEquals(uri, ShelfIntent.strip(uri, "good"));
    }

    @Test public void stripLeavesOtherSchemesAlone() {
        assertEquals("https://x/y", ShelfIntent.strip("https://x/y", "good"));
        assertEquals("intent:foo", ShelfIntent.strip("intent:foo", "good"));
    }
}
