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

    // ---- eviction

    @Test public void nothingLeavesWhileTheCacheFits() {
        assertTrue(PhotoCache.evict(photos(15, 700_000), PhotoCache.MAX_PHOTOS, PhotoCache.MAX_BYTES).isEmpty());
        assertTrue(PhotoCache.evict(new ArrayList<PhotoCache.Photo>(), 15, 100).isEmpty());
    }

    @Test public void theOldestFivePhotosLeaveWhenFiveNewOnesArrive() {
        List<PhotoCache.Photo> all = photos(20, 500_000);
        List<String> gone = PhotoCache.evict(all, PhotoCache.MAX_PHOTOS, PhotoCache.MAX_BYTES);
        assertEquals(5, gone.size());
        for (int i = 1; i <= 5; i++) assertEquals(photo(i, 0, 0).name, gone.get(i - 1));
    }

    @Test public void theOrderOfTheListDoesNotMatterOnlyTheAge() {
        List<PhotoCache.Photo> all = photos(17, 100_000);
        java.util.Collections.reverse(all);
        List<String> gone = PhotoCache.evict(all, 15, PhotoCache.MAX_BYTES);
        assertEquals(Arrays.asList(photo(1, 0, 0).name, photo(2, 0, 0).name), gone);
    }

    @Test public void theByteLimitAlsoEvictsOldestFirst() {
        List<PhotoCache.Photo> all = photos(15, 1_500_000); // 22.5 MB
        List<String> gone = PhotoCache.evict(all, 15, 14L * 1024 * 1024);
        long left = 15L * 1_500_000;
        for (int i = 0; i < gone.size(); i++) {
            assertEquals(photo(i + 1, 0, 0).name, gone.get(i));
            left -= 1_500_000;
        }
        assertTrue(left <= 14L * 1024 * 1024);
        assertTrue(left + 1_500_000 > 14L * 1024 * 1024);
    }

    @Test public void theNewestPhotoAlwaysStays() {
        List<PhotoCache.Photo> all = Arrays.asList(photo(1, 1000, 20_000_000), photo(2, 2000, 20_000_000));
        List<String> gone = PhotoCache.evict(all, 15, 1024);
        assertEquals(Arrays.asList(photo(1, 0, 0).name), gone);
    }

    // ---- how many a refresh fetches

    @Test public void aRefreshFetchesFiveToRotateOrEnoughToFill() {
        assertEquals(15, PhotoCache.wanted(0));
        assertEquals(12, PhotoCache.wanted(3));
        assertEquals(5, PhotoCache.wanted(10));
        assertEquals(5, PhotoCache.wanted(15));
        assertEquals(5, PhotoCache.wanted(40));
        assertEquals(15, PhotoCache.wanted(-2));
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
