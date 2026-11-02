package io.github.sumyabazar1.weather;

/** When to read the weather again. Pure, so the decisions are tested on the JVM. */
final class RefreshPolicy {
    static final int DEFAULT_MINUTES = 30;
    /** The choices in settings; JobScheduler does not run a periodic job more often than every 15 minutes. */
    static final int[] CHOICES = {15, 30, 60};
    /** A query that finds data older than this asks for a one-shot refresh. */
    static final long STALE_AFTER_MS = 35 * 60_000L;
    /** ...but at most once in this long. */
    static final long MIN_KICK_GAP_MS = 10 * 60_000L;

    private RefreshPolicy() { }

    /** A saved interval if it is one of the choices, else the default. */
    static int minutes(int saved) {
        for (int c : CHOICES) if (c == saved) return saved;
        return DEFAULT_MINUTES;
    }

    /** The next choice after this one, wrapping, for the settings row. */
    static int nextMinutes(int current) {
        for (int i = 0; i < CHOICES.length; i++) if (CHOICES[i] == current) return CHOICES[(i + 1) % CHOICES.length];
        return DEFAULT_MINUTES;
    }

    /**
     * Whether a query should schedule a one-shot refresh. snapshotMs is when the data was read, or a value
     * below zero when there is none; lastKickMs is when the last one-shot was asked for (0 if never). A timestamp
     * in the future (the clock was set back) is not trusted: data from the future is stale, a kick from the future
     * does not block a new one.
     */
    static boolean shouldKick(long nowMs, long snapshotMs, long lastKickMs, boolean hasPlace) {
        if (!hasPlace) return false;
        boolean stale = snapshotMs < 0 || nowMs - snapshotMs > STALE_AFTER_MS || snapshotMs > nowMs + STALE_AFTER_MS;
        if (!stale) return false;
        return lastKickMs <= 0 || nowMs < lastKickMs || nowMs - lastKickMs >= MIN_KICK_GAP_MS;
    }
}
