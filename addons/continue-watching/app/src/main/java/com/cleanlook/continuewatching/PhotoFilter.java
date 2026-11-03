package com.cleanlook.continuewatching;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Wikimedia Commons pictures become Top Shelf photos, and the credit shown with them. Pure: the JVM tests cover
 * the size, shape and licence rules, the credit text and the reading of the API's answers.
 */
final class PhotoFilter {
    private PhotoFilter() {}

    static final int MIN_WIDTH = 2500;
    static final float MIN_ASPECT = 1.5f;
    static final float MAX_ASPECT = 2.2f;
    static final int CREDIT_MAX = 80;
    private static final int LICENCE_MAX = 30;
    private static final String MIME = "image/jpeg";

    /** One picture that passed every rule: where to get the 1920 px copy, its size and its credit line. */
    static final class Candidate {
        final String title;
        final String url;
        final int width;
        final int height;
        final String credit;

        Candidate(String title, String url, int width, int height, String credit) {
            this.title = title;
            this.url = url;
            this.width = width;
            this.height = height;
            this.credit = credit;
        }
    }

    /** JPEG, at least 2500 px wide and 1.5 to 2.2 times wider than tall, under a free licence. */
    static boolean accept(String mime, int width, int height, String licence) {
        return MIME.equals(mime) && width >= MIN_WIDTH && aspectOk(width, height) && freeLicence(licence);
    }

    static boolean aspectOk(int width, int height) {
        if (height <= 0) return false;
        float a = width / (float) height;
        return a >= MIN_ASPECT && a <= MAX_ASPECT;
    }

    /**
     * CC BY, CC BY-SA, CC0 and public domain only. Anything with a non-commercial or no-derivatives clause, any
     * "fair use" or "non-free" label, and anything unlabelled is refused.
     */
    static boolean freeLicence(String licence) {
        if (licence == null) return false;
        String l = licence.trim().toUpperCase(Locale.ROOT);
        if (l.isEmpty() || l.contains("FAIR USE") || l.contains("NON-FREE") || l.contains("NONFREE")) return false;
        if (l.contains("-NC") || l.contains("-ND") || l.contains(" NC") || l.contains(" ND")) return false;
        return l.startsWith("CC BY") || l.startsWith("CC0") || l.startsWith("PUBLIC DOMAIN") || l.equals("PD")
                || l.startsWith("PD-") || l.startsWith("PD ");
    }

    // ---- scenery only

    private static final Pattern UNWANTED_TITLE = Pattern.compile(
            "\\b(maps?|diagrams?|charts?|plans?|paintings?|drawings?|sketch(es)?|illustrations?|portraits?|posters?|logos?"
                    + "|flags?|scans?|manuscripts?|trains?|railways?|locomotives?|trams?|buildings?|bridges?|interiors?)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final String[] UNWANTED_CATEGORIES = {
        "maps", "diagram", "painting", "drawing", "portrait", "people", "railway", "trains", "locomotive", "interiors",
    };

    /**
     * A picture that looks like scenery: no map, diagram, artwork or portrait by its file name, and none of those among
     * its Commons categories ({@code categories} is the pipe-separated list the API gives, or null).
     */
    static boolean scenic(String title, String categories) {
        if (title != null && UNWANTED_TITLE.matcher(title).find()) return false;
        if (categories == null) return true;
        String c = categories.toLowerCase(Locale.ROOT);
        for (String bad : UNWANTED_CATEGORIES) if (c.contains(bad)) return false;
        return true;
    }

    // ---- the credit line

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");

