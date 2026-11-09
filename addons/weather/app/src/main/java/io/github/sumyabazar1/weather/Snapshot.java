package io.github.sumyabazar1.weather;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The last weather read, kept so the provider can answer without the network. Always degrees Celsius as read;
 * the owner's unit choice is applied by the reader, not stored here. Pure Java: serialises to one string for
 * SharedPreferences and parses it back, returning null for anything damaged.
 */
final class Snapshot {
    private static final String MAGIC = "w1";
    static final int MAX_DAILY = 7;

    /** One row of /daily. precipPct is -1 when unknown. */
    static final class Day {
        final String date;
        final int code;
        final double highC;
        final double lowC;
        final int precipPct;

        Day(String date, int code, double highC, double lowC, int precipPct) {
            this.date = date;
            this.code = code;
            this.highC = highC;
            this.lowC = lowC;
            this.precipPct = precipPct;
        }
    }

    final long updatedMs;
    final String place;
    /** Where it was read, so a snapshot of a city the owner has since replaced is never published. */
    final double latitude;
    final double longitude;
    final double tempC;
    final double feelsC;
    final double highC;
    final double lowC;
    final int code;
    final boolean day;
    final int humidity;
    final int windKmh;
    /** US AQI, -1 unknown. */
    final int aqi;
    /** Today first, then the days after it; at most {@link #MAX_DAILY}. */
    final List<Day> daily;

    Snapshot(long updatedMs, String place, double latitude, double longitude, double tempC, double feelsC,
             double highC, double lowC, int code, boolean day, int humidity, int windKmh, int aqi, List<Day> daily) {
        this.updatedMs = updatedMs;
        this.place = place == null ? "" : place;
        this.latitude = latitude;
        this.longitude = longitude;
        this.tempC = tempC;
        this.feelsC = feelsC;
        this.highC = highC;
        this.lowC = lowC;
        this.code = code;
        this.day = day;
        this.humidity = humidity;
        this.windKmh = windKmh;
        this.aqi = aqi;
        List<Day> rows = new ArrayList<>(daily.subList(0, Math.min(MAX_DAILY, daily.size())));
        this.daily = Collections.unmodifiableList(rows);
    }

    /** A fresh read as a snapshot. Today's high and low fall back to the reading when the daily read lacked them. */
    static Snapshot of(Forecast.Report r, String place, double latitude, double longitude, long nowMs) {
        List<Day> rows = new ArrayList<>();
        if (r.today != null) rows.add(day(r.today));
        for (Forecast.Day d : r.days) rows.add(day(d));
        double high = r.today != null ? r.today.maxC : r.tempC;
        double low = r.today != null ? r.today.minC : r.tempC;
        return new Snapshot(nowMs, place, latitude, longitude, r.tempC, r.feelsC, high, low, r.code, r.day,
                r.humidity, r.windKmh, r.aqi, rows);
    }

    private static Day day(Forecast.Day d) {
        return new Day(d.date.toString(), d.code, d.maxC, d.minC, d.precipPct);
    }

    String serialize() {
        StringBuilder out = new StringBuilder(MAGIC);
        field(out, Long.toString(updatedMs));
        field(out, encode(place));
        field(out, Double.toString(latitude));
        field(out, Double.toString(longitude));
        field(out, Double.toString(tempC));
        field(out, Double.toString(feelsC));
        field(out, Double.toString(highC));
        field(out, Double.toString(lowC));
        field(out, Integer.toString(code));
        field(out, day ? "1" : "0");
        field(out, Integer.toString(humidity));
        field(out, Integer.toString(windKmh));
        field(out, Integer.toString(aqi));
        for (Day d : daily) {
            out.append('\n').append(d.date).append('\t').append(d.code).append('\t').append(d.highC).append('\t')
                    .append(d.lowC).append('\t').append(d.precipPct);
        }
        return out.toString();
    }

    private static void field(StringBuilder out, String value) {
        out.append('\t').append(value);
    }

    /** The snapshot written by {@link #serialize}, or null when the text is missing, from another version or damaged. */
    static Snapshot parse(String text) {
        if (text == null) return null;
        try {
            String[] lines = text.split("\n", -1);
            String[] f = lines[0].split("\t", -1);
            if (f.length != 14 || !MAGIC.equals(f[0])) return null;
            List<Day> rows = new ArrayList<>();
            for (int i = 1; i < lines.length && rows.size() < MAX_DAILY; i++) {
                if (lines[i].isEmpty()) continue;
                String[] d = lines[i].split("\t", -1);
                if (d.length != 5) return null;
                rows.add(new Day(d[0], Integer.parseInt(d[1]), finite(d[2]), finite(d[3]), Integer.parseInt(d[4])));
            }
            return new Snapshot(Long.parseLong(f[1]), decode(f[2]), finite(f[3]), finite(f[4]), finite(f[5]),
                    finite(f[6]), finite(f[7]), finite(f[8]), Integer.parseInt(f[9]), "1".equals(f[10]),
                    Integer.parseInt(f[11]), Integer.parseInt(f[12]), Integer.parseInt(f[13]), rows);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double finite(String s) {
        double v = Double.parseDouble(s);
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException(s);
        return v;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
