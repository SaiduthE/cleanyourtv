package io.github.sumyabazar1.weather;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;

/**
 * Keeps the widget snapshot fresh without a service of its own: a persisted periodic job (every 15, 30 or 60
 * minutes, network required), and one-shot jobs when the city or units change or a query finds old data. All of
 * it starts only when a reader has queried the provider, and stops 24 hours after the last query.
 * {@link #refreshBlocking} is the only place the provider's data is read from the network, and it runs on the job's
 * thread, never on a binder thread.
 */
final class Refresher {
    private static final int JOB_PERIODIC = 1;
    private static final int JOB_ONCE = 2;
    /** Days after today to ask for: today plus six makes the seven rows of /daily. */
    private static final int WIDGET_DAYS = 6;

    private Refresher() { }

    static String authority(Context c) {
        return c.getPackageName() + ".widget";
    }

    static Uri currentUri(Context c) {
        return Uri.parse("content://" + authority(c) + "/current");
    }

    static Uri dailyUri(Context c) {
        return Uri.parse("content://" + authority(c) + "/daily");
    }

    static void notifyChanged(Context c) {
        c.getContentResolver().notifyChange(currentUri(c), null);
        c.getContentResolver().notifyChange(dailyUri(c), null);
    }

    /** True while there is a city and a reader has queried the provider within the last 24 hours. */
    static boolean inUse(Context c) {
        return WidgetStore.hasPlace(c) && RefreshPolicy.inUse(System.currentTimeMillis(), WidgetStore.lastQuery(c));
    }

    /**
     * Keeps the background reading to when it is wanted: schedules the periodic job if a reader has queried the
     * provider lately and it is missing or its interval is not the one chosen now, and cancels it when no reader has.
     * Nothing reads Open-Meteo in the background for an owner whose launcher does not use the widget.
     */
    static void ensurePeriodic(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!inUse(c)) {
            js.cancel(JOB_PERIODIC);
            return;
        }
        long wanted = WidgetSettings.refreshMinutes(c) * 60_000L;
        JobInfo pending = js.getPendingJob(JOB_PERIODIC);
        if (pending != null && pending.isPeriodic() && pending.getIntervalMillis() == wanted) return;
        JobInfo.Builder job = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(c, RefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(wanted);
        try {
            js.schedule(job.setPersisted(true).build());
        } catch (SecurityException | IllegalArgumentException e) {
            js.schedule(job.setPersisted(false).build());   // without the boot permission it still runs until restart
        }
    }

    /** One refresh as soon as there is a network. A pending one-shot is replaced, not stacked. */
    static void requestNow(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        js.schedule(new JobInfo.Builder(JOB_ONCE, new ComponentName(c, RefreshJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    /** The owner changed the city or the units: tell readers now, read again, and keep the schedule current. */
    static void onChoicesChanged(Context c, boolean refetch) {
        notifyChanged(c);
        ensurePeriodic(c);
        if (refetch && inUse(c)) requestNow(c);
    }

    /** Called by a query: remembers that a reader exists (saving the time at most every 5 minutes) and schedules the job. */
    static void noteQuery(Context c) {
        long now = System.currentTimeMillis();
        if (!RefreshPolicy.shouldNoteQuery(now, WidgetStore.lastQuery(c))) return;
        WidgetStore.setLastQuery(c, now);
        ensurePeriodic(c);
    }

    /** Called by a query: asks for a one-shot refresh if the data is old and none was asked for lately. */
    static void kickIfStale(Context c, Snapshot s) {
        long now = System.currentTimeMillis();
        if (!RefreshPolicy.shouldKick(now, s == null ? -1 : s.updatedMs, WidgetStore.lastKick(c),
                WidgetStore.hasPlace(c))) return;
        WidgetStore.setLastKick(c, now);
        ensurePeriodic(c);
        requestNow(c);
    }

    /**
     * Reads Open-Meteo for the chosen city, saves the snapshot and tells readers. Blocks: call it only off the main
     * and binder threads. True when there was nothing to do or it worked, false when the network read failed.
     */
    static boolean refreshBlocking(Context c) {
        if (!WidgetStore.hasPlace(c)) return true;
        if (!inUse(c)) {   // no reader for 24 hours: stop reading in the background until one comes back
            ensurePeriodic(c);
            return true;
        }
        double lat = WidgetStore.latitude(c);
        double lon = WidgetStore.longitude(c);
        String name = WidgetStore.placeName(c);
        Forecast.Report report;
        try {
            report = Forecast.fetch(lat, lon, WIDGET_DAYS);
        } catch (Exception e) {
            return false;
        }
        // The owner may have chosen another city while this read was running: drop it, that city has its own job.
        if (!WidgetStore.hasPlace(c) || Double.compare(lat, WidgetStore.latitude(c)) != 0
                || Double.compare(lon, WidgetStore.longitude(c)) != 0) return true;
        WidgetStore.save(c, Snapshot.of(report, name, lat, lon, System.currentTimeMillis()));
        notifyChanged(c);
        return true;
    }
}
