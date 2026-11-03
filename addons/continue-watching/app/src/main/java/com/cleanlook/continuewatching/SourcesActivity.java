package com.cleanlook.continuewatching;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where Continue watching comes from, managed with the remote alone. The screen is a grid of tiles for the sources the
 * owner added (an app, or the Jellyfin server), each with a status line, and a last "+ Add source" tile. OK on a tile
 * opens a small popup on it: the source's switch, its channels, Test, Remove. Add source opens a picker of the apps
 * that publish rows (most first), a Jellyfin entry, and "Show all apps" for the rest, loaded only when asked.
 * Changes are saved at once and the shelf follows without waiting for the next refresh.
 */
public final class SourcesActivity extends Activity {
    private static final int REQUEST_LISTINGS = 1;
    private static final int COLS = 6;
    private static final int POPUP_VISIBLE_CHANNELS = 5;
    private static final String ADD = "+add";
    private static final String SHOW_ALL = "+all";
    private static final int NEUTRAL = 0;
    private static final int GOOD = 1;
    private static final int BAD = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private View column;
    private LinearLayout grid;
    private SmoothScrollView scroll;
    private TextView title;
    private TextView status;
    private View allow;
    private View rescan;
    private View shelf;
    private float density;

    private boolean busy;
    private boolean picker;
    private boolean showAll;
    private boolean awaitingJellyfin;
    private String lastKey = "";

    private final List<View> tiles = new ArrayList<>();
    private final List<String> tileKeys = new ArrayList<>();
    private List<AddedSources.Candidate> offered = new ArrayList<>();
    private List<AddedSources.Candidate> extras = new ArrayList<>();

    // what the tiles are drawn from, read once per build
    private Store.State state = new Store.State();
    private Map<String, int[]> probe = new HashMap<>();
    private boolean granted;

