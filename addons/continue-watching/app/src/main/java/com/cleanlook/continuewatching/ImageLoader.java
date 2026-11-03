package com.cleanlook.continuewatching;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Images from https, http and content URIs. A small LruCache of decoded bitmaps, a disk cache of the
 * downloaded bytes in cacheDir (about 100 MB), RGB_565 decoding. It never logs an address.
 */
final class ImageLoader {
    interface Callback {
        void onBitmap(Bitmap b);
    }

    static final class Handle {
        volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }
    }

    private static final long DISK_MAX = 100L * 1024 * 1024;
    private static final long DISK_TRIM_TO = 85L * 1024 * 1024;

    private final Context ctx;
    private final File dir;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String authBase = "";
    private volatile Map<String, String> authHeaders;
    private int writes;

    ImageLoader(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getCacheDir(), "img");
        dir.mkdirs();
    }

    /** Requests to this server get the Jellyfin headers; nothing else does. */
    void setAuth(String base, Map<String, String> headers) {
        authBase = base == null ? "" : base;
        authHeaders = headers;
    }

    private static String key(String url, int width) {
        return url + "@" + width;
    }

    /** Calls back on the main thread, once, unless cancelled. A failed load calls nothing. */
    Handle load(final String url, final int width, final Callback cb) {
        final Handle h = new Handle();
        if (url == null || url.isEmpty()) return h;
        final String k = key(url, width);
        Bitmap hit = mem.get(k);
        if (hit != null) {
            cb.onBitmap(hit);
            return h;
        }
        pool.execute(new Runnable() {
            @Override public void run() {
                if (h.cancelled) return;
                final Bitmap b = decode(url, width);
                if (b == null) return;
                mem.put(k, b);
                main.post(new Runnable() {
                    @Override public void run() {
                        if (!h.cancelled) cb.onBitmap(b);
                    }
                });
            }
        });
        return h;
    }

    /** Fetches and decodes on the calling thread (the shelf worker uses this). Null on any failure. */
    Bitmap decode(String url, int width) {
        return decode(url, width, false);
    }

    /** With {@code alpha} the pixels keep their transparency (logos); otherwise RGB_565 saves memory. */
    Bitmap decode(String url, int width, boolean alpha) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            if (scheme.equals("content") || scheme.equals("android.resource")) {
                ContentResolver cr = ctx.getContentResolver();
                o.inJustDecodeBounds = true;
                try (InputStream in = cr.openInputStream(u)) {
                    if (in == null) return null;
                    BitmapFactory.decodeStream(in, null, o);
                }
                o.inSampleSize = sample(o.outWidth, width);
                o.inJustDecodeBounds = false;
                try (InputStream in = cr.openInputStream(u)) {
                    return in == null ? null : BitmapFactory.decodeStream(in, null, o);
                }
            }
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            File f = new File(dir, Text.sha1Hex(url));
            if (!f.exists() || f.length() == 0) {
                byte[] bytes = Http.getBytes(url, headersFor(url), Http.IMAGE_MAX);
                File tmp = new File(dir, f.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(f)) tmp.delete();
                trimMaybe();
            } else {
                f.setLastModified(System.currentTimeMillis());
            }
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = sample(o.outWidth, width);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private Map<String, String> headersFor(String url) {
        String base = authBase;
        Map<String, String> h = authHeaders;
        return !base.isEmpty() && h != null && url.startsWith(base) ? h : null;
    }

    /** The biggest power of two that keeps the decoded width at least {@code target}. */
    static int sample(int srcWidth, int target) {
        int s = 1;
        if (srcWidth <= 0 || target <= 0) return 1;
        while (srcWidth / (s * 2) >= target) s *= 2;
        return s;
    }

    private synchronized void trimMaybe() {
        if (++writes % 20 != 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_MAX) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= DISK_TRIM_TO) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
