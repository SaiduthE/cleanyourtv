package com.cleanlook.continuewatching;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where Continue watching comes from, managed with the remote alone. Watch Next: every installed app, with how many
 * rows it publishes (or "no rows found") and an on/off switch, and under an app that publishes preview channels
 * (Plex, Netflix) each channel with its row count and its own switch. Servers: add, test and remove the Jellyfin server.
 * Changes are saved at once and the shelf follows without waiting for the next refresh.
 */
public final class SourcesActivity extends Activity {
    private static final int REQUEST_LISTINGS = 1;
    private static final int NEUTRAL = 0;
    private static final int GOOD = 1;
    private static final int BAD = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private LinearLayout list;
    private TextView status;
    private boolean busy;
    private boolean confirmRemove;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sources);
        prefs = App.prefs(this);
        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
    }

    @Override protected void onResume() {
        super.onResume();
        confirmRemove = false;
        build();
        if (prefs.probeAt() == 0 && WatchNextReader.granted(this)) scan(null);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_LISTINGS) return;
        if (WatchNextReader.granted(this)) {
            scan(null);
        } else {
            say("Watch Next access was not given. From a computer: " + WatchNextReader.GRANT_COMMAND, BAD);
            build();
        }
    }

    private void say(String message, int tone) {
        status.setText(message);
        status.setTextColor(getColor(tone == GOOD ? R.color.good : tone == BAD ? R.color.bad : R.color.text_dim));
    }

    // ---- the list

    private void build() {
        int focused = list.indexOfChild(getCurrentFocus());
        list.removeAllViews();

        section(getString(R.string.section_watch_next));
        boolean granted = WatchNextReader.granted(this);
        row(null, "Watch Next access", granted ? "Allowed. OK scans the apps again." : "Not allowed yet. OK to allow.",
                granted ? "Allowed" : "Off", false, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (WatchNextReader.granted(SourcesActivity.this)) scan(null);
                        else requestPermissions(new String[] {WatchNextReader.PERMISSION}, REQUEST_LISTINGS);
                    }
                });
        Store.State state = Store.read(this);
        for (AppRow a : apps(state)) {
            appRow(a, granted, state);
            if (granted) for (ChannelFilter.Info n : state.chans) if (n.pkg.equals(a.pkg)) channelRow(a, n);
        }

        section(getString(R.string.section_servers));
        jellyfinRows();

        section(getString(R.string.section_launcher));
        boolean feed = prefs.shelfEnabled();
        row(null, "Top Shelf feed", "Titles and artwork offered to the launcher", feed ? "On" : "Off", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.setShelfEnabled(!prefs.shelfEnabled());
                        republish();
                        say(prefs.shelfEnabled() ? "The launcher can read Continue watching again." : "The launcher gets nothing now.", NEUTRAL);
                        build();
                    }
                });

        View target = focused >= 0 && focused < list.getChildCount() ? list.getChildAt(focused) : null;
        if (target == null || !target.isFocusable()) target = firstFocusable();
        if (target != null) target.requestFocus();
    }

    private View firstFocusable() {
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) return list.getChildAt(i);
        return null;
    }

    private void section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.text_faint));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(dp(4), dp(list.getChildCount() == 0 ? 4 : 22), 0, dp(2));
        list.addView(t);
    }

    private View row(Drawable icon, String name, String detail, String value, boolean dim, View.OnClickListener ok) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_source, list, false);
        ImageView iv = v.findViewById(R.id.icon);
        if (icon == null) iv.setVisibility(View.GONE);
        else iv.setImageDrawable(icon);
        TextView n = v.findViewById(R.id.name);
        n.setText(name);
        n.setTextColor(getColor(dim ? R.color.text_dim : R.color.text));
        TextView d = v.findViewById(R.id.detail);
        d.setText(detail);
        d.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        TextView val = v.findViewById(R.id.value);
        val.setText(value);
        val.setTextColor(getColor(value.equals("Off") ? R.color.text_faint : R.color.accent));
        v.setOnClickListener(ok);
        list.addView(v);
        return v;
    }

    // ---- Watch Next apps

    private static final class AppRow {
        String pkg;
        String name;
        int rows;
        int channelRows;
        int channels;
        int channelItems;
    }

    private List<AppRow> apps(Store.State state) {
        Map<String, int[]> found = Probe.decode(prefs.probeJson());
        Set<String> pkgs = new TreeSet<>(found.keySet());
        for (ChannelFilter.Info n : state.chans) pkgs.add(n.pkg);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(launcher, 0)) pkgs.add(ri.activityInfo.packageName);
        pkgs.remove(getPackageName());
        pkgs.remove("(unknown)");
        List<AppRow> out = new ArrayList<>();
        for (String p : pkgs) {
            AppRow a = new AppRow();
            a.pkg = p;
            a.name = App.label(this, p);
            int[] c = found.get(p);
            a.rows = c == null ? 0 : c[0];
            a.channelRows = c == null ? 0 : c[1];
            for (ChannelFilter.Info n : state.chans) {
                if (!n.pkg.equals(p)) continue;
                a.channels++;
                a.channelItems += n.rows;
            }
            out.add(a);
        }
        Collections.sort(out, new Comparator<AppRow>() {
            @Override public int compare(AppRow x, AppRow y) {
                int xs = x.rows + x.channelItems;
                int ys = y.rows + y.channelItems;
                if ((xs > 0) != (ys > 0)) return xs > 0 ? -1 : 1;
                if (xs != ys) return ys - xs;
                return x.name.compareToIgnoreCase(y.name);
            }
        });
        return out;
    }

    private void appRow(final AppRow a, boolean granted, Store.State state) {
        boolean scanned = prefs.probeAt() != 0;
        final boolean on = !prefs.disabledPackages().contains(a.pkg);
        String detail;
        if (!granted) detail = "Needs Watch Next access";
        else if (!scanned) detail = "Not scanned yet";
        else if (a.rows > 0) detail = a.rows + (a.rows == 1 ? " row" : " rows");
        else if (a.channels > 0) {
            detail = "No Watch Next rows · " + a.channels + (a.channels == 1 ? " channel" : " channels") + " below";
        } else if (a.channelRows > 0) {
            detail = "No Watch Next rows · " + a.channelRows + " channel rows "
                    + (state.chStatus.equals("error") ? "could not be read" : "found, none readable");
        } else detail = "No rows found";
        if (a.rows > 0 && a.channels > 0) {
            detail += " · " + a.channels + (a.channels == 1 ? " channel" : " channels") + " below";
        }
        boolean publishes = granted && a.rows > 0;
        row(App.icon(this, a.pkg), a.name, detail, publishes ? (on ? "On" : "Off") : "", !publishes,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (WatchNextReader.granted(SourcesActivity.this) && a.rows > 0) toggle(a, on);
                        else scan(a);
                    }
                });
    }

    /** One preview channel of an app: its name, how many rows it holds, and its own switch (default: on for "Continue"). */
    private void channelRow(final AppRow a, final ChannelFilter.Info n) {
        final boolean on = prefs.channelEnabled(n.pkg, n.name);
        String detail = a.name + " channel · ";
        if (n.total == 0) detail += "empty right now";
        else if (n.rows == n.total) detail += n.rows + (n.rows == 1 ? " row" : " rows");
        else detail += n.rows + " of " + n.total + " rows can be shown";
        row(null, n.name, detail, on ? "On" : "Off", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.setChannelEnabled(n.pkg, n.name, !on);
                republish();
                say(n.name + (on ? " is hidden from Continue watching." : " is shown in Continue watching."), NEUTRAL);
                build();
            }
        });
    }

    private void toggle(AppRow a, boolean wasOn) {
        prefs.setPackageEnabled(a.pkg, !wasOn);
        republish();
        say(a.name + (wasOn ? " is hidden from Continue watching." : " is shown in Continue watching."), NEUTRAL);
        build();
    }

    /** Reads every app's rows again, then reports on {@code only} or on all of them. */
    private void scan(final AppRow only) {
        if (busy) return;
        if (!WatchNextReader.granted(this)) {
            say("Watch Next access is off, so no app can be read. Choose \"Watch Next access\" first.", BAD);
            return;
        }
        busy = true;
        say("Scanning the apps…", NEUTRAL);
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                Refresher.probe(app);
                Refresher.refresh(app, true, false);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isDestroyed()) return;
                        build();
                        report(only);
                    }
                });
            }
        });
    }

    private void report(AppRow only) {
        Map<String, int[]> found = Probe.decode(prefs.probeJson());
        if (only != null) {
            int[] c = found.get(only.pkg);
            int channels = 0;
            for (ChannelFilter.Info i : Store.read(this).chans) if (i.pkg.equals(only.pkg)) channels++;
            if (channels > 0 && (c == null || c[0] == 0)) {
                say(only.name + ": " + channels + (channels == 1 ? " channel" : " channels")
                        + " found. Switch the ones you want on.", GOOD);
            } else if (c != null && c[0] > 0) {
                say(only.name + ": " + c[0] + (c[0] == 1 ? " row" : " rows") + " found. It can be switched on or off now.", GOOD);
            } else {
                say(only.name + ": no Watch Next rows found. Either it publishes none, or none that this app is allowed to read.", BAD);
            }
            return;
        }
        int n = 0;
        for (int[] c : found.values()) if (c[0] > 0) n++;
        int channels = Store.read(this).chans.size();
        String ch = channels == 0 ? "" : " " + channels + (channels == 1 ? " channel" : " channels") + " found.";
        say(n == 0 && channels == 0 ? "Scanned. No app publishes rows this app can read."
                : "Scanned. " + n + (n == 1 ? " app publishes" : " apps publish") + " Watch Next rows." + ch,
                n == 0 && channels == 0 ? BAD : GOOD);
    }

    // ---- Jellyfin

    private void jellyfinRows() {
        final Intent setup = new Intent(this, JellyfinActivity.class);
        if (!prefs.jellyfinConfigured()) {
            String detail = prefs.jellyfinStatus().equals(Prefs.JF_NONE) ? "Address, user, and a password or an API key"
                    : "Sign-in lost. Add it again.";
            row(null, "Add Jellyfin server", detail, "Add", false, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(setup);
                }
            });
            return;
        }
        row(null, "Jellyfin · " + prefs.serverName(), prefs.userName() + " · " + jellyfinStatus(), "Edit", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(setup);
                    }
                });
        row(null, "Resume", "Titles partway through on the server", prefs.showResume() ? "On" : "Off", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.setShowResume(!prefs.showResume());
                        jellyfinChanged("Resume list changed.");
                    }
                });
        row(null, "Next up", "The next episode of shows being watched", prefs.showNextUp() ? "On" : "Off", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        prefs.setShowNextUp(!prefs.showNextUp());
                        jellyfinChanged("Next up list changed.");
                    }
                });
        row(null, "Test Jellyfin", "Signs in again and counts what is in progress", "Test", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                testJellyfin();
            }
        });
        final View remove = row(null, "Remove Jellyfin", "", "Remove", false, null);
        final TextView detail = remove.findViewById(R.id.detail);
        final TextView name = remove.findViewById(R.id.name);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!confirmRemove) {
                    confirmRemove = true;
                    name.setText("Press OK again to remove it");
                    detail.setVisibility(View.VISIBLE);
                    detail.setText("The saved sign-in is deleted. Move away to cancel.");
                    return;
                }
                confirmRemove = false;
                prefs.clearJellyfin();
                App.refreshImageAuth(SourcesActivity.this);
                jellyfinChanged("Jellyfin removed.");
            }
        });
        remove.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                if (focused || !confirmRemove) return;
                confirmRemove = false;
                name.setText("Remove Jellyfin");
                detail.setVisibility(View.GONE);
            }
        });
    }

    private String jellyfinStatus() {
        switch (prefs.jellyfinStatus()) {
            case Prefs.JF_OK: return "connected";
            case Prefs.JF_SIGN_IN: return "sign in again";
            case Prefs.JF_UNREACHABLE: return "server not reachable";
            default: return "not tested";
        }
    }

    private void jellyfinChanged(String message) {
        say(message, NEUTRAL);
        build();
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                Refresher.refresh(app, false, true);
            }
        });
    }

    private void testJellyfin() {
        if (busy) return;
        busy = true;
        say("Testing…", NEUTRAL);
        io.execute(new Runnable() {
            @Override public void run() {
                final JellyfinSignIn.Result r = JellyfinSignIn.test(prefs);
                main.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (isDestroyed()) return;
                        build();
                        say(r.message, r.ok ? GOOD : BAD);
                    }
                });
            }
        });
    }

    private void republish() {
        final android.content.Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override public void run() {
                Refresher.republish(app);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
