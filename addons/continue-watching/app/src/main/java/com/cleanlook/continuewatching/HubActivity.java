package com.cleanlook.continuewatching;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching page: every title the owner is partway through, or is up next in, grouped by app. Each app is
 * a section, a header (its icon, name, count and row label) over one sideways row of cards; the sections run down the
 * page, the app with the most recent watch first. OK starts the title in its own app. It reads the saved result at once
 * and looks again when it is older than {@link #STALE_MS}; the sources themselves are managed in {@link SourcesActivity}.
 *
 * Left and right move inside a row, up and down between sections (landing on the column the owner was on), and every
 * move is one glide of {@link SmoothRowView} or {@link SmoothScrollView}. Pictures are loaded only for the cards on or
 * near the screen and let go again when they are far away.
 */
public final class HubActivity extends Activity {
    private static final long STALE_MS = 2 * 60_000L;
    private static final int DEFAULT_ACCENT = 0xFF7AB8FF;
    private static final int CARD_W = 232;
    private static final int CARD_H = 130;
    private static final int CARD_GAP = 14;
    private static final int PICTURE_PX = 480;

    /** One card and what it needs to load and drop its picture. */
    private static final class Card {
        View view;
        Merger.Entry entry;
        Item item;
        ImageView picture;
        ImageView icon;
        String url = "";
        ImageLoader.Handle handle;
        boolean loaded;
        int sec;
        int col;
    }

    /** One app's section. */
    private static final class Sec {
        HubGrouping.Section data;
        View box;
        SmoothRowView row;
        final List<Card> cards = new ArrayList<>();
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Sec> secs = new ArrayList<>();
    private final HubGrouping.Columns cols = new HubGrouping.Columns();
    private Prefs prefs;
    private SmoothScrollView scroll;
    private LinearLayout rows;
    private TextView status;
    private View empty;
    private View refreshPill;
    private View sourcesPill;
    private int accent = DEFAULT_ACCENT;
    private boolean refreshing;
    private int count;
    /** What the owner last said about the title they pressed ("Opened Plex. Look for ..."), kept until the next load. */
    private String notice = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = App.prefs(this);
        accent = getIntent().getIntExtra(Contract.EXTRA_ACCENT, DEFAULT_ACCENT);
        if (startRequestedItem(getIntent())) {
            finish();
            return;
        }
        setContentView(R.layout.activity_hub);
        scroll = findViewById(R.id.scroll);
        rows = findViewById(R.id.rows);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        refreshPill = findViewById(R.id.refresh);
        sourcesPill = findViewById(R.id.sources);
        refreshPill.setOnClickListener(v -> refresh());
        sourcesPill.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
        View.OnKeyListener intoCards = new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent e) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && !secs.isEmpty()) {
                    Sec s = secs.get(0);
                    int c = cols.landing(s.cards.size());
                    cols.landed(s.data.pkg, c);
                    s.cards.get(c).view.requestFocus();
                }
                return true;
            }
        };
        refreshPill.setOnKeyListener(intoCards);
        sourcesPill.setOnKeyListener(intoCards);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                updateImages();
            }
        });
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                updateImages();
            }
        });
    }

    /** ACTION_RESUME with an item id: the launcher's Top Shelf card was pressed. Starts the title and shows nothing. */
    private boolean startRequestedItem(Intent i) {
        String id = i == null ? null : i.getStringExtra(Contract.EXTRA_ITEM_ID);
        if (id == null) return false;
        Item it = Store.find(Store.visible(this, Store.read(this), prefs.disabledPackages()), id);
        if (it == null) return false;
        Launcher.launch(this, it);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        PhotoFeed.kick(this); // the first photos, or the three-day refresh, when the add-on is opened
        if (rows == null) return;
        show();
        if (!Refresher.recent(STALE_MS)) refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        releaseAll();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        status.setText(R.string.refreshing);
        io.execute(new Runnable() {
            @Override public void run() {
                Refresher.probeOnce(HubActivity.this);
                Refresher.refreshAll(HubActivity.this);
                main.post(new Runnable() {
                    @Override public void run() {
                        refreshing = false;
                        if (!isDestroyed() && !isFinishing()) show();
                    }
                });
            }
        });
    }

    // ---- the page

    private void show() {
        releaseAll();
        Store.State s = Store.read(this);
        List<Merger.Entry> entries = Merger.merge(Store.visible(this, s, prefs.disabledPackages()));
        List<HubGrouping.Section> groups = HubGrouping.group(entries, HubGrouping.MAX_SECTIONS, HubGrouping.MAX_PER_ROW);

        // What to put back after the rebuild: the focused card (by title, else by place) and each row's scroll.
        Card was = focusedCard();
        String wasKey = was == null ? null : was.entry.key;
        String wasPkg = was == null ? null : secs.get(was.sec).data.pkg;
        int wasCol = was == null ? 0 : was.col;
        Map<String, Integer> scrollX = new HashMap<>();
        for (Sec old : secs) scrollX.put(old.data.pkg, old.row.getScrollX());

        rows.removeAllViews();
        secs.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < groups.size(); i++) {
            Sec sec = new Sec();
            sec.data = groups.get(i);
            buildSection(inflater, sec, i);
            Integer x = scrollX.get(sec.data.pkg);
            if (x != null) sec.row.restoreScroll(x);
            secs.add(sec);
            rows.addView(sec.box);
        }

        boolean none = secs.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        scroll.setVisibility(none ? View.GONE : View.VISIBLE);
        count = HubGrouping.totalEntries(groups);
        status.setText(statusLine(s));

        if (none) {
            if (was != null || getCurrentFocus() == null) sourcesPill.requestFocus();
            return;
        }
        Card target = null;
        if (was != null) {
            target = find(wasKey);
            if (target == null) {
                for (int i = 0; i < secs.size() && target == null; i++) {
                    Sec sec = secs.get(i);
                    if (sec.data.pkg.equals(wasPkg)) target = sec.cards.get(HubGrouping.clampColumn(wasCol, sec.cards.size()));
                }
            }
            if (target == null) target = secs.get(0).cards.get(0);
        } else if (getCurrentFocus() == null) {
            Sec first = secs.get(0);
            target = first.cards.get(first.cards.size() > 0 ? cols.remembered(first.data.pkg, first.cards.size()) : 0);
        }
        if (target != null) {
            scroll.jumpOnNextFocus();
            secs.get(target.sec).row.jumpOnNextFocus();
            cols.landed(secs.get(target.sec).data.pkg, target.col);
            target.view.requestFocus();
        }
    }

    private Card find(String key) {
        for (Sec sec : secs) for (Card c : sec.cards) if (c.entry.key.equals(key)) return c;
        return null;
    }

    private Card focusedCard() {
        View f = getCurrentFocus();
        return f != null && f.getTag() instanceof Card ? (Card) f.getTag() : null;
    }

    private void buildSection(LayoutInflater inflater, Sec sec, int index) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(index == 0 ? 4 : 14);
        box.setLayoutParams(bp);
        sec.box = box;

        box.addView(header(sec.data), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));

        SmoothRowView row = new SmoothRowView(this);
        row.setHorizontalScrollBarEnabled(false);
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(48), dp(8), dp(48), dp(8));
        row.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                updateImages();
            }
        });
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        row.addView(strip, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sec.row = row;
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int c = 0; c < sec.data.entries.size(); c++) {
            Card card = card(inflater, strip, sec.data.entries.get(c), index, c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
            if (c < sec.data.entries.size() - 1) lp.setMarginEnd(dp(CARD_GAP));
            strip.addView(card.view, lp);
            sec.cards.add(card);
        }
    }

    /** Icon at its own size (never past 1.5 times), the app's name, the count, and the row label at the far end. */
    private View header(HubGrouping.Section g) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(52), 0, dp(48), 0);

        String name = App.label(this, g.pkg);
        Drawable icon = App.icon(this, g.pkg);
        if (icon == null && Item.JELLYFIN_PKG.equals(g.pkg)) icon = getDrawable(R.drawable.ic_jellyfin);
        int box = dp(28);
        if (icon != null) {
            int size = AddedSources.fitPx(icon.getIntrinsicWidth(), box);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(size, size);
            ip.setMarginEnd(dp(10));
            h.addView(iv, ip);
        } else {
            TextView glyph = new TextView(this);
            glyph.setGravity(Gravity.CENTER);
            glyph.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            glyph.setTextColor(getColor(R.color.text));
            glyph.setTextSize(14);
            glyph.setBackgroundResource(R.drawable.chip);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(box, box);
            gp.setMarginEnd(dp(10));
            h.addView(glyph, gp);
        }

        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(getColor(R.color.text));
        n.setTextSize(18);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView c = new TextView(this);
        c.setText(getResources().getQuantityString(R.plurals.titles, g.entries.size(), g.entries.size()));
        c.setTextColor(getColor(R.color.text_dim));
        c.setTextSize(14);
        c.setSingleLine(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMarginStart(dp(12));
        h.addView(c, cp);

        View filler = new View(this);
        h.addView(filler, new LinearLayout.LayoutParams(0, 1, 1f));

        if (!g.label.isEmpty()) {
            TextView l = new TextView(this);
            l.setText(g.label);
            l.setTextColor(getColor(R.color.text_faint));
            l.setTextSize(12);
            l.setSingleLine(true);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setMaxWidth(dp(280));
            h.addView(l, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return h;
    }

    private String statusLine(Store.State s) {
        if (!notice.isEmpty()) return notice;
        if (s.wnStatus.equals("denied") && !prefs.jellyfinConfigured()) return getString(R.string.status_denied);
        long at = Math.max(s.wnAt, s.jfAt);
        if (count == 0) return at == 0 ? "" : getString(R.string.status_updated_empty, Text.ago(System.currentTimeMillis() - at));
        String text = getResources().getQuantityString(R.plurals.titles, count, count);
        return at == 0 ? text : text + " · " + getString(R.string.status_updated, Text.ago(System.currentTimeMillis() - at));
    }

    private Card card(LayoutInflater inflater, ViewGroup parent, final Merger.Entry e, int sec, int col) {
        final Item it = e.primary;
        final Card card = new Card();
        View v = inflater.inflate(R.layout.item_card, parent, false);
        card.view = v;
        card.entry = e;
        card.item = it;
        card.sec = sec;
        card.col = col;
        v.setTag(card);
        v.setClipToOutline(true);
        final float radius = dp(12);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        ((TextView) v.findViewById(R.id.title)).setText(it.title);
        ((TextView) v.findViewById(R.id.detail)).setText(detail(it));

        TextView tag = v.findViewById(R.id.tag);
        String t = it.kind == Item.NEXT ? (it.tag.isEmpty() ? getString(R.string.up_next) : it.tag) : "";
        tag.setText(t);
        tag.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);

        ProgressBar bar = v.findViewById(R.id.progress);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        boolean hasProgress = it.kind == Item.RESUME && it.progress >= 0;
        bar.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
        if (hasProgress) bar.setProgress(Math.round(Math.min(1f, it.progress) * 100));

        card.picture = v.findViewById(R.id.picture);
        card.icon = v.findViewById(R.id.appIcon);
        card.icon.setImageDrawable(App.icon(this, it.pkg));
        card.url = !it.image.isEmpty() ? it.image : !it.backdrop.isEmpty() ? it.backdrop : it.poster;

        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Launcher.Result r = Launcher.launch(HubActivity.this, it);
                String m = Launcher.message(HubActivity.this, it, r);
                notice = m == null ? "" : m;
                if (m != null) status.setText(m);
            }
        });
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                view.animate().scaleX(focused ? 1.03f : 1f).scaleY(focused ? 1.03f : 1f).setDuration(120).start();
                if (!focused) return;
                ensure(card);
                if (!notice.isEmpty()) {
                    notice = "";
                    status.setText(statusLine(Store.read(HubActivity.this)));
                }
            }
        });
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent ev) {
                int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                if (dx == 0 && dy == 0) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                    if (dx != 0) moveInRow(card, dx);
                    else moveBetweenSections(card, dy);
                }
                return true;
            }
        });
        return card;
    }

    /** Left or right: the next card in the row. At either end focus stays where it is. */
    private void moveInRow(Card from, int delta) {
        Sec sec = secs.get(from.sec);
        int to = from.col + delta;
        if (to < 0 || to >= sec.cards.size()) return;
        cols.moved(sec.data.pkg, to);
        sec.cards.get(to).view.requestFocus();
    }

    /** Up or down: the card in the next section nearest the column the owner has been on. Above the first: the buttons. */
    private void moveBetweenSections(Card from, int delta) {
        int to = HubGrouping.sectionAt(from.sec, delta, secs.size());
        if (to < 0) {
            if (delta < 0) refreshPill.requestFocus();
            return;
        }
        Sec sec = secs.get(to);
        int c = cols.landing(sec.cards.size());
        cols.landed(sec.data.pkg, c);
        sec.cards.get(c).view.requestFocus();
    }

    // ---- pictures: loaded near the screen, dropped far from it

    private void ensure(final Card c) {
        if (c.loaded || c.url.isEmpty()) return;
        c.loaded = true;
        c.handle = App.images(this).load(c.url, PICTURE_PX, new ImageLoader.Callback() {
            @Override public void onBitmap(Bitmap b) {
                if (!c.loaded) return;
                c.picture.setImageBitmap(b);
                c.icon.setVisibility(View.GONE);
            }
        });
    }

    private void release(Card c) {
        if (!c.loaded) return;
        c.loaded = false;
        if (c.handle != null) c.handle.cancel();
        c.handle = null;
        c.picture.setImageDrawable(null);
        c.icon.setVisibility(View.VISIBLE);
    }

    private void releaseAll() {
        for (Sec s : secs) for (Card c : s.cards) release(c);
    }

    /** Loads the cards within about a screen of the viewport and lets go of those more than two screens away. */
    private void updateImages() {
        if (secs.isEmpty() || scroll == null) return;
        int vh = scroll.getHeight();
        if (vh == 0) return;
        int sy = scroll.getScrollY();
        int stride = dp(CARD_W + CARD_GAP);
        for (Sec s : secs) {
            int w = s.row.getWidth();
            if (w == 0) continue;
            int top = s.box.getTop();
            int bottom = s.box.getBottom();
            boolean near = bottom > sy - vh / 2 && top < sy + vh + vh / 2;
            boolean far = bottom < sy - 2 * vh || top > sy + 3 * vh;
            int sx = s.row.getScrollX();
            for (Card c : s.cards) {
                int left = c.view.getLeft() + s.row.getPaddingLeft() - sx;
                int right = left + c.view.getWidth();
                boolean sideNear = right > -2 * stride && left < w + 2 * stride;
                boolean sideFar = right < -4 * stride || left > w + 4 * stride;
                if (near && sideNear) ensure(c);
                else if (far || sideFar) release(c);
            }
        }
    }

    /** "S2 · E4 · 23 min left", "2024 · 1 h 10 min left", or the episode's name. */
    private static String detail(Item it) {
        StringBuilder b = new StringBuilder(it.subtitle());
        String left = Text.left(it.kind == Item.RESUME ? it.remainingMs() : -1);
        if (left != null) {
            if (b.length() > 0) b.append(" · ");
            b.append(left);
        }
        return b.toString();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
