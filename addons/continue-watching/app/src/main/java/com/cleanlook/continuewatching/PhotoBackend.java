package com.cleanlook.continuewatching;

import android.content.Context;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** {@link PhotoCalls.Backend} on the real preferences and {@link PhotoFeed}. Every slow job runs on its own thread. */
final class PhotoBackend implements PhotoCalls.Backend {
    /** Set from the moment a refresh is asked for until it has ended, so the first status already says "Refreshing". */
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);

    private final Context app;
    private final Prefs prefs;

    PhotoBackend(Context c) {
        app = c.getApplicationContext();
        prefs = App.prefs(app);
    }

    @Override public boolean enabled() {
        return prefs.photosEnabled();
    }

    @Override public void setEnabled(final boolean on) {
        prefs.setPhotosEnabled(on);
        if (on) {
            startRefresh();
        } else {
            new Thread(new Runnable() {
                @Override public void run() {
                    PhotoFeed.clear(app);
                }
            }, "cw-photos-clear").start();
        }
    }

    @Override public Set<String> off() {
        return prefs.photosOff();
    }

    @Override public void setOff(Set<String> off) {
        prefs.setPhotosOff(off);
    }

    @Override public int count() {
        return PhotoFeed.available(app).size();
    }

    @Override public long okMs() {
        return prefs.photosOkMs();
    }

    @Override public String note() {
        return prefs.photosNote();
    }

    @Override public boolean refreshing() {
        return PENDING.get() || PhotoFeed.running();
    }

    @Override public void startRefresh() {
        if (!PENDING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    PhotoFeed.refresh(app, true);
                } catch (RuntimeException e) {
                    Log.w(Contract.TAG, "photos refresh failed: " + e.getClass().getSimpleName());
                } finally {
                    PENDING.set(false);
                }
            }
        }, "cw-photos-manual").start();
    }

    @Override public long now() {
        return System.currentTimeMillis();
    }
}
