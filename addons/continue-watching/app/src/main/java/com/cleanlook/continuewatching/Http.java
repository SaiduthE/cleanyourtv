package com.cleanlook.continuewatching;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Minimal HTTP. It never logs an address or a header: they can hold a server name or a token. */
final class Http {
    private Http() {}

    static final int JSON_MAX = 4 * 1024 * 1024;
    static final int IMAGE_MAX = 12 * 1024 * 1024;

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
            if (out.size() > max) throw new IOException("response too large");
        }
        return out.toByteArray();
    }
}
