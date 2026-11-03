package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Watch Next rows carry an intent_uri that another app wrote. Before it becomes an Intent in this
 * app, the string is checked here, and Launcher then pins it to the row's own package. Pure
 * string work, so the JVM tests cover it.
 */
final class IntentPolicy {
    private IntentPolicy() {}

    static final int MAX_LENGTH = 4096;

    /** The uri trimmed, or null when it must not be launched at all. */
    static String check(String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        if (u.isEmpty() || u.length() > MAX_LENGTH) return null;
        for (int i = 0; i < u.length(); i++) {
            if (u.charAt(i) < 0x20) return null;
        }
        String scheme = Text.scheme(u);
        if (scheme.isEmpty() || !schemeAllowed(scheme)) return null;
        return u;
    }

    /** Schemes that name a local file or script are never launched. */
    static boolean schemeAllowed(String scheme) {
        String s = scheme.toLowerCase(Locale.ROOT);
        return !(s.equals("file") || s.equals("content") || s.equals("javascript") || s.equals("data")
                || s.equals("android.resource") || s.equals("android-app") || s.equals("vnd.android.cursor.item"));
    }

    /**
     * Rebuilds an "intent:...#Intent;...;end" string without the parts a row has no business setting:
     * a selector (SEL), launch flags, a component in another package, a package and a browser fallback.
     * Other strings (https://..., netflix://...) are returned unchanged.
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
            if (p.startsWith("launchFlags=") || p.startsWith("package=") || p.startsWith("S.browser_fallback_url=")) {
                continue;
            }
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
        b.append(";end");
        return b.toString();
    }

    /** A short human description for the diagnostics screen: no host, no path, no extras. */
    static String describe(String uri) {
        if (uri == null || uri.isEmpty()) return "(none)";
        String scheme = Text.scheme(uri);
        if (scheme.isEmpty()) return "(unparsable)";
        if (!scheme.equals("intent")) return scheme;
        String inner = "";
        boolean comp = false;
        int at = uri.indexOf("#Intent;");
        if (at >= 0) {
            for (String p : uri.substring(at + 8).split(";")) {
                if (p.startsWith("scheme=")) inner = p.substring(7);
                else if (p.startsWith("component=")) comp = true;
            }
        } else {
            return "intent (malformed)";
        }
        StringBuilder b = new StringBuilder("intent");
        if (!inner.isEmpty() || comp) {
            b.append(" (");
            if (!inner.isEmpty()) b.append("scheme=").append(inner);
            if (comp) b.append(inner.isEmpty() ? "" : ", ").append("component");
            b.append(')');
        }
        return b.toString();
    }
}
