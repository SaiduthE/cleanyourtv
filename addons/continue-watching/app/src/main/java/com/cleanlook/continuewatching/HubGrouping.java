package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Discover hub's layout as plain data: the merged entries grouped by app, one section per app that has any. No
 * Android types, so the ordering and the D-pad column rules are unit tested.
 */
final class HubGrouping {
    private HubGrouping() {}

    static final int MAX_SECTIONS = 8;
    static final int MAX_PER_ROW = 20;

    /** One app's row: its entries in the order {@link Merger#merge} gave them. */
    static final class Section {
        final String pkg;
        final List<Merger.Entry> entries = new ArrayList<>();
        /** Epoch ms of the most recent watch in this app, or 0 when none of its items carries a time. */
        long newest;
        /** The preview channel most of the row came from ("Continue watching"), or empty. */
        String label = "";

        Section(String pkg) {
            this.pkg = pkg;
        }
    }

    /**
     * Sections ordered by the most recent watch (apps with timestamped items first), then by item count, then in the
     * order the apps first appear. Apps with no entry have no section. At most {@code maxSections} sections and
     * {@code maxPerRow} entries in each.
     */
    static List<Section> group(List<Merger.Entry> entries, int maxSections, int maxPerRow) {
        Map<String, Section> byPkg = new LinkedHashMap<>();
        for (Merger.Entry e : entries) {
            if (e == null || e.primary == null) continue;
            String pkg = e.primary.pkg;
            Section s = byPkg.get(pkg);
            if (s == null) {
                s = new Section(pkg);
                byPkg.put(pkg, s);
            }
            s.entries.add(e);
            s.newest = Math.max(s.newest, e.primary.updatedMs);
        }
        List<Section> out = new ArrayList<>(byPkg.values());
        for (Section s : out) s.label = commonChannel(s.entries);
        Collections.sort(out, new Comparator<Section>() {
            @Override public int compare(Section a, Section b) {
                boolean ta = a.newest > 0;
                boolean tb = b.newest > 0;
                if (ta != tb) return ta ? -1 : 1;
                if (ta) {
                    int c = Long.compare(b.newest, a.newest);
                    if (c != 0) return c;
                }
                return Integer.compare(b.entries.size(), a.entries.size());
            }
        });
        if (out.size() > maxSections) out = new ArrayList<>(out.subList(0, Math.max(0, maxSections)));
        for (Section s : out) {
            if (s.entries.size() > maxPerRow) {
                List<Merger.Entry> cut = new ArrayList<>(s.entries.subList(0, Math.max(0, maxPerRow)));
                s.entries.clear();
                s.entries.addAll(cut);
            }
        }
        return out;
    }

    static int totalEntries(List<Section> sections) {
        int n = 0;
        for (Section s : sections) n += s.entries.size();
        return n;
    }

    /** The channel name used by most of the entries (the first one wins a tie), or empty. */
    static String commonChannel(List<Merger.Entry> entries) {
        Map<String, Integer> n = new LinkedHashMap<>();
        String best = "";
        int bestN = 0;
        for (Merger.Entry e : entries) {
            String c = e.primary.channel == null ? "" : e.primary.channel.trim();
            if (c.isEmpty()) continue;
            Integer old = n.get(c);
            int now = old == null ? 1 : old + 1;
            n.put(c, now);
            if (now > bestN) {
                best = c;
                bestN = now;
            }
        }
        return best;
    }

    // ---- D-pad

    /** The column to land on in a row of {@code size} cards: {@code want}, held inside the row. -1 for an empty row. */
    static int clampColumn(int want, int size) {
        if (size <= 0) return -1;
        return Math.max(0, Math.min(want, size - 1));
    }

    /** The section {@code delta} away from {@code from}, or -1 when that is past either end. */
    static int sectionAt(int from, int delta, int count) {
        int to = from + delta;
        return to < 0 || to >= count ? -1 : to;
    }

    /**
     * Where up and down land. One wanted column follows the owner's left and right moves, so passing through a short row
     * and on to a long one returns to where they started; each section also remembers its own last column, for putting
     * focus back after the page is rebuilt.
     */
    static final class Columns {
        private int wanted;
        private final Map<String, Integer> last = new HashMap<>();

        /** Left or right inside a row: this is now the column to hold on to. */
        void moved(String pkg, int column) {
            wanted = Math.max(0, column);
            last.put(pkg, wanted);
        }

        /** Focus arrived on {@code column} by up or down (or a rebuild): remember it for the section, keep the wanted one. */
        void landed(String pkg, int column) {
            last.put(pkg, Math.max(0, column));
        }

        /** The column to focus when moving vertically into a row of {@code size} cards. */
        int landing(int size) {
            return clampColumn(wanted, size);
        }

        /** The column this section was last on, held inside a row of {@code size} cards. */
        int remembered(String pkg, int size) {
            Integer c = last.get(pkg);
            return clampColumn(c == null ? 0 : c, size);
        }

        void reset() {
            wanted = 0;
            last.clear();
        }
    }
}
