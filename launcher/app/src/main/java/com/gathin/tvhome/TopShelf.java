package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Layered Top Shelf above the dock. At rest a crisp rotating picture ({@link ShelfWallpaper}: the add-on's photos, else the launcher's
 * wallpapers; never show artwork) shows under a light veil, with the photo's credit small at the bottom right. When focus rests on a dock app for {@link TopShelfLogic#DWELL_MS} the shelf shows what that app has in
 * Continue watching (the show's own backdrop, where you are, a Resume button and up to two up-next items); an app with
 * nothing to resume gets its name in large type, and its icon when that can be drawn without enlarging it more than
 * {@link TopShelfLogic#MAX_UPSCALE} times, over the wallpaper. Only a genuine landscape picture
 * ({@link TopShelfLogic#isBackdrop}) is ever drawn full-bleed; a poster, cover or square picture is a small crisp
 * card at the right of the resume block, over the wallpaper. A top scrim keeps the clock and date legible.
 * Focus away from the dock for {@link TopShelfLogic#LEAVE_MS} brings the plain wallpaper back. Nothing is blurred and
 * no picture is scaled up beyond that limit. The Resume button and the up-next items take focus (Up from the dock),
 * and the shelf never goes away while one of them has it.
 */
final class TopShelf {

    interface Host {
        /** Puts focus back on the dock tile the shelf belongs to. */
        void focusTiles();

        void open(Intent intent);

        /** The big app name above the dock gives way to the show's title while a show is on the shelf. */
        void setTitleHidden(boolean hidden);

        /** Bottom edge of the clock row and the top bar, in the shelf's coordinates. */
        int headerBottom();
    }

    private static final int ICON_DP = 56;
    private static final int GAP_DP = 28;
    private static final int NO_PIC = 0xFF1C1F26;

    private final Context context;
    private final FrameLayout container;
    private final Host host;
    private final ContentResolver resolver;
    private final boolean animations, reducedMotion;
    /** Launcher settings > Top Shelf, read when Home is built (a change rebuilds Home): the app's own artwork, the photo credit. */
    private final boolean appArtwork, showCredit;
    private final float density;
    private final int accent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ShelfCache<TopShelfLogic.Shelf> cache = new ShelfCache<>(TopShelfLogic.CACHE_MS, TopShelfLogic.CACHE_SIZE);
    private static final TopShelfLogic.Shelf NONE = new TopShelfLogic.Shelf(null, Collections.emptyList());

    private final ShelfWallpaper wall;
    /** The light veil over the wallpaper at rest, and the black stage a real backdrop is shown on. */
    private final View light, stage;
    /** The left text shade at rest (lighter than behind a shelf's own type); gone while shelf content is up. */
    private final View restScrim;
    /** The photo's author and licence: tiny and faint at the bottom right, only while a photo shows on the idle shelf. */
    private final TextView credit;
    private String photoCredit = "";
    /** The dock's top edge last seen, so the credit sits just above the dock. */
    private int lastDockTop;
    private final FrameLayout content;
    private final ImageView art;
    private final View scrim;
    /** A poster (not landscape) as a small card at the right, never stretched over the shelf. */
    private final ImageView card;
    /** Darkens the top (clock, date, weather) so no picture competes with them. */
    private final View topScrim;
    private final LinearLayout nameRow;
    private final ImageView nameIcon;
    private final TextView nameText;
    private final LinearLayout block;
    private final TextView source, title, meta, resumeButton;
    private final FrameLayout progressTrack;
    private final View progressFill;
    private final LinearLayout upNext;
    private final List<FrameLayout> thumbs = new ArrayList<>();
    private final List<ImageView> thumbImages = new ArrayList<>();
    private final List<TextView> thumbLabels = new ArrayList<>();
    private final List<View> thumbShades = new ArrayList<>();

    private Bitmap artBitmap, cardBitmap;
    private final Bitmap[] thumbBitmaps = new Bitmap[TopShelfLogic.MAX_UP_NEXT];

    private TopShelfLogic.Content shown = TopShelfLogic.Content.REST;
    private String shownPkg;
    /** The dock app focus rests on (null when focus is elsewhere) and what the shelf needs of it. */
    private String wantPkg, wantLabel;
    private Drawable wantArt;
    private int wantDockTop;
    private boolean suspended, closed;
    /** Bumped by every new request, so a query or decode that is no longer wanted drops its result. */
    private int generation;
    private boolean leavePending;

    private final Runnable dwell = this::resolve;
    private final Runnable leave = this::leaveDue;

    /** @param wallpapers the launcher's wallpaper pictures (empty when the wallpaper is off); the feed's are added */
    TopShelf(Context context, FrameLayout container, Host host, boolean animations, boolean reducedMotion, int accent,
             List<File> wallpapers) {
        this.context = context;
        this.container = container;
        this.host = host;
        this.resolver = context.getContentResolver();
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        android.content.SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        this.appArtwork = choices.getBoolean(Config.TOP_SHELF_ARTWORK_KEY, true);
        this.showCredit = choices.getBoolean(Config.TOP_SHELF_CREDIT_KEY, true);
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = accent;

        wall = new ShelfWallpaper(context, container, wallpapers, animations, reducedMotion, this::onCredit);
        light = new View(context);
        light.setBackground(lightVeil());
        container.addView(light, new FrameLayout.LayoutParams(-1, -1));
        restScrim = new View(context);
        restScrim.setBackground(sideScrim(0.55f));
        restScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        container.addView(restScrim, new FrameLayout.LayoutParams(-1, -1));
        credit = new TextView(context);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        credit.setTextColor(0x66FFFFFF);
        credit.setTypeface(UiFont.get(context, R.string.font_regular));
        credit.setSingleLine(true);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setIncludeFontPadding(false);
        credit.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        credit.setMaxWidth(dp(520));
        credit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        credit.setVisibility(View.GONE);
        FrameLayout.LayoutParams creditLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        creditLp.setMarginEnd(dp(TopShelfLogic.SAFE_MARGIN_DP));
        container.addView(credit, creditLp);
        stage = new View(context);
        stage.setBackgroundColor(Color.BLACK);
        stage.setAlpha(0f);
        container.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(context);
        content.setAlpha(0f);
        content.setVisibility(View.GONE);
        // Fades draw the pictures with the alpha instead of rendering the whole group into a buffer first.
        content.forceHasOverlappingRendering(false);
        content.setClipChildren(false);
        container.addView(content, new FrameLayout.LayoutParams(-1, -1));
        topScrim = new View(context);
        topScrim.setBackground(topVeil());
        topScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        container.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(190)));
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            sizeTopScrim();
            placeCredit();
        });

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0.8f);
        content.addView(art, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(context);
        content.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setBackground(scrimOnWallpaper());

        card = new ImageView(context);
        card.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.setBackground(round(NO_PIC, 0, 0, dp(8)));
        card.setClipToOutline(true);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM | Gravity.END);
        cardLp.setMarginEnd(dp(TopShelfLogic.SAFE_MARGIN_DP));
        content.addView(card, cardLp);
        card.setVisibility(View.GONE);

        nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setClipChildren(false);
        nameIcon = new ImageView(context);
        nameIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nameRow.addView(nameIcon, margins(0, 0, 18, 0, ICON_DP, ICON_DP));
        nameText = text(48, R.string.font_light, Color.WHITE);
        nameText.setLetterSpacing(-0.01f);
        nameText.setMaxWidth(dp(620));
        nameText.setShadowLayer(dp(14), 0, dp(2), 0x80000000);
        nameRow.addView(nameText);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        nameLp.setMarginStart(dp(TopShelfLogic.SAFE_MARGIN_DP));
        content.addView(nameRow, nameLp);
        nameRow.setVisibility(View.GONE);

        block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setClipChildren(false);
        source = text(13, R.string.font_medium, 0x9EFFFFFF);
        title = text(38, R.string.font_light, Color.WHITE);
        title.setLetterSpacing(-0.01f);
        title.setMaxWidth(dp(460));
        meta = text(15, R.string.font_regular, 0xC7FFFFFF);
        block.addView(source);
        block.addView(title, margins(0, 4, 0, 0));
        block.addView(meta, margins(0, 4, 0, 0));
        progressTrack = new FrameLayout(context);
        progressTrack.setBackground(round(0x40FFFFFF, 0, 0, dp(2)));
        progressFill = new View(context);
        progressFill.setBackground(round(accent, 0, 0, dp(2)));
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0, -1));
        block.addView(progressTrack, margins(0, 12, 0, 0, 240, 3));
        resumeButton = text(16, R.string.font_medium, 0);
        resumeButton.setGravity(Gravity.CENTER_VERTICAL);
        resumeButton.setPadding(dp(20), 0, dp(24), 0);
        resumeButton.setCompoundDrawablePadding(dp(10));
        resumeButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_shelf_play, 0, 0, 0);
        resumeButton.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {Color.BLACK, Color.WHITE}));
        resumeButton.setCompoundDrawableTintList(resumeButton.getTextColors());
        resumeButton.setBackground(stateBackground(0xF5FFFFFF, 0x2EFFFFFF, 0x45FFFFFF, dp(20)));
        focusable(resumeButton, 1.05f);
        block.addView(resumeButton, margins(0, 16, 0, 0, -2, 40));
        FrameLayout.LayoutParams blockLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        blockLp.setMarginStart(dp(TopShelfLogic.SAFE_MARGIN_DP));
        content.addView(block, blockLp);

        upNext = new LinearLayout(context);
        upNext.setOrientation(LinearLayout.VERTICAL);
        upNext.setClipChildren(false);
        TextView upLabel = text(13, R.string.font_medium, 0x99FFFFFF);
        upLabel.setText(R.string.shelf_up_next);
        upNext.addView(upLabel, margins(0, 0, 0, 8));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        for (int i = 0; i < TopShelfLogic.MAX_UP_NEXT; i++) {
            FrameLayout thumb = new FrameLayout(context);
            thumb.setBackground(round(NO_PIC, 0, 0, dp(8)));
            thumb.setClipToOutline(true);
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(image, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(context);
            GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0x00000000, 0xB3000000});
            shade.setBackground(fade);
            thumb.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            TextView label = text(12, R.string.font_medium, 0xF2FFFFFF);
            label.setMaxLines(2);
            label.setPadding(dp(8), 0, dp(8), dp(7));
            thumb.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            thumb.setForeground(stateBackground(0, 0, 0, dp(8), 0xFFFFFFFF));
            focusable(thumb, 1.08f);
            row.addView(thumb, margins(0, i == 0 ? 0 : TopShelfLogic.UP_NEXT_GAP_DP, 0, 0,
                    TopShelfLogic.UP_NEXT_W_DP, TopShelfLogic.UP_NEXT_W_DP * 9 / 16));            thumbs.add(thumb);
            thumbImages.add(image);
            thumbShades.add(shade);
            thumbLabels.add(label);
        }
        upNext.addView(row);
        FrameLayout.LayoutParams upLp = new FrameLayout.LayoutParams(dp(TopShelfLogic.UP_NEXT_W_DP), -2, Gravity.BOTTOM | Gravity.END);
        upLp.setMarginEnd(dp(TopShelfLogic.SAFE_MARGIN_DP));
        content.addView(upNext, upLp);
        block.setVisibility(View.GONE);
        upNext.setVisibility(View.GONE);
    }

    // ---- focus events from Home ------------------------------------------------------------------------------

    /** Focus rests on a dock app (or moved to another one). dockTop is the tile's top edge in the shelf's coordinates. */
    void onAppFocused(String pkg, String label, Drawable artwork, int ambientColor, int dockTop) {
        if (closed || suspended || pkg == null) {
            onFocusLeft();
            return;
        }
        cancelLeave();
        wantLabel = label;
        wantArt = artwork;
        wantDockTop = dockTop;
        if (dockTop > 0 && dockTop != lastDockTop) {
            lastDockTop = dockTop;
            placeCredit();
        }
        if (pkg.equals(wantPkg)) return;   // already shown, waiting for its dwell, or resolved to the wallpaper
        wantPkg = pkg;
        generation++;
        handler.removeCallbacks(dwell);
        handler.postDelayed(dwell, TopShelfLogic.DWELL_MS);
    }

    /** Focus is on something that is neither a dock app nor the shelf. */
    void onFocusLeft() {
        handler.removeCallbacks(dwell);
        generation++;
        wantPkg = null;
        wantArt = null;
        if (shown == TopShelfLogic.Content.REST || leavePending) return;
        leavePending = true;
        handler.postDelayed(leave, TopShelfLogic.LEAVE_MS);
    }

    /** Focus has been away from the dock for LEAVE_MS: the wallpaper again, unless the shelf itself has focus. */
    private void leaveDue() {
        leavePending = false;
        if (!container.hasFocus()) showRest(true);
    }

    private void cancelLeave() {
        leavePending = false;
        handler.removeCallbacks(leave);
    }

    /** Focus entered the Resume button or an up-next item: the shelf stays as it is. */
    void onShelfFocused() {
        cancelLeave();
        handler.removeCallbacks(dwell);
    }

    boolean contains(View view) {
        for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == container) return true;
        }
        return false;
    }

    /** Up from the dock app pkg: focus goes to Resume when the shelf shows that app's show; false otherwise. */
    boolean focusResume(String pkg) {
        if (shown != TopShelfLogic.Content.RESUME || pkg == null || !pkg.equals(shownPkg)) return false;
        return resumeButton.requestFocus();
    }

    /** Reorder mode (and the like) takes the dock for itself: back to the wallpaper at once, and stay there. */
    void suspend(boolean on) {
        suspended = on;
        if (on) {
            handler.removeCallbacks(dwell);
            generation++;
            wantPkg = null;
            showRest(false);
        }
    }

    /** Home is leaving the screen: wallpaper again, instantly, no query in flight, and the wallpaper pictures let go. */
    void pause() {
        handler.removeCallbacks(dwell);
        generation++;
        wantPkg = null;
        wantArt = null;
        showRest(false);
        wall.stop();
    }

    /** Home is on screen: the wallpaper shows and rotates. */
    void resume() {
        if (!closed) wall.start();
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        generation++;
        worker.shutdownNow();
        wall.close();
        art.setImageDrawable(null);
        for (ImageView t : thumbImages) t.setImageDrawable(null);
        card.setImageDrawable(null);
        recycle(artBitmap);
        recycle(cardBitmap);
        artBitmap = cardBitmap = null;
        for (int i = 0; i < thumbBitmaps.length; i++) {
            recycle(thumbBitmaps[i]);
            thumbBitmaps[i] = null;
        }
    }

    // ---- resolving what to show -------------------------------------------------------------------------------

    private void resolve() {
        final String pkg = wantPkg;
        if (closed || suspended || pkg == null) return;
        final int gen = ++generation;
        final TopShelfLogic.Shelf cached = cache.get(pkg, SystemClock.uptimeMillis());
        if (cached == NONE) {
            show(null, null, null, null);
            return;
        }
        worker.execute(() -> {
            TopShelfLogic.Shelf found = cached != null ? cached
                    : TopShelfLogic.shelf(ShelfFeed.query(resolver, pkg), pkg);
            if (gen != generation) return;
            Bitmap back = null, poster = null;
            Bitmap[] small = new Bitmap[TopShelfLogic.MAX_UP_NEXT];
            // App artwork off: the Resume text stands on the photos or wallpapers, so no picture of the app is decoded.
            if (found != null && appArtwork) {
                // Each picture is classified by its decoded size: only a genuine landscape picture is a backdrop;
                // anything else (a poster, a square cover, a composite) is a small card, never stretched.
                for (String uri : new String[] {found.main.backdropUri(), found.main.posterUri()}) {
                    if (uri == null || back != null || poster != null) continue;
                    Bitmap b = ShelfFeed.decode(resolver, uri, TopShelfLogic.MAX_W, TopShelfLogic.MAX_H);
                    if (b == null) continue;
                    switch (TopShelfLogic.classify(b.getWidth(), b.getHeight())) {
                        case BACKDROP: back = b; break;
                        case POSTER: poster = b; break;
                        default: recycle(b);
                    }
                }
                for (int i = 0; i < found.upNext.size() && gen == generation; i++) {
                    Bitmap t = ShelfFeed.decode(resolver, found.upNext.get(i).backdropUri(),
                            TopShelfLogic.THUMB_W, TopShelfLogic.THUMB_H);
                    if (t != null && !TopShelfLogic.thumbFits(t.getWidth(), t.getHeight())) {
                        recycle(t);   // a poster would be cropped to a sliver: the label stands alone
                        t = null;
                    }
                    small[i] = t;
                }
            }
            final TopShelfLogic.Shelf result = found;
            final Bitmap backdrop = back, cardPic = poster;
            handler.post(() -> {
                if (gen != generation || closed) {
                    recycle(backdrop);
                    recycle(cardPic);
                    for (Bitmap b : small) recycle(b);
                    return;
                }
                cache.put(pkg, result == null ? NONE : result, SystemClock.uptimeMillis());
                show(result, backdrop, cardPic, small);
            });
        });
    }

    private void show(TopShelfLogic.Shelf shelf, Bitmap backdrop, Bitmap cardPic, Bitmap[] small) {
        float room = (wantDockTop - dp(GAP_DP) - host.headerBottom()) / density;
        TopShelfLogic.Content what = TopShelfLogic.select(TopShelfLogic.Mode.LAYERED, true, shelf != null,
                TopShelfLogic.roomForResume(room), wantLabel != null && !wantLabel.isEmpty());
        if (what == TopShelfLogic.Content.REST) {
            recycle(backdrop);
            recycle(cardPic);
            if (small != null) for (Bitmap b : small) recycle(b);
            showRest(true);
            return;
        }
        if (what == TopShelfLogic.Content.APP_NAME && shelf != null) {
            // The shelf has a show but no room for it: the app's name stands in; drop the decoded pictures.
            recycle(backdrop);
            recycle(cardPic);
            if (small != null) for (Bitmap b : small) recycle(b);
        }
        shownPkg = wantPkg;
        shown = what;
        updateCredit();
        int artHeight = Math.max(1, wantDockTop);
        FrameLayout.LayoutParams artLp = (FrameLayout.LayoutParams) art.getLayoutParams();
        FrameLayout.LayoutParams scrimLp = (FrameLayout.LayoutParams) scrim.getLayoutParams();
        if (artLp.height != artHeight) {
            artLp.height = scrimLp.height = artHeight;
            art.setLayoutParams(artLp);
            scrim.setLayoutParams(scrimLp);
        }
        boolean backdropShown = what == TopShelfLogic.Content.RESUME && backdrop != null;
        scrim.setBackground(backdropShown ? scrimOnBackdrop() : scrimOnWallpaper());
        if (what == TopShelfLogic.Content.RESUME) {
            nameRow.setVisibility(View.GONE);
            fillResume(shelf, backdrop, cardPic, small);
        } else {
            setArt(null);
            setCard(null);
            card.setVisibility(View.GONE);
            block.setVisibility(View.GONE);
            upNext.setVisibility(View.GONE);
            setThumbs(null);
            fillName();
        }
        // The shelf's own large type takes the place of the app name above the dock.
        host.setTitleHidden(true);
        sizeTopScrim();
        reveal(backdropShown);
    }

    /** No resume item: the app's name in clean large type, and its icon small beside it when it stays crisp. */
    private void fillName() {
        nameText.setText(wantLabel);
        Drawable icon = null;
        int target = dp(ICON_DP);
        if (wantArt != null && TopShelfLogic.iconCrisp(wantArt.getIntrinsicWidth(), wantArt.getIntrinsicHeight(), target)) {
            // A private copy: setting the bounds of the drawable a tile shows would blank the tile.
            icon = wantArt.getConstantState() != null ? wantArt.getConstantState().newDrawable().mutate() : null;
        }
        nameIcon.setImageDrawable(icon);
        nameIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        nameRow.setVisibility(View.VISIBLE);
        placeAboveDock(nameRow);
    }

    private void placeAboveDock(View... views) {
        int bottom = container.getHeight() - wantDockTop + dp(GAP_DP);
        for (View v : views) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom;
                v.setLayoutParams(lp);
            }
        }
    }

    private void fillResume(TopShelfLogic.Shelf shelf, Bitmap backdrop, Bitmap cardPic, Bitmap[] small) {
        TopShelfLogic.Entry main = shelf.main;
        source.setText(wantLabel == null ? "" : wantLabel);
        title.setText(main.title);
        meta.setText(main.subtitle);
        meta.setVisibility(main.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        progressTrack.setVisibility(main.progress > 0 ? View.VISIBLE : View.GONE);
        progressFill.getLayoutParams().width = dp(240) * main.progress / 100;
        progressFill.requestLayout();
        resumeButton.setText(TopShelfLogic.isResume(main) ? R.string.shelf_resume : R.string.shelf_play);
        resumeButton.setOnClickListener(v -> launch(main));
        setArt(backdrop);
        setThumbs(small);
        // A picture that is not landscape is a small card at the right; with a real backdrop there is none.
        int cardW = placeCard(backdrop == null ? cardPic : null);
        if (backdrop != null) recycle(cardPic);
        FrameLayout.LayoutParams upLp = (FrameLayout.LayoutParams) upNext.getLayoutParams();
        // The canvas is the screen's own measured width, never an assumed one.
        int canvasPx = container.getWidth();
        if (canvasPx <= 0 && container.getRootView() != null) canvasPx = container.getRootView().getWidth();
        if (canvasPx <= 0) canvasPx = context.getResources().getDisplayMetrics().widthPixels;
        int canvasDp = Math.round(canvasPx / density);
        float roomDp = (wantDockTop - dp(GAP_DP) - host.headerBottom()) / density - 24;
        // An up-next item without a thumbnail is a compact text-only card, not a tall empty rectangle.
        boolean[] hasPic = new boolean[TopShelfLogic.MAX_UP_NEXT];
        for (int i = 0; i < hasPic.length; i++) hasPic[i] = small != null && small[i] != null;
        TopShelfLogic.UpNextPlan plan = TopShelfLogic.upNextPlan(canvasDp, Math.round(cardW / density) + (cardW > 0 ? 28 : 0),
                Math.round(roomDp), shelf.upNext.size(), hasPic);
        title.setMaxWidth(dp(plan.titleMaxW));
        // The column is exactly one card wide: a wrap_content column was only as wide as its "Up next" caption, so the
        // wider cards overflowed it and ran off the right edge.
        int upEnd = dp(plan.marginEnd), upW = dp(plan.columnW);
        if (upLp.getMarginEnd() != upEnd || upLp.width != upW) {
            upLp.setMarginEnd(upEnd);
            upLp.width = upW;
            upNext.setLayoutParams(upLp);
        }
        for (int i = 0; i < thumbs.size(); i++) {
            boolean on = i < plan.count;
            thumbs.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) continue;
            ViewGroup.LayoutParams tlp = thumbs.get(i).getLayoutParams();
            tlp.width = dp(plan.cardW);
            tlp.height = dp(plan.heights[i]);
            thumbs.get(i).setLayoutParams(tlp);
            thumbShades.get(i).setVisibility(hasPic[i] ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams llp = (FrameLayout.LayoutParams) thumbLabels.get(i).getLayoutParams();
            int labelGravity = hasPic[i] ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL;
            if (llp.gravity != labelGravity) {
                llp.gravity = labelGravity;
                thumbLabels.get(i).setLayoutParams(llp);
            }
            thumbLabels.get(i).setPadding(dp(10), 0, dp(10), hasPic[i] ? dp(7) : 0);
            TopShelfLogic.Entry e = shelf.upNext.get(i);
            thumbLabels.get(i).setText(e.subtitle.isEmpty() ? e.title : e.subtitle + "\n" + e.title);
            thumbs.get(i).setOnClickListener(v -> launch(e));
        }
        upNext.setVisibility(plan.count == 0 ? View.GONE : View.VISIBLE);
        block.setVisibility(View.VISIBLE);
        placeAboveDock(block, upNext, card);
    }

    /** Shows pic as the poster card at its own size (at most 1.5x) when it fits the room; returns its width in px. */
    private int placeCard(Bitmap pic) {
        int[] size = null;
        if (pic != null) {
            int room = wantDockTop - dp(GAP_DP) - host.headerBottom() - dp(24);
            size = TopShelfLogic.cardSize(pic.getWidth(), pic.getHeight(), dp(TopShelfLogic.CARD_MAX_W_DP),
                    Math.min(dp(TopShelfLogic.CARD_MAX_H_DP), room));
        }
        if (size == null) {
            setCard(null);
            recycle(pic);
            card.setVisibility(View.GONE);
            return 0;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        lp.width = size[0];
        lp.height = size[1];
        card.setLayoutParams(lp);
        setCard(pic);
        card.setVisibility(View.VISIBLE);
        return size[0];
    }

    /** The wallpaper layer says which credit goes with the picture on screen. */
    private void onCredit(String text) {
        photoCredit = text == null ? "" : text;
        updateCredit();
    }

    /** Shown only while a photo is up and nothing but the idle shelf is: not over a show, not under an app's name. */
    private void updateCredit() {
        boolean on = showCredit && !photoCredit.isEmpty() && shown == TopShelfLogic.Content.REST && !closed;
        if (on) credit.setText(photoCredit);
        credit.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void placeCredit() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) credit.getLayoutParams();
        int bottom = lastDockTop > 0 ? container.getHeight() - lastDockTop + dp(8) : dp(12);
        if (lp.bottomMargin != bottom && container.getHeight() > 0) {
            lp.bottomMargin = bottom;
            credit.setLayoutParams(lp);
        }
    }

    /** The top scrim covers the clock row and a little below it. */
    private void sizeTopScrim() {
        int h = host.headerBottom() + dp(40);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topScrim.getLayoutParams();
        if (h > dp(40) && lp.height != h) {
            lp.height = h;
            topScrim.setLayoutParams(lp);
        }
    }

    private void launch(TopShelfLogic.Entry e) {
        Intent intent = ShelfFeed.launchIntent(e.intentUri, e.pkg);
        if (intent != null) host.open(intent);
    }

    /** Fades the content in; a real backdrop also fades a black stage in under it and rests the wallpaper. */
    private void reveal(boolean onStage) {
        int ms = SettingsModel.duration(animations, reducedMotion, 240);
        stage.animate().cancel();
        content.animate().cancel();
        content.setVisibility(View.VISIBLE);
        wall.setRotating(!onStage);
        restScrim.animate().cancel();
        restScrim.animate().alpha(0f).setDuration(ms).start();
        float stageAlpha = onStage ? 1f : 0f;
        if (ms == 0) {
            stage.setAlpha(stageAlpha);
            content.setAlpha(1f);
            return;
        }
        stage.animate().alpha(stageAlpha).setDuration(ms).start();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(ms).start();
    }

    /** Back to the plain wallpaper. Never while the Resume button or an up-next item has focus: that goes to the tile first. */
    private void showRest(boolean fade) {
        cancelLeave();
        if (container.hasFocus()) host.focusTiles();
        if (shown == TopShelfLogic.Content.REST && content.getVisibility() != View.VISIBLE) return;
        shown = TopShelfLogic.Content.REST;
        shownPkg = null;
        placeCredit();
        updateCredit();
        host.setTitleHidden(false);
        int ms = fade ? SettingsModel.duration(animations, reducedMotion, 320) : 0;
        stage.animate().cancel();
        content.animate().cancel();
        wall.setRotating(true);
        restScrim.animate().cancel();
        restScrim.animate().alpha(1f).setDuration(ms).start();
        if (ms == 0) {
            stage.setAlpha(0f);
            clearContent();
            return;
        }
        stage.animate().alpha(0f).setDuration(ms).start();
        content.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
            if (shown == TopShelfLogic.Content.REST) clearContent();
        }).start();
    }

    private void clearContent() {
        content.setAlpha(0f);
        content.setVisibility(View.GONE);
        setArt(null);
        setCard(null);
        card.setVisibility(View.GONE);
        setThumbs(null);
        nameIcon.setImageDrawable(null);
    }

    // ---- pictures: at most three held ---------------------------------------------------------------------------

    private void setArt(Bitmap next) {
        Bitmap old = artBitmap;
        artBitmap = next;
        art.setImageBitmap(next);
        retire(old, next);
    }

    private void setCard(Bitmap next) {
        Bitmap old = cardBitmap;
        cardBitmap = next;
        card.setImageBitmap(next);
        retire(old, next);
    }

    private void setThumbs(Bitmap[] next) {
        for (int i = 0; i < thumbBitmaps.length; i++) {
            Bitmap old = thumbBitmaps[i];
            Bitmap b = next == null ? null : next[i];
            thumbBitmaps[i] = b;
            thumbImages.get(i).setImageBitmap(b);
            retire(old, b);
        }
    }

    /** A picture the views no longer show is recycled after the frame that stops drawing it. */
    private void retire(Bitmap old, Bitmap now) {
        if (old != null && old != now) container.postDelayed(() -> recycle(old), 600);
    }

    private static void recycle(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    // ---- views ---------------------------------------------------------------------------------------------------

    /** Over the wallpaper at rest: a little dark at the top and the bottom; the top scrim adds the clock's own shade. */
    private Drawable lightVeil() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x4D000000, 0x14000000, 0x14000000, 0x73000000});
    }

    /** Over everything on the shelf, under the clock row: no frame or bright picture competes with the clock text. */
    private Drawable topVeil() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xB8000000, 0x80000000, 0x00000000});
    }

    /** Behind a real backdrop: the strong shading the picture was always given. */
    private Drawable scrimOnBackdrop() {
        GradientDrawable vertical = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99000000, 0x26000000, 0x26000000, 0xFF000000});
        return new LayerDrawable(new Drawable[] {sideScrim(1f), vertical});
    }

    /** Behind type on the wallpaper: a soft shade from the left and the bottom, so the picture stays bright. */
    private Drawable scrimOnWallpaper() {
        GradientDrawable vertical = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x00000000, 0x4D000000});
        return new LayerDrawable(new Drawable[] {sideScrim(1f), vertical});
    }

    /**
     * The text scrim: a horizontal gradient from {@link TopShelfLogic#SCRIM_ALPHA} dark at the left edge, fading to
     * transparent by 60% of the width, so the right of the picture stays as it is. strength scales the darkness.
     */
    private static Drawable sideScrim(float strength) {
        ShapeDrawable d = new ShapeDrawable(new RectShape());
        d.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int width, int height) {
                int[] colors = new int[TopShelfLogic.SCRIM_ALPHA.length];
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = Math.round(255 * TopShelfLogic.SCRIM_ALPHA[i] * strength) << 24;
                }
                return new LinearGradient(0, 0, Math.max(1, width), 0, colors, TopShelfLogic.SCRIM_POS,
                        Shader.TileMode.CLAMP);
            }
        });
        return d;
    }

    private TextView text(int sp, int font, int color) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setTypeface(UiFont.get(context, font));
        t.setSingleLine(false);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable round(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
    }

    /** Focused: a solid face (or, with a rim colour, only a rim); otherwise a faint face with a hairline. */
    private StateListDrawable stateBackground(int focusedFill, int fill, int hairline, int radius) {
        return stateBackground(focusedFill, fill, hairline, radius, 0);
    }

    private StateListDrawable stateBackground(int focusedFill, int fill, int hairline, int radius, int focusedRim) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = round(focusedFill, focusedRim, focusedRim == 0 ? 0 : dp(2), radius);
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[0], round(fill, hairline, hairline == 0 ? 0 : dp(1), radius));
        return s;
    }

    /** A button the remote can reach: focusable, grows a little when focused, and keys that stay inside the shelf. */
    private void focusable(View v, float focusedScale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, focused) -> {
            if (focused) onShelfFocused();
            int ms = SettingsModel.duration(animations, reducedMotion, 160);
            float s = focused ? focusedScale : 1f;
            if (ms == 0) {
                view.setScaleX(s);
                view.setScaleY(s);
            } else {
                view.animate().scaleX(s).scaleY(s).setDuration(ms).start();
            }
        });
        v.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int at = thumbs.indexOf(view);
            // The up-next cards are a column: Down and Up move between them, Down from the last one goes to the dock.
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (at >= 0 && at + 1 < thumbs.size() && thumbs.get(at + 1).getVisibility() == View.VISIBLE) {
                    thumbs.get(at + 1).requestFocus();
                    return true;
                }
                host.focusTiles();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && at > 0) {
                thumbs.get(at - 1).requestFocus();
                return true;
            }
            // Left of Resume and right of the cards have nowhere to go: stay put rather than leave the shelf.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return view == resumeButton;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return at >= 0 || upNext.getVisibility() != View.VISIBLE;
            return false;
        });
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        return margins(l, t, r, b, -2, -2);
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b, int widthDp, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                widthDp > 0 ? dp(widthDp) : ViewGroup.LayoutParams.WRAP_CONTENT,
                heightDp > 0 ? dp(heightDp) : ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
