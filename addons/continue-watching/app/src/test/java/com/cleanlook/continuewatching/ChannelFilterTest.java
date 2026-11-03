package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class ChannelFilterTest {
    private static ChannelFilter.Channel channel(long id, String pkg, String name) {
        ChannelFilter.Channel c = new ChannelFilter.Channel();
        c.id = id;
        c.pkg = pkg;
        c.name = name;
        return c;
    }

    private static ChannelFilter.Program program(long channel, String title, long pos, long dur, int weight) {
        ChannelFilter.Program p = new ChannelFilter.Program();
        p.channelId = channel;
        p.title = title;
        p.intentUri = "https://app.example/watch/" + title.replace(' ', '-');
        p.positionMs = pos;
        p.durationMs = dur;
        p.weight = weight;
        p.poster = "https://img.example/p.jpg";
        return p;
    }

    private static List<Item> convert(List<ChannelFilter.Channel> ch, ChannelFilter.Program... ps) {
        return ChannelFilter.convert(ch, Arrays.asList(ps), "self", null);
    }

    @Test public void continueLikeNamesAreRecognised() {
        for (String n : new String[] {"Continue Watching", "continue watching on Plex", "Resume", "Watch Next",
                "UP NEXT", "Next up", "Keep watching", "Recently Watched", "Continuar viendo", "Reprendre la lecture",
                "Weiterschauen"}) {
            assertTrue(n, ChannelFilter.isContinueName(n));
        }
        for (String n : new String[] {"Recommended", "Trending now", "Movies", "", null}) {
            assertFalse(String.valueOf(n), ChannelFilter.isContinueName(n));
        }
    }

    @Test public void defaultIsOnlyForContinueChannelsAndTheOwnersChoiceWins() {
        Set<String> on = new HashSet<>(Collections.singleton(ChannelFilter.key("p", "  Trending ")));
        Set<String> off = new HashSet<>(Collections.singleton(ChannelFilter.key("p", "Continue Watching")));
        assertTrue(ChannelFilter.enabled("p", "Resume", null, null));
        assertFalse(ChannelFilter.enabled("p", "Trending", null, null));
        assertTrue("forced on, case and spaces ignored", ChannelFilter.enabled("p", "trending", on, off));
        assertFalse("forced off", ChannelFilter.enabled("p", "continue watching", on, off));
        assertTrue("per package", ChannelFilter.enabled("q", "Continue Watching", on, off));
    }

    @Test public void progressFromPositionAndDurationElseHidden() {
        List<ChannelFilter.Channel> ch = Arrays.asList(channel(1, "com.plexapp.android", "Continue Watching"));
        List<Item> out = convert(ch, program(1, "Half", 1_500_000, 3_000_000, 0), program(1, "Unknown", -1, -1, 0),
                program(1, "No duration", 500, -1, 0));
        assertEquals(0.5f, out.get(0).progress, 0.001f);
        assertEquals(1_500_000L, out.get(0).remainingMs());
        assertEquals(Item.RESUME, out.get(0).kind);
        assertEquals(-1f, out.get(1).progress, 0f);
        assertEquals("a Continue channel keeps unknown rows as resume", Item.RESUME, out.get(1).kind);
        assertEquals(-1f, out.get(2).progress, 0f);
    }

    @Test public void finishedProgramsAreDroppedAndOtherChannelsWithoutPositionAreUpNext() {
        List<ChannelFilter.Channel> ch = Arrays.asList(channel(1, "a", "Continue Watching"), channel(2, "a", "Recommended"));
        List<Item> out = convert(ch, program(1, "Done", 96, 100, 0), program(2, "Fresh", -1, -1, 0),
                program(2, "Started", 10, 100, 0));
        assertEquals(2, out.size());
        assertEquals("Fresh", out.get(0).title);
        assertEquals(Item.NEXT, out.get(0).kind);
        assertEquals(-1f, out.get(0).progress, 0f);
        assertEquals(Item.RESUME, out.get(1).kind);
    }

    @Test public void skipsRowsWithoutTitleOrLinkHiddenRowsAndOurOwnChannels() {
        List<ChannelFilter.Channel> ch = Arrays.asList(channel(1, "a", "Continue"), channel(2, "self", "Continue"));
        ChannelFilter.Program noLink = program(1, "No link", 1, 100, 0);
        noLink.intentUri = " ";
        ChannelFilter.Program hidden = program(1, "Hidden", 1, 100, 0);
        hidden.browsable = false;
        List<Item> out = convert(ch, noLink, hidden, program(1, "!!!", 1, 100, 0), program(2, "Mine", 1, 100, 0),
                program(1, "Kept", 1, 100, 0));
        assertEquals(1, out.size());
        assertEquals("Kept", out.get(0).title);
        assertEquals("Continue", out.get(0).channel);
        assertTrue(out.get(0).id.startsWith("ch:"));
    }

    @Test public void orderIsByWeightInsideAChannelAndInfosCountHonestly() {
        List<ChannelFilter.Channel> ch = Arrays.asList(channel(1, "a", "Continue"), channel(2, "a", "Recommended"));
        ChannelFilter.Program bad = program(1, "Bad", 1, 100, 99);
        bad.intentUri = null;
        List<ChannelFilter.Info> infos = new ArrayList<>();
        List<Item> out = ChannelFilter.convert(ch, Arrays.asList(program(1, "Low", 1, 100, 1), program(1, "High", 1, 100, 5), bad),
                "self", infos);
        assertEquals("High", out.get(0).title);
        assertEquals("Low", out.get(1).title);
        assertEquals(2, infos.size());
        assertEquals(2, infos.get(0).rows);
        assertEquals(3, infos.get(0).total);
        assertEquals(0, infos.get(1).total);
    }

    @Test public void thumbnailIsTheBackdropAndAPortraitPosterStaysAPoster() {
        List<ChannelFilter.Channel> ch = Arrays.asList(channel(1, "a", "Continue"));
        ChannelFilter.Program p = program(1, "Art", 1, 100, 0);
        p.thumb = "https://img.example/t.jpg";
        p.thumbAspect = ChannelFilter.ASPECT_16_9;
        p.posterAspect = 4;
        Item it = convert(ch, p).get(0);
        assertEquals("https://img.example/t.jpg", it.backdrop);
        assertEquals("https://img.example/p.jpg", it.poster);
        assertEquals(it.backdrop, it.image);

        ChannelFilter.Program wide = program(1, "Wide", 1, 100, 0);
        wide.posterAspect = ChannelFilter.ASPECT_16_9;
        Item w = convert(ch, wide).get(0);
        assertEquals("a landscape poster is also the backdrop", "https://img.example/p.jpg", w.backdrop);

        ChannelFilter.Program square = program(1, "Square", 1, 100, 0);
        square.poster = null;
        square.thumb = "https://img.example/sq.jpg";
        square.thumbAspect = 3;
        Item s = convert(ch, square).get(0);
        assertEquals("", s.backdrop);
        assertEquals("https://img.example/sq.jpg", s.poster);
    }

    private static Item chItem(String pkg, String channel, String title, String link, float progress, int weight) {
        Item it = new Item();
        it.src = Item.SRC_CHANNEL;
        it.id = "ch:" + title + channel;
        it.pkg = pkg;
        it.channel = channel;
        it.title = title;
        it.intentUri = link;
        it.progress = progress;
        it.weight = weight;
        return it;
    }

    @Test public void onlyEnabledChannelsShow() {
        List<Item> in = Arrays.asList(chItem("a", "Continue Watching", "One", "l1", 0.1f, 0),
                chItem("a", "Recommended", "Two", "l2", -1f, 0));
        List<Item> out = ChannelFilter.visible(in, null, null, null);
        assertEquals(1, out.size());
        assertEquals("One", out.get(0).title);
        Set<String> on = new HashSet<>(Collections.singleton(ChannelFilter.key("a", "Recommended")));
        assertEquals(2, ChannelFilter.visible(in, on, null, null).size());
    }

    @Test public void duplicatesWithWatchNextAndAcrossChannelsAreRemoved() {
        Item wn = new Item();
        wn.pkg = "a";
        wn.title = "Show";
        wn.intentUri = "link-wn";
        List<Item> in = Arrays.asList(
                chItem("a", "Continue", "SHOW!", "other-link", 0.5f, 0), // same title as a Watch Next row
                chItem("a", "Continue", "By link", "link-wn", 0.5f, 0),      // same link as a Watch Next row
                chItem("b", "Continue", "Show", "x", 0.5f, 0),               // another app: stays
                chItem("a", "Continue", "Twice", "l", 0.2f, 1),
                chItem("a", "Resume", "Twice", "l", 0.6f, 2));               // same item in two channels
        List<Item> out = ChannelFilter.visible(in, null, null, Collections.singletonList(wn));
        assertEquals(2, out.size());
        assertEquals("b", out.get(0).pkg);
        assertEquals("Twice", out.get(1).title);
        assertEquals("the further along copy wins", 0.6f, out.get(1).progress, 0f);
    }

    @Test public void storeAppliesWatchNextSwitchesAndChannelSwitchesSeparately() {
        Store.State s = new Store.State();
        Item wn = new Item();
        wn.id = "wn:1";
        wn.pkg = "a";
        wn.title = "Wn";
        wn.intentUri = "w";
        s.wn.add(wn);
        s.ch.add(chItem("a", "Continue", "Ch", "c", 0.3f, 0));
        List<Item> all = Store.visible(s, new HashSet<String>(), null, null);
        assertEquals(2, all.size());
        List<Item> noWn = Store.visible(s, new HashSet<>(Collections.singleton("a")), null, null);
        assertEquals("app switch hides Watch Next rows only", 1, noWn.size());
        assertEquals("Ch", noWn.get(0).title);
        Set<String> off = new HashSet<>(Collections.singleton(ChannelFilter.key("a", "Continue")));
        assertEquals(1, Store.visible(s, new HashSet<String>(), null, off).size());
        assertNull(Store.find(Store.visible(s, new HashSet<String>(), null, off), "ch:ChContinue"));
    }

    @Test public void channelFieldsSurviveJson() throws Exception {
        Item it = chItem("a", "Continue", "T", "l", 0.5f, 7);
        Item back = Item.fromJson(it.toJson());
        assertEquals("Continue", back.channel);
        assertEquals(7, back.weight);
    }
}
