package com.cleanlook.continuewatching;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What the last refresh found, as one JSON file in this app's private storage. The shelf provider reads it too. */
final class Store {
    static final class State {
        List<Item> wn = new ArrayList<>();
        List<Item> jf = new ArrayList<>();
        /** Programs of every preview channel of every app; the owner's channel switches pick from them. */
        List<Item> ch = new ArrayList<>();
        /** One entry per preview channel found, with its row count, for the Sources screen. */
        List<ChannelFilter.Info> chans = new ArrayList<>();
        /** "ok", "denied" or "error" for the channel read; "" before the first run. */
        String chStatus = "";
        long wnAt;
        long jfAt;
        /** "ok", "denied" (no READ_TV_LISTINGS), "error" or "" before the first run. */
        String wnStatus = "";
    }

    private static final Object LOCK = new Object();
    private static State cached;
    private static long cachedStamp = -1;

    private Store() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "state.json");
    }

    static State read(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            long stamp = f.exists() ? f.lastModified() * 31 + f.length() : 0;
            if (cached != null && stamp == cachedStamp) return cached;
            State s = new State();
            if (f.exists()) {
                try {
                    byte[] b;
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        b = Http.readAll(in, 8 * 1024 * 1024);
                    }
                    JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    s.wn = items(o.optJSONArray("wn"));
                    s.jf = items(o.optJSONArray("jf"));
                    s.ch = items(o.optJSONArray("ch"));
                    s.chans = infos(o.optJSONArray("chans"));
                    s.chStatus = o.optString("ch_status", "");
                    s.wnAt = o.optLong("wn_at", 0);
                    s.jfAt = o.optLong("jf_at", 0);
                    s.wnStatus = o.optString("wn_status", "");
                } catch (Exception e) {
                    Log.w(Contract.TAG, "state.json unreadable, starting empty");
                    s = new State();
                }
            }
            cached = s;
            cachedStamp = stamp;
            return s;
        }
    }

    private static List<Item> items(JSONArray a) {
        List<Item> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = Item.fromJson(o);
            if (!it.id.isEmpty() && !it.pkg.isEmpty()) out.add(it);
        }
        return out;
    }

    private static List<ChannelFilter.Info> infos(JSONArray a) {
        List<ChannelFilter.Info> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            ChannelFilter.Info n = new ChannelFilter.Info();
            n.pkg = o.optString("pkg", "");
            n.name = o.optString("name", "");
            n.rows = o.optInt("rows", 0);
            n.total = o.optInt("total", 0);
            if (!n.pkg.isEmpty() && !n.name.isEmpty()) out.add(n);
        }
        return out;
    }

    static void write(Context c, State s) throws IOException {
        synchronized (LOCK) {
            try {
                JSONObject o = new JSONObject();
                JSONArray wn = new JSONArray();
                for (Item it : s.wn) wn.put(it.toJson());
                JSONArray jf = new JSONArray();
                for (Item it : s.jf) jf.put(it.toJson());
                JSONArray ch = new JSONArray();
                for (Item it : s.ch) ch.put(it.toJson());
                JSONArray chans = new JSONArray();
                for (ChannelFilter.Info n : s.chans) {
                    chans.put(new JSONObject().put("pkg", n.pkg).put("name", n.name).put("rows", n.rows)
                            .put("total", n.total));
                }
                o.put("wn", wn);
                o.put("jf", jf);
                o.put("ch", ch);
                o.put("chans", chans);
                o.put("ch_status", s.chStatus);
                o.put("wn_at", s.wnAt);
                o.put("jf_at", s.jfAt);
                o.put("wn_status", s.wnStatus);
                File tmp = new File(c.getFilesDir(), "state.json.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file(c))) throw new IOException("rename failed");
                cached = null;
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }
    }

    /** Switches are applied here, so a toggle shows at once: Watch Next rows per app, channel rows per channel. */
    static List<Item> visible(Context c, State s, Set<String> disabled) {
        Prefs p = App.prefs(c);
        return visible(s, disabled, p.channelsForcedOn(), p.channelsForcedOff());
    }

    static List<Item> visible(State s, Set<String> disabled, Set<String> channelsOn, Set<String> channelsOff) {
        List<Item> out = new ArrayList<>();
        for (Item it : s.wn) if (!disabled.contains(it.pkg)) out.add(it);
        out.addAll(ChannelFilter.visible(s.ch, channelsOn, channelsOff, new ArrayList<>(out)));
        out.addAll(s.jf);
        return out;
    }

    static Item find(List<Item> items, String id) {
        if (id == null) return null;
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }
}
