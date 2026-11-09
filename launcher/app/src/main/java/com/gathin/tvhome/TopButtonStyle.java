package com.gathin.tvhome;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How each top-bar button shows itself: its icon only (the round pill), its short text only, or both. No Android in
 * it so it runs in JVM tests: the style names, the saved keys, what an unset or unreadable choice falls back to (the
 * theme's own icon_only_* settings, so nothing changes until the owner picks), and the summary the Launcher settings
 * row shows. The Brightness chip keeps its own style in the Widgets manager; both draw through {@link TopButtonStyler}.
 */
final class TopButtonStyle {

    static final String ICON = "icon";
    static final String TEXT = "text";
    static final String BOTH = "both";
    /** In popup order. */
    static final String[] STYLES = {ICON, TEXT, BOTH};
    /** The summary when the buttons do not all share one style. */
    static final String MIXED = "mixed";

    /** The buttons that take a style, in the order the per-button list shows them. */
    enum Button {
        SETTINGS("settings"), INPUTS("inputs"), DISCOVER("discover"), VPN("vpn");

        final String id;

        Button(String id) {
            this.id = id;
        }

        /** The Config.SETTINGS_NAME key. */
        String key() {
            return "top_button_style_" + id;
        }
    }

    /** Reads saved choices: the string saved under {@code key}, or null. */
    interface Source {
        String get(String key);
    }

    private TopButtonStyle() { }

    static boolean valid(String style) {
        return ICON.equals(style) || TEXT.equals(style) || BOTH.equals(style);
    }

    /** {@code style} when it is one of the three, else {@code fallback}. */
    static String normalize(String style, String fallback) {
        return valid(style) ? style : fallback;
    }

    static boolean showsIcon(String style) {
        return !TEXT.equals(style);
    }

    static boolean showsText(String style) {
        return !ICON.equals(style);
    }

    static int indexOf(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return i;
        return 0;
    }

    /**
     * What a button looked like before this setting existed, from the theme's icon_only_* switches: Settings is a
     * round icon with icon_only_buttons or the one-gear top menu, Inputs with icon_only_inputs, the VPN pill with
     * icon_only_vpn, and the Discover compass has always been round.
     */
    static Map<Button, String> themeDefaults(boolean iconOnlyButtons, boolean iconOnlyInputs, boolean iconOnlyVpn,
                                             boolean topMenu) {
        Map<Button, String> out = new EnumMap<>(Button.class);
        out.put(Button.SETTINGS, iconOnlyButtons || topMenu ? ICON : BOTH);
        out.put(Button.INPUTS, iconOnlyInputs ? ICON : BOTH);
        out.put(Button.DISCOVER, ICON);
        out.put(Button.VPN, iconOnlyVpn ? ICON : BOTH);
        return out;
    }

    /** The style of every button: the saved choice, or the default for a button with none (or an unreadable one). */
    static Map<Button, String> load(Source saved, Map<Button, String> defaults) {
        Map<Button, String> out = new EnumMap<>(Button.class);
        for (Button b : Button.values()) {
            String fallback = normalize(defaults.get(b), ICON);
            out.put(b, normalize(saved.get(b.key()), fallback));
        }
        return out;
    }

    /** The one style all buttons share, or {@link #MIXED}. */
    static String common(Map<Button, String> styles) {
        String first = null;
        for (Button b : Button.values()) {
            String s = styles.get(b);
            if (first == null) first = s;
            else if (!first.equals(s)) return MIXED;
        }
        return first == null ? MIXED : first;
    }

    /** The keys and values that give every button {@code style}; written together so they cannot disagree. */
    static Map<String, String> allTo(String style) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Button b : Button.values()) out.put(b.key(), normalize(style, ICON));
        return out;
    }

    /** The key and value that give one button {@code style}. */
    static Map<String, String> one(Button button, String style) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(button.key(), normalize(style, ICON));
        return out;
    }
}
