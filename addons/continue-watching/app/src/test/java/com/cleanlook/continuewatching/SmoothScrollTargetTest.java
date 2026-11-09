package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SmoothScrollTargetTest {
    @Test public void focusedRowIsCentredAndClampedToTheContent() {
        assertEquals("centred", 300, SmoothScrollView.centreTarget(500, 400, 1000));
        assertEquals("top row stays at the top", 0, SmoothScrollView.centreTarget(60, 400, 1000));
        assertEquals("last row stops at the end", 1000, SmoothScrollView.centreTarget(1350, 400, 1000));
        assertEquals("content shorter than the view never scrolls", 0, SmoothScrollView.centreTarget(500, 400, -80));
    }
}
