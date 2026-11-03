package com.cleanlook.continuewatching;

import org.json.JSONException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.Set;

/**
 * One photo refresh, without Android or the network: the HTTP layer ({@link Net}), the clock ({@link Env}) and the
 * file store ({@link Saver}) are handed in, so the JVM tests drive it with fakes. One bad picture or one failed request
 * never ends the run: it is skipped and the run goes on with the next. A busy server (429, 503) is waited out as long as
 * it asks ({@code Retry-After}) and tried again at most twice. Requests are spaced out, one after the other.
 */
final class PhotoRun {
    /** The HTTP layer: the body of a GET, or an IOException ({@link Http.HttpException} for a non-2xx answer). */
    interface Net {
        byte[] get(String url, boolean image) throws IOException;
    }

    interface Env {
        /** Waits; false when the thread was interrupted (the run then stops). */
        boolean sleep(long ms);

        long now();
    }

    interface Saver {
        /** Checks and keeps one downloaded picture and records it; null when it is not a usable picture. */
        PhotoCache.Photo store(PhotoFilter.Candidate cand, String name, byte[] body) throws IOException;
    }

    static final int BATCH = 20;
    /** From one batch of one category at most this many are taken, so the photos come from several categories. */
    static final int PER_ROUND = 3;
    static final int MAX_CALLS = 30;
    static final int MAX_RETRIES = 2;
    static final long PAUSE_MS = 400;
    static final long RETRY_MIN_MS = 1000;
    static final long RETRY_MAX_MS = 30_000;
    /** Without a Retry-After the wait is this, doubled for the second retry. */
    static final long RETRY_DEFAULT_MS = 3000;
    /** This many failed requests in a row (after their retries) end the run: the server or the line is not there. */
    static final int MAX_STREAK = 4;
    /** A refresh that is still going after this long stops with what it has. */
    static final long DEADLINE_MS = 6L * 60 * 1000;

    enum Kind { OFFLINE, TIMEOUT, BUSY, REFUSED, OTHER, SKIP }

    /** Why a request failed: the kind and, for an HTTP answer, its status code. */
    static final class Failure {
        final Kind kind;
        final int code;

        Failure(Kind kind, int code) {
            this.kind = kind;
            this.code = code;
        }
    }

    static final class Result {
        int added;
        int want;
        /** The last failure that counts (no network, timeout, busy server, refusal, other); null when none. */
        Failure failure;
        /** The last pictures that could not be had one by one (too big, 404); only said when nothing else is. */
        Failure soft;
        int rejected;
        boolean stopped;
    }

