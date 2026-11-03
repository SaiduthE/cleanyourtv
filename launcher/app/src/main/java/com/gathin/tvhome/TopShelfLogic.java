package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Top Shelf shows and when, with no Android in it so it runs in JVM tests: the setting, which of the three
 * views a focused app gets, the timings, how the Continue watching rows become a shelf, the wallpaper pool and its
 * rotation, the limits on stretching pictures, and the checks on the addresses the add-on hands back.
 */
final class TopShelfLogic {

    /** The Top Shelf row of Launcher settings. Layered is the default. */
    enum Mode {
        LAYERED("layered"), OFF("off");

        final String key;

        Mode(String key) {
            this.key = key;
        }

        static Mode parse(String saved) {
            for (Mode m : values()) if (m.key.equals(saved)) return m;
            return LAYERED;
        }
    }

    /**
     * What the shelf shows: the wallpaper alone, the show to resume, or the focused app's name (and a small icon)
     * over the wallpaper when it has nothing to resume.
     */
    enum Content { REST, RESUME, APP_NAME }

    /** Focus has to rest on an app this long before the shelf changes. */
    static final long DWELL_MS = 600;
    /** Focus has to stay away from the dock this long before the shelf goes back to the wallpaper. */
    static final long LEAVE_MS = 1500;
    /** How long the answer for one app is reused. */
    static final long CACHE_MS = 60_000;
    /** Apps kept in that cache. */
    static final int CACHE_SIZE = 8;
    /** The largest picture decoded (the canvas of a 4K TV at 4x density). */
    static final int MAX_W = 960, MAX_H = 540;
    /** The up-next thumbnails are decoded at twice their on-screen size at most. */
    static final int THUMB_W = 440, THUMB_H = 248;
    /** At most two up-next items next to the one to resume. */
    static final int MAX_UP_NEXT = 2;
    /** The safe margin of the 960x540 canvas: the title's left margin, and the same at the right edge. */
    static final int SAFE_MARGIN_DP = 58;
    /** Up-next cards: a fixed width (16:9), the gap between stacked cards, and the "Up next" caption above them. */
    static final int UP_NEXT_W_DP = 220, UP_NEXT_MIN_W_DP = 150, UP_NEXT_GAP_DP = 12, UP_NEXT_CAPTION_DP = 24;
    static final int UP_NEXT_MIN_H_DP = 56;
    /** The title is at most this wide; the up-next column keeps {@link #BLOCK_GAP_DP} clear of it. */
    static final int TITLE_MAX_W_DP = 460, BLOCK_GAP_DP = 24;
    /** The room the resume block needs between the header and the dock. */
    static final int RESUME_MIN_DP = 150;
    /** A backdrop narrower than this is never stretched across the shelf: the wallpaper is used instead. */
    static final int MIN_BACKDROP_W = 640;
    /** A full-bleed picture is at least this much wider than tall; posters, covers and squares are under it. */
    static final float MIN_BACKDROP_ASPECT = 1.5f;
    /** The poster card (a picture that is not landscape) is at most this tall and this wide, in dp. */
    static final int CARD_MAX_H_DP = 220, CARD_MAX_W_DP = 220;
    /** No icon or banner is ever drawn larger than this many times its own size. */
    static final float MAX_UPSCALE = 1.5f;
    /** The wallpaper layer changes picture this often, crossfading over {@link #FADE_MS}. */
    static final long ROTATE_MS = 11_000;
    static final int FADE_MS = 1600;
    /** The most pictures the wallpaper pool will hold. */
    static final int POOL_MAX = 40;
    /** The feed rows read for the wallpaper pool (the app shelves read only 3). */
    static final int POOL_FEED_ROWS = 12;

    static final String AUTHORITY = "com.cleanlook.continuewatching.shelf";
    static final String ITEMS_URI = "content://" + AUTHORITY + "/items";

    private TopShelfLogic() { }

    /** One row of the add-on's feed, trimmed to what the shelf uses. */
    static final class Entry {
        final String kind, title, subtitle, pkg, intentUri, backdrop, poster;
        final int progress;

        Entry(String kind, String title, String subtitle, int progress, String pkg, String intentUri,
              String backdrop, String poster) {
            this.kind = kind == null ? "" : kind;
            this.title = title == null ? "" : title.trim();
            this.subtitle = subtitle == null ? "" : subtitle.trim();
            this.progress = Math.max(0, Math.min(100, progress));
            this.pkg = pkg == null ? "" : pkg;
            this.intentUri = intentUri;
            this.backdrop = backdrop;
            this.poster = poster;
        }

