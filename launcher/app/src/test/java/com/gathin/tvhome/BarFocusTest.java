package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Plain JVM tests for Left and Right along the top bar. The bar is modelled as its children in order, true where a
 * child can take focus: [Inputs, Brightness chip, Network, Settings] is the Horizon bar.
 */
public class BarFocusTest {
    private static final int INPUTS = 0, BRIGHTNESS = 1, NETWORK = 2, SETTINGS = 3;

    @Test
    public void leftFromTheNetworkPillReachesTheBrightnessChip() {
        assertEquals(BRIGHTNESS, BarFocus.next(new boolean[] {true, true, true, true}, NETWORK, -1));
    }

    @Test
    public void rightFromTheBrightnessChipReachesTheNetworkPill() {
        assertEquals(NETWORK, BarFocus.next(new boolean[] {true, true, true, true}, BRIGHTNESS, 1));
    }

    @Test
    public void leftFromTheChipGoesToWhateverIsBeforeIt() {
        assertEquals(INPUTS, BarFocus.next(new boolean[] {true, true, true, true}, BRIGHTNESS, -1));
    }

    @Test
    public void withTheInputsPillHiddenTheChipIsTheLeftmostThingAndTheNetworkPillIsNot() {
        // The reported bug: with Inputs hidden the network pill counted as the first button and Left skipped the chip.
        boolean[] bar = {false, true, true, true};
        assertEquals(BRIGHTNESS, BarFocus.next(bar, NETWORK, -1));
        assertEquals(-1, BarFocus.next(bar, BRIGHTNESS, -1));   // the end: the widgets before the bar take over
        assertEquals(BRIGHTNESS, BarFocus.edge(bar, 1));
    }

    @Test
    public void hiddenAndUnfocusableChildrenAreSkipped() {
        boolean[] bar = {true, false, false, true};
        assertEquals(SETTINGS, BarFocus.next(bar, INPUTS, 1));
        assertEquals(INPUTS, BarFocus.next(bar, SETTINGS, -1));
    }

    @Test
    public void theEndsOfTheBarHaveNoNeighbour() {
        boolean[] bar = {true, true, true, true};
        assertEquals(-1, BarFocus.next(bar, INPUTS, -1));
        assertEquals(-1, BarFocus.next(bar, SETTINGS, 1));
        assertEquals(-1, BarFocus.next(bar, 7, 1));    // not in the bar
        assertEquals(-1, BarFocus.next(bar, -1, 1));
        assertEquals(-1, BarFocus.next(bar, NETWORK, 0));
    }

    @Test
    public void theEdgesAreTheFirstAndLastFocusableChild() {
        boolean[] bar = {false, true, true, false};
        assertEquals(BRIGHTNESS, BarFocus.edge(bar, 1));
        assertEquals(NETWORK, BarFocus.edge(bar, -1));
        assertEquals(-1, BarFocus.edge(new boolean[] {false, false}, 1));
        assertEquals(-1, BarFocus.edge(new boolean[0], -1));
    }
}