    static Failure classify(IOException e) {
        if (e instanceof Http.HttpException) {
            int code = ((Http.HttpException) e).code;
            if (code == 429 || code >= 500) return new Failure(Kind.BUSY, code);
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

    /** How long to wait before retry number {@code attempt} (0 first): what the server asked, else 3 s then 6 s; 1 to 30 s. */
    static long backoffMs(long retryAfterMs, int attempt) {
        long w = retryAfterMs > 0 ? retryAfterMs + 500 : RETRY_DEFAULT_MS << attempt;
        return Math.max(RETRY_MIN_MS, Math.min(RETRY_MAX_MS, w));
    }

    // ---- the run

    private final Net net;
    private final Env env;
    private final Saver saver;
    private final Result result = new Result();
    private int streak;
    private boolean requested;

    PhotoRun(Net net, Env env, Saver saver) {
        this.net = net;
        this.env = env;
        this.saver = saver;
    }

    /**
     * Fetches up to {@code want} new photos from {@code cats} (in this order), skipping the names in {@code have}.
     * Never throws for a network problem: it is in {@link Result#failure}.
     */
    Result run(PhotoPolicy.Category[] cats, int want, Set<String> have, Random rnd) {
        result.want = want;
        List<PhotoPolicy.Category> order = new ArrayList<>();
        Collections.addAll(order, cats);
        Collections.shuffle(order, rnd);
        long start = env.now();
        Map<String, List<Long>> pools = new HashMap<>();
        int calls = 0;
        int empty = 0;
        for (int round = 0; result.added < want && calls < MAX_CALLS && empty < order.size() && !result.stopped; round++) {
            if (env.now() - start > DEADLINE_MS) {
                result.stopped = true;
                break;
            }
            PhotoPolicy.Category cat = order.get(round % order.size());
            List<Long> pool = pools.get(cat.key);
            if (pool == null) {
                calls++;
                pool = new ArrayList<>();
                pools.put(cat.key, pool);
                try {
                    pool.addAll(PhotoFilter.pageIds(text(fetch(PhotoFilter.listUrl(cat.commons), false))));
                    Collections.shuffle(pool, rnd);
                } catch (IOException | JSONException e) {
                    apiFailed(e);
                }
            }
            if (pool.isEmpty()) {
                empty++;
                continue;
            }
            empty = 0;
            List<Long> batch = new ArrayList<>(pool.subList(0, Math.min(BATCH, pool.size())));
            pool.subList(0, batch.size()).clear();
            calls++;
            List<PhotoFilter.Candidate> candidates;
            try {
                candidates = PhotoFilter.candidates(text(fetch(PhotoFilter.infoUrl(batch), false)));
            } catch (IOException | JSONException e) {
                apiFailed(e);
                continue;
            }
            int fromThisBatch = 0;
            for (PhotoFilter.Candidate cand : candidates) {
                if (result.added >= want || fromThisBatch >= PER_ROUND || result.stopped) break;
                String name = PhotoCache.fileName(cand.title);
                if (have.contains(name)) continue;
                if (download(cand, name)) {
                    have.add(name);
                    fromThisBatch++;
                    result.added++;
                }
            }
        }
        return result;
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

    /** A failed API call: a refusal (a bad User-Agent, say) or no network would fail every call, so it ends the run. */
    private void apiFailed(Exception e) {
        Failure f = e instanceof IOException ? classify((IOException) e) : new Failure(Kind.OTHER, 0);
        failed(f, false);
        if (f.kind == Kind.REFUSED) result.stopped = true;
    }

    private void failed(Failure f, boolean image) {
        if (f.kind == Kind.SKIP || (image && f.kind == Kind.REFUSED)) {
            result.soft = f; // this one picture is gone or too big: the next one is tried, nothing is counted against the run
            return;
        }
        result.failure = f;
        if (f.kind == Kind.OFFLINE || ++streak >= MAX_STREAK) result.stopped = true;
    }

    /** One GET with the pause before it and the waits and retries for a busy server. */
    private byte[] fetch(String url, boolean image) throws IOException {
        if (requested && !env.sleep(PAUSE_MS)) {
            result.stopped = true;
            throw new IOException("interrupted");
        }
        requested = true;
        for (int attempt = 0; ; attempt++) {
            try {
                byte[] body = net.get(url, image);
                streak = 0;
                return body;
            } catch (IOException e) {
                if (!retryable(e) || attempt >= MAX_RETRIES) throw e;
                long ask = ((Http.HttpException) e).retryAfterMs;
                if (!env.sleep(backoffMs(ask, attempt))) {
                    result.stopped = true;
                    throw e;
                }
            }
        }
    }

    // ---- the plain words

    /**
     * The note for the status line: empty when the refresh got everything it wanted, otherwise what happened in plain
     * words, such as "3 of 15 photos fetched: server busy, try again later" or "Wikimedia refused: HTTP 403".
     */
    static String note(Result r) {
        Failure f = r.failure != null ? r.failure : r.soft;
        if (r.added > 0) {
            if (f == null || r.added >= r.want) return "";
            return r.added + " of " + r.want + " photos fetched: " + because(f, false);
        }
        if (f == null) return r.rejected > 0 ? "No new photos found, try again later" : "No new photos found";
        return because(f, true);
    }

    private static String because(Failure f, boolean sentence) {
        String s;
        switch (f.kind) {
            case OFFLINE: s = "no network"; break;
            case TIMEOUT: s = "timed out, try again later"; break;
            case BUSY: s = "server busy, try again later"; break;
            case REFUSED: s = "Wikimedia refused: HTTP " + f.code; break;
            case SKIP: s = "pictures too big, try again later"; break;
            default: s = "could not reach Wikimedia, try again later"; break;
        }
        if (f.kind == Kind.REFUSED && !sentence) s = "Wikimedia refused (HTTP " + f.code + ")";
        if (f.kind == Kind.REFUSED && f.code == 0) s = "Wikimedia refused the request";
        return sentence ? s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1) : s;
    }
}
