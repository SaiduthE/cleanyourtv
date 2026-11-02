package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Launcher settings > Remote buttons: map any remote button to an app or an action, for a press, a hold or a double
 * press, from the couch. Everything happens inside the panel, one screen at a time (the list, "press a button",
 * which gesture, what it does); Back steps out. "Add a button" waits for a press on the remote through
 * InputKeyService, which sees every real button the firmware lets through. Mappings are kept by {@link KeyMap} and
 * laid over app_keys from config.xml or the theme; the running key helper reloads them at once.
 */
public final class RemoteKeysActivity extends Activity {

    /** Action words {@link RemoteKeys} runs, with the name shown for each. */
    private static final String[][] ACTIONS = {
            {"launcher_settings", "Launcher settings"},
            {"home", "Go Home"},
            {"back", "Back"},
            {"recents", "Recent apps"},
            {"mute", "Mute / unmute"},
            {"sleep", "Sleep (standby)"},
            {"screensaver", "Start screensaver"},
            {"settings", "TV settings"},
            {"wifi", "Wi-Fi settings"},
            {"library", "App library"},
            {RemoteKeys.INPUTS, "Input switcher"},
            {"ignore", "Do nothing (button is ignored)"},
    };
    private static final String[][] GESTURES = {
            {KeyMap.PRESS, "Press"},
            {KeyMap.HOLD, "Hold"},
            {KeyMap.DOUBLE, "Double press"},
    };
    private static final long CAPTURE_TIMEOUT_MS = 20_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private Config config;
    /** What Back does on the current screen; null on the list, where it leaves the page. */
    private Runnable onBack;
    private boolean capturing;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = Config.load(this);
        content = new LinearLayout(this);
        setContentView(PanelUi.scroller(this, content));
        SettingsPanel.apply(this);
        showMain();
    }

    @Override
    protected void onDestroy() {
        stopCapture();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (onBack != null) onBack.run();
        else super.onBackPressed();
    }

    /** While waiting for a button, any unprotected key that reaches this window is the one to map. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (capturing && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && !KeyMap.isProtected(event.getKeyCode())) {
            onCaptured(event.getKeyCode(), event.getScanCode());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean helperOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        return enabled != null && enabled.contains("InputKeyService");
    }

    /** Starts a screen: clears the panel and sets what Back does. Returns the content to fill. */
    private LinearLayout screen(String title, Runnable back) {
        content.removeAllViews();
        onBack = back;
        content.addView(PanelUi.title(this, title));
        return content;
    }

    /** Puts focus on the row marked selected, else the first focusable row, once the screen is built. */
    private void focusFirst(View preferred) {
        View target = preferred;
        if (target == null) {
            for (int i = 0; i < content.getChildCount() && target == null; i++) {
                View v = content.getChildAt(i);
                if (v.isFocusable()) target = v;
            }
        }
        if (target != null) target.requestFocus();
    }

    // ---- the list ----------------------------------------------------------------------------------------------

    /** Every mapping now in force, with whether it came from config.xml / the theme rather than from this page. */
    private Map<String, KeyMap.Entry> current(Map<String, Boolean> fromConfig) {
        Map<String, KeyMap.Entry> all = new LinkedHashMap<>();
        for (String line : config.appKeys) {
            KeyMap.Entry e = KeyMap.parse(line);
            if (e == null) continue;
            all.put(e.id(), e);
            fromConfig.put(e.id(), true);
        }
        for (KeyMap.Entry e : KeyMap.load(this)) {
            all.put(e.id(), e);
            fromConfig.put(e.id(), false);
        }
        return all;
    }

    private void showMain() {
        stopCapture();
        screen("Remote buttons", null);
        content.addView(PanelUi.note(this, "Choose what a button does when pressed, held or pressed twice. A plain "
                + "press answers at once; only a button with a double-press action waits a moment for a second press."));
        content.addView(PanelUi.note(this, helperOn()
                ? "Key helper: on. It sees buttons before the TV's firmware, so firmware-handled ones (a Netflix "
                    + "button) can be remapped. Buttons the firmware injects itself, such as the mic button, cannot."
                : "Key helper: OFF. Only buttons that reach the launcher can be mapped, and a mapped button won't "
                    + "work inside other apps. Turn it on by running the launcher install with -InputKeyService."));

        TextView add = PanelUi.row(this, "+  Add a button");
        ((LinearLayout.LayoutParams) add.getLayoutParams()).topMargin = PanelUi.dp(this, 24);
        add.setOnClickListener(v -> showCapture());
        content.addView(add);

        Map<String, Boolean> fromConfig = new LinkedHashMap<>();
        Map<String, KeyMap.Entry> all = current(fromConfig);
        if (all.isEmpty()) {
            content.addView(PanelUi.note(this, "No remote buttons are mapped yet."));
        } else {
            content.addView(PanelUi.section(this, "Mapped buttons"));
            List<KeyMap.Entry> entries = new ArrayList<>(all.values());
            Collections.sort(entries, (a, b) -> a.token.compareTo(b.token));
            for (KeyMap.Entry e : entries) {
                boolean theme = Boolean.TRUE.equals(fromConfig.get(e.id()));
                TextView row = PanelUi.row(this, buttonLabel(e.token) + "   " + gestureLabel(e.gesture)
                        + "  →  " + targetLabel(e.target) + (theme ? "   (preset)" : ""));
                row.setOnClickListener(v -> showTarget(e.gesture, e.token, () -> showMain()));
                content.addView(row);
            }
        }
        focusFirst(add);
    }

    private static String gestureLabel(String gesture) {
        for (String[] g : GESTURES) if (g[0].equals(gesture)) return g[1].toLowerCase(Locale.ROOT);
        return "press";
    }

    private static String buttonLabel(String token) {
        if (token.startsWith("scan:")) return "Button (scancode " + token.substring(5) + ")";
        int code;
        try {
            code = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return token;
        }
        String name = KeyEvent.keyCodeToString(code);
        if (name.startsWith("KEYCODE_")) name = name.substring(8).replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " (" + code + ")";
    }

    private String targetLabel(String target) {
        for (String[] a : ACTIONS) if (a[0].equals(target)) return a[1];
        if (target.startsWith("input:")) return "HDMI input";
        if (target.indexOf('/') > 0) return target;
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(target, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return target + " (not installed)";
        }
    }

    // ---- learning a button -------------------------------------------------------------------------------------

    private void showCapture() {
        screen("Press a button", this::showMain);
        content.addView(PanelUi.note(this, "Press the button on your remote that you want to change. The D-pad, OK, "
                + "Back and Home can't be remapped, so this page always stays usable."));
        content.addView(PanelUi.note(this, "Waiting… (it gives up after 20 seconds)"));
        TextView cancel = PanelUi.row(this, "Cancel");
        ((LinearLayout.LayoutParams) cancel.getLayoutParams()).topMargin = PanelUi.dp(this, 28);
        cancel.setOnClickListener(v -> showMain());
        content.addView(cancel);
        focusFirst(cancel);
        // With the key helper on, it hands over every unprotected button, firmware-handled ones included. Without
        // it, only buttons that reach this window arrive (dispatchKeyEvent).
        capturing = true;
        RemoteKeys.capture = e -> runOnUiThread(() -> onCaptured(e.getKeyCode(), e.getScanCode()));
        handler.postDelayed(() -> {
            if (capturing) showMain();
        }, CAPTURE_TIMEOUT_MS);
    }

    private void stopCapture() {
        capturing = false;
        RemoteKeys.capture = null;
        handler.removeCallbacksAndMessages(null);
    }

    private void onCaptured(int keyCode, int scanCode) {
        if (!capturing) return;
        stopCapture();
        String token = KeyMap.token(keyCode, scanCode);
        if (token == null) {
            Toast.makeText(this, "That button sent no usable code.", Toast.LENGTH_LONG).show();
            showMain();
            return;
        }
        showGesture(token);
    }

    // ---- which gesture -----------------------------------------------------------------------------------------

    private void showGesture(String token) {
        screen(buttonLabel(token), this::showMain);
        content.addView(PanelUi.note(this, "Which action do you want to set for this button?"));
        Map<String, KeyMap.Entry> all = current(new LinkedHashMap<>());
        View first = null;
        for (String[] g : GESTURES) {
            KeyMap.Entry now = all.get(g[0] + token);
            TextView row = PanelUi.row(this, g[1] + (now == null ? "" : "   →  " + targetLabel(now.target)));
            ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = PanelUi.dp(this, first == null ? 22 : 10);
            row.setSelected(now != null);
            row.setOnClickListener(v -> showTarget(g[0], token, () -> showGesture(token)));
            content.addView(row);
            if (first == null) first = row;
        }
        focusFirst(first);
    }

    // ---- what it does ------------------------------------------------------------------------------------------

    private void showTarget(String gesture, String token, Runnable back) {
        screen(buttonLabel(token) + "  —  " + gestureLabel(gesture), back);
        Map<String, KeyMap.Entry> all = current(new LinkedHashMap<>());
        KeyMap.Entry now = all.get(gesture + token);
        content.addView(PanelUi.note(this, "What should it do?"));

        View focus = null;
        boolean saved = false;
        for (KeyMap.Entry e : KeyMap.load(this)) saved |= e.id().equals(gesture + token);
        if (saved) {
            TextView remove = PanelUi.row(this, "Remove this mapping");
            ((LinearLayout.LayoutParams) remove.getLayoutParams()).topMargin = PanelUi.dp(this, 22);
            remove.setOnClickListener(v -> {
                KeyMap.remove(this, gesture, token);
                showMain();
            });
            content.addView(remove);
            focus = remove;
        }

        PackageManager pm = getPackageManager();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        Collections.sort(found, (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        content.addView(PanelUi.section(this, "Open an app"));
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || seen.containsKey(pkg)) continue;
            seen.put(pkg, true);
            TextView row = PanelUi.row(this, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm));
            boolean current = now != null && now.target.equals(pkg);
            row.setSelected(current);
            row.setOnClickListener(v -> choose(gesture, token, pkg));
            content.addView(row);
            if (current && focus == null) focus = row;
        }
        content.addView(PanelUi.section(this, "Or do an action"));
        for (String[] a : ACTIONS) {
            TextView row = PanelUi.row(this, a[1]);
            boolean current = now != null && now.target.equals(a[0]);
            row.setSelected(current);
            row.setOnClickListener(v -> choose(gesture, token, a[0]));
            content.addView(row);
            if (current && focus == null) focus = row;
        }
        focusFirst(focus);
    }

    private void choose(String gesture, String token, String target) {
        // Once a button is taken over, a plain press that has no mapping would be swallowed and do nothing at all.
        // Say so, and make that explicit so the owner can give the press something to do.
        if (!gesture.equals(KeyMap.PRESS) && !KeyMap.hasPress(this, config.appKeys, token)) {
            KeyMap.put(this, KeyMap.PRESS, token, "ignore");
            Toast.makeText(this, "A plain press of this button now does nothing. Set \"Press\" too if you want it to.",
                    Toast.LENGTH_LONG).show();
        }
        KeyMap.put(this, gesture, token, target);
        showMain();
    }
}