    /** HTML to plain text: tags dropped, the common entities decoded, controls, angle brackets and runs of blanks gone. */
    static String plain(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", " ");
        s = TAG.matcher(s).replaceAll("");
        Matcher m = ENTITY.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(entity(m.group(1))));
        m.appendTail(out);
        s = out.toString().replaceAll("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E<>]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String entity(String e) {
        try {
            if (e.startsWith("#x")) return new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            if (e.startsWith("#")) return new String(Character.toChars(Integer.parseInt(e.substring(1))));
        } catch (IllegalArgumentException ex) {
            return " ";
        }
        switch (e) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            default: return " ";
        }
    }

    /** "Author — Licence", plain text, at most 80 characters; the author gives way (with an ellipsis), not the licence. */
    static String credit(String artistHtml, String licenceHtml) {
        String who = plain(artistHtml).replaceFirst("^(?i)User:", "").trim();
        if (who.isEmpty()) who = "Unknown author";
        String lic = plain(licenceHtml);
        if (lic.length() > LICENCE_MAX) lic = lic.substring(0, LICENCE_MAX).trim();
        String tail = lic.isEmpty() ? "" : " — " + lic;
        int room = CREDIT_MAX - tail.length();
        if (who.length() > room) who = who.substring(0, Math.max(1, room - 1)).trim() + "…";
        String out = who + tail;
        return out.length() > CREDIT_MAX ? out.substring(0, CREDIT_MAX) : out;
    }

    // ---- the API's answers

    static final String API_HOST = "commons.wikimedia.org";
    static final String UPLOAD_HOST = "upload.wikimedia.org";
    /** The API names thumbnails on this host; the same path on upload.wikimedia.org serves the same file. */
    private static final String THUMB_HOST = "thumb.wikimedia.org";

    private static final String API = "https://" + API_HOST + "/w/api.php?action=query&format=json";

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The query that lists the files of a Commons category (up to 500, which is more than any category used has). */
    static String listUrl(String category) {
        return API + "&list=categorymembers&cmtype=file&cmlimit=500&cmtitle=" + enc(category);
    }

    /** The query that reads the size, licence, author and 1920 px copy of these pages. */
    static String infoUrl(List<Long> pageIds) {
        StringBuilder ids = new StringBuilder();
        for (Long id : pageIds) ids.append(ids.length() == 0 ? "" : "|").append(id);
        return API + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1920"
                + "&iiextmetadatafilter=" + enc("Artist|LicenseShortName|Categories") + "&pageids=" + enc(ids.toString());
    }

    /** The https address of the 1920 px copy on upload.wikimedia.org, without tracking parameters, or null. */
    static String thumbUrl(String url) {
        if (url == null) return null;
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        String thumb = "https://" + THUMB_HOST;
        if (u.startsWith(thumb + "/")) u = "https://" + UPLOAD_HOST + u.substring(thumb.length());
        if (!u.startsWith("https://" + UPLOAD_HOST + "/wikipedia/commons/thumb/")) return null;
        return u;
    }

    /** The page ids of a {@code list=categorymembers} answer: what the next query asks about, free of any encoding. */
    static List<Long> pageIds(String json) throws JSONException {
        List<Long> out = new ArrayList<>();
        JSONObject q = new JSONObject(json).optJSONObject("query");
        JSONArray a = q == null ? null : q.optJSONArray("categorymembers");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            long id = a.getJSONObject(i).optLong("pageid", 0);
            if (id > 0) out.add(id);
        }
        return out;
    }

    /** The file titles of a {@code list=categorymembers} answer. */
    static List<String> titles(String json) throws JSONException {
        List<String> out = new ArrayList<>();
        JSONObject q = new JSONObject(json).optJSONObject("query");
        JSONArray a = q == null ? null : q.optJSONArray("categorymembers");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String t = a.getJSONObject(i).optString("title", "");
            if (t.startsWith("File:")) out.add(t);
        }
        return out;
    }

    /** The {@code cmcontinue} token of an answer, or null when it was the last page. */
    static String continuation(String json) throws JSONException {
        JSONObject c = new JSONObject(json).optJSONObject("continue");
        return c == null ? null : c.optString("cmcontinue", null);
    }

    /** The pictures of a {@code prop=imageinfo} answer that pass {@link #accept}, with their credit. */
    static List<Candidate> candidates(String json) throws JSONException {
        List<Candidate> out = new ArrayList<>();
        JSONObject q = new JSONObject(json).optJSONObject("query");
        JSONObject pages = q == null ? null : q.optJSONObject("pages");
        if (pages == null) return out;
        for (Iterator<String> it = pages.keys(); it.hasNext();) {
            JSONObject p = pages.getJSONObject(it.next());
            JSONArray infos = p.optJSONArray("imageinfo");
            if (infos == null || infos.length() == 0) continue;
            JSONObject ii = infos.getJSONObject(0);
            JSONObject meta = ii.optJSONObject("extmetadata");
            String licence = meta == null ? null : plain(value(meta, "LicenseShortName"));
            if (!accept(ii.optString("mime", ""), ii.optInt("width", 0), ii.optInt("height", 0), licence)) continue;
            if (!scenic(p.optString("title", ""), meta == null ? null : value(meta, "Categories"))) continue;
            String url = thumbUrl(ii.optString("thumburl", null));
            int tw = ii.optInt("thumbwidth", 0);
            int th = ii.optInt("thumbheight", 0);
            if (url == null || tw <= 0 || th <= 0) continue;
            out.add(new Candidate(p.optString("title", ""), url, tw, th, credit(value(meta, "Artist"), licence)));
        }
        return out;
    }

    private static String value(JSONObject meta, String key) {
        JSONObject o = meta.optJSONObject(key);
        return o == null ? null : o.optString("value", null);
    }
}
