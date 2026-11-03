package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class PhotoRunTest {
    private static final PhotoPolicy.Category[] ONE = {
        new PhotoPolicy.Category("landscapes", "Landscapes", "Category:Featured pictures of landscapes"),
    };

    /** A scripted HTTP layer: the list answer, the info answer, and one reaction per picture URL. */
    private static class FakeNet implements PhotoRun.Net {
        final List<String> log = new ArrayList<>();
        final int pictures;
        /** Per picture index: the failures to throw before it works (consumed one per attempt). */
        final java.util.Map<Integer, List<IOException>> failures = new java.util.HashMap<>();
        IOException listFailure;
        IOException infoFailure;

        FakeNet(int pictures) {
            this.pictures = pictures;
        }

        void fail(int picture, IOException... errors) {
            List<IOException> l = new ArrayList<>();
            for (IOException e : errors) l.add(e);
            failures.put(picture, l);
        }

        /** The failures for whichever picture is asked for first (the order inside one answer is not defined). */
        final List<IOException> firstFailures = new ArrayList<>();
        String firstUrl;

        void failFirst(IOException... errors) {
            for (IOException e : errors) firstFailures.add(e);
        }

        int requestsForFirst() {
            int n = 0;
            for (String u : log) if (u.equals(firstUrl)) n++;
            return n;
        }

        @Override public byte[] get(String url, boolean image) throws IOException {
            log.add(url);
            if (image) {
                if (firstUrl == null) firstUrl = url;
                if (url.equals(firstUrl) && !firstFailures.isEmpty()) throw firstFailures.remove(0);
                int i = Integer.parseInt(url.substring(url.lastIndexOf('/') + 1));
                List<IOException> l = failures.get(i);
                if (l != null && !l.isEmpty()) throw l.remove(0);
                return new byte[] {1, 2, 3};
            }
            if (url.contains("list=categorymembers")) {
                if (listFailure != null) throw listFailure;
                StringBuilder sb = new StringBuilder("{\"query\":{\"categorymembers\":[");
                for (int i = 0; i < pictures; i++) sb.append(i == 0 ? "" : ",").append("{\"pageid\":").append(i + 1).append("}");
                return sb.append("]}}").toString().getBytes();
            }
            if (infoFailure != null) throw infoFailure;
            // the info query: one candidate per requested page id
            String ids = java.net.URLDecoder.decode(url.substring(url.indexOf("pageids=") + 8), "UTF-8");
            StringBuilder sb = new StringBuilder("{\"query\":{\"pages\":{");
            boolean first = true;
            for (String id : ids.split("\\|")) {
                int n = Integer.parseInt(id) - 1;
                sb.append(first ? "" : ",").append("\"").append(id).append("\":{\"title\":\"File:Pic").append(n)
                        .append(".jpg\",\"imageinfo\":[{\"mime\":\"image/jpeg\",\"width\":6000,\"height\":3000,")
                        .append("\"thumburl\":\"https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Pic").append(n)
                        .append(".jpg/1920px-Pic").append(n).append(".jpg/").append(n).append("\",")
                        .append("\"thumbwidth\":1920,\"thumbheight\":960,\"extmetadata\":{")
                        .append("\"LicenseShortName\":{\"value\":\"CC BY-SA 4.0\"},\"Artist\":{\"value\":\"Ann\"}}}]}");
                first = false;
            }
            return sb.append("}}}").toString().getBytes();
        }
    }

    private static final class FakeSaver implements PhotoRun.Saver {
        final List<String> saved = new ArrayList<>();
        int rejectNext;
        int failNext;

        @Override public PhotoCache.Photo store(PhotoFilter.Candidate cand, String name, byte[] body) throws IOException {
            if (failNext > 0) {
                failNext--;
                throw new IOException("disk full");
            }
            if (rejectNext > 0) {
                rejectNext--;
                return null;
            }
            saved.add(cand.title);
            return new PhotoCache.Photo(name, cand.title, 1920, 960, cand.credit, 1, body.length);
        }
    }

    private static class FakeEnv implements PhotoRun.Env {
        long now = 1_000_000;
        final List<Long> sleeps = new ArrayList<>();
        boolean interruptAt;
        boolean waitingNow;
        int waitingTrue;

        @Override public boolean sleep(long ms) {
            sleeps.add(ms);
            now += ms;
            return !interruptAt;
        }

        @Override public long now() {
            return now;
        }

        @Override public void waiting(boolean busy) {
            waitingNow = busy;
            if (busy) waitingTrue++;
        }
    }

    private static final PhotoPolicy.Category[] OTHER = {
        new PhotoPolicy.Category("lakes", "Lakes", "Category:Featured pictures of lakes"),
    };

    private static PhotoRun.Result one(PhotoRun run) {
        return run.fetchOne(ONE, new HashSet<String>(), new Random(1));
    }

    private static PhotoRun make(FakeNet net, FakeEnv env, FakeSaver saver) {
        return new PhotoRun(net, env, saver);
    }

    private static Http.HttpException http(int code, long retryAfterMs) {
        return new Http.HttpException(code, retryAfterMs);
    }

    private static int pictureRequests(FakeNet net) {
        int n = 0;
        for (String u : net.log) if (u.contains("/thumb/")) n++;
        return n;
    }

    // ---- one photo, one picture request

    @Test public void aFirstCallListsTheCategoryAsksForTheInfoAndDownloadsOnePicture() {
        FakeNet net = new FakeNet(200);
        FakeSaver saver = new FakeSaver();
        PhotoRun.Result r = one(make(net, new FakeEnv(), saver));
        assertEquals(1, r.added);
        assertNull(r.failure);
        assertEquals("", PhotoRun.note(r));
        assertEquals(3, net.log.size());
        assertEquals(1, pictureRequests(net));
        assertEquals(1, saver.saved.size());
    }

    @Test public void theNextCallCostsASinglePictureRequest() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv();
        PhotoRun run = make(net, env, new FakeSaver());
        Set<String> have = new HashSet<>();
        assertEquals(1, run.fetchOne(ONE, have, new Random(1)).added);
        net.log.clear();
        env.sleeps.clear();
        assertEquals(1, run.fetchOne(ONE, have, new Random(1)).added);
        assertEquals(1, net.log.size());
        assertTrue(net.log.get(0).contains("/thumb/"));
        assertTrue("no pause with a single request", env.sleeps.isEmpty());
    }

    @Test public void requestsWithinOneCallArePausedApart() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv();
        one(make(net, env, new FakeSaver()));
        assertEquals(net.log.size() - 1, env.sleeps.size());
        for (long s : env.sleeps) assertEquals(PhotoRun.PAUSE_MS, s);
    }

    @Test public void aPhotoAlreadyInTheRingIsNotFetchedAgain() {
        FakeNet net = new FakeNet(200);
        Set<String> have = new HashSet<>();
        for (int i = 0; i < 200; i++) have.add(PhotoCache.fileName("File:Pic" + i + ".jpg"));
        have.remove(PhotoCache.fileName("File:Pic7.jpg"));
        FakeSaver saver = new FakeSaver();
        PhotoRun.Result r = new PhotoRun(net, new FakeEnv(), saver).fetchOne(ONE, have, new Random(1));
        assertEquals(1, r.added);
        assertEquals("File:Pic7.jpg", saver.saved.get(0));
    }

    @Test public void candidatesOfACategorySwitchedOffAreDropped() {
        FakeNet net = new FakeNet(200);
        PhotoRun run = make(net, new FakeEnv(), new FakeSaver());
        Set<String> have = new HashSet<>();
        run.fetchOne(ONE, have, new Random(1));
        net.log.clear();
        assertEquals(1, run.fetchOne(OTHER, have, new Random(1)).added);
        boolean listedLakes = false;
        for (String u : net.log) listedLakes |= u.contains("list=categorymembers") && u.contains("lakes");
        assertTrue("the new category was listed, the old queue not used", listedLakes);
    }

    @Test public void aCategoryWithNothingLeftSaysSo() {
        FakeNet net = new FakeNet(0);
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals("No new photo found", PhotoRun.note(r));
    }

    // ---- one bad picture does not end the call

    @Test public void aTimedOutPictureIsSkippedAndTheNextOneTried() {
        FakeNet net = new FakeNet(200);
        net.failFirst(new SocketTimeoutException("read"));
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(1, r.added);
        assertEquals(PhotoRun.Kind.TIMEOUT, r.failure.kind);
        assertEquals(1, net.requestsForFirst());
        assertEquals("", PhotoRun.note(r));
    }

    @Test public void aPictureTheSaverRefusesOrCannotWriteIsSkipped() {
        FakeNet net = new FakeNet(200);
        FakeSaver saver = new FakeSaver();
        saver.rejectNext = 1;
        saver.failNext = 1;
        PhotoRun.Result r = one(make(net, new FakeEnv(), saver));
        assertEquals(1, r.added);
        assertEquals(1, r.rejected);
    }

    @Test public void missingOrOversizedPicturesAreSkippedWithoutCountingAgainstTheCall() {
        FakeNet net = new FakeNet(200);
        for (int i = 0; i < 200; i++) if (i % 3 != 0) net.fail(i, i % 2 == 0 ? http(404, 0) : new Http.TooLarge());
        PhotoRun.Result r = new PhotoRun(net, new FakeEnv(), new FakeSaver()).fetchOne(ONE, new HashSet<String>(), new Random(1));
        assertEquals(1, r.added);
        assertFalse(r.stopped);
        assertNull(r.failure);
    }

    @Test public void aCallGivesUpAfterAFewPicturesThatAllFail() {
        FakeNet net = new FakeNet(200);
        for (int i = 0; i < 200; i++) net.fail(i, new SocketTimeoutException("x"));
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals(PhotoRun.MAX_PICTURES, pictureRequests(net));
        assertEquals("Server slow, retrying", PhotoRun.note(r));
    }

    @Test public void aFailedInfoCallEndsTheCallWithItsReason() {
        FakeNet net = new FakeNet(200);
        net.infoFailure = new SocketTimeoutException("x");
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals("Server slow, retrying", PhotoRun.note(r));
    }

    // ---- a busy server

    @Test public void aBusyServerIsWaitedOutByItsRetryAfterAtLeastFourSecondsAndTriedAgain() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 10_000));
        FakeEnv env = new FakeEnv();
        PhotoRun.Result r = one(make(net, env, new FakeSaver()));
        assertEquals(1, r.added);
        assertTrue("waited the 10 s it asked for", env.sleeps.contains(10_500L));
        assertEquals("the same picture was asked for again", 2, net.requestsForFirst());
        assertEquals(2, pictureRequests(net));
        assertTrue("told it is waiting for the server", env.waitingTrue > 0);
        assertFalse("and that it is over", env.waitingNow);
    }

    @Test public void aShortRetryAfterIsStretchedToFourSeconds() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 1000));
        FakeEnv env = new FakeEnv();
        one(make(net, env, new FakeSaver()));
        assertTrue(env.sleeps.contains(PhotoRun.RETRY_MIN_MS));
    }

    @Test public void thereAreAtMostTwoRetriesAndThenTheCallStopsInsteadOfTryingOtherPictures() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 1000), http(429, 1000), http(429, 1000), http(429, 1000));
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals("first try plus two retries", 3, net.requestsForFirst());
        assertEquals("no other picture was tried", 3, pictureRequests(net));
        assertEquals(PhotoRun.Kind.BUSY, r.failure.kind);
        assertEquals("Server busy, retrying", PhotoRun.note(r));
    }

    @Test public void aServerUnavailableAnswerIsRetriedToo() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(503, 0));
        FakeEnv env = new FakeEnv();
        PhotoRun.Result r = one(make(net, env, new FakeSaver()));
        assertEquals(1, r.added);
        assertTrue("no Retry-After: the default backoff", env.sleeps.contains(PhotoRun.RETRY_DEFAULT_MS));
    }

    @Test public void aRefusalIsNotRetried() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(404, 0));
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(1, net.requestsForFirst());
        assertEquals(1, r.added);
    }

    @Test public void backoffFollowsRetryAfterWithinLimits() {
        assertEquals(10_500, PhotoRun.backoffMs(10_000, 0));
        assertEquals(PhotoRun.RETRY_MAX_MS, PhotoRun.backoffMs(600_000, 0));
        assertEquals(4000, PhotoRun.backoffMs(0, 0));
        assertEquals(8000, PhotoRun.backoffMs(0, 1));
        assertEquals(PhotoRun.RETRY_MIN_MS, PhotoRun.backoffMs(1, 0));
    }

    @Test public void aRetryThatWouldRunPastTheDeadlineIsNotStarted() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 14_000), http(429, 14_000), http(429, 14_000));
        FakeEnv env = new FakeEnv();
        PhotoRun.Result r = one(make(net, env, new FakeSaver()));
        long spent = env.now - 1_000_000;
        assertTrue("a call never runs much past its deadline: " + spent, spent <= PhotoRun.DEADLINE_MS);
        assertEquals(0, r.added);
        assertEquals(PhotoRun.Kind.BUSY, r.failure.kind);
    }

    // ---- the status words

    @Test public void nothingFetchedSaysWhat() {
        PhotoRun.Result r = new PhotoRun.Result();
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.REFUSED, 403);
        assertEquals("Wikimedia refused: HTTP 403", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.OFFLINE, 0);
        assertEquals("No network", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.TIMEOUT, 0);
        assertEquals("Server slow, retrying", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.BUSY, 429);
        assertEquals("Server busy, retrying", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.OTHER, 0);
        assertEquals("Could not reach Wikimedia", PhotoRun.note(r));
        r.failure = null;
        assertEquals("No new photo found", PhotoRun.note(r));
    }

    @Test public void aPhotoThatWasAddedHasNoNoteEvenAfterAnEarlierFailure() {
        PhotoRun.Result r = new PhotoRun.Result();
        r.added = 1;
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.TIMEOUT, 0);
        assertEquals("", PhotoRun.note(r));
    }

    // ---- the whole-call failures

    @Test public void noNetworkEndsTheCallAtOnce() {
        FakeNet net = new FakeNet(200);
        net.listFailure = new UnknownHostException("commons.wikimedia.org");
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals(1, net.log.size());
        assertEquals("No network", PhotoRun.note(r));
    }

    @Test public void aRefusedApiCallEndsTheCallWithTheStatusCode() {
        FakeNet net = new FakeNet(200);
        net.listFailure = http(403, 0);
        PhotoRun.Result r = one(make(net, new FakeEnv(), new FakeSaver()));
        assertEquals(0, r.added);
        assertEquals(1, net.log.size());
        assertEquals("Wikimedia refused: HTTP 403", PhotoRun.note(r));
    }

    @Test public void anInterruptedWaitStopsTheCall() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv();
        env.interruptAt = true;
        PhotoRun.Result r = one(make(net, env, new FakeSaver()));
        assertTrue(r.stopped);
        assertEquals(0, r.added);
    }

    @Test public void aCallThatTakesTooLongStopsWithoutAPhoto() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv() {
            @Override public boolean sleep(long ms) {
                now += 120_000; // every pause "takes" two minutes
                return true;
            }
        };
        PhotoRun.Result r = one(make(net, env, new FakeSaver()));
        assertTrue(r.stopped);
        assertEquals(0, r.added);
    }

    @Test public void failuresAreClassified() {
        assertEquals(PhotoRun.Kind.OFFLINE, PhotoRun.classify(new UnknownHostException("h")).kind);
        assertEquals(PhotoRun.Kind.TIMEOUT, PhotoRun.classify(new SocketTimeoutException("t")).kind);
        assertEquals(PhotoRun.Kind.BUSY, PhotoRun.classify(http(429, 0)).kind);
        assertEquals(PhotoRun.Kind.BUSY, PhotoRun.classify(http(503, 0)).kind);
        assertEquals(PhotoRun.Kind.REFUSED, PhotoRun.classify(http(403, 0)).kind);
        assertEquals(403, PhotoRun.classify(http(403, 0)).code);
        assertEquals(7000, PhotoRun.classify(http(429, 7000)).retryAfterMs);
        assertEquals(PhotoRun.Kind.SKIP, PhotoRun.classify(new Http.TooLarge()).kind);
        assertEquals(PhotoRun.Kind.OTHER, PhotoRun.classify(new IOException("address not allowed")).kind);
        assertNotNull(PhotoRun.classify(new IOException()));
    }

    @Test public void retryAfterHeaderIsReadAsSeconds() {
        assertEquals(10_000, Http.retryAfterMs("10"));
        assertEquals(1000, Http.retryAfterMs(" 1 "));
        assertEquals(0, Http.retryAfterMs(null));
        assertEquals(0, Http.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0, Http.retryAfterMs("-5"));
    }
}
