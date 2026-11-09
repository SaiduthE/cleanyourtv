package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Continue watching rows carry an intent_uri that another app wrote. Before it becomes an Intent here, the string is
 * checked and rebuilt by these functions (the same policy as the add-on's IntentPolicy), and ShelfFeed then pins the
 * Intent to the row's package. Pure string work, so JVM tests cover it.
 */
final class ShelfIntent {

    static final int MAX_LENGTH = 4096;

    private ShelfIntent() { }

    /** The uri trimmed, or null when it must not be launched at all. */
    static String check(String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        if (u.isEmpty() || u.length() > MAX_LENGTH) return null;
        for (int i = 0; i < u.length(); i++) if (u.charAt(i) < 0x20) return null;
        String scheme = scheme(u);
        return scheme.isEmpty() || !schemeAllowed(scheme) ? null : u;
    }

    /** The part before the first colon, when it is a plausible scheme; else empty. */
    static String scheme(String uri) {
        int colon = uri.indexOf(':');
        if (colon <= 0) return "";
        for (int i = 0; i < colon; i++) {
            char c = uri.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'));
            if (!ok) return "";
        }
        return uri.substring(0, colon);
    }

    /** Schemes that name a local file, a script or a provider are never launched. */
    static boolean schemeAllowed(String scheme) {
        String s = scheme.toLowerCase(Locale.ROOT);
        return !(s.equals("file") || s.equals("content") || s.equals("javascript") || s.equals("data")
                || s.equals("android.resource") || s.equals("android-app") || s.equals("vnd.android.cursor.item"));
    }

    /**
     * Rebuilds an "intent:...#Intent;...;end" string without what a row has no business setting: a selector (SEL),
     * launch flags, a component in another package, a package and a browser fallback. Other strings are unchanged.
     */
    static String strip(String uri, String pkg) {
        if (uri == null || !uri.regionMatches(true, 0, "intent:", 0, 7)) return uri;
        int at = uri.indexOf("#Intent;");
        if (at < 0) return uri;
        String head = uri.substring(0, at + "#Intent".length());
        String[] parts = uri.substring(at + "#Intent;".length()).split(";", -1);
        List<String> keep = new ArrayList<>();
        boolean inSelector = false;
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (p.equals("end")) break;
            if (p.equals("SEL")) {
                inSelector = true;
                continue;
            }
            if (inSelector) continue;
            if (p.startsWith("launchFlags=") || p.startsWith("package=") || p.startsWith("S.browser_fallback_url=")) continue;
            if (p.startsWith("component=")) {
                String comp = p.substring("component=".length());
                int slash = comp.indexOf('/');
                String cp = slash < 0 ? comp : comp.substring(0, slash);
                if (!cp.equals(pkg)) continue;
            }
            keep.add(p);
        }
        StringBuilder b = new StringBuilder(head);
        for (String p : keep) b.append(';').append(p);
        return b.append(";end").toString();
    }
}
