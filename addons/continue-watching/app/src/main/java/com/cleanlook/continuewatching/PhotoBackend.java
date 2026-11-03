package com.cleanlook.continuewatching;

import android.content.Context;

import java.util.Set;

/** {@link PhotoCalls.Backend} on the real preferences and {@link PhotoFeed}. Nothing here waits for the network. */
final class PhotoBackend implements PhotoCalls.Backend {
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
            PhotoFeed.kick(app);
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

    @Override public String note() {
        return prefs.photosNote();
    }

    @Override public long flightMs() {
        return PhotoFeed.flightMs();
    }

    @Override public boolean busy() {
        return PhotoFeed.busy();
    }

    @Override public void advance(String shown, boolean manual) {
        PhotoFeed.advance(app, shown, manual);
    }

    @Override public long now() {
        return System.currentTimeMillis();
    }
}
