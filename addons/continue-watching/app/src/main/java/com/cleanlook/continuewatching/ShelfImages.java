package com.cleanlook.continuewatching;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
        String stem = Text.sha1Hex("shelf2|" + itemId).substring(0, 16);
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

    /** A backdrop must be at least this wide and at least this much wider than tall (aspect = width / height). */
    static final int MIN_BACKDROP_WIDTH = 640;
    static final float MIN_BACKDROP_ASPECT = 1.5f;

    /** Which decoded picture becomes the backdrop. */
    static final int PICK_NONE = 0;
    static final int PICK_BACKDROP = 1;
    static final int PICK_POSTER = 2;

    /**
     * A genuine landscape picture: aspect 1.5 or more and 640 px wide or more. Square covers, posters and composites
     * (a framed photo with a logo beside it) are not: stretched over a wide card they look broken.
     */
    static boolean isBackdrop(int width, int height) {
        return width >= MIN_BACKDROP_WIDTH && height > 0 && width >= height * MIN_BACKDROP_ASPECT;
    }

    /**
     * The backdrop is the declared landscape picture; a landscape "poster" replaces it when it is wider. Sizes are
     * of the decoded pictures (0 when there is none). Only a picture that passes {@link #isBackdrop} can be picked;
     * anything else is a poster at best.
     */
    static int pickBackdrop(int backdropWidth, int backdropHeight, int posterWidth, int posterHeight) {
        boolean bd = isBackdrop(backdropWidth, backdropHeight);
        boolean pst = isBackdrop(posterWidth, posterHeight);
        if (pst && (!bd || posterWidth > backdropWidth)) return PICK_POSTER;
        return bd ? PICK_BACKDROP : PICK_NONE;
    }

    /** Makes the pictures for the rows the shelf would return, and deletes every other file. */
    static void sync(Context c, List<Item> visible) {
        Set<String> keep = new HashSet<>();
        Set<String> seen = new HashSet<>();
        List<ShelfRows.Row> rows = new ArrayList<>(ShelfRows.build(visible, null, 0, null, null, null));
        Set<String> pkgs = new LinkedHashSet<>();
        for (Item it : visible) pkgs.add(it.pkg);
        // A launcher may ask for one app (?pkg=) and get more of its items than the overview has.
        for (String pkg : pkgs) rows.addAll(ShelfRows.build(visible, pkg, 0, null, null, null));
        for (ShelfRows.Row r : rows) {
            if (!seen.add(r.itemId)) continue;
            Item it = Store.find(visible, r.itemId);
            if (it == null) continue;
            File backdrop = new File(dir(c), fileName(it.id, Contract.IMG_BACKDROP));
            File poster = new File(dir(c), fileName(it.id, Contract.IMG_POSTER));
            File logo = new File(dir(c), fileName(it.id, Contract.IMG_LOGO));
            keep.add(backdrop.getName());
            keep.add(poster.getName());
            keep.add(logo.getName());

            boolean backdropDone = ready(backdrop);
            boolean needPoster = !ready(poster) && !it.poster.isEmpty();
            // No poster of its own: a declared thumbnail that is not landscape is the poster.
            boolean thumbAsPoster = !ready(poster) && it.poster.isEmpty() && !it.backdrop.isEmpty();
            // An app that publishes a wide picture as its "poster" (many do): it can be the backdrop too.
            Bitmap bd = (!backdropDone || thumbAsPoster) && !it.backdrop.isEmpty()
                    ? App.images(c).decode(it.backdrop, BACKDROP_WIDTH) : null;
            Bitmap pst = (needPoster || !backdropDone) && !it.poster.isEmpty()
                    ? App.images(c).decode(it.poster, BACKDROP_WIDTH) : null;
            int pick = backdropDone ? PICK_NONE : pickBackdrop(bd == null ? 0 : bd.getWidth(),
                    bd == null ? 0 : bd.getHeight(), pst == null ? 0 : pst.getWidth(), pst == null ? 0 : pst.getHeight());
            if (pick == PICK_BACKDROP) save(bd, BACKDROP_WIDTH, backdrop, false);
            if (pick == PICK_POSTER) save(pst, BACKDROP_WIDTH, backdrop, false);
            // A poster that became the backdrop is not also a poster.
            if (needPoster && pst != null && pick != PICK_POSTER) save(pst, POSTER_WIDTH, poster, false);
            if (thumbAsPoster && bd != null && !isBackdrop(bd.getWidth(), bd.getHeight())) save(bd, POSTER_WIDTH, poster, false);
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
