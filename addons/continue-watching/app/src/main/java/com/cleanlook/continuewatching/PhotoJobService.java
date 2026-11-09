package com.cleanlook.continuewatching;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** The system's daily, unmetered-network wake-up: tops the Top Shelf photo ring up to its target, one photo at a time. */
public final class PhotoJobService extends JobService {
    private static final int JOB_ID = 4201;
    private static final long PERIOD_MS = PhotoPolicy.JOB_EVERY_MS;

    /** Schedules the periodic job once (it is kept while the app stays installed). */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo pending = js.getPendingJob(JOB_ID);
        if (pending != null && pending.getIntervalMillis() == PERIOD_MS) return;   // an older build's three-day job is replaced
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, PhotoJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(PERIOD_MS)
                .build());
    }

    @Override public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                PhotoFeed.job(app);
                jobFinished(params, false);
            }
        }, "cw-photos-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
