package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PhotoCacheTest {
    private static PhotoCache.Photo photo(int n, long addedMs, long bytes) {
        return new PhotoCache.Photo(String.format("%016x.jpg", n), "File:" + n + ".jpg", 1920, 1080, "Ann — CC0", addedMs, bytes);
    }

    private static List<PhotoCache.Photo> photos(int count, long bytesEach) {
        List<PhotoCache.Photo> all = new ArrayList<>();
        for (int i = 1; i <= count; i++) all.add(photo(i, i * 1000L, bytesEach));
        return all;
    }

    // ---- names

    @Test public void fileNamesAreSixteenHexDigitsAndJpg() {
        String n = PhotoCache.fileName("File:Cirque de Gavarnie.jpg");
        assertTrue(n, n.matches(PhotoCache.NAME_PATTERN));
        assertEquals(n, PhotoCache.fileName("File:Cirque de Gavarnie.jpg"));
        assertFalse(n.equals(PhotoCache.fileName("File:Other.jpg")));
    }

    @Test public void onlyListedNamesOfTheRightShapeAreServed() {
        Set<String> listing = new HashSet<>(Arrays.asList("0123456789abcdef.jpg", "fedcba9876543210.jpg"));
        assertTrue(PhotoCache.validName("0123456789abcdef.jpg", listing));
        assertFalse("not listed", PhotoCache.validName("0000000000000000.jpg", listing));
        assertFalse(PhotoCache.validName(null, listing));
        assertFalse(PhotoCache.validName("0123456789abcdef.jpg", null));
    }

    @Test public void namesThatCouldEscapeTheFolderAreRefusedEvenIfListed() {
        for (String bad : new String[] {"../0123456789abcdef.jpg", "..", ".", "", "a/b.jpg", "0123456789abcdef.jpg/",
                "/0123456789abcdef.jpg", "..\\0123456789abcdef.jpg", "0123456789abcdef.jpg.tmp", "0123456789ABCDEF.jpg",
                "0123456789abcde.jpg", "0123456789abcdef0.jpg", "0123456789abcdef.png", "0123456789abcdef.jpg\n",
                "%2e%2e%2f0123456789abcdef.jpg", "0123456789abcdef.jpg%00"}) {
            Set<String> listing = new HashSet<>(Arrays.asList(bad));
            assertFalse(bad, PhotoCache.validName(bad, listing));
        }
    }

    // ---- eviction: the ring keeps its target, the photo shown longest ago goes

    private static PhotoCache.Photo shown(int n, long addedMs, long shownMs) {
        return photo(n, addedMs, 500_000).shownAt(shownMs);
    }

    @Test public void nothingLeavesWhileTheRingFits() {
        assertTrue(PhotoCache.evict(photos(PhotoCache.TARGET, 700_000), PhotoCache.TARGET, PhotoCache.MAX_BYTES).isEmpty());
        assertTrue(PhotoCache.evict(new ArrayList<PhotoCache.Photo>(), PhotoCache.TARGET, 100).isEmpty());
    }

    @Test public void theRingIsSmall() {
        assertEquals(4, PhotoCache.TARGET);
        assertEquals(6, PhotoCache.MAX_PHOTOS);
        assertTrue(PhotoCache.MAX_BYTES <= 6L * 1024 * 1024);
        assertTrue(PhotoCache.belowTarget(3));
        assertFalse(PhotoCache.belowTarget(4));
        assertTrue(PhotoCache.hasRoom(5));
        assertFalse("never more than six", PhotoCache.hasRoom(6));
    }

    @Test public void thePhotoShownLongestAgoGoesWhenTheRingIsOverTarget() {
        List<PhotoCache.Photo> all = new ArrayList<>(Arrays.asList(
                shown(1, 1000, 9000), shown(2, 2000, 5000), shown(3, 3000, 7000), shown(4, 4000, 8000),
                photo(5, 5000, 500_000)));   // the new photo, never shown
        List<String> gone = PhotoCache.evict(all, PhotoCache.TARGET, PhotoCache.MAX_BYTES);
        assertEquals(Arrays.asList(photo(2, 0, 0).name), gone);
    }

    @Test public void theNewPhotoAndTheOneOnScreenAreNeverDeleted() {
        // 1 is on screen (shown last) and also the oldest; 5 is the newest and never shown
        List<PhotoCache.Photo> all = new ArrayList<>(Arrays.asList(
                shown(1, 1000, 9000), shown(2, 2000, 3000), shown(3, 3000, 4000), shown(4, 4000, 5000),
                photo(5, 5000, 500_000)));
        List<String> gone = PhotoCache.evict(all, PhotoCache.TARGET, PhotoCache.MAX_BYTES);
        assertEquals(Arrays.asList(photo(2, 0, 0).name), gone);
        assertFalse(gone.contains(photo(1, 0, 0).name));
        assertFalse(gone.contains(photo(5, 0, 0).name));
    }

    @Test public void photosNeverShownGoAfterShownOnesOldestFirst() {
        List<PhotoCache.Photo> all = photos(6, 100_000); // none shown: oldest added first, the newest stays
        List<String> gone = PhotoCache.evict(all, PhotoCache.TARGET, PhotoCache.MAX_BYTES);
        assertEquals(Arrays.asList(photo(1, 0, 0).name, photo(2, 0, 0).name), gone);
    }

    @Test public void theOrderOfTheListDoesNotMatter() {
        List<PhotoCache.Photo> all = photos(5, 100_000);
        java.util.Collections.reverse(all);
        assertEquals(Arrays.asList(photo(1, 0, 0).name), PhotoCache.evict(all, PhotoCache.TARGET, PhotoCache.MAX_BYTES));
    }

    @Test public void theByteLimitAlsoEvicts() {
        List<PhotoCache.Photo> all = photos(4, 2_000_000); // 8 MB in four photos
        List<String> gone = PhotoCache.evict(all, PhotoCache.TARGET, PhotoCache.MAX_BYTES);
        assertEquals(Arrays.asList(photo(1, 0, 0).name), gone);
    }

    @Test public void twoPhotosAreNeverTrimmedAwayEvenOverTheByteLimit() {
        List<PhotoCache.Photo> all = Arrays.asList(shown(1, 1000, 3000), photo(2, 2000, 20_000_000));
        assertTrue(PhotoCache.evict(all, PhotoCache.TARGET, 1024).isEmpty());
    }

    @Test public void markShownSetsOnlyThatPhoto() {
        List<PhotoCache.Photo> out = PhotoCache.markShown(photos(3, 1), photo(2, 0, 0).name, 777);
        assertEquals(0, out.get(0).shownMs);
        assertEquals(777, out.get(1).shownMs);
        assertEquals(0, out.get(2).shownMs);
        assertEquals(3, PhotoCache.markShown(photos(3, 1), "0000000000000000.jpg", 5).size());
    }

    // ---- the record

    @Test public void theRecordSurvivesEncodingAndDropsBadNames() {
        List<PhotoCache.Photo> in = photos(3, 800_000);
        List<PhotoCache.Photo> out = PhotoCache.decode(PhotoCache.encode(in));
        assertEquals(3, out.size());
        assertEquals(in.get(1).name, out.get(1).name);
        assertEquals("Ann — CC0", out.get(1).credit);
        assertEquals(1920, out.get(1).width);
        assertEquals(800_000, out.get(1).bytes);
        PhotoCache.Photo s = PhotoCache.decode(PhotoCache.encode(Arrays.asList(shown(1, 1000, 4242)))).get(0);
        assertEquals(4242, s.shownMs);
        String tampered = "[{\"n\":\"../etc/passwd\",\"w\":1,\"h\":1},{\"n\":\"0123456789abcdef.jpg\",\"w\":1920,\"h\":1080}]";
        List<PhotoCache.Photo> kept = PhotoCache.decode(tampered);
        assertEquals(1, kept.size());
        assertEquals("0123456789abcdef.jpg", kept.get(0).name);
        assertTrue(PhotoCache.decode("not json").isEmpty());
        assertTrue(PhotoCache.decode("").isEmpty());
        assertTrue(PhotoCache.decode(null).isEmpty());
    }

    @Test public void availablePhotosAreTheListedOnesNewestFirst() {
        List<PhotoCache.Photo> record = photos(4, 1);
        Set<String> listing = new HashSet<>(Arrays.asList(photo(1, 0, 0).name, photo(3, 0, 0).name, photo(4, 0, 0).name));
        List<PhotoCache.Photo> out = PhotoCache.available(record, listing);
        assertEquals(3, out.size());
        assertEquals(photo(4, 0, 0).name, out.get(0).name);
        assertEquals(photo(1, 0, 0).name, out.get(2).name);
    }
}
