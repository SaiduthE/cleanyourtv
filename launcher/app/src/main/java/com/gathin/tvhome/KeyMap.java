package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Remote button mappings set on the TV itself (Launcher settings > Remote buttons), kept in the launcher's private
 * preferences. Each is one line in the app_keys format ({@link RemoteKeys}): a button as a keycode ("199") or a
 * scancode ("scan:393"), an optional gesture prefix ("hold:" or "dbl:"; none means a plain press) and a target (a
 * package, an action word, "inputs", ...). {@link #merged} lays them over config.xml / a theme's app_keys, so a
 * choice made here wins for the same button and gesture.
 */
final class KeyMap {

    static final String PREFS = "remote_key_map";
    private static final String KEY = "entries";

    static final String PRESS = "";
    static final String HOLD = "hold:";
    static final String DOUBLE = "dbl:";

    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,6}");

    private KeyMap() { }

    /** One mapping: which button, which gesture and what it does. */
    static final class Entry {
        final String gesture;   // PRESS, HOLD or DOUBLE
        final String token;     // "199" or "scan:393"
        final String target;

        Entry(String gesture, String token, String target) {
            this.gesture = gesture;
            this.token = token;
            this.target = target;
        }

        String line() {
            return gesture + token + ":" + target;
        }

        String id() {
            return gesture + token;
        }
    }

    /** The button as a token: its keycode, or its scancode when Android gave it no keycode. */
    static String token(int keyCode, int scanCode) {
        if (keyCode > 0 && keyCode != KeyEvent.KEYCODE_UNKNOWN) return String.valueOf(keyCode);
        return scanCode > 0 ? "scan:" + scanCode : null;
    }

    /** Buttons the page itself needs to be usable. Never offered for mapping: a mapped one stops moving focus. */
    static boolean isProtected(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
                return true;
            default:
                return false;
        }
    }

    /** A line of the app_keys format as an Entry; null when it is not a well-formed button mapping. */
    static Entry parse(String line) {
        if (line == null) return null;
        String text = line.trim();
        String gesture = PRESS;
        if (text.startsWith(HOLD)) {
            gesture = HOLD;
            text = text.substring(HOLD.length());
        } else if (text.startsWith(DOUBLE)) {
            gesture = DOUBLE;
            text = text.substring(DOUBLE.length());
        }
        String token;
        String target;
        if (text.startsWith("scan:")) {
            String[] parts = text.split(":", 3);
            if (parts.length != 3 || !NUMBER.matcher(parts[1]).matches()) return null;
            token = "scan:" + parts[1];
            target = parts[2];
        } else {
            String[] parts = text.split(":", 2);
            if (parts.length != 2 || !NUMBER.matcher(parts[0]).matches()) return null;
            token = parts[0];
            target = parts[1];
        }
        target = target.trim();
        return target.isEmpty() ? null : new Entry(gesture, token, target);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Mappings made on the TV, one per button and gesture, in the order they were added. */
    static List<Entry> load(Context ctx) {
        Map<String, Entry> found = new LinkedHashMap<>();
        String stored = prefs(ctx).getString(KEY, "");
        for (String line : stored.split("\n")) {
            Entry e = parse(line);
            if (e != null) found.put(e.id(), e);
        }
        return new ArrayList<>(found.values());
    }

    private static void save(Context ctx, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) sb.append(e.line()).append('\n');
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    /** Sets what this button does for this gesture, replacing an earlier choice for the same pair. */
    static void put(Context ctx, String gesture, String token, String target) {
        List<Entry> entries = load(ctx);
        Entry next = new Entry(gesture, token, target);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(next.id())) {
                entries.set(i, next);
                save(ctx, entries);
                return;
            }
        }
        entries.add(next);
        save(ctx, entries);
    }

    static void remove(Context ctx, String gesture, String token) {
        List<Entry> entries = load(ctx);
        String id = gesture + token;
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).id().equals(id)) entries.remove(i);
        save(ctx, entries);
    }

    /** True when a press of this button already has a mapping (config or on-TV), so hold/double won't kill it. */
    static boolean hasPress(Context ctx, List<String> configEntries, String token) {
        for (String line : merged(ctx, configEntries)) {
            Entry e = parse(line);
            if (e != null && e.gesture.equals(PRESS) && e.token.equals(token)) return true;
        }
        return false;
    }

    /**
     * config.xml / theme app_keys first, then the on-TV mappings: the parsers keep the last entry for a button, so
     * a choice made on the TV wins. Entries written with a KEYCODE_ name are kept as they are.
     */
    static List<String> merged(Context ctx, List<String> configEntries) {
        List<String> all = new ArrayList<>(configEntries);
        for (Entry e : load(ctx)) all.add(e.line());
        return all;
    }

    /** Registers for changes, so a running key helper picks a new mapping up without a restart. */
    static void listen(Context ctx, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs(ctx).registerOnSharedPreferenceChangeListener(listener);
    }

    static void unlisten(Context ctx, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs(ctx).unregisterOnSharedPreferenceChangeListener(listener);
    }
}
