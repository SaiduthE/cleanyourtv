package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.ReorderPill.Stage;

import org.junit.Test;

public class ReorderPillTest {

    @Test public void stageFollowsTheModeAndTheStep() {
        assertEquals(Stage.BROWSE, ReorderPill.stage(false, false, false));
        assertEquals(Stage.CARRY, ReorderPill.stage(false, true, false));
        assertEquals(Stage.DOCK_PLACE, ReorderPill.stage(true, false, false));
        assertEquals(Stage.DOCK_APP, ReorderPill.stage(true, false, true));
        // Edit dock never carries; a stale carry flag must not change its step.
        assertEquals(Stage.DOCK_PLACE, ReorderPill.stage(true, true, false));
    }

    @Test public void carryTitleNamesTheApp() {
        assertEquals("Moving Plex", ReorderPill.carryTitle("Moving %s", "Plex"));
        assertEquals("Moving Prime Video", ReorderPill.carryTitle("Moving %s", "  Prime \n Video "));
    }

    @Test public void longNamesAreCut() {
        String name = ReorderPill.name("A very long application name that does not fit a pill");
        assertTrue(name.length() <= ReorderPill.MAX_NAME);
        assertTrue(name.endsWith("…"));
        assertEquals("Plex", ReorderPill.name("Plex"));
    }

    @Test public void missingNameIsEmpty() {
        assertEquals("", ReorderPill.name(null));
        assertEquals("", ReorderPill.name("   "));
    }
}
