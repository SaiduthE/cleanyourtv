package com.cleanlook.continuewatching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Preview channels (Plex's and Netflix's "Continue watching" rows, which the stock Android TV home shows): turns the
 * channels and their programs into Items, decides which channels are on by default, and removes duplicates. Pure
 * Java, like {@link WatchNextFilter}; WatchNextReader does the querying.
 */
final class ChannelFilter {
    private ChannelFilter() {}

    /** At most this many programs are kept per channel, and this many in all. */
    static final int MAX_PER_CHANNEL = 40;
    static final int MAX_TOTAL = 600;

    /** TvContract aspect-ratio constants: 16:9, 3:2, 4:3 are landscape; 1:1, 2:3 and the movie poster are not. */
    static final int ASPECT_16_9 = 0;
    static final int ASPECT_3_2 = 1;
    static final int ASPECT_4_3 = 2;

    /** Name fragments of a channel that holds what the owner is partway through (lower case). */
    private static final String[] CONTINUE_WORDS = {
        "continue", "resume", "watch next", "up next", "next up", "keep watching", "recently watched",
        "continuar", "continuer", "continua", "reprendre", "reanudar", "retomar", "riprendi", "weiterschauen",
        "weiter schauen", "verder kijken",
    };

    /** One preview channel as the provider returned it. */
    static final class Channel {
        long id;
        String pkg;
        String name;
        boolean browsable = true;
    }

    /** One preview program. Missing values are null, -1 or 0. */
    static final class Program {
        long channelId;
        String title;
        String episodeTitle;
        String season;
        String episode;
        String poster;
        String thumb;
        int posterAspect = -1;
        int thumbAspect = -1;
        String intentUri;
        String description;
        long positionMs = -1;
        long durationMs = -1;
        int weight;
        boolean browsable = true;
    }

    /** What the Sources screen shows for one channel. */
    static final class Info {
        String pkg;
        String name;
        /** Programs this app can show (title and a link). */
        int rows;
        /** Programs the channel holds, shown or not. */
        int total;
    }

