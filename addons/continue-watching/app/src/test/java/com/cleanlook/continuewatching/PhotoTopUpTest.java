package com.cleanlook.continuewatching;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PhotoTopUpTest {
    private static final long T0 = 10_000_000L;

    private static final class FakeClock implements PhotoTopUp.Clock {
        long now = T0;
        final List<Long> fetchTimes = new ArrayList<>();
        boolean interrupt;

        @Override public long now() {
            return now;
        }

        @Override public boolean sleep(long ms) {
            now += ms;
            return !interrupt;
        }
    }

    /** A ring that gains a photo per good fetch; scripted failures. */
    private static final class FakeSource implements PhotoTopUp.Source {
        int count;
        int fetches;
        int failNext;
        PhotoRun.Failure failure = new PhotoRun.Failure(PhotoRun.Kind.TIMEOUT, 0);
        final FakeClock clock;
        Runnable duringFetch;

        FakeSource(FakeClock clock, int count) {
            this.clock = clock;
            this.count = count;
        }

        @Override public int count() {
            return count;
        }

        @Override public PhotoRun.Result fetchOne() {
            fetches++;
            clock.fetchTimes.add(clock.now);
            if (duringFetch != null) duringFetch.run();
            PhotoRun.Result r = new PhotoRun.Result();
            if (failNext > 0) {
                failNext--;
                r.failure = failure;
                return r;
            }
            r.added = 1;
            count++;
            return r;
        }
    }

    // ---- filling and advancing

    @Test public void fillBringsTheRingUpToItsTargetOnePhotoAtATime() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 1);
        PhotoTopUp up = new PhotoTopUp();
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
        PhotoTopUp.Report r = up.run(PhotoTopUp.Mode.FILL, src, clock);
        assertEquals(PhotoCache.TARGET - 1, src.fetches);
        assertEquals(PhotoCache.TARGET - 1, r.added);
        assertEquals(PhotoCache.TARGET, src.count);
    }

    @Test public void fillDoesNothingWhenTheRingIsFull() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, PhotoCache.TARGET);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        PhotoTopUp.Report r = up.run(PhotoTopUp.Mode.FILL, src, clock);
        assertEquals(0, src.fetches);
        assertNull(r.last);
    }

    @Test public void advanceAddsExactlyOnePhotoEvenWhenTheRingIsAtItsTarget() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, PhotoCache.TARGET);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        PhotoTopUp.Report r = up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(1, src.fetches);
        assertEquals(1, r.added);
    }

    @Test public void theRingNeverGrowsPastItsMaximum() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, PhotoCache.MAX_PHOTOS);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, true, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(0, src.fetches);
    }

    // ---- spacing

    @Test public void photoRequestsAreAtLeastFiveSecondsApart() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 0);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        up.run(PhotoTopUp.Mode.FILL, src, clock);
        assertEquals(PhotoCache.TARGET, clock.fetchTimes.size());
        for (int i = 1; i < clock.fetchTimes.size(); i++) {
            assertTrue(clock.fetchTimes.get(i) - clock.fetchTimes.get(i - 1) >= 5000);
        }
    }

    @Test public void aFetchRightAfterTheLastOneWaitsOutTheSpacing() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 3);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        clock.now += 1000; // the launcher advances again one second later
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(2, clock.fetchTimes.size());
        assertTrue(clock.fetchTimes.get(1) - clock.fetchTimes.get(0) >= PhotoPolicy.SPACING_MS);
    }

    @Test public void aTimeLongAfterTheLastFetchStartsAtOnce() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 3);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        clock.now += 60_000;
        long before = clock.now;
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(before, clock.fetchTimes.get(1).longValue());
    }

    @Test public void anInterruptedWaitStopsTheTopUp() {
        FakeClock clock = new FakeClock();
        clock.interrupt = true;
        FakeSource src = new FakeSource(clock, 0);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        up.run(PhotoTopUp.Mode.FILL, src, clock);
        assertEquals("the first fetch needs no wait, the second is cut", 1, src.fetches);
    }

    // ---- single flight

    @Test public void onlyOneTopUpIsEverInFlight() {
        FakeClock clock = new FakeClock();
        PhotoTopUp up = new PhotoTopUp();
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
        assertFalse("a second one is refused", up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
        assertFalse(up.claim(PhotoTopUp.Mode.ADVANCE, true, clock.now));
        assertTrue(up.flightMs(clock.now + 1500) >= 1500);
        up.run(PhotoTopUp.Mode.FILL, new FakeSource(clock, 4), clock);
        assertEquals("over: the flight is given back", -1, up.flightMs(clock.now));
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
    }

    @Test public void aRequestDuringAFlightIsRememberedAsOneMoreAdvance() {
        FakeClock clock = new FakeClock();
        final PhotoTopUp up = new PhotoTopUp();
        FakeSource src = new FakeSource(clock, 3);
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        src.duringFetch = new Runnable() {
            int calls;

            @Override public void run() {
                if (calls++ == 0) {
                    assertFalse(up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now));
                    assertFalse("asking twice is still one more", up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now));
                }
            }
        };
        PhotoTopUp.Report r = up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals("the one asked for, plus one remembered", 2, src.fetches);
        assertEquals(2, r.added);
        assertEquals(-1, up.flightMs(clock.now));
    }

    @Test public void aFillRequestDuringAFlightIsNotRemembered() {
        FakeClock clock = new FakeClock();
        final PhotoTopUp up = new PhotoTopUp();
        FakeSource src = new FakeSource(clock, 3);
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        src.duringFetch = new Runnable() {
            @Override public void run() {
                assertFalse(up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
            }
        };
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(1, src.fetches);
    }

    @Test public void theFlightIsGivenBackEvenWhenTheFetchCrashes() {
        FakeClock clock = new FakeClock();
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        PhotoTopUp.Source boom = new PhotoTopUp.Source() {
            @Override public int count() {
                return 0;
            }

            @Override public PhotoRun.Result fetchOne() {
                throw new IllegalStateException("boom");
            }
        };
        try {
            up.run(PhotoTopUp.Mode.FILL, boom, clock);
        } catch (IllegalStateException expected) {
            // the caller turns this into a plain note
        }
        assertEquals(-1, up.flightMs(clock.now));
        assertFalse(up.busy());
        assertTrue("and a new one can start", up.claim(PhotoTopUp.Mode.FILL, true, clock.now));
    }

    @Test public void aFlightThatHungIsGivenUpAfterAWhile() {
        FakeClock clock = new FakeClock();
        PhotoTopUp up = new PhotoTopUp();
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, clock.now));
        assertEquals(-1, up.flightMs(clock.now + PhotoTopUp.STALE_MS + 1));
        assertTrue(up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now + PhotoTopUp.STALE_MS + 1));
    }

    @Test public void releaseGivesTheFlightBack() {
        PhotoTopUp up = new PhotoTopUp();
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, T0));
        up.release();
        assertTrue(up.claim(PhotoTopUp.Mode.FILL, false, T0));
    }

    // ---- failures rest the automatic top-ups

    @Test public void aFailureStopsTheTopUpAndRestsAutomaticOnes() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 0);
        src.failNext = 1;
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        PhotoTopUp.Report r = up.run(PhotoTopUp.Mode.FILL, src, clock);
        assertEquals("it does not hammer the server", 1, src.fetches);
        assertEquals(0, r.added);
        assertEquals(PhotoRun.Kind.TIMEOUT, r.last.failure.kind);
        assertEquals(1, up.failStreak());
        assertFalse("automatic ones rest", up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now + 1000));
        assertEquals("a flight was not started by the refused claim", -1, up.flightMs(clock.now + 1000));
        assertTrue("the owner's own request does not wait", up.claim(PhotoTopUp.Mode.ADVANCE, true, clock.now + 1000));
    }

    @Test public void theRestEndsAndASuccessClearsTheStreak() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 0);
        src.failNext = 1;
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        long later = clock.now + PhotoPolicy.FAIL_WAIT_MS + 1;
        assertTrue(up.claim(PhotoTopUp.Mode.ADVANCE, false, later));
        clock.now = later;
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(0, up.failStreak());
        assertTrue(up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now));
    }

    @Test public void theServersRetryAfterLengthensTheRest() {
        FakeClock clock = new FakeClock();
        FakeSource src = new FakeSource(clock, 0);
        src.failNext = 1;
        src.failure = new PhotoRun.Failure(PhotoRun.Kind.BUSY, 429, 120_000);
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertFalse(up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now + 100_000));
        assertTrue(up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now + 121_000));
    }

    @Test public void aQueuedAdvanceIsDroppedWhenTheFetchFailed() {
        FakeClock clock = new FakeClock();
        final PhotoTopUp up = new PhotoTopUp();
        FakeSource src = new FakeSource(clock, 3);
        src.failNext = 1;
        up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
        src.duringFetch = new Runnable() {
            @Override public void run() {
                up.claim(PhotoTopUp.Mode.ADVANCE, false, clock.now);
            }
        };
        up.run(PhotoTopUp.Mode.ADVANCE, src, clock);
        assertEquals(1, src.fetches);
    }

    @Test public void theBusyFlagIsClearedWhenTheTopUpEnds() {
        FakeClock clock = new FakeClock();
        PhotoTopUp up = new PhotoTopUp();
        up.claim(PhotoTopUp.Mode.FILL, false, clock.now);
        up.setBusy(true);
        up.run(PhotoTopUp.Mode.FILL, new FakeSource(clock, 4), clock);
        assertFalse(up.busy());
    }
}
