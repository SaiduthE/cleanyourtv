package com.cleanlook.continuewatching;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minimal HTTP. It never logs an address or a header: they can hold a server name or a token. */
final class Http {
    private Http() {}

    static final int JSON_MAX = 4 * 1024 * 1024;
    static final int IMAGE_MAX = 12 * 1024 * 1024;

    /** A non-2xx answer: the status code and, for a busy server, how long it asked us to wait (0 when it did not say). */
    static final class HttpException extends IOException {
        final int code;
        final long retryAfterMs;

        HttpException(int code, long retryAfterMs) {
            super("HTTP " + code);
            this.code = code;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /** The body is bigger than the caller allows. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("response too large");
        }
    }

    /** The {@code Retry-After} header in milliseconds when it is a number of seconds (the form Wikimedia uses), else 0. */
    static long retryAfterMs(String header) {
        if (header == null) return 0;
        try {
            long s = Long.parseLong(header.trim());
            return s < 0 ? 0 : Math.min(s, 3600) * 1000;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static final class Response {
        int code;
        byte[] body = new byte[0];

        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static Response request(String method, String url, Map<String, String> headers, String jsonBody, int maxBytes)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            }
            if (jsonBody != null) {
                byte[] b = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
            }
            Response r = new Response();
            r.code = c.getResponseCode();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try {
                    r.body = readAll(in, maxBytes);
                } finally {
                    in.close();
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static final int MAX_REDIRECTS = 3;

    /** True for an https address (default port, no user info) whose host is exactly one of {@code hosts}. */
    static boolean allowedHttps(String url, Set<String> hosts) {
        if (url == null) return false;
        try {
            URI u = new URI(url);
            String host = u.getHost();
            return "https".equals(u.getScheme()) && host != null && hosts.contains(host.toLowerCase(Locale.ROOT))
                    && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * A GET that only ever talks https to {@code hosts}: same timeouts as {@link #request}, but redirects are followed
     * by hand (at most three) and every hop must pass {@link #allowedHttps}, so a redirect off the list is refused.
     */
    static Response getGuarded(String url, Map<String, String> headers, int maxBytes, Set<String> hosts)
            throws IOException {
        return getGuarded(url, headers, maxBytes, hosts, 15000);
    }

    /** As above with the read timeout chosen by the caller (a thumbnail rendered for the first time can be slow). */
    static Response getGuarded(String url, Map<String, String> headers, int maxBytes, Set<String> hosts,
            int readTimeoutMs) throws IOException {
        String at = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!allowedHttps(at, hosts)) throw new IOException("address not allowed");
            HttpURLConnection c = (HttpURLConnection) new URL(at).openConnection();
            try {
                c.setRequestMethod("GET");
                c.setConnectTimeout(8000);
                c.setReadTimeout(readTimeoutMs);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(false);
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without target");
                    at = new URL(new URL(at), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) throw new HttpException(code, retryAfterMs(c.getHeaderField("Retry-After")));
                Response r = new Response();
                r.code = code;
                try (InputStream in = c.getInputStream()) {
                    r.body = readAll(in, maxBytes);
                }
                return r;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    static byte[] getBytes(String url, Map<String, String> headers, int maxBytes) throws IOException {
        Response r = request("GET", url, headers, null, maxBytes);
        if (r.code < 200 || r.code >= 300) throw new IOException("HTTP " + r.code);
        return r.body;
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new TooLarge();
        }
        return out.toByteArray();
    }
}
