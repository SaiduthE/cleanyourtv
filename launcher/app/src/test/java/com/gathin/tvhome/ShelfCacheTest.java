package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ShelfCacheTest {

    @Test public void returnsTheValueWithinTheTimeToLive() {
        ShelfCache<String> cache = new ShelfCache<>(60_000, 4);
        cache.put("a", "one", 1_000);
        assertEquals("one", cache.get("a", 1_000));
        assertEquals("one", cache.get("a", 60_999));
    }

    @Test public void expiresAtTheTimeToLiveAndForgetsIt() {
        ShelfCache<String> cache = new ShelfCache<>(60_000, 4);
        cache.put("a", "one", 1_000);
        assertNull(cache.get("a", 61_000));
        assertEquals(0, cache.size());
    }

    @Test public void aClockThatWentBackwardsIsAMiss() {
        ShelfCache<String> cache = new ShelfCache<>(60_000, 4);
        cache.put("a", "one", 5_000);
        assertNull(cache.get("a", 1_000));
    }

    @Test public void keepsTheNewestFewKeys() {
        ShelfCache<Integer> cache = new ShelfCache<>(60_000, 2);
        cache.put("a", 1, 0);
        cache.put("b", 2, 0);
        cache.put("c", 3, 0);
        assertNull(cache.get("a", 1));
        assertEquals(Integer.valueOf(2), cache.get("b", 1));
        assertEquals(Integer.valueOf(3), cache.get("c", 1));
    }

    @Test public void putAgainRefreshesTheEntry() {
        ShelfCache<String> cache = new ShelfCache<>(60_000, 2);
        cache.put("a", "old", 0);
        cache.put("a", "new", 50_000);
        assertEquals("new", cache.get("a", 100_000));
        cache.clear();
        assertNull(cache.get("a", 100_000));
    }
}
