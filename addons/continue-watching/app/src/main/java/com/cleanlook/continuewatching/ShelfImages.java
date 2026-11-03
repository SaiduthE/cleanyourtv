package com.cleanlook.continuewatching;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pictures the shelf provider hands to the launcher: per shelf row a backdrop (JPEG, at most 960 px wide), a
 * poster (JPEG, 400 px) and a logo (PNG, 480 px, keeps its transparency). They are made here while there is
 * network, so the launcher never needs any, and they are the artwork as published: scaled, never blurred.
 */
final class ShelfImages {
    private ShelfImages() {}

    static final int BACKDROP_WIDTH = 960;
    static final int POSTER_WIDTH = 400;
    static final int LOGO_WIDTH = 480;

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "shelf");
        d.mkdirs();
        return d;
    }

    /** 16 hex digits of the item, "_b", "_p" or "_l", and the extension; the only names the provider serves. */
    static final String NAME_PATTERN = "[0-9a-f]{16}_[bpl]\\.(jpg|png)";

    static String fileName(String itemId, String kind) {
        String stem = Text.sha1Hex("shelf|" + itemId).substring(0, 16);
        switch (kind) {
            case Contract.IMG_BACKDROP: return stem + "_b.jpg";
            case Contract.IMG_POSTER: return stem + "_p.jpg";
            default: return stem + "_l.png";
        }
    }

    /** The content:// URI of an item's picture, or null while there is none. */
    static String uriFor(Context c, Item it, String kind) {
        String name = fileName(it.id, kind);
        File f = new File(dir(c), name);
        if (!f.isFile() || f.length() == 0) return null;
        return new Uri.Builder().scheme("content").authority(Contract.AUTHORITY).appendPath(Contract.PATH_IMG)
                .appendPath(name).build().toString();
    }

    /** Makes the pictures for the rows the shelf would return, and deletes every other file. */
    static void sync(Context c, List<Item> visible) {
        Set<String> keep = new HashSet<>();
        for (ShelfRows.Row r : ShelfRows.build(visible, null, 0, null, null, null)) {
            Item it = Store.find(visible, r.itemId);
            if (it == null) continue;
            File backdrop = new File(dir(c), fileName(it.id, Contract.IMG_BACKDROP));
            File poster = new File(dir(c), fileName(it.id, Contract.IMG_POSTER));
            File logo = new File(dir(c), fileName(it.id, Contract.IMG_LOGO));
            keep.add(backdrop.getName());
            keep.add(poster.getName());
            keep.add(logo.getName());

            if (!ready(backdrop) && !it.backdrop.isEmpty()) {
                make(c, it.backdrop, BACKDROP_WIDTH, backdrop, false);
            }
            if (!ready(poster) && !it.poster.isEmpty()) {
                Bitmap b = App.images(c).decode(it.poster, BACKDROP_WIDTH);
                if (b != null) {
                    // An app that publishes a wide picture as its "poster" (many do): it is also the backdrop.
                    if (b.getWidth() > b.getHeight() && !ready(backdrop) && it.backdrop.isEmpty()) {
                        save(b, BACKDROP_WIDTH, backdrop, false);
                    } else {
                        save(b, POSTER_WIDTH, poster, false);
                    }
                }
            }
            if (!ready(logo) && !it.logo.isEmpty()) make(c, it.logo, LOGO_WIDTH, logo, true);
        }
        File[] all = dir(c).listFiles();
        if (all != null) {
            for (File f : all) if (!keep.contains(f.getName())) f.delete();
        }
    }

    private static boolean ready(File f) {
        return f.isFile() && f.length() > 0;
    }

    private static void make(Context c, String url, int width, File out, boolean alpha) {
        Bitmap b = App.images(c).decode(url, width, alpha);
        if (b != null) save(b, width, out, alpha);
    }

    private static void save(Bitmap src, int width, File out, boolean png) {
        Bitmap b = src;
        if (b.getWidth() > width) {
            int h = Math.max(1, Math.round(b.getHeight() * (width / (float) b.getWidth())));
            b = Bitmap.createScaledBitmap(b, width, h, true);
        }
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            boolean ok = png ? b.compress(Bitmap.CompressFormat.PNG, 100, os) : b.compress(Bitmap.CompressFormat.JPEG, 82, os);
            if (!ok) throw new IOException("compress failed");
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
        } catch (IOException e) {
            tmp.delete();
            out.delete();
        }
    }
}
