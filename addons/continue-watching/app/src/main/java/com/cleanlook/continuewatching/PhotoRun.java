package com.cleanlook.continuewatching;

import org.json.JSONException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Gets ONE new photo, without Android or the network: the HTTP layer ({@link Net}), the clock ({@link Env}) and the
 * file store ({@link Saver}) are handed in, so the JVM tests drive it with fakes. It keeps what it learned between
 * calls (the page ids of each category, and the checked candidates not tried yet), so most calls cost a single picture
 * request. One bad picture is skipped and the next candidate tried; a busy server (429, 503) is waited out as long as
 * it asks ({@code Retry-After}), retried at most twice, and then ends the call (the caller backs off). The call never
 * runs past {@link #DEADLINE_MS}.
 */
final class PhotoRun {
    /** The HTTP layer: the body of a GET, or an IOException ({@link Http.HttpException} for a non-2xx answer). */
    interface Net {
        byte[] get(String url, boolean image) throws IOException;
    }

    interface Env {
        /** Waits; false when the thread was interrupted (the call then stops). */
        boolean sleep(long ms);

        long now();

        /** The server is making the call wait (a 429 or 503): shown as "Server busy, retrying". */
        void waiting(boolean busy);
    }

    interface Saver {
        /** Checks and keeps one downloaded picture and records it; null when it is not a usable picture. */
        PhotoCache.Photo store(PhotoFilter.Candidate cand, String name, byte[] body) throws IOException;
    }

    static final int BATCH = 20;
    /** At most this many API calls (category lists and picture info) and picture downloads for one photo. */
    static final int MAX_API_CALLS = 6;
    static final int MAX_PICTURES = 4;
    static final int MAX_RETRIES = 2;
    /** A pause between two requests of one call. */
    static final long PAUSE_MS = 400;
    /** Measured: after a 429 the same picture is served again about 4 s later (2 s is too soon), whatever Retry-After says. */
    static final long RETRY_MIN_MS = 4000;
    static final long RETRY_MAX_MS = 15_000;
    /** Without a Retry-After the wait is this, doubled for the second retry. */
    static final long RETRY_DEFAULT_MS = 4000;
    /** A call that is still going after this long stops (no retry is started past it). */
    static final long DEADLINE_MS = 60_000;

    enum Kind { OFFLINE, TIMEOUT, BUSY, REFUSED, OTHER, SKIP }

    /** Why a request failed: the kind, for an HTTP answer its status code and what the server asked us to wait. */
    static final class Failure {
        final Kind kind;
        final int code;
        final long retryAfterMs;

        Failure(Kind kind, int code) {
            this(kind, code, 0);
        }

        Failure(Kind kind, int code, long retryAfterMs) {
            this.kind = kind;
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    static final class Result {
        /** 1 when a new photo was kept, else 0. */
        int added;
        /** The last failure that counts (no network, timeout, busy server, refusal, other); null when none. */
        Failure failure;
        /** The last picture that could not be had (too big, 404); only said when nothing else is. */
        Failure soft;
        int rejected;
        boolean stopped;
    }

    static Failure classify(IOException e) {
        if (e instanceof Http.HttpException) {
            int code = ((Http.HttpException) e).code;
            long ask = ((Http.HttpException) e).retryAfterMs;
            if (code == 429 || code >= 500) return new Failure(Kind.BUSY, code, ask);
            return new Failure(Kind.REFUSED, code);
        }
        if (e instanceof Http.TooLarge) return new Failure(Kind.SKIP, 0);
        if (e instanceof UnknownHostException || e instanceof ConnectException || e instanceof NoRouteToHostException) {
            return new Failure(Kind.OFFLINE, 0);
        }
        if (e instanceof SocketTimeoutException) return new Failure(Kind.TIMEOUT, 0);
        return new Failure(Kind.OTHER, 0);
    }

    private static boolean retryable(IOException e) {
        return e instanceof Http.HttpException && (((Http.HttpException) e).code == 429
                || ((Http.HttpException) e).code == 503);
    }

    /** How long to wait before retry number {@code attempt} (0 first): what the server asked, else 4 s then 8 s; 4 to 15 s. */
    static long backoffMs(long retryAfterMs, int attempt) {
        long w = retryAfterMs > 0 ? retryAfterMs + 500 : RETRY_DEFAULT_MS << attempt;
        return Math.max(RETRY_MIN_MS, Math.min(RETRY_MAX_MS, w));
    }

    // ---- the state kept between calls

    private static final class Pending {
        final String category;
        final PhotoFilter.Candidate cand;

        Pending(String category, PhotoFilter.Candidate cand) {
            this.category = category;
            this.cand = cand;
        }
    }

    private final Net net;
    private final Env env;
    private final Saver saver;
    private final Map<String, List<Long>> pools = new HashMap<>();
    private final List<Pending> queue = new ArrayList<>();

    // per call
    private Result result;
    private long start;
    private int apiCalls;
    private boolean requested;

    PhotoRun(Net net, Env env, Saver saver) {
        this.net = net;
        this.env = env;
        this.saver = saver;
    }

    /** Fetches one new photo from {@code cats}, skipping the names in {@code have}. A network problem is in the result. */
    synchronized Result fetchOne(PhotoPolicy.Category[] cats, Set<String> have, Random rnd) {
        result = new Result();
        start = env.now();
        apiCalls = 0;
        requested = false;
        int pictures = 0;
        try {
            while (result.added == 0 && !result.stopped) {
                if (env.now() - start > DEADLINE_MS) {
                    result.stopped = true;
                    break;
                }
                Pending p = take(cats, have);
                if (p == null) {
                    if (apiCalls >= MAX_API_CALLS || !refill(cats, rnd)) break;
                    continue;
                }
                if (pictures++ >= MAX_PICTURES) break;
                String name = PhotoCache.fileName(p.cand.title);
                if (download(p.cand, name)) {
                    have.add(name);
                    result.added = 1;
                } else if (result.failure != null && result.failure.kind == Kind.BUSY) {
                    break; // the server is busy: stop here, the caller backs off instead of trying other pictures
                }
            }
        } finally {
            env.waiting(false);
        }
        return result;
    }

    /** The next candidate that is of an enabled category and not in the ring yet; the ones passed over are dropped. */
    private Pending take(PhotoPolicy.Category[] cats, Set<String> have) {
        for (Iterator<Pending> it = queue.iterator(); it.hasNext();) {
            Pending p = it.next();
            it.remove();
            if (!enabled(cats, p.category) || have.contains(PhotoCache.fileName(p.cand.title))) continue;
            return p;
        }
        return null;
    }

    private static boolean enabled(PhotoPolicy.Category[] cats, String key) {
        for (PhotoPolicy.Category c : cats) if (c.key.equals(key)) return true;
        return false;
    }

    /**
     * Reads the next batch of one category (listing the category first if it was never listed) and queues the
     * candidates that pass the filters. False when there is nothing left to try or the call failed for good.
     */
    private boolean refill(PhotoPolicy.Category[] cats, Random rnd) {
        List<PhotoPolicy.Category> order = new ArrayList<>();
        Collections.addAll(order, cats);
        Collections.shuffle(order, rnd);
        for (PhotoPolicy.Category cat : order) {
            List<Long> pool = pools.get(cat.key);
            if (pool == null) {
                pool = new ArrayList<>();
                apiCalls++;
                try {
                    pool.addAll(PhotoFilter.pageIds(text(fetch(PhotoFilter.listUrl(cat.commons), false))));
                    Collections.shuffle(pool, rnd);
                } catch (IOException | JSONException e) {
                    apiFailed(e);
                    return false;
                }
                pools.put(cat.key, pool);
            }
            if (pool.isEmpty()) continue;
            List<Long> batch = new ArrayList<>(pool.subList(0, Math.min(BATCH, pool.size())));
            pool.subList(0, batch.size()).clear();
            apiCalls++;
            try {
                List<PhotoFilter.Candidate> found = PhotoFilter.candidates(text(fetch(PhotoFilter.infoUrl(batch), false)));
                Collections.shuffle(found, rnd);
                for (PhotoFilter.Candidate c : found) queue.add(new Pending(cat.key, c));
            } catch (IOException | JSONException e) {
                apiFailed(e);
                return false;
            }
            return true;
        }
        pools.clear(); // every category is used up: the next call lists them again
        return false;
    }

    private static String text(byte[] body) {
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** True when the picture was kept. A failure here only skips this picture. */
    private boolean download(PhotoFilter.Candidate cand, String name) {
        byte[] body;
        try {
            body = fetch(cand.url, true);
        } catch (IOException e) {
            failed(classify(e), true);
            return false;
        }
        try {
            PhotoCache.Photo p = saver.store(cand, name, body);
            if (p == null) {
                result.rejected++;
                return false;
            }
            return true;
        } catch (IOException e) {
            failed(new Failure(Kind.OTHER, 0), true);
            return false;
        }
    }

    /** A failed API call: a refusal (a bad User-Agent, say) or no network would fail every call, so it ends the call. */
    private void apiFailed(Exception e) {
        Failure f = e instanceof IOException ? classify((IOException) e) : new Failure(Kind.OTHER, 0);
        failed(f, false);
        result.stopped = true;
    }

    private void failed(Failure f, boolean image) {
        if (f.kind == Kind.SKIP || (image && f.kind == Kind.REFUSED)) {
            result.soft = f; // this one picture is gone or too big: the next one is tried
            return;
        }
        result.failure = f;
        if (f.kind == Kind.OFFLINE) result.stopped = true;
    }

    /** One GET with the pause before it and the waits and retries for a busy server. */
    private byte[] fetch(String url, boolean image) throws IOException {
        if (requested && !env.sleep(PAUSE_MS)) {
            result.stopped = true;
            throw new IOException("interrupted");
        }
        requested = true;
        try {
            for (int attempt = 0; ; attempt++) {
                try {
                    return net.get(url, image);
                } catch (IOException e) {
                    if (!retryable(e) || attempt >= MAX_RETRIES) throw e;
                    long wait = backoffMs(((Http.HttpException) e).retryAfterMs, attempt);
                    if (env.now() + wait - start > DEADLINE_MS) throw e;
                    env.waiting(true);
                    if (!env.sleep(wait)) {
                        result.stopped = true;
                        throw e;
                    }
                }
            }
        } finally {
            env.waiting(false);
        }
    }

    // ---- the plain words

    /**
     * The note for the status line: empty when a photo was added, otherwise what happened in plain words, such as
     * "Server busy, retrying" or "No network".
     */
    static String note(Result r) {
        if (r.added > 0) return "";
        Failure f = r.failure != null ? r.failure : r.soft;
        if (f == null) return r.rejected > 0 ? "No new photo found, try again" : "No new photo found";
        switch (f.kind) {
            case OFFLINE: return "No network";
            case TIMEOUT: return "Server slow, retrying";
            case BUSY: return "Server busy, retrying";
            case REFUSED: return f.code == 0 ? "Wikimedia refused the request" : "Wikimedia refused: HTTP " + f.code;
            case SKIP: return "Picture too big, retrying";
            default: return "Could not reach Wikimedia";
        }
    }
}
