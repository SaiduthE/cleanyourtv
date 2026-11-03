package com.gathin.tvhome;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The last answer per key (an app's package) for a limited time, so moving along the dock and back does not ask the
 * Continue watching app again. The clock is passed in so JVM tests can step it. Keeps the newest few keys only.
 */
final class ShelfCache<V> {

    private static final class Entry<V> {
        final V value;
        final long at;

        Entry(V value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private final long ttlMs;
    private final int capacity;
    private final Map<String, Entry<V>> entries = new LinkedHashMap<>();

    ShelfCache(long ttlMs, int capacity) {
        this.ttlMs = ttlMs;
        this.capacity = capacity;
    }

    /** The value stored for key less than the time-to-live ago, else null (an expired entry is dropped). */
    V get(String key, long now) {
        Entry<V> e = entries.get(key);
        if (e == null) return null;
        if (now < e.at || now - e.at >= ttlMs) {
            entries.remove(key);
            return null;
        }
        return e.value;
    }

    void put(String key, V value, long now) {
        entries.remove(key);
        entries.put(key, new Entry<>(value, now));
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
