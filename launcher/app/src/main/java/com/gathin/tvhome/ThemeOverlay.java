package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The theme overlay as JSON, for keys read outside Config's constructor. Config.load keeps a copy of the overlay
 * in the launcher's own files folder (getFilesDir()/config.json), so this works before external storage mounts.
 */
final class ThemeOverlay {

    private static final int MAX_BYTES = 64 * 1024;

    private ThemeOverlay() { }

    /** The overlay, or null when there is none or it cannot be parsed. Never throws. */
    static JSONObject read(Context ctx) {
        try {
            File copy = new File(ctx.getFilesDir(), Config.FILE_NAME);
            if (!copy.isFile() || copy.length() > MAX_BYTES) return null;
            try (InputStream in = new FileInputStream(copy)) {
                byte[] buf = new byte[(int) copy.length()];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                return new JSONObject(text);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A string key from the overlay, or the fallback. */
    static String string(Context ctx, String key, String fallback) {
        JSONObject json = read(ctx);
        return json == null ? fallback : json.optString(key, fallback);
    }
}
