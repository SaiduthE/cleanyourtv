package com.cleanlook.continuewatching;

/**
 * The rolling buffer's top-up, without Android or the network: at most one top-up in flight, one photo at a time,
 * requests at least {@link PhotoPolicy#SPACING_MS} apart, and a rest after a failure. The photo source and the clock
 * are handed in, so the JVM tests drive it with fakes.
 *
 * <p>Two kinds of top-up: {@link Mode#FILL} brings the ring up to its target (opening the add-on, a nearly empty ring,
 * the scheduled job) and {@link Mode#ADVANCE} adds one new photo (the launcher rotated to the next picture, or the
 * owner asked for a new photo now), after which the caller trims the ring back to its target. A request that comes
 * while one is in flight does not start a second: an advance is remembered and runs once after the current one.
 */
final class PhotoTopUp {
    enum Mode { FILL, ADVANCE }

    interface Clock {
        long now();

        /** Waits; false when the thread was interrupted (the top-up then stops). */
        boolean sleep(long ms);
    }

    interface Source {
        /** How many photos are ready now. */
        int count();

        /** Gets one new photo into the ring (and trims the ring); the result says whether it worked and why not. */
        PhotoRun.Result fetchOne();
    }

    /** What a top-up did: photos added and the result of its last fetch (null when it fetched nothing). */
    static final class Report {
        int added;
        PhotoRun.Result last;
    }

    /** A flight older than this is taken to be dead and a new one may start. */
    static final long STALE_MS = 3L * 60 * 1000;

    private boolean inFlight;
    private boolean advanceQueued;
    private long flightStartMs;
    private volatile boolean busy;
    private long lastFetchMs;
    private int failStreak;
    private long blockedUntilMs;

    /**
     * Claims the single flight. True: the caller must now call {@link #run} (on a worker thread) and nothing else may
     * start meanwhile. False: one is already in flight (an advance is remembered for after it) or, for an automatic
     * request, the rest after a failure is not over.
     */
    synchronized boolean claim(Mode mode, boolean manual, long now) {
        // A flight whose thread hung (a connection that trickles) is given up after STALE_MS: the status never sticks.
        if (inFlight && now - flightStartMs > STALE_MS) inFlight = false;
        if (inFlight) {
            if (mode == Mode.ADVANCE) advanceQueued = true;
            return false;
        }
        if (!manual && now < blockedUntilMs) return false;
        inFlight = true;
        flightStartMs = now;
        advanceQueued = false;
        return true;
    }

    /** Gives the flight back without running it (the worker thread could not be started). */
    synchronized void release() {
        inFlight = false;
        advanceQueued = false;
    }

    /** How long the top-up in flight has been going, or -1 when none is (or it is long dead). */
    synchronized long flightMs(long now) {
        return inFlight && now - flightStartMs <= STALE_MS ? Math.max(0, now - flightStartMs) : -1;
    }

    /** True while the server is making a fetch wait (a 429 or 503). */
    boolean busy() {
        return busy;
    }

    void setBusy(boolean b) {
        busy = b;
    }

    synchronized int failStreak() {
        return failStreak;
    }

    /** Runs the claimed top-up to its end and gives the flight back, whatever happens. */
    Report run(Mode mode, Source source, Clock clock) {
        Report report = new Report();
        try {
            Mode m = mode;
            do {
                round(m, source, clock, report);
                m = Mode.ADVANCE;
            } while (takeQueued(report));
        } finally {
            busy = false;
            synchronized (this) {
                inFlight = false;
                advanceQueued = false;
            }
        }
        return report;
    }

    private synchronized boolean takeQueued(Report report) {
        boolean again = advanceQueued && (report.last == null || report.last.added > 0);
        advanceQueued = false;
        return again;
    }

    private void round(Mode mode, Source source, Clock clock, Report report) {
        int done = 0;
        while (true) {
            int count = source.count();
            if (!PhotoCache.hasRoom(count)) break;
            if (mode == Mode.FILL ? !PhotoCache.belowTarget(count) : done >= 1) break;
            long wait;
            synchronized (this) {
                wait = lastFetchMs == 0 ? 0 : lastFetchMs + PhotoPolicy.SPACING_MS - clock.now();
            }
            if (wait > 0 && !clock.sleep(wait)) break;
            PhotoRun.Result r = source.fetchOne();
            synchronized (this) {
                lastFetchMs = clock.now();
                report.last = r;
                if (r.added > 0) {
                    failStreak = 0;
                    blockedUntilMs = 0;
                } else {
                    failStreak++;
                    long ask = r.failure == null ? 0 : r.failure.retryAfterMs;
                    blockedUntilMs = clock.now() + PhotoPolicy.failWaitMs(failStreak, ask);
                }
            }
            if (r.added <= 0) break;
            report.added += r.added;
            done++;
        }
    }
}
