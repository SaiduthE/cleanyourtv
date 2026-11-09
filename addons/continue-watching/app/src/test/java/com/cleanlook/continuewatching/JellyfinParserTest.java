package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

public class JellyfinParserTest {
    private static final String BASE = "http://nas:8096";
    private static final long NOW = 1_800_000_000_000L;

    private static final String RESUME = "{\"Items\":["
            + "{\"Name\":\"The Reckoning\",\"Id\":\"aaaa1111bbbb2222cccc3333dddd4444\",\"Type\":\"Episode\","
            + "\"SeriesName\":\"Severance\",\"SeriesId\":\"s1\",\"ParentIndexNumber\":2,\"IndexNumber\":4,"
            + "\"RunTimeTicks\":30000000000,\"Overview\":\"Mark digs in.\",\"ProductionYear\":2025,"
            + "\"UserData\":{\"PlaybackPositionTicks\":6000000000,\"PlayedPercentage\":20.0,\"Played\":false,"
            + "\"LastPlayedDate\":\"2026-10-30T20:11:12.0000000Z\"},"
            + "\"ImageTags\":{\"Primary\":\"ptag\"},\"BackdropImageTags\":[],"
            + "\"ParentBackdropItemId\":\"s1\",\"ParentBackdropImageTags\":[\"btag\"]},"
            + "{\"Name\":\"Heat\",\"Id\":\"eeee5555ffff6666aaaa7777bbbb8888\",\"Type\":\"Movie\","
            + "\"RunTimeTicks\":100000000000,\"ProductionYear\":1995,"
            + "\"UserData\":{\"PlaybackPositionTicks\":50000000000,\"PlayedPercentage\":50.0,"
            + "\"LastPlayedDate\":\"2026-10-31T01:00:00.0000000Z\"},"
            + "\"ImageTags\":{\"Primary\":\"poster\"},\"BackdropImageTags\":[\"bd1\"]},"
            + "{\"Name\":\"Almost done\",\"Id\":\"11112222333344445555666677778888\",\"Type\":\"Movie\","
            + "\"RunTimeTicks\":100000000000,"
            + "\"UserData\":{\"PlaybackPositionTicks\":97000000000,\"PlayedPercentage\":97.0}},"
            + "{\"Name\":\"Some Album\",\"Id\":\"zzzz\",\"Type\":\"Audio\"}"
            + "],\"TotalRecordCount\":4}";

    @Test public void parsesAnEpisodeResumeRow() throws Exception {
        List<Item> items = JellyfinParser.parseItems(RESUME, Item.RESUME, BASE, NOW);
        assertEquals(2, items.size());
        Item ep = items.get(0);
        assertEquals("Severance", ep.title);
        assertEquals("The Reckoning", ep.episodeTitle);
        assertEquals("S2 · E4", ep.subtitle());
        assertEquals(0.2f, ep.progress, 0.001f);
        assertEquals(600_000L, ep.positionMs);
        assertEquals(3_000_000L, ep.durationMs);
        assertEquals(2_400_000L, ep.remainingMs());
        assertEquals("jf:aaaa1111bbbb2222cccc3333dddd4444", ep.id);
        assertEquals(Item.JELLYFIN_PKG, ep.pkg);
        assertEquals("Mark digs in.", ep.overview);
        assertEquals(JellyfinParser.parseDate("2026-10-30T20:11:12.0000000Z"), ep.updatedMs);
        assertTrue(ep.updatedMs > 0);
    }

    @Test public void episodesUseTheirStillAndTheSeriesBackdrop() throws Exception {
        Item ep = JellyfinParser.parseItems(RESUME, Item.RESUME, BASE, NOW).get(0);
        assertEquals(BASE + "/Items/aaaa1111bbbb2222cccc3333dddd4444/Images/Primary?maxWidth=700&quality=85&tag=ptag", ep.image);
        assertEquals(BASE + "/Items/s1/Images/Backdrop/0?maxWidth=960&quality=85&tag=btag", ep.backdrop);
        assertFalse("no token in an image address", ep.image.contains("Token") || ep.backdrop.contains("api_key"));
    }

    @Test public void moviesUseTheirBackdropForTheCardToo() throws Exception {
        Item m = JellyfinParser.parseItems(RESUME, Item.RESUME, BASE, NOW).get(1);
        assertEquals("Heat", m.title);
        assertEquals("1995", m.subtitle());
        assertEquals(m.backdrop, m.image);
        assertTrue(m.backdrop.contains("/Backdrop/0?maxWidth=960"));
    }