        boolean resume() {
            return "resume".equals(kind);
        }

        /** The add-on's own backdrop picture of this row, or null. It may still turn out not to be landscape. */
        String backdropUri() {
            return ownUri(backdrop) ? backdrop : null;
        }

        /** The add-on's own poster picture of this row, or null. */
        String posterUri() {
            return ownUri(poster) ? poster : null;
        }
    }

    /** The show to resume and what comes after it, from one app's rows. */
    static final class Shelf {
        final Entry main;
        final List<Entry> upNext;

        Shelf(Entry main, List<Entry> upNext) {
            this.main = main;
            this.upNext = upNext;
        }
    }

    /**
     * The shelf for one app's rows: the first row partway through (else the first row), then up to two of the
     * "next episode" rows after it. Rows without a title, and rows of another app, are left out. Null when none is left.
     */
    static Shelf shelf(List<Entry> rows, String pkg) {
        List<Entry> usable = new ArrayList<>();
        if (rows != null) {
            for (Entry e : rows) {
                if (e != null && !e.title.isEmpty() && (pkg == null || pkg.equals(e.pkg))) usable.add(e);
            }
        }
        if (usable.isEmpty()) return null;
        Entry main = usable.get(0);
        for (Entry e : usable) {
            if (e.resume()) {
                main = e;
                break;
            }
        }
        List<Entry> next = new ArrayList<>();
        for (Entry e : usable) {
            if (e != main && !e.resume() && next.size() < MAX_UP_NEXT) next.add(e);
        }
        return new Shelf(main, next);
    }

    /**
     * What to show for the app that has focus. The dock is where the shelf lives: focus anywhere else, a mode that is
     * off, or no app name to show, leave the wallpaper alone; a shelf with nothing to resume, or too little room for
     * the resume block, shows the app's name (which needs no room).
     */
    static Content select(Mode mode, boolean onDock, boolean hasShelf, boolean roomForResume, boolean hasName) {
        if (mode != Mode.LAYERED || !onDock) return Content.REST;
        if (hasShelf && roomForResume) return Content.RESUME;
        return hasName ? Content.APP_NAME : Content.REST;
    }

    /** Whether a decoded backdrop is wide enough to stand in for the wallpaper behind the resume block. */
    static boolean backdropWideEnough(int widthPx) {
        return widthPx >= MIN_BACKDROP_W;
    }

    /** How a picture may be used. */
    enum Art { NONE, BACKDROP, POSTER }

    /**
     * A genuine landscape picture (aspect 1.5 or more, 640 px wide or more) may go full-bleed or into the wallpaper
     * rotation. Everything else (posters, square covers, composites, small pictures) is only ever a poster card.
     */
    static boolean isBackdrop(int widthPx, int heightPx) {
        return widthPx >= MIN_BACKDROP_W && heightPx > 0 && widthPx >= heightPx * MIN_BACKDROP_ASPECT;
    }

