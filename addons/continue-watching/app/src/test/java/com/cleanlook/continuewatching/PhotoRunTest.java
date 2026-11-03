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

    private static class FakeEnv implements PhotoRun.Env {
        long now = 1_000_000;
        final List<Long> sleeps = new ArrayList<>();
        boolean interruptAt;

        @Override public boolean sleep(long ms) {
            sleeps.add(ms);
            now += ms;
            return !interruptAt;
        }

        @Override public long now() {
            return now;
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

    private static PhotoRun.Result run(FakeNet net, FakeEnv env, FakeSaver saver, int want) {
        return new PhotoRun(net, env, saver).run(ONE, want, new HashSet<String>(), new Random(1));
    }

    private static Http.HttpException http(int code, long retryAfterMs) {
        return new Http.HttpException(code, retryAfterMs);
    }

    private static int pictureRequests(FakeNet net) {
        int n = 0;
        for (String u : net.log) if (u.contains("/thumb/")) n++;
        return n;
    }

    // ---- a good run

    @Test public void aGoodRunFetchesWhatWasWantedWithAPauseBetweenRequests() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv();
        FakeSaver saver = new FakeSaver();
        PhotoRun.Result r = run(net, env, saver, 8);
        assertEquals(8, r.added);
        assertNull(r.failure);
        assertEquals("", PhotoRun.note(r));
        assertEquals(net.log.size() - 1, env.sleeps.size());
        for (long s : env.sleeps) assertEquals(PhotoRun.PAUSE_MS, s);
    }

    @Test public void photosAlreadyCachedAreNotFetchedAgain() {
        FakeNet net = new FakeNet(200);
        Set<String> have = new HashSet<>();
        have.add(PhotoCache.fileName("File:Pic0.jpg"));
        FakeSaver saver = new FakeSaver();
        PhotoRun.Result r = new PhotoRun(net, new FakeEnv(), saver).run(ONE, 15, have, new Random(1));
        assertEquals(15, r.added);
        assertFalse(saver.saved.contains("File:Pic0.jpg"));
    }

    // ---- one bad picture does not end the run

    @Test public void aTimedOutPictureIsSkippedAndTheRunGoesOn() {
        FakeNet net = new FakeNet(200);
        net.failFirst(new SocketTimeoutException("read"));
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 6);
        assertEquals(6, r.added);
        assertEquals("timed out once, then everything worked", PhotoRun.Kind.TIMEOUT, r.failure.kind);
        assertEquals(1, net.requestsForFirst());
        assertEquals("", PhotoRun.note(r));
    }

    @Test public void aPictureTheSaverRefusesOrCannotWriteIsSkipped() {
        FakeNet net = new FakeNet(200);
        FakeSaver saver = new FakeSaver();
        saver.rejectNext = 1;
        saver.failNext = 1;
        PhotoRun.Result r = run(net, new FakeEnv(), saver, 5);
        assertEquals(5, r.added);
        assertEquals(5, saver.saved.size());
        assertEquals(1, r.rejected);
    }

    @Test public void aMissingOrOversizedPictureIsSkippedWithoutCountingAgainstTheRun() {
        FakeNet net = new FakeNet(200);
        for (int i = 0; i < 12; i++) net.fail(i, i % 2 == 0 ? http(404, 0) : new Http.TooLarge());
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 5);
        assertEquals(5, r.added);
        assertFalse("not stopped by 12 skipped pictures", r.stopped);
        assertNull(r.failure);
    }

    @Test public void aFailedInfoCallLosesOnlyThatBatch() {
        FakeNet net = new FakeNet(200) {
            int infoCalls;

            @Override public byte[] get(String url, boolean image) throws IOException {
                if (!image && !url.contains("list=categorymembers") && infoCalls++ == 0) throw new SocketTimeoutException("x");
                return super.get(url, image);
            }
        };
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 5);
        assertEquals(5, r.added);
    }

    // ---- a busy server

    @Test public void aBusyServerIsWaitedOutByItsRetryAfterAndTriedAgain() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 10_000));
        FakeEnv env = new FakeEnv();
        PhotoRun.Result r = run(net, env, new FakeSaver(), 3);
        assertEquals(3, r.added);
        assertTrue("waited the 10 s it asked for", env.sleeps.contains(10_500L));
        assertEquals("the same picture was asked for again", 2, net.requestsForFirst());
        assertEquals("no picture was lost to the busy answer", 4, pictureRequests(net));
    }

    @Test public void thereAreAtMostTwoRetriesPerRequest() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(429, 1000), http(429, 1000), http(429, 1000), http(429, 1000));
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 1);
        assertEquals("the next picture was taken instead", 1, r.added);
        assertEquals("first try plus two retries", 3, net.requestsForFirst());
    }

    @Test public void aServerUnavailableAnswerIsRetriedToo() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(503, 0));
        FakeEnv env = new FakeEnv();
        PhotoRun.Result r = run(net, env, new FakeSaver(), 1);
        assertEquals(1, r.added);
        assertTrue("no Retry-After: the default backoff", env.sleeps.contains(PhotoRun.RETRY_DEFAULT_MS));
    }

    @Test public void aRefusalIsNotRetried() {
        FakeNet net = new FakeNet(200);
        net.failFirst(http(404, 0));
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 1);
        assertEquals(1, net.requestsForFirst());
        assertEquals(1, r.added);
    }

    @Test public void backoffFollowsRetryAfterWithinLimits() {
        assertEquals(10_500, PhotoRun.backoffMs(10_000, 0));
        assertEquals(PhotoRun.RETRY_MAX_MS, PhotoRun.backoffMs(600_000, 0));
        assertEquals(3000, PhotoRun.backoffMs(0, 0));
        assertEquals(6000, PhotoRun.backoffMs(0, 1));
        assertEquals(1000, PhotoRun.backoffMs(1, 0));
    }

    @Test public void aServerThatStaysBusyEndsTheRunAfterAFewPictures() {
        FakeNet net = new FakeNet(200);
        for (int i = 0; i < 200; i++) net.fail(i, http(429, 1000), http(429, 1000), http(429, 1000));
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 15);
        assertEquals(0, r.added);
        assertTrue(r.stopped);
        assertEquals(PhotoRun.Kind.BUSY, r.failure.kind);
        assertEquals(PhotoRun.MAX_STREAK * (PhotoRun.MAX_RETRIES + 1), pictureRequests(net));
        assertEquals("Server busy, try again later", PhotoRun.note(r));
    }

    @Test public void aBusyPictureInTheMiddleOfAGoodRunDoesNotEndIt() {
        FakeNet net = new FakeNet(200);
        for (int i = 0; i < 3; i++) net.fail(i, http(429, 1000), http(429, 1000), http(429, 1000));
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 15);
        assertEquals(15, r.added);
        assertFalse(r.stopped);
    }

    // ---- the status words

    @Test public void partialRunSaysHowManyAndWhy() {
        PhotoRun.Result r = new PhotoRun.Result();
        r.added = 3;
        r.want = 15;
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.BUSY, 429);
        assertEquals("3 of 15 photos fetched: server busy, try again later", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.TIMEOUT, 0);
        assertEquals("3 of 15 photos fetched: timed out, try again later", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.OFFLINE, 0);
        assertEquals("3 of 15 photos fetched: no network", PhotoRun.note(r));
    }

    @Test public void nothingFetchedSaysWhat() {
        PhotoRun.Result r = new PhotoRun.Result();
        r.want = 15;
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.REFUSED, 403);
        assertEquals("Wikimedia refused: HTTP 403", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.OFFLINE, 0);
        assertEquals("No network", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.TIMEOUT, 0);
        assertEquals("Timed out, try again later", PhotoRun.note(r));
        r.failure = new PhotoRun.Failure(PhotoRun.Kind.OTHER, 0);
        assertEquals("Could not reach Wikimedia, try again later", PhotoRun.note(r));
        r.failure = null;
        assertEquals("No new photos found", PhotoRun.note(r));
    }

    @Test public void fullRunHasNoNoteEvenWithASkippedPicture() {
        PhotoRun.Result r = new PhotoRun.Result();
        r.added = 5;
        r.want = 5;
        r.soft = new PhotoRun.Failure(PhotoRun.Kind.REFUSED, 404);
        assertEquals("", PhotoRun.note(r));
    }

    // ---- the whole-run failures

    @Test public void noNetworkEndsTheRunAtOnce() {
        FakeNet net = new FakeNet(200);
        net.listFailure = new UnknownHostException("commons.wikimedia.org");
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 15);
        assertEquals(0, r.added);
        assertEquals(1, net.log.size());
        assertEquals("No network", PhotoRun.note(r));
    }

    @Test public void aRefusedApiCallEndsTheRunWithTheStatusCode() {
        FakeNet net = new FakeNet(200);
        net.listFailure = http(403, 0);
        PhotoRun.Result r = run(net, new FakeEnv(), new FakeSaver(), 15);
        assertEquals(0, r.added);
        assertEquals(1, net.log.size());
        assertEquals("Wikimedia refused: HTTP 403", PhotoRun.note(r));
    }

    @Test public void photosThatArrivedBeforeTheNetworkWentAreKept() {
        FakeNet net = new FakeNet(200) {
            @Override public byte[] get(String url, boolean image) throws IOException {
                if (image && log.size() > 6) throw new UnknownHostException("gone");
                return super.get(url, image);
            }
        };
        FakeSaver saver = new FakeSaver();
        PhotoRun.Result r = run(net, new FakeEnv(), saver, 15);
        assertTrue(r.added > 0);
        assertEquals(r.added, saver.saved.size());
        assertTrue(PhotoRun.note(r).startsWith(r.added + " of 15 photos fetched: no network"));
    }

    @Test public void anInterruptedWaitStopsTheRun() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv();
        env.interruptAt = true;
        PhotoRun.Result r = run(net, env, new FakeSaver(), 15);
        assertTrue(r.stopped);
        assertTrue(r.added <= 1);
    }

    @Test public void aRunThatTakesTooLongStopsWithWhatItHas() {
        FakeNet net = new FakeNet(200);
        FakeEnv env = new FakeEnv() {
            @Override public boolean sleep(long ms) {
                now += 120_000; // every pause "takes" two minutes
                return true;
            }
        };
        PhotoRun.Result r = run(net, env, new FakeSaver(), 15);
        assertTrue(r.stopped);
        assertTrue(r.added < 15);
    }

    @Test public void failuresAreClassified() {
        assertEquals(PhotoRun.Kind.OFFLINE, PhotoRun.classify(new UnknownHostException("h")).kind);
        assertEquals(PhotoRun.Kind.TIMEOUT, PhotoRun.classify(new SocketTimeoutException("t")).kind);
        assertEquals(PhotoRun.Kind.BUSY, PhotoRun.classify(http(429, 0)).kind);
        assertEquals(PhotoRun.Kind.BUSY, PhotoRun.classify(http(503, 0)).kind);
        assertEquals(PhotoRun.Kind.REFUSED, PhotoRun.classify(http(403, 0)).kind);
        assertEquals(403, PhotoRun.classify(http(403, 0)).code);
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
