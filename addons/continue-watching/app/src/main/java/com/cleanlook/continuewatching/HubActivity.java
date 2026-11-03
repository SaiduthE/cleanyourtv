package com.cleanlook.continuewatching;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching page: every title the owner is partway through, or is up next in, from all sources, newest
 * first. OK starts the title in its own app. It reads the saved result at once and looks again when it is older than
 * {@link #STALE_MS}; the sources themselves are managed in {@link SourcesActivity}.
 */
public final class HubActivity extends Activity {
    private static final int COLUMNS = 3;
    private static final int MAX_CARDS = 30;
    private static final long STALE_MS = 2 * 60_000L;
    private static final int DEFAULT_ACCENT = 0xFF7AB8FF;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ImageLoader.Handle> loads = new ArrayList<>();
    private Prefs prefs;
    private ScrollView scroll;
    private LinearLayout rows;
    private TextView status;
    private TextView empty;
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
        sourcesPill = findViewById(R.id.sources);
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        sourcesPill.setOnClickListener(v -> startActivity(new Intent(this, SourcesActivity.class)));
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
        if (rows == null) return;
        show();
        if (!Refresher.recent(STALE_MS)) refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        cancelLoads();
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

    private void cancelLoads() {
        for (ImageLoader.Handle h : loads) h.cancel();
        loads.clear();
    }

    // ---- the page

    private void show() {
        cancelLoads();
        Store.State s = Store.read(this);
        List<Merger.Entry> entries = Merger.merge(Store.visible(this, s, prefs.disabledPackages()));
        if (entries.size() > MAX_CARDS) entries = entries.subList(0, MAX_CARDS);

        String focusKey = null;
        View f = getCurrentFocus();
        if (f != null && f.getTag() instanceof Merger.Entry) focusKey = ((Merger.Entry) f.getTag()).key;

        rows.removeAllViews();
        View first = null;
        View keep = null;
        LayoutInflater inflater = LayoutInflater.from(this);
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = dp(16);
                rows.addView(row, rp);
            }
            Merger.Entry e = entries.get(i);
            View card = card(inflater, row, e);
            row.addView(card, cell(i % COLUMNS));
            if (first == null) first = card;
            if (e.key.equals(focusKey)) keep = card;
        }
        // Pad the last row so its cards keep the column width.
        if (row != null) {
            for (int c = entries.size() % COLUMNS; c != 0 && c < COLUMNS; c++) {
                row.addView(new View(this), cell(c));
            }
        }

        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        scroll.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        if (entries.isEmpty()) empty.setText(R.string.empty);
        count = entries.size();
        status.setText(statusLine(s));
        if (getCurrentFocus() == null || keep != null) {
            View target = keep != null ? keep : first != null ? first : sourcesPill;
            target.requestFocus();
        }
    }

    private LinearLayout.LayoutParams cell(int column) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(156), 1f);
        if (column < COLUMNS - 1) lp.setMarginEnd(dp(16));
        return lp;
    }

    private String statusLine(Store.State s) {
        if (!notice.isEmpty()) return notice;
        if (s.wnStatus.equals("denied") && !prefs.jellyfinConfigured()) return getString(R.string.status_denied);
        long at = Math.max(s.wnAt, s.jfAt);
        if (count == 0) return at == 0 ? "" : getString(R.string.status_updated_empty, Text.ago(System.currentTimeMillis() - at));
        String text = getResources().getQuantityString(R.plurals.titles, count, count);
        return at == 0 ? text : text + " · " + getString(R.string.status_updated, Text.ago(System.currentTimeMillis() - at));
    }

    private View card(LayoutInflater inflater, ViewGroup parent, final Merger.Entry e) {
        final Item it = e.primary;
        View v = inflater.inflate(R.layout.item_card, parent, false);
        v.setTag(e);
        v.setClipToOutline(true);
        final float radius = dp(12);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        ((TextView) v.findViewById(R.id.title)).setText(it.title);
        ((TextView) v.findViewById(R.id.detail)).setText(detail(it));
        ((TextView) v.findViewById(R.id.source)).setText(App.label(this, it.pkg));

        TextView tag = v.findViewById(R.id.tag);
        String t = it.kind == Item.NEXT ? (it.tag.isEmpty() ? getString(R.string.up_next) : it.tag) : "";
        tag.setText(t);
        tag.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);

        ProgressBar bar = v.findViewById(R.id.progress);
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        boolean hasProgress = it.kind == Item.RESUME && it.progress >= 0;
        bar.setVisibility(hasProgress ? View.VISIBLE : View.GONE);
        if (hasProgress) bar.setProgress(Math.round(Math.min(1f, it.progress) * 100));

        final ImageView picture = v.findViewById(R.id.picture);
        final ImageView icon = v.findViewById(R.id.appIcon);
        Drawable appIcon = App.icon(this, it.pkg);
        icon.setImageDrawable(appIcon);
        String url = !it.image.isEmpty() ? it.image : !it.backdrop.isEmpty() ? it.backdrop : it.poster;
        if (!url.isEmpty()) {
            loads.add(App.images(this).load(url, 600, new ImageLoader.Callback() {
                @Override public void onBitmap(Bitmap b) {
                    picture.setImageBitmap(b);
                    icon.setVisibility(View.GONE);
                }
            }));
        }

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
                if (focused && !notice.isEmpty()) {
                    notice = "";
                    status.setText(statusLine(Store.read(HubActivity.this)));
                }
            }
        });
        return v;
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
