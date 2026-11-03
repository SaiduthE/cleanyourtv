package com.cleanlook.continuewatching;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** The first-run probe: what the TV provider shows this app, per package, and how that is saved. Pure. */
final class Probe {
    private Probe() {}

    /** One row of watch_next_program or preview_program, reduced to what the summary needs. */
    static final class Sample {
        boolean watchNext;
        String pkg;
        String title;
        String intentUri;
        String posterUri;
        long engagedMs;
        boolean hasPosition;
    }

    static final class Stat {
        String pkg;
        int watchNext;
        int preview;
        int withPosition;
        long newestEngaged;
        final TreeSet<String> intentKinds = new TreeSet<>();
        final TreeSet<String> posterSchemes = new TreeSet<>();
        String sampleTitle = "";
    }

    static Map<String, Stat> summarize(List<Sample> samples) {
        Map<String, Stat> out = new TreeMap<>();
        for (Sample s : samples) {
            String pkg = s.pkg == null || s.pkg.isEmpty() ? "(unknown)" : s.pkg;
            Stat st = out.get(pkg);
            if (st == null) {
                st = new Stat();
                st.pkg = pkg;
                out.put(pkg, st);
            }
            if (s.watchNext) st.watchNext++;
            else st.preview++;
            if (s.hasPosition) st.withPosition++;
            if (s.engagedMs > st.newestEngaged) st.newestEngaged = s.engagedMs;
            st.intentKinds.add(IntentPolicy.describe(s.intentUri));
            String ps = s.posterUri == null || s.posterUri.isEmpty() ? "(none)" : Text.scheme(s.posterUri);
            st.posterSchemes.add(ps.isEmpty() ? "(unparsable)" : ps);
            if (st.sampleTitle.isEmpty() && s.title != null) st.sampleTitle = Text.clip(s.title, 40);
        }
        return out;
    }

    /** {"pkg":[watchNextRows,previewRows]} for the preferences. */
    static String encode(Map<String, Stat> stats) {
        JSONObject o = new JSONObject();
        try {
            for (Stat s : stats.values()) o.put(s.pkg, new JSONArray().put(s.watchNext).put(s.preview));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return o.toString();
    }

    /** The reverse of {@link #encode}: package -> {watchNextRows, previewRows}. Empty for anything unreadable. */
    static Map<String, int[]> decode(String json) {
        Map<String, int[]> out = new TreeMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray names = o.names();
            if (names == null) return out;
            for (int i = 0; i < names.length(); i++) {
                String pkg = names.getString(i);
                JSONArray a = o.optJSONArray(pkg);
                if (a != null) out.put(pkg, new int[] {a.optInt(0), a.optInt(1)});
            }
        } catch (JSONException e) {
            return new TreeMap<>();
        }
        return out;
    }

    /** The lines written to logcat under CWProbe: counts and schemes only, never titles or addresses. */
    static List<String> logLines(Map<String, Stat> stats, boolean permitted) {
        List<String> lines = new ArrayList<>();
        lines.add("READ_TV_LISTINGS granted=" + permitted + " packages=" + stats.size());
        for (Stat s : stats.values()) {
            lines.add(s.pkg + " watch_next=" + s.watchNext + " preview=" + s.preview + " withPosition=" + s.withPosition
                    + " intent=" + s.intentKinds + " poster=" + s.posterSchemes);
        }
        return lines;
    }
}
