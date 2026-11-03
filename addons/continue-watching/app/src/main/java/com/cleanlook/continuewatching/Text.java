package com.cleanlook.continuewatching;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Small pure helpers: title keys, time labels, hashes. No Android classes, so JVM tests cover them. */
final class Text {
    private Text() {}

    /** Lower case letters and digits only: "The Bear (2022)" and "the bear 2022" are the same key. */
    static String normalizeTitle(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String lower = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /** The first run of digits as a number, or -1: "Season 2" and "S2" and "2" all give 2. */
    static int digits(String s) {
        if (s == null) return -1;
        int i = 0;
        int n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) i++;
        if (i == n) return -1;
        long v = 0;
        while (i < n && Character.isDigit(s.charAt(i)) && v < 100000) {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        return (int) v;
    }

    /** "S2 · E4", "E4", or null. */
    static String episodeLabel(int season, int episode) {
        if (season >= 0 && episode >= 0) return "S" + season + " · E" + episode;
        if (episode >= 0) return "E" + episode;
        if (season >= 0) return "S" + season;
        return null;
    }

    /** "23 min left", "1 h 11 min left", "2 h left"; null when nothing is known. */
    static String left(long remainingMs) {
        if (remainingMs <= 0) return null;
        long mins = (remainingMs + 59_999) / 60_000;
        return duration(mins * 60_000) + " left";
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago". */
    static String ago(long diffMs) {
        long mins = Math.max(0, diffMs) / 60_000;
        if (mins < 1) return "just now";
        if (mins < 60) return mins + " min ago";
        if (mins < 24 * 60) return mins / 60 + " h ago";
        return mins / (24 * 60) + " d ago";
    }

    /** "52 min", "1 h 52 min", "2 h". */
    static String duration(long ms) {
        long mins = Math.max(1, (ms + 30_000) / 60_000);
        if (mins < 60) return mins + " min";
        long h = mins / 60;
        long m = mins % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "https" for "https://x", "intent" for "intent:...", "" when there is none. */
    static String scheme(String uri) {
        if (uri == null) return "";
        int i = 0;
        int n = uri.length();
        if (n == 0 || !Character.isLetter(uri.charAt(0))) return "";
        while (i < n) {
            char c = uri.charAt(i);
            if (c == ':') return uri.substring(0, i).toLowerCase(Locale.ROOT);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) return "";
            i++;
        }
        return "";
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
