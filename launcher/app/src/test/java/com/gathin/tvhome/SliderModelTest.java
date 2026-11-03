package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SliderModelTest {
    @Test public void clampKeepsTheRange() {
        assertEquals(0, SliderModel.clamp(-5, 0, 100));
        assertEquals(100, SliderModel.clamp(105, 0, 100));
        assertEquals(40, SliderModel.clamp(40, 0, 100));
    }

    @Test public void stepsMoveByFiveAndStopAtTheEnds() {
        assertEquals(65, SliderModel.move(60, 1, 5, 0, 100));
        assertEquals(55, SliderModel.move(60, -1, 5, 0, 100));
        assertEquals(100, SliderModel.move(100, 1, 5, 0, 100));
        assertEquals(0, SliderModel.move(0, -1, 5, 0, 100));
        assertEquals(0, SliderModel.move(3, -1, 5, 0, 100));
    }

    @Test public void anOffGridValueLandsOnTheNextStepInThatDirection() {
        assertEquals(75, SliderModel.move(72, 1, 5, 0, 100));
        assertEquals(70, SliderModel.move(72, -1, 5, 0, 100));
        assertEquals(100, SliderModel.move(98, 1, 5, 0, 100));
    }

    @Test public void snapGoesToTheNearestStepTiesUp() {
        assertEquals(70, SliderModel.snap(72, 5, 0, 100));
        assertEquals(75, SliderModel.snap(73, 5, 0, 100));
        assertEquals(75, SliderModel.snap(75, 5, 0, 100));
        assertEquals(100, SliderModel.snap(250, 5, 0, 100));
    }

    @Test public void aHeldKeyDoublesTheStepAfterAboutASecond() {
        assertEquals(5, SliderModel.stepFor(0, 5));
        assertEquals(5, SliderModel.stepFor(999, 5));
        assertEquals(10, SliderModel.stepFor(1000, 5));
        assertEquals(10, SliderModel.stepFor(4000, 5));
    }

    @Test public void repeatsAreThinnedButAPressAlwaysCounts() {
        assertTrue(SliderModel.accept(0, 100, 99));
        assertFalse(SliderModel.accept(3, 150, 100));
        assertTrue(SliderModel.accept(3, 100 + SliderModel.REPEAT_GAP_MS, 100));
    }

    @Test public void fractionRunsAlongTheTrack() {
        assertEquals(0f, SliderModel.fraction(0, 0, 100), 0f);
        assertEquals(0.65f, SliderModel.fraction(65, 0, 100), 0.0001f);
        assertEquals(1f, SliderModel.fraction(130, 0, 100), 0f);
    }
}
