package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class PhotoFilterTest {
    @Test public void aGoodPictureIsAJpegWideEnoughAndWideShapedUnderAFreeLicence() {
        assertTrue(PhotoFilter.accept("image/jpeg", 4000, 2250, "CC BY-SA 4.0"));
        assertTrue(PhotoFilter.accept("image/jpeg", 2500, 1600, "CC0"));
        assertTrue(PhotoFilter.accept("image/jpeg", 5000, 2500, "Public domain"));
    }

    @Test public void sizeShapeAndTypeAreChecked() {
        assertFalse(PhotoFilter.accept("image/jpeg", 2499, 1400, "CC BY 4.0"));
        assertFalse(PhotoFilter.accept("image/png", 4000, 2250, "CC BY 4.0"));
        assertFalse(PhotoFilter.accept("image/tiff", 4000, 2250, "CC BY 4.0"));
        assertFalse(PhotoFilter.accept("image/jpeg", 4000, 3000, "CC BY 4.0"));   // 1.33: too square
        assertFalse(PhotoFilter.accept("image/jpeg", 9000, 3000, "CC BY 4.0"));   // 3.0: a panorama strip
        assertFalse(PhotoFilter.accept("image/jpeg", 4000, 0, "CC BY 4.0"));
        assertTrue(PhotoFilter.aspectOk(1500, 1000));
        assertTrue(PhotoFilter.aspectOk(2200, 1000));
        assertFalse(PhotoFilter.aspectOk(2201, 1000));
        assertFalse(PhotoFilter.aspectOk(1499, 1000));
    }

    @Test public void onlyFreeLicencesPass() {
        for (String ok : new String[] {"CC BY 2.0", "CC BY-SA 3.0", "CC BY-SA 4.0", "CC0", "CC0 1.0", "Public domain", "PD",
                "PD-old", "cc by 4.0"}) {
            assertTrue(ok, PhotoFilter.freeLicence(ok));
        }
        for (String no : new String[] {"CC BY-NC 4.0", "CC BY-ND 2.0", "CC BY-NC-SA 3.0", "CC BY-SA ND", "Fair use", "Non-free",
                "FAL", "GFDL", "All rights reserved", "", "   "}) {
            assertFalse(no, PhotoFilter.freeLicence(no));
        }
        assertFalse(PhotoFilter.freeLicence(null));
    }

    @Test public void mapsDiagramsArtAndPortraitsAreNotScenery() {
        assertTrue(PhotoFilter.scenic("File:Cirque de Gavarnie, France.jpg", "Pyrenees|Featured pictures of landscapes"));
        assertTrue(PhotoFilter.scenic("File:Sunset over the Baltic Sea.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Map of the Alps.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Volcano diagram.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Portrait of a hiker.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Oil painting of a lake.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Scotrail train at Corrour.jpg", null));
        assertFalse(PhotoFilter.scenic("File:Lake.jpg", "Lakes|Paintings of lakes"));
        assertFalse(PhotoFilter.scenic("File:Lake.jpg", "Lakes|Maps of Switzerland"));
        assertFalse(PhotoFilter.scenic("File:Lake.jpg", "Lakes|People of Norway"));
    }

    // ---- the credit line

    @Test public void creditIsPlainTextAuthorAndLicence() {
        String artist = "<a href=\"//commons.wikimedia.org/wiki/User:Richard_Bartz\" title=\"User:Richard Bartz\">Richard Bartz</a>";
        assertEquals("Richard Bartz — CC BY-SA 3.0", PhotoFilter.credit(artist, "CC BY-SA 3.0"));
    }

    @Test public void creditDropsTagsDecodesEntitiesAndControlCharacters() {
        assertEquals("A & B c \"d\" é", PhotoFilter.plain("<b>A &amp; B</b> &lt;c&gt; &quot;d&quot; &#233;"));
        assertEquals("one two three", PhotoFilter.plain("one<br/>two\u0000‏three"));
        assertEquals("a b", PhotoFilter.plain("a\n\t  b"));
        assertEquals("x y", PhotoFilter.plain("x &unknown; y"));
        assertEquals("", PhotoFilter.plain(null));
        assertEquals("ok", PhotoFilter.plain("<script>ok</script>"));
        assertEquals("a script b", PhotoFilter.plain("a &lt;script&gt; b"));
    }

    @Test public void creditHasAFallbackAuthorAndAHardLimit() {
        assertEquals("Ggia — CC BY-SA 3.0", PhotoFilter.credit("User:Ggia", "CC BY-SA 3.0"));
        assertEquals("Unknown author — CC0", PhotoFilter.credit(null, "CC0"));
        assertEquals("Unknown author — CC0", PhotoFilter.credit("  <span></span> ", "CC0"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 40; i++) longName.append("Photographer ");
        String c = PhotoFilter.credit(longName.toString(), "CC BY-SA 4.0");
        assertTrue(c.length() <= PhotoFilter.CREDIT_MAX);
        assertTrue(c.endsWith("… — CC BY-SA 4.0"));
        String huge = PhotoFilter.credit("A", "CC BY-SA 4.0 and a very long extra text that goes on and on and on forever");
        assertTrue(huge.length() <= PhotoFilter.CREDIT_MAX);
    }

    @Test public void creditNeverCarriesMarkupFromTheServer() {
        String c = PhotoFilter.credit("<img src=x onerror=alert(1)>Eve<script>bad()</script>", "<a href=\"x\">CC BY 4.0</a>");
        assertFalse(c.contains("<"));
        assertFalse(c.contains(">"));
        assertTrue(c.endsWith("CC BY 4.0"));
    }

    // ---- addresses

    @Test public void thumbnailAddressesMoveToTheUploadHostWithoutTracking() {
        String api = "https://thumb.wikimedia.org/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg?utm_source=commons.wikimedia.org";
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg", PhotoFilter.thumbUrl(api));
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg",
                PhotoFilter.thumbUrl("https://upload.wikimedia.org/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg"));
    }

    @Test public void otherAddressesAreRefused() {
        assertNull(PhotoFilter.thumbUrl("http://upload.wikimedia.org/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg"));
        assertNull(PhotoFilter.thumbUrl("https://evil.example/wikipedia/commons/thumb/2/20/X.jpg/1920px-X.jpg"));
        assertNull(PhotoFilter.thumbUrl("https://upload.wikimedia.org.evil.example/wikipedia/commons/thumb/a.jpg"));
        assertNull(PhotoFilter.thumbUrl("https://upload.wikimedia.org/other/path.jpg"));
        assertNull(PhotoFilter.thumbUrl(null));
    }

    @Test public void queriesAreEncodedAndStayOnTheApiHost() {
        String list = PhotoFilter.listUrl("Category:Featured pictures of landscapes");
        assertTrue(list.startsWith("https://commons.wikimedia.org/w/api.php?"));
        assertTrue(list.contains("cmtitle=Category%3AFeatured+pictures+of+landscapes"));
        String info = PhotoFilter.infoUrl(Arrays.asList(11L, 22L));
        assertTrue(info.startsWith("https://commons.wikimedia.org/w/api.php?"));
        assertTrue(info.contains("pageids=11%7C22"));
        assertTrue(info.contains("iiurlwidth=1920"));
    }

    // ---- the API's answers

    @Test public void pageIdsAndContinuationAreRead() throws Exception {
        String json = "{\"continue\":{\"cmcontinue\":\"file|abc\",\"continue\":\"-||\"},\"query\":{\"categorymembers\":["
                + "{\"pageid\":10,\"ns\":6,\"title\":\"File:A.jpg\"},{\"pageid\":20,\"ns\":6,\"title\":\"File:B.jpg\"},"
                + "{\"ns\":14,\"title\":\"Category:Sub\"}]}}";
        assertEquals(Arrays.asList(10L, 20L), PhotoFilter.pageIds(json));
        assertEquals(Arrays.asList("File:A.jpg", "File:B.jpg"), PhotoFilter.titles(json));
        assertEquals("file|abc", PhotoFilter.continuation(json));
        assertNull(PhotoFilter.continuation("{\"query\":{}}"));
        assertTrue(PhotoFilter.pageIds("{}").isEmpty());
    }

    private static String page(String title, String mime, int w, int h, String licence, String artist, String cats, String thumb) {
        return "\"" + title.hashCode() + "\":{\"title\":\"" + title + "\",\"imageinfo\":[{\"width\":" + w + ",\"height\":" + h
                + ",\"mime\":\"" + mime + "\",\"thumburl\":\"" + thumb + "\",\"thumbwidth\":1920,\"thumbheight\":1080,"
                + "\"extmetadata\":{\"LicenseShortName\":{\"value\":\"" + licence + "\"},\"Artist\":{\"value\":\"" + artist
                + "\"},\"Categories\":{\"value\":\"" + cats + "\"}}}]}";
    }

    @Test public void candidatesKeepOnlyWhatPassesEveryRule() throws Exception {
        String thumb = "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1920px-X.jpg?utm_source=x";
        String json = "{\"query\":{\"pages\":{"
                + page("File:Good.jpg", "image/jpeg", 6000, 3375, "CC BY-SA 4.0", "<b>Ann</b>", "Mountains", thumb) + ","
                + page("File:Small.jpg", "image/jpeg", 1600, 900, "CC BY 4.0", "Bob", "Mountains", thumb) + ","
                + page("File:Png.png", "image/png", 6000, 3375, "CC BY 4.0", "Bob", "Mountains", thumb) + ","
                + page("File:Nc.jpg", "image/jpeg", 6000, 3375, "CC BY-NC 4.0", "Bob", "Mountains", thumb) + ","
                + page("File:Square.jpg", "image/jpeg", 4000, 4000, "CC BY 4.0", "Bob", "Mountains", thumb) + ","
                + page("File:Map of x.jpg", "image/jpeg", 6000, 3375, "CC BY 4.0", "Bob", "Mountains", thumb) + ","
                + page("File:Odd host.jpg", "image/jpeg", 6000, 3375, "CC0", "Bob", "Mountains", "https://evil.example/x.jpg") + ","
                + page("File:Person.jpg", "image/jpeg", 6000, 3375, "CC0", "Bob", "Portrait photographs|Alps", thumb)
                + "}}}";
        List<PhotoFilter.Candidate> out = PhotoFilter.candidates(json);
        assertEquals(1, out.size());
        PhotoFilter.Candidate c = out.get(0);
        assertEquals("File:Good.jpg", c.title);
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/X.jpg/1920px-X.jpg", c.url);
        assertEquals("Ann — CC BY-SA 4.0", c.credit);
        assertEquals(1920, c.width);
    }

    @Test public void anAnswerWithoutPagesGivesNothing() throws Exception {
        assertTrue(PhotoFilter.candidates("{\"batchcomplete\":\"\"}").isEmpty());
        assertTrue(PhotoFilter.candidates("{\"query\":{\"pages\":{\"-1\":{\"title\":\"File:X\",\"missing\":\"\"}}}}").isEmpty());
    }
}
