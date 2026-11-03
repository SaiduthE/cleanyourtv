package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts items from every source into hub entries. A title appears once; when it is partway through in
 * several apps, the entry keeps one item per app and the "Open in..." chooser lists them.
 */
final class Merger {
    private Merger() {}

    static final class Entry {
        final String key;
        /** One item per app, in chooser order: the NAS first, then the furthest along. */
        final List<Item> alts = new ArrayList<>();
        Item primary;

        Entry(String key) {
            this.key = key;
        }

        boolean isResume() {
            return primary.kind == Item.RESUME;
        }

        long updated() {
            return primary.updatedMs;
        }
    }

    static String groupKey(Item it) {
        String t = Text.normalizeTitle(it.title);
        return t.isEmpty() ? it.pkg + "#" + it.id : t;
    }

    /** At most one item per (app, title): resume beats next, then the newer, then the Jellyfin API row. */
    static List<Item> collapsePerApp(List<Item> items) {
        Map<String, Item> best = new LinkedHashMap<>();
        for (Item it : items) {
            String k = it.pkg + "|" + groupKey(it);
            Item old = best.get(k);
            if (old == null || beats(it, old)) best.put(k, it);
        }
        return new ArrayList<>(best.values());
    }

    private static boolean beats(Item a, Item b) {
        if (a.kind != b.kind) return a.kind == Item.RESUME;
        if (a.updatedMs != b.updatedMs) return a.updatedMs > b.updatedMs;
        return Item.SRC_JELLYFIN.equals(a.src) && !Item.SRC_JELLYFIN.equals(b.src);
    }

    /** All entries: those partway through first, each group with the most recently watched first. */
    static List<Entry> merge(List<Item> items) {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Item it : collapsePerApp(items)) {
            String k = groupKey(it);
            Entry e = byKey.get(k);
            if (e == null) {
                e = new Entry(k);
                byKey.put(k, e);
            }
            e.alts.add(it);
        }
        List<Entry> out = new ArrayList<>(byKey.values());
        for (Entry e : out) {
            Item newest = null;
            boolean anyResume = false;
            for (Item it : e.alts) if (it.kind == Item.RESUME) anyResume = true;
            for (Item it : e.alts) {
                if (anyResume && it.kind != Item.RESUME) continue;
                if (newest == null || it.updatedMs > newest.updatedMs) newest = it;
            }
            e.primary = newest;
            Collections.sort(e.alts, CHOOSER_ORDER);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.isResume() != b.isResume()) return a.isResume() ? -1 : 1;
                int c = Long.compare(b.updated(), a.updated());
                return c != 0 ? c : Integer.compare(b.primary.weight, a.primary.weight);
            }
        });
        return out;
    }

    /** The NAS before streaming apps, then resumable before next, then furthest progress. */
    static final Comparator<Item> CHOOSER_ORDER = new Comparator<Item>() {
        @Override public int compare(Item a, Item b) {
            boolean ja = Item.SRC_JELLYFIN.equals(a.src);
            boolean jb = Item.SRC_JELLYFIN.equals(b.src);
            if (ja != jb) return ja ? -1 : 1;
            if (a.kind != b.kind) return a.kind == Item.RESUME ? -1 : 1;
            int c = Float.compare(b.progress, a.progress);
            if (c != 0) return c;
            return Long.compare(b.updatedMs, a.updatedMs);
        }
    };
}