    @Test public void postersAndLogosComeFromTheSeriesWhenAnEpisodeHasNone() throws Exception {
        String json = "{\"Items\":["
                + "{\"Name\":\"Pilot\",\"Id\":\"e1\",\"Type\":\"Episode\",\"SeriesName\":\"Show\",\"SeriesId\":\"s9\","
                + "\"SeriesPrimaryImageTag\":\"sp\",\"ParentLogoItemId\":\"s9\",\"ParentLogoImageTag\":\"sl\","
                + "\"ImageTags\":{\"Primary\":\"still\"}},"
                + "{\"Name\":\"Film\",\"Id\":\"m1\",\"Type\":\"Movie\",\"ImageTags\":{\"Primary\":\"fp\",\"Logo\":\"fl\"}}"
                + "]}";
        List<Item> items = JellyfinParser.parseItems(json, Item.NEXT, BASE, NOW);
        assertEquals(BASE + "/Items/s9/Images/Primary?maxWidth=400&quality=85&tag=sp", items.get(0).poster);
        assertEquals(BASE + "/Items/s9/Images/Logo?maxWidth=480&quality=85&tag=sl", items.get(0).logo);
        assertEquals(BASE + "/Items/m1/Images/Primary?maxWidth=400&quality=85&tag=fp", items.get(1).poster);
        assertEquals(BASE + "/Items/m1/Images/Logo?maxWidth=480&quality=85&tag=fl", items.get(1).logo);
        assertFalse(items.get(0).poster.contains("Token"));
    }

    @Test public void dropsFinishedItemsAndNonVideo() throws Exception {
        for (Item it : JellyfinParser.parseItems(RESUME, Item.RESUME, BASE, NOW)) {
            assertFalse(it.title.equals("Almost done"));
            assertFalse(it.title.equals("Some Album"));
        }
    }

    @Test public void nextUpKeepsTheServersOrder() throws Exception {
        String json = "{\"Items\":["
                + "{\"Name\":\"Pilot\",\"Id\":\"a\",\"Type\":\"Episode\",\"SeriesName\":\"One\",\"ParentIndexNumber\":1,\"IndexNumber\":2},"
                + "{\"Name\":\"Two\",\"Id\":\"b\",\"Type\":\"Episode\",\"SeriesName\":\"Two\",\"ParentIndexNumber\":3,\"IndexNumber\":1}"
                + "]}";
        List<Item> items = JellyfinParser.parseItems(json, Item.NEXT, BASE, NOW);
        assertEquals(2, items.size());
        assertEquals(Item.NEXT, items.get(0).kind);
        assertTrue(items.get(0).updatedMs > items.get(1).updatedMs);
        assertEquals(-1f, items.get(0).progress, 0f);
        assertEquals("", items.get(0).image);
    }

    @Test public void anEmptyOrOddResponseGivesNoItems() throws Exception {
        assertEquals(0, JellyfinParser.parseItems("{\"Items\":[]}", Item.RESUME, BASE, NOW).size());
        assertEquals(0, JellyfinParser.parseItems("{}", Item.RESUME, BASE, NOW).size());
        assertEquals(0, JellyfinParser.parseItems("{\"Items\":[null,{\"Type\":\"Movie\"}]}", Item.RESUME, BASE, NOW).size());
    }

    @Test public void parsesSignInAnswers() throws Exception {
        JellyfinParser.Auth a = JellyfinParser.parseAuth(
                "{\"User\":{\"Name\":\"sai\",\"Id\":\"u1\"},\"AccessToken\":\"tok\",\"ServerId\":\"x\"}");
        assertEquals("tok", a.token);
        assertEquals("u1", a.userId);
        assertEquals("sai", a.userName);
        List<String[]> users = JellyfinParser.parseUsers("[{\"Name\":\"a\",\"Id\":\"1\"},{\"Name\":\"b\",\"Id\":\"2\"},{}]");
        assertEquals(2, users.size());
        assertEquals("b", users.get(1)[1]);
        assertEquals("Home NAS", JellyfinParser.parseServerName("{\"ServerName\":\"Home NAS\"}"));
    }

    @Test public void normalizesTheServerAddress() {
        assertEquals("http://nas:8096", JellyfinParser.normalizeBase("nas:8096"));
        assertEquals("http://192.168.1.20:8096", JellyfinParser.normalizeBase(" http://192.168.1.20:8096/ "));
        assertEquals("https://jf.example.com", JellyfinParser.normalizeBase("https://jf.example.com/web/index.html"));
        assertEquals("http://nas/jellyfin", JellyfinParser.normalizeBase("http://nas/jellyfin/"));
        assertNull(JellyfinParser.normalizeBase(""));
        assertNull(JellyfinParser.normalizeBase("ftp://nas"));
        assertNull(JellyfinParser.normalizeBase("http://"));
        assertNull(JellyfinParser.normalizeBase("not an address"));
    }

    @Test public void dashesAJellyfinId() {
        assertEquals("aaaa1111-bbbb-2222-cccc-3333dddd4444", JellyfinParser.dashedUuid("aaaa1111bbbb2222cccc3333dddd4444"));
        assertEquals("short", JellyfinParser.dashedUuid("short"));
    }

    @Test public void itemSurvivesAJsonRoundTrip() throws Exception {
        Item ep = JellyfinParser.parseItems(RESUME, Item.RESUME, BASE, NOW).get(0);
        Item back = Item.fromJson(new JSONObject(ep.toJson().toString()));
        assertEquals(ep.id, back.id);
        assertEquals(ep.title, back.title);
        assertEquals(ep.progress, back.progress, 0.0001f);
        assertEquals(ep.season, back.season);
        assertEquals(ep.image, back.image);
        assertEquals(ep.poster, back.poster);
        assertEquals(ep.logo, back.logo);
        assertEquals(ep.updatedMs, back.updatedMs);
    }
}
