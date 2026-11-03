package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The sources the owner added, and the rules around them: how the list is saved and ordered, how an existing
 * install is migrated into it, which apps the picker offers, what a tile's status line says, how large an icon may
 * be drawn and where a popup goes. Pure Java; SourcesActivity draws it.
 */
final class AddedSources {
    private AddedSources() {}

    /** The key of the Jellyfin server in the list (every other key is an app's package name). */
    static final String JELLYFIN = "jellyfin";

    // ---- the saved list: keys in the order they were added, one per line

    static List<String> parse(String saved) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (saved != null) for (String k : saved.split("\n")) if (!k.trim().isEmpty()) out.add(k.trim());
        return new ArrayList<>(out);
    }

    static String join(List<String> keys) {
        StringBuilder b = new StringBuilder();
        for (String k : keys) {
            if (b.length() > 0) b.append('\n');
            b.append(k);
        }
        return b.toString();
    }

    /** The list with {@code key} appended; unchanged when it is already there or empty. */
    static List<String> with(List<String> keys, String key) {
        List<String> out = new ArrayList<>(keys);
        if (key != null && !key.isEmpty() && !out.contains(key)) out.add(key);
        return out;
    }

    static List<String> without(List<String> keys, String key) {
        List<String> out = new ArrayList<>(keys);
        out.remove(key);
        return out;
    }

    /** Switch entries ("package|channel") with the package's entries taken out. */
    static Set<String> withoutPackage(Set<String> entries, String pkg) {
        Set<String> out = new HashSet<>();
        for (String e : entries) if (!e.startsWith(pkg + "|")) out.add(e);
        return out;
    }

    // ---- migration: before the added list existed, every app that published rows and was not switched off was shown

    /**
     * What an existing install keeps. Jellyfin when it is set up; each app that publishes Watch Next rows and is not
     * switched off; each app with a channel that is on (the owner's choice, else by name). Jellyfin first, then the
     * apps with the most rows, then by package.
     */
    static List<String> migrate(boolean jellyfinConfigured, Map<String, int[]> probe, Collection<String> wnPackages,
            List<ChannelFilter.Info> chans, Set<String> disabled, Set<String> forcedOn, Set<String> forcedOff,
            String self) {
        Map<String, Integer> rows = new TreeMap<>();
        if (probe != null) {
            for (Map.Entry<String, int[]> e : probe.entrySet()) {
                if (e.getValue()[0] > 0 && !disabled.contains(e.getKey())) rows.put(e.getKey(), e.getValue()[0]);
            }
        }
        if (wnPackages != null) {
            for (String p : wnPackages) if (!disabled.contains(p) && !rows.containsKey(p)) rows.put(p, 1);
        }
        if (chans != null) {
            for (ChannelFilter.Info n : chans) {
                if (!ChannelFilter.enabled(n.pkg, n.name, forcedOn, forcedOff)) continue;
                Integer have = rows.get(n.pkg);
                rows.put(n.pkg, (have == null ? 0 : have) + Math.max(n.rows, 0));
            }
        }
        rows.remove(self);
        rows.remove("(unknown)");
        List<String> apps = new ArrayList<>(rows.keySet());
        final Map<String, Integer> by = rows;
        Collections.sort(apps, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = by.get(b) - by.get(a);
                return d != 0 ? d : a.compareTo(b);
            }
        });
        List<String> out = new ArrayList<>();
        if (jellyfinConfigured) out.add(JELLYFIN);
        out.addAll(apps);
        return out;
    }

    // ---- the picker

    /** One app the picker can offer. */
    static final class Candidate {
        String pkg;
        String name;
        int rows;
        boolean added;
    }

    /**
     * Installed apps that publish Watch Next rows or preview channels, the ones with the most rows first, then by name.
     * Apps already added stay in the list, marked.
     */
    static List<Candidate> candidates(Map<String, int[]> probe, List<ChannelFilter.Info> chans, Collection<String> added,
            Set<String> installed, Map<String, String> labels, String self) {
        Map<String, Integer> chanRows = new TreeMap<>();
        Set<String> pkgs = new LinkedHashSet<>();
        if (probe != null) {
            for (Map.Entry<String, int[]> e : probe.entrySet()) {
                if (e.getValue()[0] > 0 || e.getValue()[1] > 0) pkgs.add(e.getKey());
            }
        }
        if (chans != null) {
            for (ChannelFilter.Info n : chans) {
                pkgs.add(n.pkg);
                Integer have = chanRows.get(n.pkg);
                chanRows.put(n.pkg, (have == null ? 0 : have) + n.rows);
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (String p : pkgs) {
            if (p.equals(self) || p.equals("(unknown)") || (installed != null && !installed.contains(p))) continue;
            int[] c = probe == null ? null : probe.get(p);
            Candidate k = new Candidate();
            k.pkg = p;
            k.name = nameOf(labels, p);
            Integer cr = chanRows.get(p);
            k.rows = (c == null ? 0 : c[0]) + (cr != null ? cr : c == null ? 0 : c[1]);
            k.added = added != null && added.contains(p);
            out.add(k);
        }
        Collections.sort(out, new Comparator<Candidate>() {
            @Override public int compare(Candidate x, Candidate y) {
                if (x.rows != y.rows) return y.rows - x.rows;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return out;
    }

    /** The rest of the installed apps, for "Show all apps": by name, without those {@code skip} already lists. */
    static List<Candidate> others(Collection<String> allPkgs, Collection<String> skip, Collection<String> added,
            Map<String, String> labels, String self) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>(skip);
        for (String p : allPkgs) {
            if (p.equals(self) || !seen.add(p)) continue;
            Candidate k = new Candidate();
            k.pkg = p;
            k.name = nameOf(labels, p);
            k.added = added != null && added.contains(p);
            out.add(k);
        }
        Collections.sort(out, new Comparator<Candidate>() {
            @Override public int compare(Candidate x, Candidate y) {
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return out;
    }

    private static String nameOf(Map<String, String> labels, String pkg) {
        String l = labels == null ? null : labels.get(pkg);
        return l == null || l.isEmpty() ? pkg : l;
    }

    // ---- a tile's status line

    enum Level { ROWS, NONE, ERROR, OFF }

    static final class Status {
        final Level level;
        final String text;

        Status(Level level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    static String rows(int n) {
        return n + (n == 1 ? " row" : " rows");
    }

    /** An app: rows found, no rows, or an error; or off. {@code rows} counts Watch Next rows and readable channel rows. */
    static Status appStatus(boolean on, boolean granted, boolean scanned, int rows, boolean readError) {
        if (!granted) return new Status(Level.ERROR, "Needs access");
        if (!on) return new Status(Level.OFF, "Off");
        if (!scanned) return new Status(Level.NONE, "Not scanned");
        if (rows > 0) return new Status(Level.ROWS, rows(rows));
        if (readError) return new Status(Level.ERROR, "Could not read");
        return new Status(Level.NONE, "No rows found");
    }

    static Status jellyfinStatus(boolean on, boolean configured, String status, int titles) {
        if (!configured) return new Status(Level.ERROR, "Sign in again");
        if (!on) return new Status(Level.OFF, "Off");
        if (Prefs.JF_SIGN_IN.equals(status)) return new Status(Level.ERROR, "Sign in again");
        if (Prefs.JF_UNREACHABLE.equals(status)) return new Status(Level.ERROR, "Not reachable");
        if (Prefs.JF_OK.equals(status)) {
            return titles > 0 ? new Status(Level.ROWS, titles + (titles == 1 ? " title" : " titles"))
                    : new Status(Level.NONE, "Nothing in progress");
        }
        return new Status(Level.NONE, "Not tested");
    }

    // ---- pictures and popup placement

    /** Largest an image is drawn when it came at {@code intrinsicPx}: the box, but never more than 1.5 times its own size. */
    static int fitPx(int intrinsicPx, int boxPx) {
        if (intrinsicPx <= 0) return boxPx;
        return Math.min(boxPx, Math.round(intrinsicPx * 1.5f));
    }

    /** A banner is used only when it can fill the box without being enlarged beyond 1.5 times. */
    static boolean bannerFits(int bannerPx, int boxPx) {
        return bannerPx > 0 && bannerPx * 1.5f >= boxPx;
    }

    /**
     * Top-left of a popup of w x h under the tile (left edges aligned, 'gap' below). Flips to the left edge of the
     * tile's right side when it would leave the screen on the right, above the tile when it would leave it at the
     * bottom, and finally is held inside the screen margin.
     */
    static int[] popupOrigin(int tl, int tt, int tr, int tb, int w, int h, int rootW, int rootH, int margin, int gap) {
        int x = tl;
        if (x + w > rootW - margin) x = tr - w;
        int y = tb + gap;
        if (y + h > rootH - margin) y = tt - gap - h;
        x = Math.max(margin, Math.min(x, rootW - margin - w));
        y = Math.max(margin, Math.min(y, rootH - margin - h));
        return new int[] {x, y};
    }
}
