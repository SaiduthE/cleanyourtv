package com.cleanlook.continuewatching;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** The few Jellyfin calls this app needs. All of them block: call from a worker thread. */
final class JellyfinClient {
    /** The server said the token is not valid (401/403). */
    static final class AuthException extends IOException {
        AuthException(String m) {
            super(m);
        }
    }

    private final String base;
    private final String token;
    private final String userId;
    private final String deviceId;

    JellyfinClient(String base, String token, String userId, String deviceId) {
        this.base = base;
        this.token = token;
        this.userId = userId;
        this.deviceId = deviceId;
    }

    /**
     * The MediaBrowser header Jellyfin expects: client, device, device id and version, plus the token
     * once there is one. Sent as Authorization, and as X-Emby-Authorization for older servers.
     */
    static String header(String deviceId, String token) {
        StringBuilder b = new StringBuilder("MediaBrowser Client=\"Continue watching\", Device=\"")
                .append(clean(Build.MODEL == null ? "Android TV" : Build.MODEL))
                .append("\", DeviceId=\"").append(clean(deviceId)).append("\", Version=\"0.1\"");
        if (token != null && !token.isEmpty()) b.append(", Token=\"").append(clean(token)).append('"');
        return b.toString();
    }

    private static String clean(String s) {
        return s.replace("\"", "").replace("\n", "").replace("\r", "");
    }

    private static Map<String, String> headers(String deviceId, String token) {
        String h = header(deviceId, token);
        Map<String, String> m = new HashMap<>();
        m.put("Authorization", h);
        m.put("X-Emby-Authorization", h);
        return m;
    }

    private Map<String, String> headers() {
        return headers(deviceId, token);
    }

    private String get(String path) throws IOException {
        Http.Response r = Http.request("GET", base + path, headers(), null, Http.JSON_MAX);
        return check(r);
    }

    private static String check(Http.Response r) throws IOException {
        if (r.code == 401 || r.code == 403) throw new AuthException("HTTP " + r.code);
        if (r.code < 200 || r.code >= 300) throw new IOException("HTTP " + r.code);
        return r.text();
    }

    // ---- continue watching

    List<Item> resume(long now) throws IOException, JSONException {
        String json = get("/Users/" + userId + "/Items/Resume?Limit=24&MediaTypes=Video&Fields=Overview"
                + "&EnableImageTypes=Primary,Backdrop,Logo&ImageTypeLimit=1&EnableTotalRecordCount=false");
        return JellyfinParser.parseItems(json, Item.RESUME, base, now);
    }

    List<Item> nextUp(long now) throws IOException, JSONException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String cutoff = URLEncoder.encode(f.format(now - WatchNextFilter.MAX_AGE_MS), "UTF-8");
        String json = get("/Shows/NextUp?UserId=" + userId + "&Limit=24&Fields=Overview"
                + "&EnableImageTypes=Primary,Backdrop,Logo&ImageTypeLimit=1&DisableFirstEpisode=true"
                + "&EnableResumable=false&EnableRewatching=false&EnableTotalRecordCount=false&NextUpDateCutoff=" + cutoff);
        return JellyfinParser.parseItems(json, Item.NEXT, base, now);
    }

    // ---- sign-in (static: there is no token yet)

    static String serverName(String base, String deviceId) throws IOException {
        Http.Response r = Http.request("GET", base + "/System/Info/Public", headers(deviceId, null), null, Http.JSON_MAX);
        return JellyfinParser.parseServerName(check(r));
    }

    /** With an API key: the users the key can see. */
    static List<String[]> users(String base, String apiKey, String deviceId) throws IOException, JSONException {
        Http.Response r = Http.request("GET", base + "/Users", headers(deviceId, apiKey), null, Http.JSON_MAX);
        return JellyfinParser.parseUsers(check(r));
    }

    static JellyfinParser.Auth byName(String base, String user, String password, String deviceId)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("Username", user);
        body.put("Pw", password);
        Http.Response r = Http.request("POST", base + "/Users/AuthenticateByName", headers(deviceId, null), body.toString(),
                Http.JSON_MAX);
        JellyfinParser.Auth a = JellyfinParser.parseAuth(check(r));
        if (a.token.isEmpty() || a.userId.isEmpty()) throw new IOException("no token in the answer");
        return a;
    }

    /** Header map for images from this server. */
    Map<String, String> imageHeaders() {
        return headers();
    }
}