    /** True for names like "Continue watching", "Resume", "Watch Next", "Up next", "Recently watched". */
    static boolean isContinueName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String w : CONTINUE_WORDS) if (n.contains(w)) return true;
        return false;
    }

    /** The key a channel's switch is saved under: its package and its name, trimmed and in lower case. */
    static String key(String pkg, String name) {
        return pkg + "|" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    /** The owner's choice if there is one, else on only for channels named like "Continue watching". */
    static boolean enabled(String pkg, String name, Set<String> forcedOn, Set<String> forcedOff) {
        String k = key(pkg, name);
        if (forcedOn != null && forcedOn.contains(k)) return true;
        if (forcedOff != null && forcedOff.contains(k)) return false;
        return isContinueName(name);
    }

    /** True when the aspect ratio constant is a landscape picture. */
    static boolean landscape(int aspect) {
        return aspect == ASPECT_16_9 || aspect == ASPECT_3_2 || aspect == ASPECT_4_3;
    }

    /**
     * Items of every channel of every app (not yet filtered by the owner's switches), grouped by channel in the order
     * the provider gave them and by weight inside a channel. {@code infos} (may be null) receives one entry per channel.
     */
    static List<Item> convert(List<Channel> channels, List<Program> programs, String selfPkg, List<Info> infos) {
        Map<Long, List<Program>> byChannel = new HashMap<>();
        for (Program p : programs) {
            List<Program> l = byChannel.get(p.channelId);
            if (l == null) {
                l = new ArrayList<>();
                byChannel.put(p.channelId, l);
            }
            l.add(p);
        }
        List<Item> out = new ArrayList<>();
        Map<String, Info> infoByKey = new HashMap<>();
        for (Channel ch : channels) {
            if (ch == null || isEmpty(ch.pkg) || isEmpty(ch.name) || ch.pkg.equals(selfPkg)) continue;
            List<Program> ps = byChannel.get(ch.id);
            if (ps == null) ps = Collections.emptyList();
            // The same name can occur twice (an app with one channel per profile): one switch, one entry.
            String k = key(ch.pkg, ch.name);
            Info info = infoByKey.get(k);
            if (info == null) {
                info = new Info();
                info.pkg = ch.pkg;
                info.name = ch.name.trim();
                infoByKey.put(k, info);
                if (infos != null) infos.add(info);
            }
            info.total += ps.size();
            List<Program> sorted = new ArrayList<>(ps);
            Collections.sort(sorted, new Comparator<Program>() {
                @Override public int compare(Program a, Program b) {
                    return Integer.compare(b.weight, a.weight);
                }
            });
            int kept = 0;
            for (Program p : sorted) {
                if (kept >= MAX_PER_CHANNEL || out.size() >= MAX_TOTAL) break;
                Item it = toItem(ch, p);
                if (it == null) continue;
                out.add(it);
                kept++;
                info.rows++;
            }
        }
        return out;
    }

    /** Null when the program should not show (no title or link, hidden by the app, or finished). */
    static Item toItem(Channel ch, Program p) {
        if (p == null || !p.browsable || isEmpty(p.title) || isEmpty(p.intentUri)) return null;
        if (Text.normalizeTitle(p.title).isEmpty()) return null;

        Item it = new Item();
        it.src = Item.SRC_CHANNEL;
        it.pkg = ch.pkg;
        it.channel = ch.name.trim();
        it.title = p.title.trim();
        it.episodeTitle = p.episodeTitle == null ? "" : p.episodeTitle.trim();
        it.season = Text.digits(p.season);
        it.episode = Text.digits(p.episode);
        it.intentUri = p.intentUri;
        it.overview = p.description == null ? "" : p.description.trim();
        it.weight = p.weight;
        it.updatedMs = 0; // preview programs carry no watch time; ordering falls back to weight

        // Progress only when both numbers exist; otherwise the bar stays hidden (-1).
        boolean timed = p.durationMs > 0 && p.positionMs >= 0 && p.positionMs <= p.durationMs;
        if (timed) {
            float f = (float) p.positionMs / (float) p.durationMs;
            if (f >= WatchNextFilter.DONE) return null;
            it.progress = f;
            it.positionMs = p.positionMs;
            it.durationMs = p.durationMs;
        } else if (p.durationMs > 0) {
            it.durationMs = p.durationMs;
        }
        boolean started = timed && p.positionMs > 0;
        it.kind = started || isContinueName(ch.name) ? Item.RESUME : Item.NEXT;
        if (it.kind == Item.NEXT) it.progress = -1f;

        // Artwork: a landscape picture is the backdrop, the app's poster is the poster; ShelfImages checks real sizes.
        String thumb = isEmpty(p.thumb) ? "" : p.thumb;
        String poster = isEmpty(p.poster) ? "" : p.poster;
        if (!thumb.isEmpty() && (p.thumbAspect < 0 || landscape(p.thumbAspect))) {
            it.backdrop = thumb;
        } else if (!poster.isEmpty() && landscape(p.posterAspect)) {
            it.backdrop = poster;
        }
        if (!poster.isEmpty()) it.poster = poster;
        else if (!thumb.isEmpty() && it.backdrop.isEmpty()) it.poster = thumb;
        it.image = !it.backdrop.isEmpty() ? it.backdrop : it.poster;

        it.id = "ch:" + Text.sha1Hex(it.pkg + "|" + Text.normalizeTitle(it.title) + "|" + it.season + "|" + it.episode)
                .substring(0, 16);
        return it;
    }

    /**
     * What shows: items of channels that are on, once each (the same link, or the same title, in two channels of an app
     * is one item; the one from a "Continue watching" channel, then the further along, then the heavier wins), without
     * those the same app already publishes as Watch Next rows ({@code watchNext}, the rows that are visible).
     */
    static List<Item> visible(List<Item> channelItems, Set<String> forcedOn, Set<String> forcedOff, List<Item> watchNext) {
        Set<String> wnLinks = new HashSet<>();
        Set<String> wnTitles = new HashSet<>();
        if (watchNext != null) {
            for (Item w : watchNext) {
                if (!w.intentUri.isEmpty()) wnLinks.add(w.pkg + "|" + w.intentUri);
                wnTitles.add(w.pkg + "|" + Text.normalizeTitle(w.title));
            }
        }
        List<Item> picked = new ArrayList<>();
        Map<String, Integer> byLink = new HashMap<>();
        Map<String, Integer> byTitle = new HashMap<>();
        for (Item it : channelItems) {
            if (!enabled(it.pkg, it.channel, forcedOn, forcedOff)) continue;
            String link = it.pkg + "|" + it.intentUri;
            String title = it.pkg + "|" + Text.normalizeTitle(it.title);
            if (wnLinks.contains(link) || wnTitles.contains(title)) continue;
            Integer at = byLink.get(link);
            if (at == null) at = byTitle.get(title);
            if (at != null) {
                if (better(it, picked.get(at))) picked.set(at, it);
                continue;
            }
            byLink.put(link, picked.size());
            byTitle.put(title, picked.size());
            picked.add(it);
        }
        return picked;
    }

    private static boolean better(Item a, Item b) {
        boolean ca = isContinueName(a.channel);
        boolean cb = isContinueName(b.channel);
        if (ca != cb) return ca;
        if (a.progress != b.progress) return a.progress > b.progress;
        return a.weight > b.weight;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
