package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Layered Top Shelf above the dock. At rest the wallpaper shows through a dim veil. When focus rests on a dock
 * app for {@link TopShelfLogic#DWELL_MS} the shelf shows what that app has in Continue watching (the show's own
 * backdrop, where you are, a Resume button and up to two up-next items), or else the app's own artwork. Focus away
 * from the dock for {@link TopShelfLogic#LEAVE_MS} brings the wallpaper back. Nothing is blurred: pictures are
 * decoded small and drawn large, and at most three are held at a time. The Resume button and the up-next items take
 * focus (Up from the dock), and the shelf never goes away while one of them has it.
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

    /** The share of black over the wallpaper at rest. */
    private static final float REST_VEIL = 0.35f;
    private static final int ART_W = 320, ART_H = 180;
    private static final int GAP_DP = 28;
    private static final int THUMB_W_DP = 122, THUMB_H_DP = 69;
    private static final int NO_PIC = 0xFF1C1F26;

    private final Context context;
    private final FrameLayout container;
    private final Host host;
    private final ContentResolver resolver;
    private final boolean animations, reducedMotion;
    private final float density;
    private final int accent;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ShelfCache<TopShelfLogic.Shelf> cache = new ShelfCache<>(TopShelfLogic.CACHE_MS, TopShelfLogic.CACHE_SIZE);
    private static final TopShelfLogic.Shelf NONE = new TopShelfLogic.Shelf(null, Collections.emptyList());

    private final View veil;
    private final FrameLayout content;
    private final ImageView art;
    private final View scrim;
    private final LinearLayout block;
    private final TextView source, title, meta, resumeButton;
    private final FrameLayout progressTrack;
    private final View progressFill;
    private final LinearLayout upNext;
    private final List<FrameLayout> thumbs = new ArrayList<>();
    private final List<ImageView> thumbImages = new ArrayList<>();
    private final List<TextView> thumbLabels = new ArrayList<>();

    private Bitmap artBitmap;
    private final Bitmap[] thumbBitmaps = new Bitmap[TopShelfLogic.MAX_UP_NEXT];

    private TopShelfLogic.Content shown = TopShelfLogic.Content.REST;
    private String shownPkg;
    /** The dock app focus rests on (null when focus is elsewhere) and what the shelf needs of it. */
    private String wantPkg, wantLabel;
    private Drawable wantArt;
    private int wantColor, wantDockTop;
    private boolean suspended, closed;
    /** Bumped by every new request, so a query or decode that is no longer wanted drops its result. */
    private int generation;
    private boolean leavePending;

    private final Runnable dwell = this::resolve;
    private final Runnable leave = this::leaveDue;

    TopShelf(Context context, FrameLayout container, Host host, boolean animations, boolean reducedMotion, int accent) {
        this.context = context;
        this.container = container;
        this.host = host;
        this.resolver = context.getContentResolver();
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.density = context.getResources().getDisplayMetrics().density;
        this.accent = accent;

        veil = new View(context);
        veil.setBackgroundColor(Color.BLACK);
        veil.setAlpha(REST_VEIL);
        container.addView(veil, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(context);
        content.setAlpha(0f);
        content.setVisibility(View.GONE);
        // Fades draw the pictures with the alpha instead of rendering the whole group into a buffer first.
        content.forceHasOverlappingRendering(false);
        content.setClipChildren(false);
        container.addView(content, new FrameLayout.LayoutParams(-1, -1));

        art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setAlpha(0.8f);
        content.addView(art, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(context);
        content.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setBackground(scrimDrawable());

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
        blockLp.setMarginStart(dp(58));
        content.addView(block, blockLp);

        upNext = new LinearLayout(context);
        upNext.setOrientation(LinearLayout.VERTICAL);
        upNext.setClipChildren(false);
        TextView upLabel = text(13, R.string.font_medium, 0x99FFFFFF);
        upLabel.setText(R.string.shelf_up_next);
        upNext.addView(upLabel, margins(0, 0, 0, 8));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
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
            TextView label = text(11, R.string.font_medium, 0xF2FFFFFF);
            label.setMaxLines(2);
            label.setPadding(dp(8), 0, dp(8), dp(7));
            thumb.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            thumb.setForeground(stateBackground(0, 0, 0, dp(8), 0xFFFFFFFF));
            focusable(thumb, 1.08f);
            row.addView(thumb, margins(i == 0 ? 0 : 12, 0, 0, 0, THUMB_W_DP, THUMB_H_DP));
            thumbs.add(thumb);
            thumbImages.add(image);
            thumbLabels.add(label);
        }
        upNext.addView(row);
        FrameLayout.LayoutParams upLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        upLp.setMarginEnd(dp(58));
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
        wantColor = ambientColor;
        wantDockTop = dockTop;
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

    /** Home is leaving the screen: wallpaper again, instantly, and no query in flight. */
    void pause() {
        handler.removeCallbacks(dwell);
        generation++;
        wantPkg = null;
        wantArt = null;
        showRest(false);
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        generation++;
        worker.shutdownNow();
        art.setImageDrawable(null);
        for (ImageView t : thumbImages) t.setImageDrawable(null);
        recycle(artBitmap);
        artBitmap = null;
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
            show(null, null, null);
            return;
        }
        worker.execute(() -> {
            TopShelfLogic.Shelf found = cached != null ? cached
                    : TopShelfLogic.shelf(ShelfFeed.query(resolver, pkg), pkg);
            if (gen != generation) return;
            Bitmap back = null;
            Bitmap[] small = new Bitmap[TopShelfLogic.MAX_UP_NEXT];
            if (found != null) {
                back = ShelfFeed.decode(resolver, found.main.image(), TopShelfLogic.MAX_W, TopShelfLogic.MAX_H);
                for (int i = 0; i < found.upNext.size() && gen == generation; i++) {
                    small[i] = ShelfFeed.decode(resolver, found.upNext.get(i).image(),
                            TopShelfLogic.THUMB_W, TopShelfLogic.THUMB_H);
                }
            }
            final TopShelfLogic.Shelf result = found;
            final Bitmap backdrop = back;
            handler.post(() -> {
                if (gen != generation || closed) {
                    recycle(backdrop);
                    for (Bitmap b : small) recycle(b);
                    return;
                }
                cache.put(pkg, result == null ? NONE : result, SystemClock.uptimeMillis());
                show(result, backdrop, small);
            });
        });
    }

    private void show(TopShelfLogic.Shelf shelf, Bitmap backdrop, Bitmap[] small) {
        float room = (wantDockTop - dp(GAP_DP) - host.headerBottom()) / density;
        TopShelfLogic.Content what = TopShelfLogic.select(TopShelfLogic.Mode.LAYERED, true, shelf != null,
                TopShelfLogic.roomForResume(room), wantArt != null);
        if (what == TopShelfLogic.Content.REST) {
            showRest(true);
            return;
        }
        if (what == TopShelfLogic.Content.ARTWORK && shelf != null) {
            // The shelf has a show but no room for it: the app's artwork stands in; drop the decoded pictures.
            recycle(backdrop);
            for (Bitmap b : small) recycle(b);
        }
        shownPkg = wantPkg;
        shown = what;
        int artHeight = Math.max(1, wantDockTop);
        FrameLayout.LayoutParams artLp = (FrameLayout.LayoutParams) art.getLayoutParams();
        FrameLayout.LayoutParams scrimLp = (FrameLayout.LayoutParams) scrim.getLayoutParams();
        if (artLp.height != artHeight) {
            artLp.height = scrimLp.height = artHeight;
            art.setLayoutParams(artLp);
            scrim.setLayoutParams(scrimLp);
        }
        if (what == TopShelfLogic.Content.RESUME) {
            fillResume(shelf, backdrop, small);
        } else {
            setArt(makeArtwork(wantArt, wantColor));
            block.setVisibility(View.GONE);
            upNext.setVisibility(View.GONE);
            setThumbs(null);
        }
        host.setTitleHidden(what == TopShelfLogic.Content.RESUME);
        reveal();
    }

    private void fillResume(TopShelfLogic.Shelf shelf, Bitmap backdrop, Bitmap[] small) {
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
        for (int i = 0; i < thumbs.size(); i++) {
            boolean on = i < shelf.upNext.size();
            thumbs.get(i).setVisibility(on ? View.VISIBLE : View.GONE);
            if (!on) continue;
            TopShelfLogic.Entry e = shelf.upNext.get(i);
            thumbLabels.get(i).setText(e.subtitle.isEmpty() ? e.title : e.subtitle + "\n" + e.title);
            thumbs.get(i).setOnClickListener(v -> launch(e));
        }
        upNext.setVisibility(shelf.upNext.isEmpty() ? View.GONE : View.VISIBLE);
        block.setVisibility(View.VISIBLE);
        int bottom = container.getHeight() - wantDockTop + dp(GAP_DP);
        for (View v : new View[] {block, upNext}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom;
                v.setLayoutParams(lp);
            }
        }
    }

    private void launch(TopShelfLogic.Entry e) {
        Intent intent = ShelfFeed.launchIntent(e.intentUri, e.pkg);
        if (intent != null) host.open(intent);
    }

    private void reveal() {
        int ms = SettingsModel.duration(animations, reducedMotion, 240);
        veil.animate().cancel();
        content.animate().cancel();
        content.setVisibility(View.VISIBLE);
        if (ms == 0) {
            veil.setAlpha(1f);
            content.setAlpha(1f);
            return;
        }
        veil.animate().alpha(1f).setDuration(ms).start();
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(ms).start();
    }

    /** Back to the dim wallpaper. Never while the Resume button or an up-next item has focus: that goes to the tile first. */
    private void showRest(boolean fade) {
        cancelLeave();
        if (container.hasFocus()) host.focusTiles();
        if (shown == TopShelfLogic.Content.REST && content.getVisibility() != View.VISIBLE) return;
        shown = TopShelfLogic.Content.REST;
        shownPkg = null;
        host.setTitleHidden(false);
        int ms = fade ? SettingsModel.duration(animations, reducedMotion, 320) : 0;
        veil.animate().cancel();
        content.animate().cancel();
        if (ms == 0) {
            veil.setAlpha(REST_VEIL);
            clearContent();
            return;
        }
        veil.animate().alpha(REST_VEIL).setDuration(ms).start();
        content.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
            if (shown == TopShelfLogic.Content.REST) clearContent();
        }).start();
    }

    private void clearContent() {
        content.setAlpha(0f);
        content.setVisibility(View.GONE);
        setArt(null);
        setThumbs(null);
    }

    // ---- pictures: at most three held ---------------------------------------------------------------------------

    private void setArt(Bitmap next) {
        Bitmap old = artBitmap;
        artBitmap = next;
        art.setImageBitmap(next);
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

    /**
     * The app's own artwork, drawn small and stretched by the view: a banner covers a 320x180 picture, an icon sits
     * to the right on a dark plate lit in the app's colour. The stretch is the softness; no blur is run.
     */
    private Bitmap makeArtwork(Drawable live, int color) {
        if (live == null) return null;
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        Bitmap b = Bitmap.createBitmap(ART_W, ART_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF101114);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(ART_W * 0.7f, ART_H * 0.35f, ART_W * 0.55f,
                (color & 0xFFFFFF) | 0x73000000, color & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, ART_W, ART_H, glow);
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w > 0 && h > 0 && w >= h * 1.4f) {
            float scale = Math.max(ART_W / (float) w, ART_H / (float) h);
            int dw = Math.round(w * scale), dh = Math.round(h * scale);
            d.setBounds((ART_W - dw) / 2, (ART_H - dh) / 2, (ART_W + dw) / 2, (ART_H + dh) / 2);
        } else {
            int size = 110;
            int left = Math.round(ART_W * 0.7f) - size / 2, top = Math.round(ART_H * 0.35f) - size / 2;
            d.setBounds(left, top, left + size, top + size);
        }
        d.draw(c);
        d.setBounds(saved);
        return b;
    }

    // ---- views ---------------------------------------------------------------------------------------------------

    private Drawable scrimDrawable() {
        GradientDrawable side = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xEB000000, 0x99000000, 0x00000000});
        GradientDrawable vertical = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99000000, 0x26000000, 0x26000000, 0xFF000000});
        return new LayerDrawable(new Drawable[] {side, vertical});
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
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                host.focusTiles();
                return true;
            }
            // Left of Resume and right of the last item have nowhere to go: stay put rather than leave the shelf.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return view == resumeButton;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return view == lastFocusable();
            return false;
        });
    }

    private View lastFocusable() {
        for (int i = thumbs.size() - 1; i >= 0; i--) {
            if (thumbs.get(i).getVisibility() == View.VISIBLE && upNext.getVisibility() == View.VISIBLE) return thumbs.get(i);
        }
        return resumeButton;
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