    // the popup
    private View popup;
    private String popupKey = "";
    private View popupAnchor;
    private boolean confirmRemove;
    private String popupNote = "";
    private final List<View[]> popupNav = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_sources);
        prefs = App.prefs(this);
        density = getResources().getDisplayMetrics().density;
        root = findViewById(R.id.root);
        column = root.getChildAt(0);
        grid = findViewById(R.id.grid);
        scroll = findViewById(R.id.scroll);
        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        allow = findViewById(R.id.allow);
        rescan = findViewById(R.id.rescan);
        shelf = findViewById(R.id.shelf);

        allow.setOnClickListener(v -> requestPermissions(new String[] {WatchNextReader.PERMISSION}, REQUEST_LISTINGS));
        rescan.setOnClickListener(v -> scan());
        shelf.setOnClickListener(v -> {
            prefs.setShelfEnabled(!prefs.shelfEnabled());
            republish();
            bindHeader();
            say(prefs.shelfEnabled() ? "The launcher can read Continue watching again." : "The launcher gets nothing now.", NEUTRAL);
        });
        View.OnKeyListener header = (v, code, e) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) focusTile(Math.max(0, tileKeys.indexOf(lastKey)));
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_UP;
        };
        allow.setOnKeyListener(header);
        rescan.setOnKeyListener(header);
        shelf.setOnKeyListener(header);
    }

    @Override protected void onResume() {
        super.onResume();
        prefs.migrateAdded(Store.read(this));
        closePopupViews();
        String focus = lastKey;
        if (awaitingJellyfin) {
            awaitingJellyfin = false;
            if (prefs.isAdded(AddedSources.JELLYFIN)) {
                picker = false;
                focus = AddedSources.JELLYFIN;
            }
        }
        build(focus);
        if (!WatchNextReader.granted(this)) {
            say("Watch Next access is off, so no app can be read. Use Allow Watch Next access.", BAD);
        } else if (prefs.probeAt() == 0) {
            scan();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_LISTINGS) return;
        if (WatchNextReader.granted(this)) {
            scan();
        } else {
            say("Watch Next access was not given. From a computer: " + WatchNextReader.GRANT_COMMAND, BAD);
            changed();
        }
    }

    @Override public void onBackPressed() {
        if (popup != null) {
            closePopup();
        } else if (picker) {
            picker = false;
            build(ADD);
        } else {
            super.onBackPressed();
        }
    }

    private void say(String message, int tone) {
        status.setText(message);
        status.setTextColor(getColor(tone == GOOD ? R.color.good : tone == BAD ? R.color.bad : R.color.text_dim));
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }

    // ---- the screen

    private void readData() {
        state = Store.read(this);
        probe = Probe.decode(prefs.probeJson());
        granted = WatchNextReader.granted(this);
    }

    private void bindHeader() {
        title.setText(picker ? R.string.add_title : R.string.sources);
        allow.setVisibility(granted ? View.GONE : View.VISIBLE);
        rescan.setVisibility(granted ? View.VISIBLE : View.GONE);
        shelf.setVisibility(picker ? View.GONE : View.VISIBLE);
        ((TextView) shelf).setText(prefs.shelfEnabled() ? "Top Shelf feed: On" : "Top Shelf feed: Off");
    }

    /** Builds the grid (or the picker) afresh and puts focus on {@code focusKey}, or on the first tile. */
    private void build(String focusKey) {
        readData();
        bindHeader();
        scroll.jumpOnNextFocus();
        grid.removeAllViews();
        tiles.clear();
        tileKeys.clear();
        if (picker) pickerTiles();
        else sourceTiles();

        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            row.addView(tiles.get(i));
        }
        if (!picker && prefs.addedSources().isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("Nothing added yet. Add an app that shows Continue watching rows, or a Jellyfin server.");
            hint.setTextColor(getColor(R.color.text_dim));
            hint.setTextSize(14);
            hint.setSingleLine(true);
            hint.setPadding(px(8), px(8), 0, 0);
            grid.addView(hint);
        } else if (picker && offered.isEmpty() && extras.isEmpty()) {
            say(granted ? "No app publishes rows this app can read. Show all apps lists the rest."
                    : "Allow Watch Next access to find apps, or choose Show all apps.", granted ? NEUTRAL : BAD);
        }
        int at = tileKeys.indexOf(focusKey);
        focusTile(at >= 0 ? at : 0);
    }

    private void focusTile(int i) {
        if (tiles.isEmpty()) return;
        tiles.get(Math.max(0, Math.min(i, tiles.size() - 1))).requestFocus();
    }

    private void sourceTiles() {
        for (String key : prefs.addedSources()) addTile(key);
        addTile(ADD);
    }

    private void pickerTiles() {
        Map<String, String> labels = new HashMap<>();
        Set<String> installed = new HashSet<>();
        for (String p : probe.keySet()) if (App.installed(this, p)) installed.add(p);
        for (ChannelFilter.Info n : state.chans) if (App.installed(this, n.pkg)) installed.add(n.pkg);
        for (String p : installed) labels.put(p, App.label(this, p));
        offered = AddedSources.candidates(probe, state.chans, prefs.addedSources(), installed, labels, getPackageName());
        addTile(AddedSources.JELLYFIN);
        for (AddedSources.Candidate c : offered) addTile(c.pkg);
        if (showAll) for (AddedSources.Candidate c : extras) addTile(c.pkg);
        else addTile(SHOW_ALL);
    }

    private void addTile(String key) {
        View t = LayoutInflater.from(this).inflate(R.layout.item_tile, grid, false);
        t.setTag(key);
        t.setOnClickListener(v -> onTile(v, key));
        t.setOnKeyListener(this::onTileKey);
        t.setOnFocusChangeListener((v, has) -> {
            if (has) lastKey = key;
        });
        tiles.add(t);
        tileKeys.add(key);
        bindTile(t, key);
    }

    /** Draws (or redraws) one tile from the current data. */
    private void bindTile(View t, String key) {
        TextView name = t.findViewById(R.id.name);
        TextView st = t.findViewById(R.id.status);
        View dot = t.findViewById(R.id.dot);
        ImageView art = t.findViewById(R.id.art);
        TextView glyph = t.findViewById(R.id.glyph);
        boolean dim = false;
        AddedSources.Status s;
        if (ADD.equals(key) || SHOW_ALL.equals(key)) {
            t.setBackgroundResource(ADD.equals(key) ? R.drawable.tile_add : R.drawable.tile);
            art.setVisibility(View.GONE);
            glyph.setVisibility(View.VISIBLE);
            glyph.setText(ADD.equals(key) ? "+" : "…");
            name.setText(ADD.equals(key) ? R.string.add_source : R.string.show_all_apps);
            st.setText("");
            dot.setVisibility(View.GONE);
            return;
        }
        glyph.setVisibility(View.GONE);
        art.setVisibility(View.VISIBLE);
        dot.setVisibility(View.VISIBLE);
        String label = labelOf(key);
        name.setText(label);
        setArt(art, glyph, key, label);
        if (picker) {
            AddedSources.Candidate c = find(key);
            boolean added = prefs.isAdded(key);
            dim = added;
            int rows = c == null ? 0 : c.rows;
            s = added ? new AddedSources.Status(AddedSources.Level.OFF, "Added")
                    : AddedSources.JELLYFIN.equals(key) ? new AddedSources.Status(AddedSources.Level.NONE, "Address, user, key")
                    : rows > 0 ? new AddedSources.Status(AddedSources.Level.ROWS, AddedSources.rows(rows))
                    : new AddedSources.Status(AddedSources.Level.NONE, "No rows known");
        } else {
            s = statusFor(key);
        }
        st.setText(s.text);
        st.setTextColor(getColor(s.level == AddedSources.Level.ERROR ? R.color.bad : R.color.text_dim));
        ((GradientDrawable) dot.getBackground().mutate()).setColor(getColor(
                s.level == AddedSources.Level.ROWS ? R.color.good
                        : s.level == AddedSources.Level.ERROR ? R.color.bad : R.color.text_faint));
        float a = dim ? 0.45f : 1f;
        art.setAlpha(a);
        glyph.setAlpha(a);
        name.setAlpha(a);
        t.setBackgroundResource(R.drawable.tile);
    }

    private AddedSources.Candidate find(String pkg) {
        for (AddedSources.Candidate c : offered) if (c.pkg.equals(pkg)) return c;
        for (AddedSources.Candidate c : extras) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    private String labelOf(String key) {
        if (AddedSources.JELLYFIN.equals(key)) {
            return picker || !prefs.jellyfinConfigured() || prefs.serverName().isEmpty() ? "Jellyfin server"
                    : "Jellyfin · " + prefs.serverName();
        }
        AddedSources.Candidate c = find(key);
        return c != null ? c.name : App.label(this, key);
    }

    /** The app's banner when it is big enough for the tile, else its icon at its own size (never past 1.5 times). */
    private void setArt(ImageView art, TextView glyph, String key, String label) {
        Drawable icon;
        Drawable banner = null;
        if (AddedSources.JELLYFIN.equals(key)) {
            icon = App.icon(this, Item.JELLYFIN_PKG);
            if (icon == null) icon = getDrawable(R.drawable.ic_jellyfin);
        } else {
            banner = App.banner(this, key);
            icon = App.icon(this, key);
        }
        art.setClipToOutline(false);
        if (banner != null && AddedSources.bannerFits(banner.getIntrinsicWidth(), px(100))) {
            art.setLayoutParams(new FrameLayout.LayoutParams(px(100), px(56), Gravity.CENTER));
            art.setImageDrawable(banner);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(6));
                }
            });
            art.setClipToOutline(true);
        } else if (icon != null) {
            int size = AddedSources.fitPx(icon.getIntrinsicWidth(), px(48));
            art.setLayoutParams(new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            art.setImageDrawable(icon);
        } else {
            art.setVisibility(View.GONE);
            glyph.setVisibility(View.VISIBLE);
            glyph.setText(label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase());
        }
    }

    private AddedSources.Status statusFor(String key) {
        boolean on = !prefs.disabledPackages().contains(key);
        if (AddedSources.JELLYFIN.equals(key)) {
            return AddedSources.jellyfinStatus(on, prefs.jellyfinConfigured(), prefs.jellyfinStatus(), state.jf.size());
        }
        int[] c = probe.get(key);
        int rows = c == null ? 0 : c[0];
        int channels = 0;
        int channelsOn = 0;
        Set<String> started = ChannelFilter.startedKeys(state.ch);
        for (ChannelFilter.Info n : state.chans) {
            if (!n.pkg.equals(key)) continue;
            channels++;
            if (prefs.channelEnabled(key, n.name, started)) {
                channelsOn++;
                rows += n.rows;
            }
        }
        if (on && granted && prefs.probeAt() != 0 && rows == 0 && channels > 0 && channelsOn == 0) {
            return new AddedSources.Status(AddedSources.Level.NONE, "Channels off");
        }
        boolean error = "error".equals(state.wnStatus) || "error".equals(state.chStatus);
        return AddedSources.appStatus(on, granted, prefs.probeAt() != 0, rows, error);
    }

    /** After data changed: redraw every tile in place (and the popup, if one is open) without moving focus. */
    private void changed() {
        readData();
        bindHeader();
        for (int i = 0; i < tiles.size(); i++) bindTile(tiles.get(i), tileKeys.get(i));
        if (popup != null) {
            int[] at = popupPos();
            showPopup(popupAnchor, popupKey, at[0], at[1]);
        }
    }

    // ---- moving about

    private boolean onTileKey(View v, int code, KeyEvent e) {
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int i = tiles.indexOf(v);
        int n = tiles.size();
        if (i < 0) return true;
        int to = i;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (i % COLS != 0) to = i - 1;
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (i % COLS != COLS - 1 && i + 1 < n) to = i + 1;
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (i - COLS >= 0) {
                to = i - COLS;
            } else {
                View h = firstHeader();
                if (h != null) h.requestFocus();
                return true;
            }
        } else if (i + COLS < n) {
            to = i + COLS;
        } else if (i / COLS < (n - 1) / COLS) {
            to = n - 1;
        }
        if (to != i) tiles.get(to).requestFocus();
        return true;
    }

    private View firstHeader() {
        for (View h : new View[] {allow, rescan, shelf}) if (h.getVisibility() == View.VISIBLE) return h;
        return null;
    }

    // ---- what a tile does

    private void onTile(View tile, String key) {
        if (picker) {
            pick(key);
        } else if (ADD.equals(key)) {
            picker = true;
            showAll = false;
            build(AddedSources.JELLYFIN);
            say("", NEUTRAL);
        } else {
            confirmRemove = false;
            popupNote = "";
            showPopup(tile, key, 0, 0);
        }
    }

    private void pick(String key) {
        if (SHOW_ALL.equals(key)) {
            loadAllApps();
        } else if (prefs.isAdded(key)) {
            say(labelOf(key) + " is already a source.", NEUTRAL);
        } else if (AddedSources.JELLYFIN.equals(key)) {
            awaitingJellyfin = true;
            startActivity(new Intent(this, JellyfinActivity.class));
        } else {
            String name = labelOf(key);
            prefs.addSource(key);
            picker = false;
            build(key);
            say("Added " + name + ". OK on its tile for options.", GOOD);
            final Context app = getApplicationContext();
            io.execute(() -> {
                if (WatchNextReader.granted(app)) Refresher.refresh(app, true, false);
                else Refresher.republish(app);
                main.post(() -> {
                    if (!isDestroyed()) changed();
                });
            });
        }
    }

    /** "Show all apps": the installed apps with a TV launcher icon that the probe found nothing for, read only now. */
    private void loadAllApps() {
        if (busy) return;
        busy = true;
        say("Listing the apps…", NEUTRAL);
        final Context app = getApplicationContext();
        final Set<String> skip = new HashSet<>();
        for (AddedSources.Candidate c : offered) skip.add(c.pkg);
        final List<String> added = prefs.addedSources();
        io.execute(() -> {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            Set<String> pkgs = new HashSet<>();
            for (ResolveInfo ri : app.getPackageManager().queryIntentActivities(launcher, 0)) {
                pkgs.add(ri.activityInfo.packageName);
            }
            Map<String, String> labels = new HashMap<>();
            for (String p : pkgs) labels.put(p, App.label(app, p));
            final List<AddedSources.Candidate> rest = AddedSources.others(pkgs, skip, added, labels, app.getPackageName());
            main.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (rest.isEmpty()) {
                    say("No other apps with a TV launcher icon.", NEUTRAL);
                    return;
                }
                extras = rest;
                showAll = true;
                build(rest.get(0).pkg);
                say("Apps the scan found no rows for can still be added: this app then says so honestly.", NEUTRAL);
            });
        });
    }

    // ---- scanning

    /** Reads every app's rows again, then redraws. */
    private void scan() {
        if (busy) return;
        if (!WatchNextReader.granted(this)) {
            say("Watch Next access is off, so no app can be read. Use Allow Watch Next access.", BAD);
            return;
        }
        busy = true;
        say("Scanning the apps…", NEUTRAL);
        final Context app = getApplicationContext();
        io.execute(() -> {
            Refresher.probe(app);
            Refresher.refresh(app, true, false);
            main.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (popup != null || picker) changed();
                else build(lastKey);
                report();
            });
        });
    }

    private void report() {
        int n = 0;
        for (int[] c : probe.values()) if (c[0] > 0) n++;
        int channels = state.chans.size();
        String ch = channels == 0 ? "" : " " + channels + (channels == 1 ? " channel" : " channels") + " found.";
        say(n == 0 && channels == 0 ? "Scanned. No app publishes rows this app can read."
                : "Scanned. " + n + (n == 1 ? " app publishes" : " apps publish") + " Watch Next rows." + ch,
                n == 0 && channels == 0 ? BAD : GOOD);
    }

    // ---- the popup

    private void closePopupViews() {
        if (popup != null) root.removeView(popup);
        popup = null;
        popupNav.clear();
        column.setVisibility(View.VISIBLE);
        ((ViewGroup) column).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
    }

    /** Back: the popup goes, focus returns to the tile it was on. */
    private void closePopup() {
        View anchor = popupAnchor;
        closePopupViews();
        popupAnchor = null;
        confirmRemove = false;
        if (anchor != null && anchor.isAttachedToWindow()) anchor.requestFocus();
        else focusTile(Math.max(0, tileKeys.indexOf(lastKey)));
    }

    private int[] popupPos() {
        View f = getCurrentFocus();
        for (int r = 0; r < popupNav.size(); r++) {
            for (int c = 0; c < popupNav.get(r).length; c++) if (popupNav.get(r)[c] == f) return new int[] {r, c};
        }
        return new int[] {0, 0};
    }

    private boolean isJellyfin() {
        return AddedSources.JELLYFIN.equals(popupKey);
    }

    /** The popup's empty box with its title line. */
    private LinearLayout popupFrame(String heading) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.popup_bg);
        box.setPadding(px(10), px(10), px(10), px(10));
        box.setElevation(px(24));
        box.setClickable(true);

        TextView head = new TextView(this);
        head.setText(heading);
        head.setTextColor(getColor(R.color.text_faint));
        head.setTextSize(12);
        head.setAllCaps(true);
        head.setLetterSpacing(0.1f);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setPadding(px(4), 0, px(4), px(4));
        box.addView(head);
        return box;
    }

    /** Builds the popup for a source on its tile and focuses row {@code row}, column {@code col}. */
    private void showPopup(View anchor, String key, int row, int col) {
        if (popup != null) root.removeView(popup);
        popupNav.clear();
        popupAnchor = anchor;
        popupKey = key;
        readData();

        LinearLayout box = popupFrame(labelOf(key));

        final boolean on = !prefs.disabledPackages().contains(key);
        box.addView(nav(popupRow("This source", "", on ? "On" : "Off", on, v -> {
            prefs.setPackageEnabled(key, !on);
            afterToggle(key, !on ? labelOf(key) + " is shown in Continue watching." : labelOf(key) + " is hidden from Continue watching.");
        })));

        if (isJellyfin()) {
            int resume = 0;
            int next = 0;
            for (Item it : state.jf) if (it.kind == Item.NEXT) next++;
            else resume++;
            box.addView(nav(popupRow("Resume", AddedSources.rows(resume), prefs.showResume() ? "On" : "Off", prefs.showResume(), v -> {
                prefs.setShowResume(!prefs.showResume());
                jellyfinChanged("Resume list changed.");
            })));
            box.addView(nav(popupRow("Next up", AddedSources.rows(next), prefs.showNextUp() ? "On" : "Off", prefs.showNextUp(), v -> {
                prefs.setShowNextUp(!prefs.showNextUp());
                jellyfinChanged("Next up list changed.");
            })));
        } else {
            addChannels(box, key);
        }

        if (isJellyfin()) {
            box.addView(nav(popupRow("Test", "", "", true, v -> testJellyfin())));
            box.addView(nav(popupRow("Edit sign-in", "", "", true, v -> startActivity(new Intent(this, JellyfinActivity.class)))));
        }

        if (confirmRemove) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, px(3), 0, 0);
            TextView ask = new TextView(this);
            ask.setText("Remove it?");
            ask.setTextColor(getColor(R.color.bad));
            ask.setTextSize(14);
            ask.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            ask.setPadding(px(8), 0, 0, 0);
            line.addView(ask, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView yes = pill("Remove", v -> removeSource());
            TextView keep = pill("Keep", v -> {
                confirmRemove = false;
                showPopup(popupAnchor, popupKey, popupNav.size() - 1, 0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
            lp.setMarginStart(px(6));
            line.addView(yes, lp);
            line.addView(keep, lp);
            box.addView(line);
            popupNav.add(new View[] {yes, keep});
            if (row == popupNav.size() - 1 && col == 0) col = 1;
        } else {
            box.addView(nav(popupRow("Remove source", "", "", false, v -> {
                confirmRemove = true;
                showPopup(popupAnchor, popupKey, popupNav.size() - 1, 1);
            })));
        }

        placePopup(box, anchor, popupNote, row, col);
    }

    /** Adds the note, puts the box beside its tile (kept inside the screen) and focuses a row and column. */
    private void placePopup(LinearLayout box, View anchor, String noteText, int row, int col) {
        if (!noteText.isEmpty()) {
            TextView note = new TextView(this);
            note.setText(noteText);
            note.setTextColor(getColor(R.color.text_dim));
            note.setTextSize(12);
            note.setMaxLines(3);
            note.setPadding(px(6), px(6), px(6), 0);
            box.addView(note);
        }

        for (View[] r : popupNav) for (View v : r) v.setOnKeyListener(this::onPopupKey);

        column.setVisibility(View.VISIBLE);
        ((ViewGroup) column).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        int w = px(264);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int rw = root.getWidth();
        int rh = root.getHeight();
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(px(100), rh - px(24)), View.MeasureSpec.AT_MOST));
        int[] a = new int[2];
        int[] r0 = new int[2];
        anchor.getLocationInWindow(a);
        root.getLocationInWindow(r0);
        int tl = a[0] - r0[0];
        int tt = a[1] - r0[1];
        int[] xy = AddedSources.popupOrigin(tl, tt, tl + anchor.getWidth(), tt + anchor.getHeight(), w,
                box.getMeasuredHeight(), rw, rh, px(12), px(6));
        lp.leftMargin = xy[0];
        lp.topMargin = xy[1];
        popup = box;
        root.addView(box, lp);

        int r = Math.max(0, Math.min(row, popupNav.size() - 1));
        View[] cols = popupNav.get(r);
        View target = cols[Math.max(0, Math.min(col, cols.length - 1))];
        scrollOf(target).jumpOnNextFocus();
        target.requestFocus();
    }

    private SmoothScrollView scrollOf(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p instanceof SmoothScrollView) return (SmoothScrollView) p;
        }
        return scroll;
    }

    private View nav(View row) {
        popupNav.add(new View[] {row});
        return row;
    }

    /** The app's channels, each with its row count and switch; they scroll inside the popup when there are many. */
    private void addChannels(LinearLayout box, String pkg) {
        List<ChannelFilter.Info> mine = new ArrayList<>();
        for (ChannelFilter.Info n : state.chans) if (n.pkg.equals(pkg)) mine.add(n);
        int[] c = probe.get(pkg);
        if (mine.isEmpty()) {
            if (c == null || c[0] == 0) {
                TextView none = new TextView(this);
                none.setText(prefs.probeAt() == 0 ? "Not scanned yet. Use Rescan apps."
                        : "No rows found. The app publishes none this app can read.");
                none.setTextColor(getColor(R.color.text_dim));
                none.setTextSize(12);
                none.setPadding(px(6), px(6), px(6), px(3));
                box.addView(none);
            }
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final Set<String> started = ChannelFilter.startedKeys(state.ch);
        for (final ChannelFilter.Info n : mine) {
            final boolean on = prefs.channelEnabled(pkg, n.name, started);
            String detail = n.total == 0 ? "empty" : n.rows == n.total ? AddedSources.rows(n.rows)
                    : n.rows + " of " + n.total;
            list.addView(nav(popupRow(n.name, detail, on ? "On" : "Off", on, v -> {
                prefs.setChannelEnabled(n.pkg, n.name, !on);
                afterToggle(pkg, n.name + (on ? " is hidden from Continue watching." : " is shown in Continue watching."));
            })));
        }
        if (mine.size() > POPUP_VISIBLE_CHANNELS) {
            SmoothScrollView sv = new SmoothScrollView(this);
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            sv.addView(list);
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    POPUP_VISIBLE_CHANNELS * px(43) + px(3)));
        } else {
            box.addView(list);
        }
    }

    private View popupRow(String name, String detail, String value, boolean accent, View.OnClickListener ok) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_popup_row, null, false);
        ((TextView) v.findViewById(R.id.name)).setText(name);
        TextView d = v.findViewById(R.id.detail);
        d.setText(detail);
        d.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        TextView val = v.findViewById(R.id.value);
        val.setText(value);
        val.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
        val.setTextColor(getColor(value.equals("Off") ? R.color.text_faint : R.color.accent));
        if (name.startsWith("Remove")) ((TextView) v.findViewById(R.id.name)).setTextColor(getColor(R.color.bad));
        v.setOnClickListener(ok);
        return v;
    }

    private TextView pill(String text, View.OnClickListener ok) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(16), 0, px(16), 0);
        t.setFocusable(true);
        t.setBackgroundResource(R.drawable.pill);
        t.setTextColor(getColorStateList(R.color.pill_text));
        t.setOnClickListener(ok);
        return t;
    }

    /** Up and Down move through the popup's rows, Left and Right along a row; nothing leaves the popup. */
    private boolean onPopupKey(View v, int code, KeyEvent e) {
        int dr = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        int dc = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        if (dr == 0 && dc == 0) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        for (int r = 0; r < popupNav.size(); r++) {
            View[] cols = popupNav.get(r);
            for (int c = 0; c < cols.length; c++) {
                if (cols[c] != v) continue;
                int nr = r + dr;
                int nc = c + dc;
                if (nr < 0 || nr >= popupNav.size() || nc < 0 || nc >= cols.length) return true;
                View[] to = popupNav.get(nr);
                to[Math.min(nc, to.length - 1)].requestFocus();
                return true;
            }
        }
        return true;
    }

    private void afterToggle(String key, String message) {
        republish();
        say(message, NEUTRAL);
        popupNote = "";
        changed();
    }

    private void removeSource() {
        String key = popupKey;
        String name = labelOf(key);
        int at = tileKeys.indexOf(key);
        closePopupViews();
        popupAnchor = null;
        confirmRemove = false;
        popupNote = "";
        final Context app = getApplicationContext();
        if (AddedSources.JELLYFIN.equals(key)) {
            prefs.clearJellyfin();
            App.refreshImageAuth(this);
            io.execute(() -> Refresher.refresh(app, false, true));
        } else {
            prefs.removeSource(key);
            republish();
        }
        String next = at >= 0 && at + 1 < tileKeys.size() ? tileKeys.get(at + 1) : ADD;
        build(next);
        say("Removed " + name + ".", NEUTRAL);
    }

    // ---- Jellyfin

    private void jellyfinChanged(String message) {
        say(message, NEUTRAL);
        popupNote = "";
        changed();
        final Context app = getApplicationContext();
        io.execute(() -> {
            Refresher.refresh(app, false, true);
            main.post(() -> {
                if (!isDestroyed()) changed();
            });
        });
    }

    private void testJellyfin() {
        if (busy) return;
        busy = true;
        popupNote = "Testing…";
        changed();
        io.execute(() -> {
            final JellyfinSignIn.Result r = JellyfinSignIn.test(prefs);
            main.post(() -> {
                busy = false;
                if (isDestroyed()) return;
                popupNote = r.message;
                changed();
                say(r.message, r.ok ? GOOD : BAD);
            });
        });
    }

    private void republish() {
        final Context app = getApplicationContext();
        io.execute(() -> Refresher.republish(app));
    }
}
