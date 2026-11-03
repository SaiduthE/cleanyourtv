package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntentPolicyTest {
    @Test public void acceptsTheUriShapesWatchNextRowsUse() {
        assertNotNull(IntentPolicy.check("https://www.netflix.com/title/80100172"));
        assertNotNull(IntentPolicy.check("netflix://title/80100172"));
        assertNotNull(IntentPolicy.check("intent://watch/1#Intent;scheme=app;package=com.x;end"));
    }

    @Test public void refusesLocalFilesScriptsAndJunk() {
        assertNull(IntentPolicy.check(null));
        assertNull(IntentPolicy.check("   "));
        assertNull(IntentPolicy.check("file:///sdcard/x.mp4"));
        assertNull(IntentPolicy.check("content://other.app/secret"));
        assertNull(IntentPolicy.check("javascript:alert(1)"));
        assertNull(IntentPolicy.check("android-app://com.x"));
        assertNull(IntentPolicy.check("no scheme here"));
        assertNull(IntentPolicy.check("https://x/\u0000bad"));
        StringBuilder big = new StringBuilder("https://x/");
        for (int i = 0; i < IntentPolicy.MAX_LENGTH; i++) big.append('a');
        assertNull(IntentPolicy.check(big.toString()));
    }

    @Test public void stripRemovesSelectorFlagsFallbackAndForeignComponent() {
        String in = "intent://play/9#Intent;scheme=app;action=android.intent.action.VIEW;launchFlags=0x10000000;"
                + "component=evil.app/.Steal;package=evil.app;S.browser_fallback_url=https%3A%2F%2Fevil;"
                + "S.title=Hello;SEL;component=evil.app/.Other;action=x;end";
        String out = IntentPolicy.strip(in, "com.netflix.ninja");
        assertEquals("intent://play/9#Intent;scheme=app;action=android.intent.action.VIEW;S.title=Hello;end", out);
    }

    @Test public void stripKeepsTheRowsOwnComponentAndExtras() {
        String in = "intent:#Intent;action=android.intent.action.MAIN;component=org.jellyfin.androidtv/.ui.startup.StartupActivity;"
                + "S.ItemId=abc;B.ItemIsUserView=false;end";
        assertEquals(in, IntentPolicy.strip(in, "org.jellyfin.androidtv"));
    }

    @Test public void stripLeavesPlainUrisAlone() {
        assertEquals("https://x/y?package=z;SEL", IntentPolicy.strip("https://x/y?package=z;SEL", "p"));
        assertEquals("intent:no-fragment", IntentPolicy.strip("intent:no-fragment", "p"));
    }

    @Test public void describeShowsSchemesNotAddressesOrExtras() {
        assertEquals("https", IntentPolicy.describe("https://www.netflix.com/title/1?secret=1"));
        assertEquals("netflix", IntentPolicy.describe("netflix://title/1"));
        assertEquals("intent (scheme=app)", IntentPolicy.describe("intent://x#Intent;scheme=app;end"));
        assertEquals("intent (scheme=app, component)",
                IntentPolicy.describe("intent://x#Intent;scheme=app;component=a.b/.C;S.token=zzz;end"));
        assertEquals("intent (component)", IntentPolicy.describe("intent:#Intent;component=a.b/.C;end"));
        assertEquals("(none)", IntentPolicy.describe(""));
        assertFalse(IntentPolicy.describe("intent://x#Intent;S.token=zzz;end").contains("zzz"));
        assertTrue(IntentPolicy.describe("intent:garbage").contains("malformed"));
    }
}
