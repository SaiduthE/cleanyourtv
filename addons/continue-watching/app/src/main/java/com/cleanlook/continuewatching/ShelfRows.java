package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the rows the shelf provider returns: titles, numbers, content:// URIs of pictures this app cached, and one
 * intent that opens the title. Never a remote address or a token.
 */
final class ShelfRows {
    private ShelfRows() {}

    static final int MAX_PER_PACKAGE = 3;
    static final int MAX_TOTAL = 12;

    /** The content:// URI of an item's cached picture ({@link Contract#IMG_BACKDROP} and the like), or null. */
    interface Images {
        String uriFor(Item it, String kind);
    }

    /** The "intent:" URI that opens an item. */
    interface Links {
        String intentFor(Item it);
    }

    /** The app's name, for source_label. */
    interface Labels {
        String labelFor(String pkg);
    }

    static final class Row {
        String itemId;
        String pkg;
        String kind;
        String title;
        String subtitle;
        /** Whole percent. */
        int progress;
        long remainingMs;
        long updatedMs;
        String image;
        String backdrop;
        String poster;
        String logo;
        String intentUri;
        String sourceLabel;
        long fetchedMs;
    }

    static List<Row> build(List<Item> items, String pkgFilter, long fetchedMs, Images images, Labels labels,
            Links links) {
        List<Item> sorted = new ArrayList<>(Merger.collapsePerApp(items));
        Collections.sort(sorted, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if (a.kind != b.kind) return a.kind == Item.RESUME ? -1 : 1;
                return Long.compare(b.updatedMs, a.updatedMs);
            }
        });
        Map<String, Integer> perPkg = new HashMap<>();
        List<Row> out = new ArrayList<>();
        for (Item it : sorted) {
            if (pkgFilter != null && !pkgFilter.isEmpty() && !pkgFilter.equals(it.pkg)) continue;
            Integer n = perPkg.get(it.pkg);
            if (n != null && n >= MAX_PER_PACKAGE) continue;
            perPkg.put(it.pkg, n == null ? 1 : n + 1);
            Row r = new Row();
            r.itemId = it.id;
            r.pkg = it.pkg;
            r.kind = it.kind == Item.RESUME ? "resume" : "next";
            r.title = it.title;
            r.subtitle = it.subtitle();
            r.progress = it.kind == Item.RESUME && it.progress >= 0 ? Math.round(Math.min(1f, it.progress) * 100) : 0;
            r.remainingMs = it.kind == Item.RESUME ? it.remainingMs() : -1;
            r.updatedMs = it.updatedMs;
            r.backdrop = images == null ? null : images.uriFor(it, Contract.IMG_BACKDROP);
            r.poster = images == null ? null : images.uriFor(it, Contract.IMG_POSTER);
            r.logo = images == null ? null : images.uriFor(it, Contract.IMG_LOGO);
            r.image = r.backdrop != null ? r.backdrop : r.poster;
            r.intentUri = links == null ? null : links.intentFor(it);
            r.sourceLabel = labels == null ? it.pkg : labels.labelFor(it.pkg);
            r.fetchedMs = fetchedMs;
            out.add(r);
            if (out.size() >= MAX_TOTAL) break;
        }
        return out;
    }
}