    static Art classify(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) return Art.NONE;
        return isBackdrop(widthPx, heightPx) ? Art.BACKDROP : Art.POSTER;
    }

    /**
     * The size, in px, a poster card is drawn at: as large as fits in maxW x maxH but never more than
     * {@link #MAX_UPSCALE} times its own size. Null when there is no room or no picture.
     */
    static int[] cardSize(int picW, int picH, int maxW, int maxH) {
        if (picW <= 0 || picH <= 0 || maxW <= 0 || maxH <= 0) return null;
        float scale = Math.min(MAX_UPSCALE, Math.min(maxW / (float) picW, maxH / (float) picH));
        return new int[] {Math.max(1, Math.round(picW * scale)), Math.max(1, Math.round(picH * scale))};
    }

    /** Whether an up-next thumbnail is landscape enough to be cropped to 16:9 (a poster would be mangled). */
    static boolean thumbFits(int widthPx, int heightPx) {
        return widthPx > 0 && heightPx > 0 && widthPx >= heightPx * MIN_BACKDROP_ASPECT;
    }

    /**
     * Whether an app's icon may be drawn at targetPx on its short side: it is a square-ish icon (a wide banner is
     * left out) and the drawing is at most {@link #MAX_UPSCALE} times its own size.
     */
    static boolean iconCrisp(int intrinsicW, int intrinsicH, int targetPx) {
        if (intrinsicW <= 0 || intrinsicH <= 0 || targetPx <= 0) return false;
        if (intrinsicW >= intrinsicH * 1.4f) return false;
        return targetPx <= Math.min(intrinsicW, intrinsicH) * MAX_UPSCALE;
    }

    /** The Continue watching rows' own backdrop addresses, each once, in order: pictures for the wallpaper pool. */
    static List<String> backdrops(List<Entry> rows) {
        List<String> out = new ArrayList<>();
        if (rows == null) return out;
        for (Entry e : rows) {
            if (e != null && ownUri(e.backdrop) && !out.contains(e.backdrop)) out.add(e.backdrop);
        }
        return out;
    }

    /** The wallpaper pool: the launcher's wallpaper paths, then the feed's backdrops, without repeats, at most POOL_MAX. */
    static List<String> pool(List<String> wallpapers, List<String> backdrops) {
        List<String> out = new ArrayList<>();
        for (List<String> source : java.util.Arrays.asList(wallpapers, backdrops)) {
            if (source == null) continue;
            for (String key : source) {
                if (key != null && !out.contains(key) && out.size() < POOL_MAX) out.add(key);
            }
        }
        return out;
    }

    /** The next picture to show: any but the current one (when there is a choice); -1 for an empty pool. */
    static int nextPick(int size, int current, java.util.Random random) {
        if (size <= 0) return -1;
        if (size == 1) return 0;
        int pick = random.nextInt(size - 1);
        return current >= 0 && pick >= current ? pick + 1 : pick;
    }

    /** Where the up-next column goes, in dp: how many cards, how big, and how wide the title may be. */
    static final class UpNextPlan {
        final int count, cardW, cardH, titleMaxW;
        /** The column's fixed width (the card width), its distance from the screen's right edge, and its left/right edges. */
        final int columnW, marginEnd, left, right;

        UpNextPlan(int canvasWDp, int rightReserveDp, int count, int cardW, int cardH, int titleMaxW) {
            this.count = count;
            this.cardW = cardW;
            this.cardH = cardH;
            this.titleMaxW = titleMaxW;
            this.columnW = cardW;
            this.marginEnd = SAFE_MARGIN_DP + Math.max(0, rightReserveDp);
            this.right = canvasWDp - marginEnd;
            this.left = right - columnW;
        }
    }

    /**
     * Places the up-next cards in a column against the right safe margin (a poster card, if any, takes rightReserveDp
     * beside it). Cards are stacked, {@link #UP_NEXT_W_DP} wide and 16:9, as many of count as fit in roomH under the
     * caption (at least one, shortened when even one is too tall). When the column would touch the title block, the
     * cards narrow to at most {@link #UP_NEXT_MIN_W_DP} and the title's maximum width gives way, so nothing overlaps.
     */
    static UpNextPlan upNextPlan(int canvasWDp, int rightReserveDp, int roomHDp, int count) {
        int rightEdge = canvasWDp - SAFE_MARGIN_DP - Math.max(0, rightReserveDp);
        int blockRight = SAFE_MARGIN_DP + TITLE_MAX_W_DP;
        int availW = rightEdge - blockRight - BLOCK_GAP_DP;
        int w = Math.max(UP_NEXT_MIN_W_DP, Math.min(UP_NEXT_W_DP, availW));
        int titleMax = Math.max(0, Math.min(TITLE_MAX_W_DP, rightEdge - w - BLOCK_GAP_DP - SAFE_MARGIN_DP));
        if (count <= 0) return new UpNextPlan(canvasWDp, rightReserveDp, 0, w, w * 9 / 16, titleMax);
        int h = w * 9 / 16;
        int avail = roomHDp - UP_NEXT_CAPTION_DP;
        int fit = (avail + UP_NEXT_GAP_DP) / (h + UP_NEXT_GAP_DP);
        int n = Math.min(count, Math.min(MAX_UP_NEXT, fit));
        if (n < 1) {
            n = 1;
            h = Math.max(UP_NEXT_MIN_H_DP, Math.min(h, avail));
        }
        return new UpNextPlan(canvasWDp, rightReserveDp, n, w, h, titleMax);
    }

    static boolean roomForResume(float availableDp) {
        return availableDp >= RESUME_MIN_DP;
    }

    /** "Resume" for a show partway through, "Play" for the next episode of one. */
    static boolean isResume(Entry main) {
        return main.resume() || main.progress > 0;
    }

    /** Only the add-on's own cached pictures are opened; the address came from another app's process. */
    static boolean ownUri(String uri) {
        return uri != null && uri.toLowerCase(Locale.ROOT).startsWith("content://" + AUTHORITY + "/");
    }
}
